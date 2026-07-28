package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.BerthRequest;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.ContractedFeeEarnedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contracted-vessel (Channel A) hub: on a {@code request_berth} REQUEST, gates the
 * berth grant through {@code isCompatible} (RULES R1-R8) before ANY binding reply —
 * REFUSE on failure, ACCEPT_PROPOSAL with deal terms on success (CLAUDE.md rule 4).
 * Then initiates the tug Contract Net (skipped when {@code tugsRequired==0}, the
 * ferry path since 07b), fires the terminal berth REQUEST (9-field, ADR-03) and,
 * for hazmat cargo, the customs pre-clearance REQUEST (task-09's deferred note:
 * "HM customs-REQUEST keys + reply parse").
 *
 * <p>Also drains the terminal's CONFIRM/REFUSE reply to that REQUEST (tagged
 * {@link #TERMINAL_BERTH_PROTOCOL} so {@code ForwardWalkInToPlayerBehaviour}'s
 * broader CONFIRM template can exclude it): the REQUEST is broadcast to every
 * terminal, so the non-owning one always REFUSEs {@code wrong_terminal} and, before
 * this fix, nothing ever consumed those replies — they piled up in the
 * HarbourMaster's queue for the life of the process (adversarial review finding,
 * task 11). Still fire-and-forget in the sense that nothing here blocks the
 * auto-flow on the reply; this only stops the leak and logs the outcome.
 */
public final class AutoFlowDispatcherBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AutoFlowDispatcherBehaviour.class);
    /** Set on the terminal berth REQUEST; its CONFIRM/REFUSE reply preserves it via {@code createReply()}. */
    public static final String TERMINAL_BERTH_PROTOCOL = "port-command-terminal-berth";
    /**
     * Set by {@link RetractIfFloodBehaviour} on its unsolicited flood DISCONFIRM.
     *
     * <p>Matched by PROTOCOL rather than by performative-plus-ontology (adversarial review, task
     * 26): the ontology is the whole game's, so a bare DISCONFIRM+ontology template claims EVERY
     * DISCONFIRM anyone ever sends the HarbourMaster. That is correct today only because the flood
     * retract is the single producer in {@code src/main}; a second one added later would be
     * silently swallowed here and logged as a berth retraction with a null berth. A tag costs one
     * line and removes the class of bug.
     */
    public static final String FLOOD_RETRACT_PROTOCOL = "port-command-flood-retract";
    private static final MessageTemplate REQUEST_TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));
    /**
     * The terminal's replies to the berth REQUEST (protocol-tagged CONFIRM/REFUSE), plus — since
     * 2026-07-27, task 26 — the terminal's UNSOLICITED flood retraction. That DISCONFIRM is not a
     * reply to anything: {@code RetractIfFloodBehaviour} builds a fresh message with no protocol
     * and no conversation id, so it needs its own arm rather than a protocol tag it has no honest
     * claim to. Without this arm the message would sit in the HarbourMaster's queue forever, and —
     * because {@code receiveLogged} is the comm log's only instrumentation point — DISCONFIRM
     * would never appear in the log or the end-of-day performative tally even once the flood
     * trigger existed. Matched on the game ontology so it cannot swallow platform traffic.
     */
    private static final MessageTemplate TERMINAL_REPLY_TEMPLATE = MessageTemplate.or(
            MessageTemplate.and(
                    MessageTemplate.MatchProtocol(TERMINAL_BERTH_PROTOCOL),
                    MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                            MessageTemplate.MatchPerformative(ACLMessage.REFUSE))),
            MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.DISCONFIRM),
                    MessageTemplate.MatchProtocol(FLOOD_RETRACT_PROTOCOL)));
    private static final MessageTemplate TEMPLATE = MessageTemplate.or(REQUEST_TEMPLATE, TERMINAL_REPLY_TEMPLATE);
    private static final int DF_RETRY_ATTEMPTS = 3;
    // Must exceed ServiceLocator's 1 s DF-search cache TTL, or a retry inside that
    // window just replays the SAME stale "not found yet" result instead of a fresh
    // lookup (found by HarbourMasterAgentIT: a customs/terminal probe spawned just
    // before the vessel's REQUEST can lose this race — DF registration is async).
    private static final long DF_RETRY_WALL_MS = 1200L;
    // Placeholder "harbour entrance" point a vessel is escorted FROM — no live per-vessel
    // position channel exists yet (ADR-07); task 18's real map supersedes this, same
    // placeholder spirit as BerthPositions. Public since task 22 (was package-private for
    // task 15's ForwardWalkInToPlayerBehaviour): the parent-package RestoreDispatchBehaviour
    // re-initiates load-interrupted Contract Nets from this same entrance point.
    public static final Position CHANNEL_ENTRY = new Position(700.0, 200.0, 180.0);

    public AutoFlowDispatcherBehaviour(Agent agent) {
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
        if (msg.getPerformative() != ACLMessage.REQUEST) {
            logTerminalReply(hm, msg);
            return;
        }

        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        if (content == null || !"request_berth".equals(text(content, "intent"))) {
            refuse(hm, msg, "malformed");
            return;
        }
        VesselSpec spec = TerminalJson.treeToOrNull(content.get("vessel_spec"), VesselSpec.class);
        if (spec == null) {
            refuse(hm, msg, "malformed");
            return;
        }
        ServiceContract contract = hm.contracts().get(text(content, "contract"));
        if (contract == null) {
            refuse(hm, msg, "unknown_contract");
            return;
        }

        // RULE 4 (CLAUDE.md): the binding grant is gated by Prolog FIRST — nothing
        // is sent to the vessel before this call succeeds.
        if (!PrologQueries.isCompatible(contract.berthId(), spec.vesselType(), spec.draft(), spec.length(),
                spec.tonnage())) {
            refuse(hm, msg, "incompatible");
            return;
        }

        ACLMessage accept = MessageFactory.reply(msg, ACLMessage.ACCEPT_PROPOSAL);
        accept.setContent(TerminalJson.write(Map.of(
                "berth_id", contract.berthId(), "price", contract.contractedFee(),
                "hours", contract.contractedHours())));
        hm.sendLogged(accept);
        // Task 20: the contracted path's counterpart to the walk-in path's DealClosedEvent, which
        // fires only from ForwardWalkInToPlayerBehaviour. Without this the fees in contracts.json
        // reached the wire (just above) and the wallet could never earn them. Published at the
        // GRANT — the Prolog-gated binding commitment — not at service-complete/departure, whose
        // INFORMs are unhandled today and which a granted contract is owed regardless of.
        hm.eventBus().publish(new ContractedFeeEarnedEvent(
                contract.contractId(), spec.vesselId(), contract.berthId(), contract.contractedFee(),
                contract.contractedHours(), hm.simClock().nowSimMillis()));

        String vesselId = spec.vesselId();
        // Checkpoint-#6 F1 (fixed 2026-07-18): mirror of the walk-in grant — a 0-tug vessel
        // transits immediately, so its tracking starts IN_TRANSIT, never AWAITING_TUG.
        int tugsRequired = PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
        hm.activeVessels().put(vesselId, VesselTracking
                .arriving(vesselId, spec, Channel.CONTRACTED, msg.getSender())
                .withStage(tugsRequired > 0 ? Stage.AWAITING_TUG : Stage.IN_TRANSIT)
                .withBerth(contract.berthId()));

        if (tugsRequired > 0) {
            Position berthPosition = BerthPositions.position(contract.berthId());
            hm.cnpCoordinator().initiate(
                    new CnpRequest(vesselId, msg.getSender(), CHANNEL_ENTRY, berthPosition, tugsRequired));
        }

        sendTerminalRequest(hm, spec, contract, DF_RETRY_ATTEMPTS);

        if (PrologQueries.isHazmat(spec.cargoClass())) {
            sendCustomsRequest(hm, spec, DF_RETRY_ATTEMPTS);
        }

        log.info("{}: ACCEPT berth {} (contract {}), tugsRequired={}",
                vesselId, contract.berthId(), contract.contractId(), tugsRequired);
    }

    /**
     * Broadcasts the 9-field {@link BerthRequest} to every registered terminal —
     * fire-and-forget (no reply listener; the terminal's own "wrong_terminal"
     * REFUSE already resolves the multi-terminal ambiguity, since no static
     * berth-to-terminal registry exists outside each terminal's own
     * {@code TerminalState}). Retries a few times (spaced past the DF-search cache
     * TTL) if no terminal is visible yet — fleet spawn is async, so a terminal
     * might not have registered by the moment the first contracted vessel arrives.
     */
    private void sendTerminalRequest(HarbourMasterAgent hm, VesselSpec spec, ServiceContract contract,
                                     int attemptsLeft) {
        List<AID> terminals = hm.serviceLocator().findAll("terminal");
        if (terminals.isEmpty()) {
            if (attemptsLeft <= 0) {
                log.warn("no terminal registered in DF after retries; cannot request berth {}", contract.berthId());
                return;
            }
            myAgent.addBehaviour(new WakerBehaviour(myAgent, DF_RETRY_WALL_MS) {
                @Override
                protected void onWake() {
                    sendTerminalRequest(hm, spec, contract, attemptsLeft - 1);
                }
            });
            return;
        }
        BerthRequest req = new BerthRequest(spec.vesselId(), spec.vesselType(), spec.draft(), spec.length(),
                spec.tonnage(), contract.berthId(), contract.expectedArrivalSimMillis(),
                contract.contractedHours(), spec.cargoClass());
        ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
        terminals.forEach(request::addReceiver);
        request.setConversationId("term-" + spec.vesselId());
        request.setProtocol(TERMINAL_BERTH_PROTOCOL);
        request.setContent(TerminalJson.write(req));
        hm.sendLogged(request);
    }

    /**
     * Drains + logs a terminal's CONFIRM/REFUSE reply to the berth REQUEST above, and the
     * unsolicited flood DISCONFIRM — see {@link #TERMINAL_REPLY_TEMPLATE}'s javadoc.
     *
     * <p>The retraction is surfaced to the player (a berth just disappeared under a docked
     * vessel — the player has to know) and logged at WARN. It deliberately does NOT re-plan the
     * affected vessel or rewrite {@code activeVessels}: re-berthing on a retraction is a gameplay
     * rule, and task 26 is a hardening task. Filed in {@code docs/POST_DEMO_BACKLOG.md}.
     */
    private void logTerminalReply(HarbourMasterAgent hm, ACLMessage reply) {
        switch (reply.getPerformative()) {
            case ACLMessage.CONFIRM ->
                    log.info("terminal CONFIRM ({}) <- {}", reply.getContent(), reply.getSender().getLocalName());
            case ACLMessage.DISCONFIRM -> {
                JsonNode content = TerminalJson.readTreeOrNull(reply.getContent());
                String berthId = text(content, "berth_id");
                log.warn("terminal DISCONFIRM: berth {} retracted <- {}",
                        berthId, reply.getSender().getLocalName());
                hm.eventBus().publish(new NotificationEvent(
                        "Berth " + (berthId == null ? "?" : berthId.replace('_', ' '))
                                + " has been retracted by the terminal.",
                        NotificationEvent.Severity.WARNING, hm.simClock().nowSimMillis()));
            }
            default ->
                    log.debug("terminal REFUSE ({}) <- {}", reply.getContent(), reply.getSender().getLocalName());
        }
    }

    /**
     * Sends the customs pre-clearance REQUEST with exactly the keys
     * {@code HandleClearanceRequestBehaviour} reads ({@code vessel_type},
     * {@code cargo_class}), and records the conversation so
     * {@code HandleEmergencyBehaviour} can correlate the eventual reply back to a
     * vesselId (no reply here carries one). Retries like {@link #sendTerminalRequest}
     * for the same async-DF-registration reason.
     */
    private void sendCustomsRequest(HarbourMasterAgent hm, VesselSpec spec, int attemptsLeft) {
        AID customsAid = hm.resolveCustomsAid();
        if (customsAid == null) {
            if (attemptsLeft <= 0) {
                log.warn("no customs registered in DF after retries; skipping pre-clearance for {}", spec.vesselId());
                return;
            }
            myAgent.addBehaviour(new WakerBehaviour(myAgent, DF_RETRY_WALL_MS) {
                @Override
                protected void onWake() {
                    sendCustomsRequest(hm, spec, attemptsLeft - 1);
                }
            });
            return;
        }
        String conversationId = "cust-" + spec.vesselId();
        hm.pendingCustomsChecks().put(conversationId, spec.vesselId());
        ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
        request.addReceiver(customsAid);
        request.setConversationId(conversationId);
        // Lets HandleEmergencyBehaviour match the reply by protocol instead of
        // sender AID (createReply() preserves it) — task 11 adversarial review.
        request.setProtocol(HandleEmergencyBehaviour.CUSTOMS_CLEARANCE_PROTOCOL);
        request.setContent(TerminalJson.write(Map.of(
                "vessel_id", spec.vesselId(), "vessel_type", spec.vesselType(), "cargo_class", spec.cargoClass())));
        hm.sendLogged(request);
    }

    private void refuse(HarbourMasterAgent hm, ACLMessage original, String reason) {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.REFUSE);
        reply.setContent(TerminalJson.write(Map.of("reason", reason)));
        hm.sendLogged(reply);
        log.info("REFUSE {} -> {}", reason, original.getSender().getLocalName());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }
}
