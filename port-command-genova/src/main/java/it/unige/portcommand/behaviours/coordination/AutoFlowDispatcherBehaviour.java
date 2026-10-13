package it.unige.portcommand.behaviours.coordination;

import java.util.LinkedHashMap;
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
 * REFUSE on failure, ACCEPT_PROPOSAL with deal terms on success (design rule 4).
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
    public static final MessageTemplate TEMPLATE = MessageTemplate.or(REQUEST_TEMPLATE, TERMINAL_REPLY_TEMPLATE);
    public static final int DF_RETRY_ATTEMPTS = 3;
    // Must exceed ServiceLocator's 1 s DF-search cache TTL, or a retry inside that
    // window just replays the SAME stale "not found yet" result instead of a fresh
    // lookup (found by HarbourMasterAgentIT: a customs/terminal probe spawned just
    // before the vessel's REQUEST can lose this race — DF registration is async).
    private static final long DF_RETRY_WALL_MS = 1200L;
    // Placeholder "harbour entrance" point a vessel is escorted FROM — no live per-vessel
    // position channel exists yet (ADR-07); a placeholder coordinate, in the same spirit
    // as BerthPositions. Public since task 22 (was package-private for
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

        // RULE 4 (the design rules): the binding grant is gated by Prolog FIRST — nothing
        // is sent to the vessel before this call succeeds.
        if (!PrologQueries.isCompatible(contract.berthId(), spec.vesselType(), spec.draft(), spec.length(),
                spec.tonnage())) {
            refuse(hm, msg, "incompatible");
            return;
        }

        // S5 (ADR-16): compatible AND free. A contract pins its berth, so "free" here means THAT
        // berth — a pinned berth occupied at announce time parks the grant in the same wait queue
        // walk-ins use, keyed on the pin, instead of double-booking. Defensive in shipped content
        // (every packaged scenario's contracts are berth-disjoint in their absolute windows), but
        // a berth-sharing future contract now queues instead of colliding. Known consequence,
        // recorded in ADR-16: the contracted fee fires at the GRANT, so a deferred contracted
        // grant defers its fee with it.
        boolean pinnedBerthClosed = hm.movement().berthClosed(contract.berthId(),
                hm.simClock().nowSimMillis());
        boolean pinnedBerthOccupied = pinnedBerthClosed || hm.activeVessels().values().stream()
                .filter(t -> t.stage().ordinal() >= Stage.AWAITING_TUG.ordinal())
                .anyMatch(t -> contract.berthId().equals(t.assignedBerth()));
        if (pinnedBerthOccupied) {
            hm.activeVessels().put(spec.vesselId(), VesselTracking
                    .arriving(spec.vesselId(), spec, Channel.CONTRACTED, msg.getSender())
                    .withStage(Stage.AWAITING_BERTH));
            hm.berthWaitQueue().enqueue(spec.vesselId(), contract.contractedFee(),
                    contract.contractedHours(), hm.simClock().nowSimMillis());
            hm.pendingContractGrants().put(spec.vesselId(), contract.contractId());
            log.info("{}: pinned berth {} {} — contracted grant queued [R7,R30]",
                    spec.vesselId(), contract.berthId(), pinnedBerthClosed ? "closed" : "occupied");
            // S16 (C4): a queued contract used to be a log line only, while a queued walk-in was
            // announced. Its fee is booked at the grant, so the player should know it is waiting.
            hm.eventBus().publish(new NotificationEvent(
                    spec.vesselId() + " (contract " + contract.contractId() + ") queued for berth "
                            + contract.berthId().replace("berth_", "") + " — it is "
                            + (pinnedBerthClosed ? "closed" : "occupied") + " [R7,R30]",
                    NotificationEvent.Severity.INFO, hm.simClock().nowSimMillis()));
            return;
        }

        // S12 (live playtest F-4): the isCompatible call above is what decided this grant, so
        // the ACCEPT names R7. A constant at a fixed code path — never a second query.
        grantContracted(hm, spec, contract, msg.getSender(), "compatible [R7]");
    }

    /**
     * The contracted grant pipeline (S5 extraction): ACCEPT + fee event + tracking + tug CNP +
     * terminal REQUEST + customs pre-clearance. Called on the announce when the pinned berth is
     * free, and from the wait-queue release sweep when it frees. The ACCEPT is built fresh on the
     * announce conversation ({@code "berth-"+localName} — the same id {@code MessageFactory.reply}
     * would have produced), so the deferred path needs no retained original message.
     *
     * <p>{@code reason} (S12, live playtest F-4) rides the ACCEPT's {@code reason} key so the comm
     * log names the rule that decided the grant: {@code compatible [R7]} on the announce, the
     * R25-R30 queue rationale on the release. Informational only — {@code AwaitBerthBehaviour}
     * reads {@code berth_id}/{@code price}/{@code hours} by field and ignores it.
     */
    static void grantContracted(HarbourMasterAgent hm, VesselSpec spec, ServiceContract contract,
                                AID vesselAid, String reason) {
        ACLMessage accept = MessageFactory.create(ACLMessage.ACCEPT_PROPOSAL);
        accept.addReceiver(vesselAid);
        accept.setConversationId("berth-" + vesselAid.getLocalName());
        // Same shape and key order as ForwardWalkInToPlayerBehaviour.grantBerth's walk-in ACCEPT.
        Map<String, Object> grantContent = new LinkedHashMap<>();
        grantContent.put("berth_id", contract.berthId());
        grantContent.put("price", contract.contractedFee());
        grantContent.put("hours", contract.contractedHours());
        if (reason != null) {
            grantContent.put("reason", reason);
        }
        accept.setContent(TerminalJson.write(grantContent));
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
        // S16 (ADR-20): the grant RESERVES the berth and nothing more. Every granted ship is
        // AWAITING_TUG - "granted, not moving" - whether or not it needs a tug: it waits at the
        // channel entry until MovementControl.tryProceed sends it (escort on station, customs
        // cleared, berth open, weather safe). The pre-S16 code launched the tug Contract Net
        // from here, unconditionally, and wrote a 0-tug vessel IN_TRANSIT at once.
        int tugsRequired = PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
        hm.activeVessels().put(vesselId, VesselTracking
                .arriving(vesselId, spec, Channel.CONTRACTED, vesselAid)
                .withStage(Stage.AWAITING_TUG)
                .withBerth(contract.berthId()));
        sendTerminalRequest(hm, spec, contract, DF_RETRY_ATTEMPTS);

        // Customs (every arrival since S16, walk-ins included) and the movement decision -
        // hold, hire the escort, or proceed - are MovementControl's, for both grant pipelines.
        it.unige.portcommand.behaviours.MovementControl.afterGrant(hm, vesselId);

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
    static void sendTerminalRequest(HarbourMasterAgent hm, VesselSpec spec, ServiceContract contract,
                                    int attemptsLeft) {
        List<AID> terminals = hm.serviceLocator().findAll("terminal");
        if (terminals.isEmpty()) {
            if (attemptsLeft <= 0) {
                log.warn("no terminal registered in DF after retries; cannot request berth {}", contract.berthId());
                return;
            }
            hm.addBehaviour(new WakerBehaviour(hm, DF_RETRY_WALL_MS) {
                @Override
                protected void onWake() {
                    sendTerminalRequest(hm, spec, contract, attemptsLeft - 1);
                }
            });
            return;
        }
        // eta_sim MUST be the spec's ABSOLUTE arrival instant, never the contract's
        // expectedArrivalSimMillis (2026-08-10, audit P1-1 / the live walk's FIND-2). A contract's
        // ETA is a time-of-day OFFSET because the schedule recurs daily (ServiceContract's own
        // javadoc); TerminalState.requestBerth adds contractedHours to whatever it is handed and
        // ProcessCargoHandlingBehaviour sweeps the result against the ABSOLUTE sim clock. Every
        // scenario boots at 08:00 (28_800_000), so an offset-valued eta_sim expires before the
        // clock has even started: the tutorial's berth_1 went PROVISIONAL -> DOCKED -> FREE on two
        // consecutive ticks (the sub-2-second "berth fill flash"), and storm's ST-901 (eta 0, 10 h,
        // granted at ~29_760_000) freed at 36_000_000 - about 1.7 sim-hours into a 10-hour stay,
        // i.e. some 8.3 hours EARLY. This is a strict no-op for the DAILY contract stream,
        // whose SpawnVesselEvent already builds the spec from the same already-absolute value.
        BerthRequest req = new BerthRequest(spec.vesselId(), spec.vesselType(), spec.draft(), spec.length(),
                spec.tonnage(), contract.berthId(),
                // S16 (C9): never earlier than NOW. On the announce path the spec's ETA is this
                // instant (a vessel announces at its ETA), so nothing changes there; a grant
                // released from the wait queue used to book from the ORIGINAL ETA, and the
                // terminal freed the berth early by the whole time the ship had queued.
                Math.max(spec.etaArrivalSimMillis(), hm.simClock().nowSimMillis()),
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
                // S16 (C3): the retraction now MEANS something here. The berth is closed for a
                // fixed time - nobody is granted it, a ship granted it waits, a ship moored there
                // stays moored - and MovementControl tells the player until when.
                it.unige.portcommand.behaviours.MovementControl.closeBerth(hm, berthId);
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
    public static void sendCustomsRequest(HarbourMasterAgent hm, VesselSpec spec, int attemptsLeft) {
        AID customsAid = hm.resolveCustomsAid();
        if (customsAid == null) {
            if (attemptsLeft <= 0) {
                log.warn("no customs registered in DF after retries; skipping pre-clearance for {}", spec.vesselId());
                // S16: the ship was marked "customs pending" at its grant and waits on that.
                // With no customs agent to answer there is nothing to wait for.
                hm.pendingCustomsChecks().remove(
                        it.unige.portcommand.behaviours.MovementControl.customsConversation(spec.vesselId()));
                it.unige.portcommand.behaviours.MovementControl.onCustomsCleared(hm, spec.vesselId());
                return;
            }
            hm.addBehaviour(new WakerBehaviour(hm, DF_RETRY_WALL_MS) {
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
