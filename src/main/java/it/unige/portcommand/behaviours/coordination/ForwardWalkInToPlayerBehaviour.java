package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.behaviours.negotiation.WithdrawalBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.AssistantChatEvent;
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
        // Audit A-01 (2026-07-27): the vessel's own REFUSE / ACCEPT_PROPOSAL replies to the
        // player's counter. Handled by PERFORMATIVE, before the intent switch, because a REFUSE
        // with unparseable content must still reach the player rather than fall through the
        // intent == null branch below.
        if (msg.getPerformative() == ACLMessage.REFUSE) {
            handleVesselRefuse(hm, msg, content);
            return;
        }
        if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
            // No action: the vessel's DealClosedBehaviour CONFIRM follows immediately and is what
            // grants the berth. Matching it at all is the point — receiveLogged is the comm log's
            // only instrumentation point, so before this the log showed the HM accepting and never
            // the vessel, and the performative tally missed the vessel's side of the close.
            log.debug("{}: vessel accepted the player's counter (deal_confirmed follows)",
                    msg.getSender().getLocalName());
            return;
        }
        String intent = text(content, "intent");
        if (intent == null) {
            // Tug-originated flow reports carry an "event" field, not "intent" (checkpoint-#6
            // F1, fixed 2026-07-18): the escort_complete INFORM is the dock report this
            // tracking previously swallowed silently.
            if ("escort_complete".equals(text(content, "event"))) {
                advanceStage(hm, text(content, "vessel_id"), Stage.DOCKED);
            }
            return; // anything else without an intent is not a walk-in negotiation message
        }
        switch (intent) {
            case "opening_offer", "counter_offer" -> handleOffer(hm, msg, content);
            case "deal_confirmed" -> handleDealConfirmed(hm, msg, content);
            case "withdraw" -> handleWithdraw(hm, msg, content);
            // Checkpoint-#6 F1/F5 (fixed 2026-07-18): both channels' DockAndServiceBehaviour
            // INFORMs {intent: service_complete} at service end, and this switch used to fall
            // through to the "unrecognised" branch — the HM-side stage never left
            // AWAITING_TUG/IN_TRANSIT, so departures logged stale stages and the persisted
            // VesselStateDTO.stage contradicted the vessel's own currentPhase.
            case "service_complete" -> advanceStage(hm, text(content, "vessel_id"), Stage.DEPARTING);
            case "departed" -> handleDeparted(hm, content);
            default -> log.debug("unrecognised walk-in intent '{}' from {}", intent, msg.getSender().getLocalName());
        }
    }

    /**
     * Forward-only stage advance from a received flow report (checkpoint-#6 F1). Monotone by
     * {@link Stage} ordinal — a late or duplicate report (two escorting tugs each INFORM
     * escort_complete; a delivered-then-storm-held vessel may report out of order) can never
     * move tracking backwards. Deliberately does NOT touch tugs/holds: the weather CANCEL
     * sweep's backward AWAITING_TUG reset stays the one legitimate regression, and the
     * re-dispatch sweep (weatherHeld AND AWAITING_TUG) is naturally skipped for a vessel this
     * advanced past AWAITING_TUG — a docked vessel must never be re-escorted. Terminal removal
     * stays exclusively in {@link #handleDeparted}/{@link #handleWithdraw} (INVARIANTS
     * "terminal removal" rule).
     *
     * <p><b>Report lag is inherent</b> (adversarial review F-1, 2026-07-18): the stage is the
     * HM's best knowledge FROM RECEIVED REPORTS, not a mirror of the vessel's own phase. The
     * DOCKED signal is the tug's escort_complete, which arrives escort-time after the vessel's
     * fixed 2-sim-min self-docking — and a 0-tug vessel emits no dock report at all, so it
     * tracks IN_TRANSIT until its service_complete. The pre-fix bug was stages STUCK for a
     * vessel's whole life; transient lag (and the 0-tug DOCKED skip) is by design.
     */
    private static void advanceStage(HarbourMasterAgent hm, String vesselId, Stage target) {
        if (vesselId == null) {
            return;
        }
        VesselTracking advanced = hm.activeVessels().computeIfPresent(vesselId,
                (id, tracking) -> tracking.stage().ordinal() < target.ordinal()
                        ? tracking.withStage(target)
                        : tracking);
        if (advanced != null && advanced.stage() == target) {
            log.debug("{}: tracking stage -> {}", vesselId, target);
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

    /**
     * The vessel bounced the player's counter (audit A-01). {@code EvaluateCounterOfferBehaviour}
     * REFUSEs {@code invalid_price} (an absent/non-positive price — producer garbage it must not
     * turn into a €0 deal) or {@code berth_incompatible}, in BOTH cases deliberately without
     * consuming a round. So this surfaces the reason to the player's own tab and touches neither
     * {@code negotiationRounds} nor {@code negotiationBerths}: the dialogue is exactly where it
     * was, and the player can simply send a better counter.
     *
     * <p>Routed as an {@link AssistantChatEvent} because {@code ChatPanel} dispatches that by exact
     * {@code dialogueId} into the tab the player is looking at — the conversation id the vessel
     * replied on IS that dialogue id ({@code "nego-"+vesselId}, set by
     * {@code DispatchPlayerCommandBehaviour}).
     */
    private void handleVesselRefuse(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        String reason = text(content, "reason");
        String dialogueId = msg.getConversationId();
        String vessel = msg.getSender().getLocalName();
        String explanation = switch (reason == null ? "" : reason) {
            case "invalid_price" -> "That counter reached " + vessel + " without a usable price, so it "
                    + "could not act on it (invalid_price). No round was used — send a price.";
            case "berth_incompatible" -> vessel + " cannot use that berth (berth_incompatible). "
                    + "No round was used — name a berth that fits, or leave the berth out.";
            default -> vessel + " refused that counter (" + reason + "). No round was used.";
        };
        hm.eventBus().publish(new AssistantChatEvent(dialogueId, explanation));
        log.info("{}: counter REFUSEd ({}) — surfaced to {}", vessel, reason, dialogueId);
    }

    private void handleOffer(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        VesselSpec spec = TerminalJson.treeToOrNull(content.get("vessel_spec"), VesselSpec.class);
        if (spec == null) {
            return; // malformed offer; nothing binding was ever exchanged
        }
        double price = num(content, "price");
        int hours = (int) num(content, "hours");
        String dialogueId = msg.getConversationId();

        String berthId = hm.negotiationBerths().computeIfAbsent(dialogueId, id -> pickBerth(hm, spec));
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

        // RULE 4 (CLAUDE.md): re-gated through Prolog before this SECOND binding
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

        // Checkpoint-#6 F1 (fixed 2026-07-18): a vessel that needs no escort never awaits one —
        // it starts its transit the moment the grant lands (AwaitBerthBehaviour attaches the
        // transit unconditionally), so AWAITING_TUG was a from-the-start lie for 0-tug vessels
        // (W9 departed still showing it). Stage the grant by what actually happens next.
        int tugsRequired = PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
        Stage grantStage = tugsRequired > 0 ? Stage.AWAITING_TUG : Stage.IN_TRANSIT;
        hm.activeVessels().put(spec.vesselId(), tracking.withStage(grantStage).withBerth(berthId));
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

    /**
     * First Prolog-compatible berth for the vessel's dimensions (RULE R8) that no live vessel is
     * already holding, or {@code null} when the vessel fits nowhere at all.
     *
     * <p><b>Audit A-02 (2026-07-27).</b> This used to be a bare {@code compatible.get(0)}. R8's
     * {@code findall} enumerates {@code instance_of/2} in clause order, so that pick was
     * DETERMINISTIC — every walk-in of a given class got the SAME berth (berth_1 for a
     * cargo_vessel). Two overlapping same-class walk-ins (which {@code busy_day} scripts at t=300)
     * were therefore both offered, and could both be granted, one berth: two vessels docked in it,
     * with the map still painting it FREE. Nothing downstream catches that — a walk-in sends the
     * terminal no berth REQUEST at all (ADR-09), so {@code TerminalState}'s {@code berth_busy}
     * guard never sees it and no {@code BerthOccupancyUpdate} is ever pushed.
     *
     * <p>The de-duplication is deliberately a PREFERENCE, not a constraint: if every compatible
     * berth is already held, this falls back to the old first-compatible pick rather than
     * returning {@code null}. Returning null would REFUSE {@code no_compatible_berth} — a lie,
     * since the berth does fit and is merely busy — and would newly turn away vessels the port
     * previously served. Real occupancy arbitration belongs with the terminal-REQUEST parity fix
     * (backlog), which is where a truthful {@code berth_busy} can come from.
     */
    private static String pickBerth(HarbourMasterAgent hm, VesselSpec spec) {
        List<String> compatible = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        if (compatible.isEmpty()) {
            return null;
        }
        Set<String> held = hm.activeVessels().values().stream()
                .map(VesselTracking::assignedBerth)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Optional<String> free = compatible.stream().filter(berth -> !held.contains(berth)).findFirst();
        if (free.isEmpty()) {
            // The residual A-02 case, made VISIBLE rather than silent (adversarial review, 2026-07-27):
            // when every compatible berth is held we fall back to the old pick, which reproduces the
            // original collision. Logging it is one line and turns "two vessels in one berth, map
            // still FREE" from invisible into greppable.
            log.warn("{}: every compatible berth {} is already held — falling back to {}; "
                            + "this vessel may collide with an existing one (audit A-02 residual)",
                    spec.vesselId(), compatible, compatible.get(0));
        }
        return free.orElse(compatible.get(0));
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
     * Matches PROPOSE/CONFIRM/INFORM/REFUSE/ACCEPT_PROPOSAL on the standard envelope, excluding
     * every OTHER tagged conversation on the HarbourMaster's shared inbound queue: CNP
     * bids, the weather threshold alert, the customs pre-clearance reply, and the
     * terminal berth reply — each routed to its own behaviour instead. Excluding by
     * PROTOCOL TAG (not sender AID) is deliberate: an earlier AID-based exclusion
     * had a real gap where a message could arrive before the HarbourMaster's first
     * successful DF lookup for that sender resolved, and this template would
     * wrongly swallow it (adversarial review finding, task 11) — a protocol tag set
     * at send time has no such timing dependency.
     *
     * <p><b>REFUSE / ACCEPT_PROPOSAL added by audit A-01 (2026-07-27).</b> The walk-in vessel's
     * {@code EvaluateCounterOfferBehaviour} replies to the player's counter with both, on the
     * {@code nego-} conversation and with no protocol tag — and no behaviour on this agent claimed
     * either, so they sat in the inbox for the process's life. The four protocol exclusions above
     * already fence off every OTHER producer of those two performatives that addresses the hub
     * (tug bids under {@code fipa-contract-net}, terminal berth replies under the terminal
     * protocol, customs under its own), which is what makes this widening safe. Verified by
     * enumerating every {@code ACLMessage.REFUSE}/{@code ACCEPT_PROPOSAL} construction in
     * {@code src/main}: the walk-in vessel's three are the only untagged ones aimed here.
     */
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.and(MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY),
                    MessageTemplate.or(
                            MessageTemplate.or(MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                                    MessageTemplate.MatchPerformative(ACLMessage.CONFIRM)),
                                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)),
                            MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.REFUSE),
                                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)))),
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
