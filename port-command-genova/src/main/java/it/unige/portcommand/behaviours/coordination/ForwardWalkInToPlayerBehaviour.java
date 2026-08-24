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
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.BerthWaitQueue;
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
            case "status_report" -> handleStatusReport(hm, msg, content);
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
     * "terminal removal" rule). S10 (bug hunt, hunter B finding 1): a WEATHER-HELD entry
     * refuses every advance — its escort was cancelled, so an arriving report (a stale
     * escort_complete from a tug whose CANCEL lost a race) is by definition about the escort
     * that no longer exists, and advancing would carry the vessel past the release sweep's
     * {@code held && AWAITING_TUG} filter: frozen mid-channel forever, berth never freed.
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
                (id, tracking) -> {
                    if (tracking.weatherHeld()) {
                        log.warn("{}: stage report {} refused — vessel is weather-held, the "
                                + "report is stale (its escort was cancelled)", vesselId, target);
                        return tracking;
                    }
                    return tracking.stage().ordinal() < target.ordinal()
                            ? tracking.withStage(target)
                            : tracking;
                });
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
        // S5 housekeeping: a departure frees a berth and retires any priority escalation.
        hm.priorityOverrides().remove(vesselId);
        hm.berthWaitQueue().remove(vesselId); // defensive; a queued vessel cannot normally depart
        releaseQueuedGrants(hm);
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
            forgetDialogue(hm, dialogueId);
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

        // Real urgency inputs since 2026-08-10 (audit P1-9); all three used to be hard-coded.
        //
        // timeUsedSec: elapsed REAL seconds since this dialogue opened, measured off the shared
        // SimClock conversion the tab's "~Ns remaining" countdown uses, against the same PUBLIC
        // nominal window (Settings.timeLimitSeconds). One clock, two consumers. Real seconds, not
        // sim: the term is normalised by a pacing-invariant window, and elapsed SIM seconds
        // against the old 600-sim-second constant saturated ~2 wall-seconds in at 300 s/day,
        // which would have swapped a dead term for a permanently-maxed one. This deliberately
        // tracks the OBSERVABLE window and never the vessel's hidden maxWaitMinutes (P-04).
        long openedAt = hm.negotiationOpenedAt()
                .computeIfAbsent(dialogueId, id -> hm.simClock().nowSimMillis());
        double timeUsedSec = hm.simClock()
                .realSecondsForSimMillis(Math.max(0L, hm.simClock().nowSimMillis() - openedAt));
        double lastPlayerOffer = hm.negotiationPlayerOffers().getOrDefault(dialogueId, 0.0);
        // threatenedWithdrawal, live since 2026-08-10 (audit P1-9 follow-up), DERIVED rather than
        // taken off the wire: the dialogue is on its last round when roundLimit + 1 - roundsUsed
        // <= 1. Both terms are public — roundLimit is a Settings value and roundsUsed is the same
        // counter the offer strip already renders to the player as "Round N of M" — so this reads
        // nothing the player cannot see, and P-04 holds without the vessel sending anything new.
        // It replaces the S2 note that no observable signal existed; that was true of a signal ON
        // THE WIRE, but the round budget was observable all along.
        // Scope, stated rather than implied: this captures the ROUND-LIMIT half only. A vessel
        // that is about to walk away for a personality-driven reason is not detectable here, and
        // must not be — that lives in WalkInState and is a hidden belief.
        boolean finalRound = Settings.load().roundLimit() + 1 - roundsUsed <= 1;

        // task 10's AutopilotExecuteBehaviour subscribes to this SAME event type
        // (INVARIANTS.md "Assistant (task 10)") — no separate INFORM needed; real
        // dispatch arrives with task 17, matching every other EventBus consumer today.
        WalkInDialogueSnapshot snapshot = WalkInDialogueSnapshot.of(
                spec.vesselId(), spec, berthId, hours, price, lastPlayerOffer, roundsUsed,
                timeUsedSec, finalRound);
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
        if (tracking.stage() != Stage.NEGOTIATING) {
            // S5 adversarial finding 1: without this guard a DUPLICATE deal_confirmed (nothing
            // produces one today — EvaluateCounterOfferBehaviour concludes before DealClosed
            // sends — but the closed-dialogue ACCEPT-spam residual shows the trigger is
            // reasoned-about) would re-run the grant against a held-set that now contains the
            // vessel's OWN berth: a backward stage write to AWAITING_BERTH, its occupied berth
            // handed to the next waiter, and a second DealClosedEvent.
            log.warn("{}: deal_confirmed at stage {} — duplicate/late CONFIRM ignored",
                    tracking.vesselId(), tracking.stage());
            return;
        }
        String dialogueId = msg.getConversationId();
        VesselSpec spec = tracking.spec();
        double price = num(content, "price");
        int hours = (int) num(content, "hours");

        // Deal economics land HERE, at the close — matching the vessel's own MarketHistory
        // DealRecord, which it writes at CONFIRM. The GRANT may be deferred below; money and the
        // tab close are not (ADR-16 D4 — LedgerCoordinator's vesselId dedupe makes this safe).
        // The Deal's berth is the dialogue's ADVISORY pick and may differ from (or precede) the
        // binding grant-time berth — no consumer reads it today (finding 9, stated not hidden).
        hm.eventBus().publish(new DealClosedEvent(new Deal("deal-" + spec.vesselId(), spec.vesselId(),
                hm.negotiationBerths().get(dialogueId),
                price, hours, hm.simClock().nowSimMillis(), Deal.Outcome.DEAL)));
        hm.eventBus().publish(new NegotiationClosedEvent(dialogueId, spec.vesselId(), Deal.Outcome.DEAL));
        forgetDialogue(hm, dialogueId);

        // S5 (ADR-16): the berth is RE-PICKED here against live occupancy, not carried from the
        // opening offer — the occupancy timeline proved that pinning the offer-time pick makes
        // the storm walk-in wait ~594 sim-min for a berth while another compatible one frees in
        // 96. RULE 4 holds: findCompatibleBerths (R8) is the binding Prolog gate, and occupancy
        // is the coordinator's own tracked state (Java on purpose — dynamic Prolog facts would
        // outlive the world in the process-wide engine; see ADR-16 for the leak scenario).
        List<String> free = freeCompatibleBerths(hm, spec);
        if (free.isEmpty()) {
            // Nothing free RIGHT NOW is no longer a refusal (S3's reverted mistake) and no
            // longer a silent double-book (the A-02 residual): the grant parks until a berth
            // frees. Post-deal waits are patience-free — negotiation timeout concluded at the
            // deal, and AwaitBerthBehaviour has no deadline.
            hm.berthWaitQueue().enqueue(spec.vesselId(), price, hours, hm.simClock().nowSimMillis());
            hm.activeVessels().put(spec.vesselId(),
                    tracking.withStage(Stage.AWAITING_BERTH).withBerth(null));
            // Finding 4 (defensive): distinguish "all held" from "fits nowhere" — the latter is
            // unreachable (handleOffer REFUSEs no_compatible_berth at dialogue open) but the
            // note is a factual claim citing rule ids, so it must not assert the wrong one.
            boolean fitsSomewhere = !PrologQueries.findCompatibleBerths(
                    spec.vesselType(), spec.draft(), spec.length(), spec.tonnage()).isEmpty();
            String note = fitsSomewhere
                    ? spec.vesselId() + " queued for a berth — every compatible berth is occupied [R7,R30]"
                    : spec.vesselId() + " queued, but NO berth fits this vessel at all [R8] — "
                            + "this should have been refused at the dialogue";
            if (!fitsSomewhere) {
                log.warn("{}: queued with zero compatible berths — open-time refusal was bypassed",
                        spec.vesselId());
            }
            hm.eventBus().publish(new it.unige.portcommand.gui.events.NotificationEvent(note,
                    it.unige.portcommand.gui.events.NotificationEvent.Severity.INFO,
                    hm.simClock().nowSimMillis()));
            hm.eventBus().publish(new AssistantChatEvent(dialogueId, note));
            log.info("{}: no compatible berth free — queued ({} waiting)",
                    spec.vesselId(), hm.berthWaitQueue().size());
            return;
        }
        grantBerth(hm, spec.vesselId(), msg.getSender(), free.get(0), price, hours, null);
    }

    /**
     * Every R8-compatible berth (the binding Prolog gate, rule 4) minus the berths held by a
     * GRANTED vessel — stages AWAITING_TUG..DEPARTING. NEGOTIATING candidates deliberately do
     * NOT hold (S5): reservation happens at grant, not at chat-open, so a slow negotiation
     * cannot starve a faster one. AWAITING_BERTH vessels hold nothing (their berth is null).
     */
    static List<String> freeCompatibleBerths(HarbourMasterAgent hm, VesselSpec spec) {
        List<String> compatible = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        if (compatible.isEmpty()) {
            return List.of();
        }
        Set<String> held = hm.activeVessels().values().stream()
                .filter(t -> t.stage().ordinal() >= Stage.AWAITING_TUG.ordinal())
                .map(VesselTracking::assignedBerth)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return compatible.stream().filter(b -> !held.contains(b)).toList();
    }

    /**
     * The one grant pipeline (S5 extraction): the ACCEPT on the vessel's {@code berth-} channel,
     * the stage write, the occupancy claim (via that stage write — occupancy IS tracked state),
     * and the tug Contract Net. Called at CONFIRM when a berth is free, and from the release
     * sweep when one frees for a queued vessel. {@code queueRationale} is non-null only on the
     * released path and rides the ACCEPT's {@code reason} so the comm log renders the rule ids.
     */
    static void grantBerth(HarbourMasterAgent hm, String vesselId, AID vesselAid, String berthId,
                           double price, int hours, String queueRationale) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null) {
            log.warn("grantBerth for untracked vessel {} — dropped", vesselId);
            return;
        }
        VesselSpec spec = tracking.spec();
        String berthConversationId = "berth-" + vesselAid.getLocalName();

        ACLMessage accept = MessageFactory.create(ACLMessage.ACCEPT_PROPOSAL);
        accept.addReceiver(vesselAid);
        accept.setConversationId(berthConversationId);
        Map<String, Object> grantContent = new java.util.LinkedHashMap<>();
        grantContent.put("berth_id", berthId);
        grantContent.put("price", price);
        grantContent.put("hours", hours);
        if (queueRationale != null) {
            grantContent.put("reason", queueRationale);
        }
        accept.setContent(TerminalJson.write(grantContent));
        hm.sendLogged(accept);

        // Checkpoint-#6 F1 (fixed 2026-07-18): a vessel that needs no escort never awaits one —
        // it starts its transit the moment the grant lands (AwaitBerthBehaviour attaches the
        // transit unconditionally), so AWAITING_TUG was a from-the-start lie for 0-tug vessels
        // (W9 departed still showing it). Stage the grant by what actually happens next.
        int tugsRequired = PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
        Stage grantStage = tugsRequired > 0 ? Stage.AWAITING_TUG : Stage.IN_TRANSIT;
        hm.activeVessels().put(vesselId, tracking.withStage(grantStage).withBerth(berthId));
        log.info("{}: berth GRANTED {} (price {}, {}h{})", vesselId, berthId, price, hours,
                queueRationale == null ? "" : ", " + queueRationale);

        // Task 15: closes the walk-in CNP-dispatch gap ADR-08 left deliberately open —
        // mirrors AutoFlowDispatcherBehaviour's own tugsRequired>0 gate exactly. Terminal/customs
        // dispatch stays out of scope (a separate, larger parity gap — see ADR-09).
        if (tugsRequired > 0) {
            Position berthPosition = BerthPositions.position(berthId);
            hm.cnpCoordinator().initiate(new CnpRequest(spec.vesselId(), vesselAid,
                    AutoFlowDispatcherBehaviour.CHANNEL_ENTRY, berthPosition, tugsRequired,
                    spec.vesselType()));
        }
    }

    /**
     * The queue release sweep (S5): whenever occupancy may have changed — a departure, or the
     * 15-sim-min periodic weather snapshot (the idempotent backstop that turns a lost
     * {@code departed} INFORM from "berth gone forever" into "berth back late" — a claim made
     * TRUE only in S10: the periodic sweep now reaps directory-absent DOCKED+ entries first,
     * see {@code HandleWeatherAlertBehaviour.reapDepartedGhosts}; this release alone never
     * removed the ghost that held the berth) — grant every queued vessel a now-free compatible
     * berth, HIGHEST PRIORITY FIRST.
     *
     * <p>This is where the priority module earns its keep (R25-R30): candidates are ranked by
     * {@code effective_priority} (R30, through the R26 class bridge — a ferry's
     * {@code scheduled_ferry} rank 3 outranks everyone's default 4), compared pairwise with
     * {@code higher_priority} (R27 — genuinely CALLED, not name-dropped), FIFO on ties. The
     * winning grant's ACCEPT carries the class, rank and rule ids in its rendered reason.
     */
    public static void releaseQueuedGrants(HarbourMasterAgent hm) {
        if (hm.berthWaitQueue().isEmpty()) {
            return;
        }
        while (true) {
            List<BerthWaitQueue.Waiting> waiting = hm.berthWaitQueue().inArrivalOrder();
            BerthWaitQueue.Waiting best = null;
            PrologQueries.VesselPriority bestPriority = null;
            String bestBerth = null;
            for (BerthWaitQueue.Waiting candidate : waiting) {
                VesselTracking tracking = hm.activeVessels().get(candidate.vesselId());
                if (tracking == null) {
                    hm.berthWaitQueue().remove(candidate.vesselId()); // departed/ghost cleanup
                    hm.pendingContractGrants().remove(candidate.vesselId());
                    continue;
                }
                // A queued CONTRACTED grant is pinned to its contract's berth — eligible only
                // when exactly that berth frees; a walk-in takes any free compatible one.
                String pinnedContract = hm.pendingContractGrants().get(candidate.vesselId());
                if (pinnedContract != null && hm.contracts().get(pinnedContract) == null) {
                    // S5 adversarial finding 3: a pin whose contract left hm.contracts() used
                    // to yield List.of() on every sweep FOREVER — a silently stranded vessel
                    // (AwaitBerthBehaviour has no deadline by design). Degrade loudly: drop
                    // the pin and serve it as a walk-in grant (berth but no contracted
                    // fee/terminal/customs pipeline — stated cost of the degraded mode).
                    log.warn("{}: pinned contract {} no longer exists — releasing as a plain "
                            + "walk-in grant", candidate.vesselId(), pinnedContract);
                    hm.pendingContractGrants().remove(candidate.vesselId());
                    pinnedContract = null;
                }
                List<String> free;
                if (pinnedContract != null) {
                    var contract = hm.contracts().get(pinnedContract);
                    free = freeCompatibleBerths(hm, tracking.spec()).contains(contract.berthId())
                            ? List.of(contract.berthId()) : List.of();
                } else {
                    free = freeCompatibleBerths(hm, tracking.spec());
                }
                if (free.isEmpty()) {
                    continue;
                }
                PrologQueries.VesselPriority priority = PrologQueries.effectivePriority(
                        tracking.spec().vesselType(),
                        hm.priorityOverrides().get(candidate.vesselId()));
                // R27 higher_priority/2 IS the comparator; arrival order breaks ties.
                if (best == null || PrologQueries.higherPriority(
                        priority.priorityClass(), bestPriority.priorityClass())) {
                    best = candidate;
                    bestPriority = priority;
                    bestBerth = free.get(0);
                }
            }
            if (best == null) {
                return; // nobody grantable this round
            }
            hm.berthWaitQueue().remove(best.vesselId());
            VesselTracking tracking = hm.activeVessels().get(best.vesselId());
            String releasedContract = hm.pendingContractGrants().remove(best.vesselId());
            if (releasedContract != null) {
                // Contracted release re-runs the FULL contracted pipeline (fee event, terminal
                // REQUEST, customs) — a walk-in grant would silently skip all three.
                log.info("{}: pinned berth freed — releasing contracted grant {} ({} rank {} "
                                + "[R25,R26,R27,R30])", best.vesselId(), releasedContract,
                        bestPriority.priorityClass(), bestPriority.rank());
                AutoFlowDispatcherBehaviour.grantContracted(hm, tracking.spec(),
                        hm.contracts().get(releasedContract), tracking.aid());
            } else {
                String rationale = "after_queue " + bestPriority.priorityClass()
                        + " rank " + bestPriority.rank() + " [R25,R26,R27,R30]";
                grantBerth(hm, best.vesselId(), tracking.aid(), bestBerth,
                        best.price(), best.hours(), rationale);
            }
            // Loop: the grant consumed a berth; another waiter may still fit a different one.
        }
    }

    /**
     * Drops every per-dialogue entry the HarbourMaster holds for {@code dialogueId}, on every
     * terminal path (grant, withdraw, no-compatible-berth). Centralised 2026-08-10 (audit P1-9)
     * when the P1-9 fix added two more maps to the set: four separate {@code remove} sites is
     * exactly the shape where the next map gets added to three of them and leaks in the fourth.
     */
    private static void forgetDialogue(HarbourMasterAgent hm, String dialogueId) {
        hm.negotiationBerths().remove(dialogueId);
        hm.negotiationRounds().remove(dialogueId);
        hm.negotiationOpenedAt().remove(dialogueId);
        hm.negotiationPlayerOffers().remove(dialogueId);
    }

    private void handleWithdraw(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        VesselTracking tracking = findByAid(hm, msg.getSender());
        if (tracking != null) {
            hm.activeVessels().remove(tracking.vesselId());
            // S5 finding 12: a withdrawing vessel leaves EVERY per-vessel side table, not just
            // the tracking — the override map has no self-healing sweep of its own.
            hm.priorityOverrides().remove(tracking.vesselId());
            hm.pendingContractGrants().remove(tracking.vesselId());
            hm.berthWaitQueue().remove(tracking.vesselId());
        }
        String dialogueId = msg.getConversationId();
        forgetDialogue(hm, dialogueId);
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
        // S5: the pick is ADVISORY — the dialogue's candidate berth for the chat and the EV
        // hint. The binding choice happens at grant time via freeCompatibleBerths, which re-picks
        // against live occupancy (granted stages only) and queues when nothing is free. So the
        // all-held fallback below no longer risks a double-book; it just names a plausible berth
        // for the conversation. The held-set here KEEPS NEGOTIATING candidates on purpose,
        // unlike freeCompatibleBerths: two concurrent dialogues should discuss different berths
        // (pinned by twoConcurrentWalkInsOfTheSameClass_getDifferentBerths), even though neither
        // reserves anything until its grant.
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
            //
            // AUDIT P1-12 WAS ATTEMPTED HERE AND REVERTED (2026-08-10). Refusing instead of falling
            // back looks obviously right and is not: `held` is every TRACKED vessel's assignedBerth,
            // contracted ones included, for their whole stay — while R8 gives four of the five
            // vessel types exactly ONE compatible berth (ferry->berth_4, tanker->berth_1,
            // container->berth_2, cruise->berth_2; only cargo_vessel has three). So "all compatible
            // berths held" is the COMMON case, not the exceptional one, and refusing there deleted
            // two of busy_day's three scripted walk-ins and the storm's only one, and hung
            // SaveLoadMidTransitIT on a walk-in left permanently NEGOTIATING.
            //
            // The real fix needs actual berth OCCUPANCY, not "someone was once granted this berth":
            // the HarbourMaster has no PortStateArtifact reference, and a walk-in never sends the
            // terminal a berth REQUEST at all (ADR-09), so nothing here can currently tell a berth
            // that is genuinely busy from one whose vessel is long gone. That is the same gap ADR-09
            // already tracks; P1-12 is blocked behind it rather than fixable in this method.
            // No longer the A-02 residual (S5, ADR-16): this candidate is conversational only —
            // the grant re-picks against live occupancy and queues when nothing is free, so an
            // occupied candidate here can never become a double-booked grant.
            log.info("{}: every compatible berth {} is occupied — dialogue proceeds on candidate "
                            + "{}; the grant will re-pick or queue",
                    spec.vesselId(), compatible, compatible.get(0));
        }
        return free.orElse(compatible.get(0));
    }

    /**
     * The vessel's answer to the player's "ask" (audit P1-6), rendered into the tab that asked.
     *
     * <p>Completes a round trip that used to stop dead at the vessel: the HarbourMaster relayed
     * the player's ASK as a QUERY_REF and no template on any agent matched it, so the question
     * produced silence in the chat. Routed as an {@code AssistantChatEvent} for the same reason
     * the REFUSE relay above is — {@code ChatPanel} dispatches it by exact {@code dialogueId}, and
     * the conversation id the vessel replied on IS that dialogue id.
     *
     * <p>Everything here is observable: the vessel's standing offer and hours are already on the
     * offer strip, and the round count is already rendered as "Round N of M".
     */
    private void handleStatusReport(HarbourMasterAgent hm, ACLMessage msg, JsonNode content) {
        String dialogueId = msg.getConversationId();
        VesselTracking tracking = findByAid(hm, msg.getSender());
        String berth = tracking != null && tracking.assignedBerth() != null
                ? tracking.assignedBerth() : hm.negotiationBerths().get(dialogueId);
        String text = String.format(java.util.Locale.ROOT,
                "%s: standing offer EUR%.0f for %dh, round %d%s.",
                text(content, "vessel_id"), num(content, "price"), (int) num(content, "hours"),
                (int) num(content, "rounds_used"),
                berth == null ? "" : ", berth under discussion " + berth);
        hm.eventBus().publish(new AssistantChatEvent(dialogueId, text));
        log.info("status query answered for {}", dialogueId);
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
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
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
                            MessageTemplate.and(
                                    MessageTemplate.not(MessageTemplate.MatchProtocol(HandleEmergencyBehaviour.CUSTOMS_CLEARANCE_PROTOCOL)),
                                    // S9 (P1-8): a tug's post-award decline is a REFUSE on the game
                                    // ontology — without this exclusion the A-01 vessel-refuse arm
                                    // consumed it and surfaced a phantom chat line while the real
                                    // handler starved (found by the born-red e2e's silent run).
                                    MessageTemplate.not(MessageTemplate.MatchProtocol(
                                            it.unige.portcommand.behaviours.cnp
                                                    .RefuseAssignmentBehaviour.POST_AWARD_DECLINE_PROTOCOL))),
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
