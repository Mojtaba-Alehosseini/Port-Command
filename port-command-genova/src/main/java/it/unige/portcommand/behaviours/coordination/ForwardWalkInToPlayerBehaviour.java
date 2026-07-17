package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.behaviours.negotiation.WithdrawalBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walk-in vessel (Channel B) inbound router on the HarbourMaster side: the
 * opening/counter PROPOSE relays to the player as a {@link NegotiationOpenedEvent}
 * (Prolog-picks the candidate berth — RULE R8 — since the vessel's own PROPOSE never
 * carries one; no compatible berth means REFUSE outright, never forwarded, a
 * non-vacuous Prolog gate); the {@code deal_confirmed} CONFIRM grants the final berth
 * (RULE 1-8 gated, mirrors {@link AutoFlowDispatcherBehaviour}'s ACCEPT/REFUSE, on a
 * fresh {@code "berth-"+localName} conversation that the vessel's own
 * {@code AwaitBerthBehaviour} is already listening on) and — task 15, closing a gap ADR-08 left
 * deliberately open — dispatches the same tug Contract Net {@link AutoFlowDispatcherBehaviour}
 * initiates for contracted vessels, when {@code tugsRequired > 0}; the {@code withdraw} INFORM
 * just clears tracking.
 *
 * <p>{@link #TEMPLATE} excludes CNP traffic and the weather/customs/terminal
 * conversations by protocol tag (never by sender AID — see the field's own
 * javadoc for why).
 */
public final class ForwardWalkInToPlayerBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(ForwardWalkInToPlayerBehaviour.class);

    public ForwardWalkInToPlayerBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        ACLMessage msg = hm.receiveLogged(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        String intent = text(content, "intent");
        if (intent == null) {
            return; // not a walk-in negotiation message we recognise; drop silently
        }
        switch (intent) {
            case "opening_offer", "counter_offer" -> handleOffer(hm, msg, content);
            case "deal_confirmed" -> handleDealConfirmed(hm, msg, content);
            case "withdraw" -> handleWithdraw(hm, msg, content);
            case "departed" -> handleDeparted(hm, content);
            default -> log.debug("unrecognised walk-in intent '{}' from {}", intent, msg.getSender().getLocalName());
        }
    }

    /**
     * Terminal tracking cleanup (task 24, visual-checkpoint-#5 B1 — the GHOST-VESSEL bug).
     * Both channels' {@code DepartBehaviour} INFORMs {@code {intent:departed, vessel_id}} on the
     * way out, and before this handler NOTHING consumed it: the {@code VesselTracking} entry
     * outlived its agent forever (a walk-in never even leaves IN_TRANSIT — the ADR-09 parity
     * gap), so a later storm "held" long-gone vessels and the clear then re-dispatched REAL tug
     * escorts (~€1,900 of charges for ghosts in the observed run). Departure now closes the
     * entry; the weather sweeps additionally filter by live stage as defence in depth.
     */
    private void handleDeparted(HarbourMasterAgent hm, JsonNode content) {
        String vesselId = text(content, "vessel_id");
        if (vesselId == null) {
            return;
        }
        VesselTracking removed = hm.activeVessels().remove(vesselId);
        if (removed != null) {
            log.info("{}: departed — tracking closed (was {}, {} tug(s) assigned)",
                    vesselId, removed.stage(), removed.assignedTugs().size());
        }
    }

    private void handleOffer(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        VesselSpec spec = TerminalJson.treeToOrNull(content.get("vessel_spec"), VesselSpec.class);
        if (spec == null) {
            return; // malformed offer; nothing binding was ever exchanged
        }
        double price = num(content, "price");
        int hours = (int) num(content, "hours");
        String dialogueId = msg.getConversationId();

        String berthId = hm.negotiationBerths().computeIfAbsent(dialogueId, id -> pickBerth(spec));
        if (berthId == null) {
            ACLMessage refuse = MessageFactory.reply(msg, ACLMessage.REFUSE);
            refuse.setContent(TerminalJson.write(Map.of("intent", "refuse", "reason", "no_compatible_berth")));
            hm.sendLogged(refuse);
            hm.negotiationBerths().remove(dialogueId);
            log.info("{}: REFUSE opening offer (no compatible berth)", spec.vesselId());
            return;
        }

        hm.activeVessels().computeIfAbsent(spec.vesselId(), id -> VesselTracking
                .arriving(spec.vesselId(), spec, Channel.WALK_IN, msg.getSender())
                .withStage(Stage.NEGOTIATING)
                .withBerth(berthId));

        int roundsUsed = hm.negotiationRounds()
                .computeIfAbsent(dialogueId, id -> new AtomicInteger(0))
                .incrementAndGet();

        // task 10's AutopilotExecuteBehaviour subscribes to this SAME event type
        // (INVARIANTS.md "Assistant (task 10)") — no separate INFORM needed; real
        // dispatch arrives with task 17, matching every other EventBus consumer today.
        WalkInDialogueSnapshot snapshot = WalkInDialogueSnapshot.of(
                spec.vesselId(), spec, berthId, hours, price, 0.0, roundsUsed, 0.0, false);
        hm.eventBus().publish(new NegotiationOpenedEvent(dialogueId, snapshot));
        log.info("{}: {} forwarded (round {}, berth {}, price {})",
                spec.vesselId(), roundsUsed == 1 ? "opening offer" : "counter", roundsUsed, berthId, price);
    }

    private void handleDealConfirmed(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        VesselTracking tracking = findByAid(hm, msg.getSender());
        if (tracking == null) {
            log.warn("deal_confirmed from untracked vessel {} — ignoring", msg.getSender().getLocalName());
            return;
        }
        String dialogueId = msg.getConversationId();
        String berthId = hm.negotiationBerths().getOrDefault(dialogueId, tracking.assignedBerth());
        VesselSpec spec = tracking.spec();
        double price = num(content, "price");
        int hours = (int) num(content, "hours");

        // RULE 4 (the design rules): re-gated through Prolog before this SECOND binding
        // grant too — a counter-PROPOSE round earlier is not a substitute.
        boolean compatible = berthId != null && PrologQueries.isCompatible(
                berthId, spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        String berthConversationId = "berth-" + msg.getSender().getLocalName();
        if (!compatible) {
            ACLMessage refuse = MessageFactory.create(ACLMessage.REFUSE);
            refuse.addReceiver(msg.getSender());
            refuse.setConversationId(berthConversationId);
            refuse.setContent(TerminalJson.write(Map.of("reason", "incompatible")));
            hm.sendLogged(refuse);
            log.info("{}: REFUSE berth grant (incompatible)", spec.vesselId());
            return;
        }

        ACLMessage accept = MessageFactory.create(ACLMessage.ACCEPT_PROPOSAL);
        accept.addReceiver(msg.getSender());
        accept.setConversationId(berthConversationId);
        accept.setContent(TerminalJson.write(Map.of("berth_id", berthId, "price", price, "hours", hours)));
        hm.sendLogged(accept);

        hm.activeVessels().put(spec.vesselId(), tracking.withStage(Stage.AWAITING_TUG).withBerth(berthId));
        hm.negotiationBerths().remove(dialogueId);
        hm.negotiationRounds().remove(dialogueId);
        log.info("{}: berth GRANTED {} (price {}, {}h)", spec.vesselId(), berthId, price, hours);

        // Task 19: closes a real gap — DealClosedEvent/NegotiationClosedEvent existed since
        // task 17 but nothing published them, so a chat tab could never close/grey on a deal.
        hm.eventBus().publish(new DealClosedEvent(new Deal("deal-" + spec.vesselId(), spec.vesselId(), berthId,
                price, hours, hm.simClock().nowSimMillis(), Deal.Outcome.DEAL)));
        hm.eventBus().publish(new NegotiationClosedEvent(dialogueId, spec.vesselId(), Deal.Outcome.DEAL));

        // Task 15: closes the walk-in CNP-dispatch gap ADR-08 left deliberately open —
        // mirrors AutoFlowDispatcherBehaviour's own tugsRequired>0 gate exactly. Terminal/customs
        // dispatch stays out of scope (not asked for; a separate, larger parity gap — see ADR-09).
        int tugsRequired = PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
        if (tugsRequired > 0) {
            Position berthPosition = BerthPositions.position(berthId);
            hm.cnpCoordinator().initiate(new CnpRequest(spec.vesselId(), msg.getSender(),
                    AutoFlowDispatcherBehaviour.CHANNEL_ENTRY, berthPosition, tugsRequired));
        }
    }

    private void handleWithdraw(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        VesselTracking tracking = findByAid(hm, msg.getSender());
        if (tracking != null) {
            hm.activeVessels().remove(tracking.vesselId());
        }
        String dialogueId = msg.getConversationId();
        hm.negotiationBerths().remove(dialogueId);
        hm.negotiationRounds().remove(dialogueId);
        String reason = text(content, "reason");
        log.info("{}: walk-in withdrew ({})", msg.getSender().getLocalName(), reason);

        // Task 19: closes the same publisher gap as handleDealConfirmed's DealClosedEvent, for
        // the non-DEAL outcomes. WithdrawalBehaviour.outcomeFor throws on an unrecognised reason
        // (a boundary this CyclicBehaviour must never let kill its own message loop over) — the
        // tracking cleanup above still happens either way.
        try {
            String vesselId = tracking != null ? tracking.vesselId() : msg.getSender().getLocalName();
            Deal.Outcome outcome = WithdrawalBehaviour.outcomeFor(reason);
            // Task 24: the vessel-computed engagement bit (absent on a legacy/malformed message
            // reads as false — a never-engaged timeout, the gentler penalty; a genuine engaged
            // withdrawal always carries it). Only TIMEOUT's delta depends on it.
            boolean engaged = content.path("engaged").asBoolean(false);
            hm.eventBus().publish(new WithdrawalEvent(vesselId, outcome, engaged, hm.simClock().nowSimMillis()));
            hm.eventBus().publish(new NegotiationClosedEvent(dialogueId, vesselId, outcome));
        } catch (IllegalArgumentException e) {
            log.warn("withdraw from {} carried an unrecognised reason '{}' -- GUI tab left as-is", msg.getSender().getLocalName(), reason);
        }
    }

    /** First Prolog-compatible berth for the vessel's dimensions (RULE R8), or {@code null}. */
    private static String pickBerth(VesselSpec spec) {
        List<String> compatible = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        return compatible.isEmpty() ? null : compatible.get(0);
    }

    private static VesselTracking findByAid(HarbourMasterAgent hm, AID aid) {
        for (VesselTracking vt : hm.activeVessels().values()) {
            if (aid.equals(vt.aid())) {
                return vt;
            }
        }
        return null;
    }

    /**
     * Matches PROPOSE/CONFIRM/INFORM on the standard envelope, excluding every
     * OTHER tagged conversation on the HarbourMaster's shared inbound queue: CNP
     * bids, the weather threshold alert, the customs pre-clearance reply, and the
     * terminal berth reply — each routed to its own behaviour instead. Excluding by
     * PROTOCOL TAG (not sender AID) is deliberate: an earlier AID-based exclusion
     * had a real gap where a message could arrive before the HarbourMaster's first
     * successful DF lookup for that sender resolved, and this template would
     * wrongly swallow it (adversarial review finding, task 11) — a protocol tag set
     * at send time has no such timing dependency.
     */
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.and(MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY),
                    MessageTemplate.or(MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                            MessageTemplate.MatchPerformative(ACLMessage.CONFIRM)),
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM))),
            MessageTemplate.and(MessageTemplate.and(
                    MessageTemplate.not(MessageTemplate.MatchProtocol(InitiateCNPBehaviour.CNP_PROTOCOL)),
                    MessageTemplate.not(MessageTemplate.MatchProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL))),
                    MessageTemplate.and(
                            MessageTemplate.not(MessageTemplate.MatchProtocol(HandleEmergencyBehaviour.CUSTOMS_CLEARANCE_PROTOCOL)),
                            MessageTemplate.not(MessageTemplate.MatchProtocol(AutoFlowDispatcherBehaviour.TERMINAL_BERTH_PROTOCOL)))));

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }

    private static double num(JsonNode node, String field) {
        if (node == null) {
            return 0.0;
        }
        JsonNode v = node.get(field);
        return v == null ? 0.0 : v.asDouble();
    }
}
