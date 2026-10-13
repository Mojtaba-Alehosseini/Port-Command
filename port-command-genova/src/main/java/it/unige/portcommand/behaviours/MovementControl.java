package it.unige.portcommand.behaviours;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.TugMath;
import it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.behaviours.coordination.ForwardWalkInToPlayerBehaviour;
import it.unige.portcommand.behaviours.coordination.TransitToVesselBehaviour;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.MovementSafetyEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.MovementBook;
import it.unige.portcommand.harbourmaster.MovementGate;
import it.unige.portcommand.harbourmaster.PortStatusSnapshot;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every decision about a ship MOVING, in one place (S16, 2026-10-13, ADR-20).
 *
 * <p>Before S16 a ship started its own channel leg the moment its berth was granted — before any
 * tug was hired, whatever the weather, whether or not customs had answered — and the
 * HarbourMaster learned that it had "docked" from a tug some four game hours later. Tug hiring,
 * the weather hold, the queue release, the restore re-dispatch and the departure each had their
 * own idea of when a movement may start, and most had none. Now:
 *
 * <ul>
 *   <li>a berth grant RESERVES the berth and nothing more: the ship waits at the channel entry
 *       ({@code Stage.AWAITING_TUG} — "granted, not moving");</li>
 *   <li>{@link #evaluate} is the single decision point for a waiting ship: hold it if the
 *       movement gate says the weather is unsafe, hire the tugs it still lacks, or — when
 *       nothing is in its way — {@link #tryProceed};</li>
 *   <li>{@link #tryProceed} sends the ONE order that starts a ship ({@code proceed_to_berth})
 *       and, in the same action, the escort's ({@code begin_escort}); only then does the
 *       tracking read {@code IN_TRANSIT}. Its conditions: the full escort on station (R11's
 *       count, every tug reported), customs cleared, the berth open, the weather safe;</li>
 *   <li>{@link #onWeather} is both sweeps — hold every waiting or moving ship the gate refuses,
 *       release every held ship it lets go (with the margin) — and runs on every weather
 *       message, so nothing depends on which kind of alert happened to be sent;</li>
 *   <li>a departure is a movement too: {@link #onServiceComplete} clears the ship to leave or
 *       keeps it at its berth.</li>
 * </ul>
 *
 * <p><b>Prolog decides.</b> Whether the weather allows a movement is {@link MovementGate}
 * (R15, R16, R17, R19, asked through {@code PrologQueries}); how many tugs a ship needs is R11;
 * who goes first is R27 over each ship's R30 class. This class holds no rule of its own — it
 * asks, and acts on the answer.
 *
 * <p>Static helpers over the agent, in the style of
 * {@code ForwardWalkInToPlayerBehaviour.releaseQueuedGrants}: not a behaviour (the catalogue is
 * locked), and <b>HarbourMaster thread only</b> — every caller is one of the agent's own
 * behaviours. The waiting-side facts that are not in {@link VesselTracking} live in the agent's
 * {@link MovementBook}.
 */
public final class MovementControl {

    private static final Logger log = LoggerFactory.getLogger(MovementControl.class);

    /** How long a flooded berth stays closed: 60 game minutes (the owner's number, S16). */
    public static final long FLOOD_CLOSURE_SIM_MILLIS = 3_600_000L;

    /** The priority class of a walk-in arrival — ranked below scheduled arrivals (R25 fact, C4). */
    public static final String WALK_IN_CLASS = "walk_in_arrival";

    private MovementControl() {
    }

    // =========================================================================================
    // Verdicts
    // =========================================================================================

    /** The HarbourMaster's current reading as the gate takes it, or {@code null} if it has none. */
    public static MovementGate.Reading reading(HarbourMasterAgent hm) {
        HarbourMasterAgent.WeatherReading w = hm.lastWeather();
        return w == null ? null
                : new MovementGate.Reading(w.windKnots(), w.visibility(), w.swell(), w.state());
    }

    /**
     * The gate's answer for {@code tracking} right now: the release test (with the wind margin)
     * for a ship that is held, the plain movement test for every other.
     */
    public static MovementGate.Verdict verdictFor(HarbourMasterAgent hm, VesselTracking tracking) {
        return tracking.weatherHeld()
                ? MovementGate.release(tracking.vesselType(), reading(hm))
                : MovementGate.check(tracking.vesselType(), reading(hm));
    }

    // =========================================================================================
    // After a grant; the decision point
    // =========================================================================================

    /**
     * Called by both grant pipelines once the ACCEPT is on the wire and the tracking reads
     * AWAITING_TUG with its berth: asks customs to clear the ship, then decides what it may do.
     */
    public static void afterGrant(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null) {
            return;
        }
        if (hm.customsOffice()) {
            // Marked pending HERE, synchronously: the REQUEST itself may go out a few seconds
            // later (the customs agent's DF registration is asynchronous), and a ship that needs
            // no escort would otherwise be cleared to proceed before customs was even asked.
            hm.pendingCustomsChecks().put(customsConversation(vesselId), vesselId);
            AutoFlowDispatcherBehaviour.sendCustomsRequest(hm, tracking.spec(),
                    AutoFlowDispatcherBehaviour.DF_RETRY_ATTEMPTS);
        }
        evaluate(hm, vesselId);
    }

    /** The conversation id of a vessel's customs clearance. */
    public static String customsConversation(String vesselId) {
        return "cust-" + vesselId;
    }

    /**
     * The decision point for a ship that holds a berth and is not moving. Idempotent: safe to
     * call whenever something that matters to it may have changed.
     */
    public static void evaluate(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null || tracking.stage() != Stage.AWAITING_TUG || tracking.assignedBerth() == null) {
            return;
        }
        MovementGate.Verdict verdict = verdictFor(hm, tracking);
        if (!verdict.safe()) {
            hold(hm, tracking, verdict);
            return;
        }
        if (tracking.weatherHeld()) {
            release(hm, tracking);
            tracking = hm.activeVessels().get(vesselId);
        }
        if (escortShortfall(tracking) > 0) {
            // No tug is hired for a ship whose berth is closed: it could not move for up to an
            // hour, and a tug waiting on station for it is a tug every other ship lacks. Tugs
            // it already has stay with it; the reopening re-evaluates it (reopenDueBerths).
            if (!hm.movement().berthClosed(tracking.assignedBerth(), hm.simClock().nowSimMillis())) {
                requestEscort(hm, tracking);
            }
            return;
        }
        tryProceed(hm, vesselId);
    }

    /** How many tugs {@code tracking} still lacks: R11's count minus the tugs assigned. */
    public static int escortShortfall(VesselTracking tracking) {
        return tugsRequired(tracking.spec()) - tracking.assignedTugs().size();
    }

    private static int tugsRequired(VesselSpec spec) {
        return PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
    }

    /** Tenders for the tugs the ship lacks — unless a tender for it is already under way. */
    private static void requestEscort(HarbourMasterAgent hm, VesselTracking tracking) {
        int missing = escortShortfall(tracking);
        if (missing <= 0 || !hm.movement().openEscortRequest(tracking.vesselId())) {
            return;
        }
        hm.cnpCoordinator().initiate(new CnpRequest(tracking.vesselId(), tracking.aid(),
                AutoFlowDispatcherBehaviour.CHANNEL_ENTRY,
                BerthPositions.position(tracking.assignedBerth()), missing, tracking.vesselType()));
        log.info("{}: tendering for {} tug(s) toward {}", tracking.vesselId(), missing,
                tracking.assignedBerth());
    }

    // =========================================================================================
    // Contract Net hooks (InitiateCNPBehaviour / AwardContractBehaviour)
    // =========================================================================================

    /**
     * May the Contract Net for {@code vesselId} still go ahead — at its launch, which can be
     * long after it was asked for (a queued session, a retry)? Not if the ship is gone, moving,
     * held, already fully escorted, or if the weather has turned: an unsafe verdict holds the
     * ship here, so the launch that found it is also what reports it.
     */
    public static boolean mayTender(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null || tracking.stage() != Stage.AWAITING_TUG || tracking.weatherHeld()
                || tracking.assignedBerth() == null || escortShortfall(tracking) <= 0
                || hm.movement().berthClosed(tracking.assignedBerth(), hm.simClock().nowSimMillis())) {
            return false;
        }
        MovementGate.Verdict verdict = MovementGate.check(tracking.vesselType(), reading(hm));
        if (!verdict.safe()) {
            hold(hm, tracking, verdict);
            return false;
        }
        return true;
    }

    /**
     * Whether a ship OTHER than {@code vesselId} is waiting with an escort that is still short.
     *
     * <p>At most one ship at a time may hold an under-strength escort (S16 adversarial review).
     * A partial award keeps a tug on station until the rest arrives; if every waiting ship were
     * allowed one, four two-tug ships could each sit on one of the four tugs and none would ever
     * be complete. So the award keeps a partial escort only for the first ship to get one, and
     * {@link #compareForTender} sends the next free tug to that ship before any other.
     */
    public static boolean anotherShipHoldsAPartialEscort(HarbourMasterAgent hm, String vesselId) {
        return hm.activeVessels().values().stream()
                .filter(t -> !t.vesselId().equals(vesselId))
                .filter(t -> t.stage() == Stage.AWAITING_TUG && t.assignedBerth() != null)
                .anyMatch(t -> !t.assignedTugs().isEmpty() && escortShortfall(t) > 0);
    }

    /**
     * The order in which waiting ships are looked at when tugs may be had: the more critical
     * class first (R27), then the ship that already has part of its escort — it is the closest
     * to moving and its tug is the one standing idle — then by id, so the order never depends on
     * a hash map's.
     */
    public static int compareForTender(HarbourMasterAgent hm, VesselTracking a, VesselTracking b) {
        int byClass = compareByPriority(hm, a, b);
        if (byClass != 0) {
            return byClass;
        }
        int byEscort = Integer.compare(b.assignedTugs().size(), a.assignedTugs().size());
        return byEscort != 0 ? byEscort : a.vesselId().compareTo(b.vesselId());
    }

    /** The tender for {@code vesselId} reached an end (award, no award, not launched). */
    public static void tenderClosed(HarbourMasterAgent hm, String vesselId) {
        hm.movement().closeEscortRequest(vesselId);
    }

    /**
     * Tugs were just awarded to {@code vesselId}. A ship released from a weather hold hears
     * {@code weather_resume} NOW — when its escort is actually hired — and not at the release:
     * before S16 the resume went out first and the hiring might then find nobody.
     */
    public static void onAwarded(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking != null && hm.movement().takeResumeOwed(vesselId)) {
            sendResume(hm, tracking);
        }
    }

    /**
     * The quick retries are spent and the ship still lacks tugs. It keeps waiting — it never
     * sails without its escort (before S16 the HarbourMaster gave up here and the ship, which had
     * left for its berth at the grant, simply docked unescorted and free of charge). The next
     * tug to report itself available, or the periodic sweep, tenders again.
     */
    public static void onTenderExhausted(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null) {
            return;
        }
        if (hm.movement().firstTugWaitNotice(vesselId)) {
            hm.eventBus().publish(new NotificationEvent(
                    displayName(tracking) + " is waiting for a free tug — every tug is busy [R11]",
                    NotificationEvent.Severity.INFO, hm.simClock().nowSimMillis()));
        }
        log.info("{}: no tug available after the quick retries — waiting; re-tendered when a tug "
                + "frees", vesselId);
    }

    // =========================================================================================
    // The order to move
    // =========================================================================================

    /**
     * Sends the ship to its berth if — and only if — nothing is in its way: the weather is safe
     * for it (the gate, asked again here), its berth is open, customs has cleared it, and every
     * tug R11 requires is assigned AND on station. One action sends the escort its
     * {@code begin_escort} and the ship its {@code proceed_to_berth}, and writes IN_TRANSIT.
     */
    public static void tryProceed(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null || tracking.stage() != Stage.AWAITING_TUG || tracking.weatherHeld()
                || tracking.blocked() || tracking.assignedBerth() == null) {
            return;
        }
        MovementGate.Verdict verdict = MovementGate.check(tracking.vesselType(), reading(hm));
        if (!verdict.safe()) {
            hold(hm, tracking, verdict);
            return;
        }
        MovementBook book = hm.movement();
        long now = hm.simClock().nowSimMillis();
        if (book.berthClosed(tracking.assignedBerth(), now) || customsPending(hm, vesselId)
                || escortShortfall(tracking) > 0 || !book.allOnStation(vesselId, tracking.assignedTugs())) {
            return;
        }
        if (book.takeResumeOwed(vesselId)) {
            sendResume(hm, tracking); // a ship with no escort to hire hears of its release here
        }
        long legSimMillis = channelLegSimMillis(tracking.assignedBerth());
        Map<String, String> station = book.onStation(vesselId);
        for (String tugId : tracking.assignedTugs()) {
            ACLMessage order = MessageFactory.create(ACLMessage.INFORM);
            order.addReceiver(new AID(tugId, AID.ISLOCALNAME));
            order.setProtocol(TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL);
            order.setConversationId(station.get(tugId));
            order.setContent(TerminalJson.write(Map.of("event", "begin_escort", "vessel_id", vesselId)));
            hm.sendLogged(order);
        }
        ACLMessage proceed = MessageFactory.create(ACLMessage.INFORM);
        proceed.addReceiver(tracking.aid());
        proceed.setProtocol(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("event", "proceed_to_berth");
        content.put("vessel_id", vesselId);
        content.put("berth_id", tracking.assignedBerth());
        content.put("transit_sim_millis", legSimMillis);
        content.put("escort", tracking.assignedTugs().size());
        proceed.setContent(TerminalJson.write(content));
        auditMoveOrder(hm, tracking);
        hm.sendLogged(proceed);
        hm.activeVessels().put(vesselId, tracking.withStage(Stage.IN_TRANSIT));
        book.clearStation(vesselId);
        log.info("{}: PROCEED to {} with {} tug(s) — channel leg {} sim-s", vesselId,
                tracking.assignedBerth(), tracking.assignedTugs().size(), legSimMillis / 1000L);
    }

    /**
     * How long the channel leg to {@code berthId} takes: the distance from the channel entry to
     * the berth at the tugs' speed — the escort leg's own duration ({@code TugMath}, the same
     * conversions the tug moves by), so ship and escort arrive together. A ship that needs no
     * tug makes the same leg at the same speed.
     */
    public static long channelLegSimMillis(String berthId) {
        double km = TugMath.distanceKm(AutoFlowDispatcherBehaviour.CHANNEL_ENTRY, BerthPositions.position(berthId));
        return Math.max(1_000L, Math.round(TugMath.etaMinutes(km, AgentRoster.TUG_TOP_SPEED_KNOTS) * 60_000.0));
    }

    private static boolean customsPending(HarbourMasterAgent hm, String vesselId) {
        return hm.pendingCustomsChecks().containsKey(customsConversation(vesselId));
    }

    // =========================================================================================
    // Weather
    // =========================================================================================

    /**
     * Both sweeps, for the reading just recorded. Run on EVERY weather message — a threshold
     * alert, a clearing, the 15-minute snapshot, the broadcast after each step — so neither a
     * hold nor a release depends on which kind of message happened to carry the news.
     *
     * <ol>
     *   <li><b>Hold</b> every ship that holds a berth and has not docked — waiting OR moving,
     *       with or without tugs — for which the gate now refuses a movement, most critical
     *       class first (R27). Before S16 only a ship that was "in transit with tugs" could be
     *       held: a ferry never, a ship still waiting for its tugs never.</li>
     *   <li><b>Release</b> every held ship the gate lets go, margin included — ships caught
     *       actually moving (escalated to the emergency class) first, which is R29's partition.</li>
     *   <li><b>Departures</b> waiting at their berths are asked again.</li>
     *   <li>Every waiting ship is re-evaluated: the backstop that tenders again for a ship
     *       whose tugs were all busy.</li>
     * </ol>
     */
    public static void onWeather(HarbourMasterAgent hm) {
        List<VesselTracking> exposed = hm.activeVessels().values().stream()
                .filter(t -> t.stage() == Stage.AWAITING_TUG || t.stage() == Stage.IN_TRANSIT)
                .filter(t -> t.assignedBerth() != null)
                .sorted((a, b) -> compareByPriority(hm, a, b))
                .toList();
        List<VesselTracking> releasable = new ArrayList<>();
        for (VesselTracking tracking : exposed) {
            MovementGate.Verdict verdict = verdictFor(hm, tracking);
            if (!verdict.safe()) {
                hold(hm, tracking, verdict);
            } else if (tracking.weatherHeld()) {
                releasable.add(tracking);
            }
        }
        // R29: emergency_overrides_all holds for every class the emergency class OVERRIDES, so
        // the ships it fails for (the emergency peers) go first.
        Map<Boolean, List<VesselTracking>> byClass = releasable.stream().collect(Collectors.partitioningBy(
                t -> !PrologQueries.emergencyOverridesAll(effectiveClassOf(hm, t))));
        for (VesselTracking tracking : byClass.get(true)) {
            evaluate(hm, tracking.vesselId());
        }
        for (VesselTracking tracking : byClass.get(false)) {
            evaluate(hm, tracking.vesselId());
        }
        for (String vesselId : hm.movement().departureRequests()) {
            tryClearDeparture(hm, vesselId);
        }
        exposed.stream()
                .filter(t -> t.stage() == Stage.AWAITING_TUG)
                .sorted((a, b) -> compareForTender(hm, a, b))
                .map(VesselTracking::vesselId)
                .toList()
                .forEach(id -> evaluate(hm, id));
    }

    /**
     * Holds {@code tracking}: recalls its tugs (CANCEL, the escort set emptied), tells the ship
     * ({@code weather_hold} — a ship under way goes back to the channel entry), and writes it
     * AWAITING_TUG + held with the rules that refused it. A ship that was really MOVING when it
     * was caught is the emergency (R26/R29) — one that was only waiting keeps its class.
     * Already held: only the recorded rules are refreshed.
     */
    public static void hold(HarbourMasterAgent hm, VesselTracking tracking, MovementGate.Verdict verdict) {
        String vesselId = tracking.vesselId();
        boolean rulesChanged = hm.movement().setHoldRules(vesselId, verdict.ruleIds());
        if (tracking.weatherHeld()) {
            if (rulesChanged) {
                log.debug("{}: still held — now {}", vesselId, verdict.reason());
            }
            return;
        }
        boolean wasMoving = tracking.stage() == Stage.IN_TRANSIT;
        for (String tugId : tracking.assignedTugs()) {
            ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
            cancel.addReceiver(new AID(tugId, AID.ISLOCALNAME));
            // S11 (fix review, R1): the CANCEL names its vessel, so the tug-side overtaken-ACCEPT
            // consume discards exactly this vessel's queued award and no other.
            cancel.setContent(TerminalJson.write(Map.of("reason", "weather_hold", "vessel_id", vesselId)));
            hm.sendLogged(cancel);
        }
        HarbourMasterAgent.WeatherReading w = hm.lastWeather();
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(tracking.aid());
        inform.setProtocol(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("event", "weather_hold");
        if (w != null) {
            content.put("wind_knots", w.windKnots());
            content.put("swell", w.swell());
            content.put("state", w.state());
        }
        content.put("reason", verdict.reason());
        inform.setContent(TerminalJson.write(content));
        hm.sendLogged(inform);
        hm.activeVessels().put(vesselId,
                tracking.withTugs(List.of()).withStage(Stage.AWAITING_TUG).withWeatherHeld(true));
        hm.movement().clearStation(vesselId);
        hm.movement().takeResumeOwed(vesselId);
        if (wasMoving) {
            hm.priorityOverrides().put(vesselId, "emergency");
        }
        hm.eventBus().publish(new NotificationEvent(
                displayName(tracking) + " held outside: " + verdict.reason(),
                NotificationEvent.Severity.WARNING, hm.simClock().nowSimMillis()));
        // S17b (E1): one blocked movement per ship per hold - this line is reached once, when the
        // hold begins (an already-held ship returned above).
        hm.eventBus().publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.BLOCKED, vesselId,
                verdict.ruleIds(), hm.simClock().nowSimMillis()));
        log.info("{}: weather HOLD ({}) -> CANCEL {} tug(s){}", vesselId, verdict.reason(),
                tracking.assignedTugs().size(),
                wasMoving ? ", caught under way: priority escalated to emergency [R26,R29]" : "");
    }

    /** Lifts the hold: the flag and its rules go, and the ship is owed a {@code weather_resume}. */
    private static void release(HarbourMasterAgent hm, VesselTracking tracking) {
        String vesselId = tracking.vesselId();
        hm.activeVessels().put(vesselId, tracking.withWeatherHeld(false));
        hm.movement().clearHoldRules(vesselId);
        hm.movement().resumeOwed(vesselId);
        log.info("{}: weather hold released", vesselId);
    }

    private static void sendResume(HarbourMasterAgent hm, VesselTracking tracking) {
        HarbourMasterAgent.WeatherReading w = hm.lastWeather();
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(tracking.aid());
        inform.setProtocol(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("event", "weather_resume");
        content.put("vessel_id", tracking.vesselId());
        if (w != null) {
            content.put("wind_knots", w.windKnots());
            content.put("state", w.state());
        }
        if ("emergency".equals(hm.priorityOverrides().get(tracking.vesselId()))) {
            // The R29 partition released this vessel first; say so where the player reads.
            content.put("reason", "emergency_override [R29]");
        }
        inform.setContent(TerminalJson.write(content));
        hm.sendLogged(inform);
    }

    // =========================================================================================
    // Reports from tugs and ships
    // =========================================================================================

    /**
     * A tug reports it has reached its ship. Recorded, and the ship is tried at once. A report
     * the HarbourMaster has no use for — the ship has gone, is no longer waiting, or this tug is
     * not (or no longer) in its escort — sends the tug home, or it would wait on station for an
     * order that never comes.
     */
    public static void onTugOnStation(HarbourMasterAgent hm, String vesselId, String tugId,
                                      String conversationId) {
        VesselTracking tracking = vesselId == null ? null : hm.activeVessels().get(vesselId);
        if (tracking != null && tracking.stage() == Stage.IN_TRANSIT
                && tracking.assignedTugs().contains(tugId)) {
            // The ship is already under way with this tug in its escort: the tug's order was lost
            // (a save taken between the order and the tug reading it restores the tug on
            // station). It is sent again, so the ship does not finish its leg unescorted.
            ACLMessage order = MessageFactory.create(ACLMessage.INFORM);
            order.addReceiver(new AID(tugId, AID.ISLOCALNAME));
            order.setProtocol(TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL);
            order.setConversationId(conversationId);
            order.setContent(TerminalJson.write(Map.of("event", "begin_escort", "vessel_id", vesselId)));
            hm.sendLogged(order);
            log.info("{}: {} reports on station for a ship already under way -> escort order re-sent",
                    vesselId, tugId);
            return;
        }
        if (tracking == null || tracking.stage() != Stage.AWAITING_TUG
                || !tracking.assignedTugs().contains(tugId)) {
            ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
            cancel.addReceiver(new AID(tugId, AID.ISLOCALNAME));
            cancel.setContent(TerminalJson.write(Map.of(
                    "reason", "not_needed", "vessel_id", String.valueOf(vesselId))));
            hm.sendLogged(cancel);
            log.info("{} on station for {}, which no longer needs it -> sent home", tugId, vesselId);
            return;
        }
        hm.movement().tugOnStation(vesselId, tugId, conversationId);
        log.info("{}: {} on station ({} of {})", vesselId, tugId,
                hm.movement().onStation(vesselId).size(), tracking.assignedTugs().size());
        tryProceed(hm, vesselId);
    }

    /** A tug is free again: every waiting ship is looked at, most critical class first. */
    public static void onTugAvailable(HarbourMasterAgent hm) {
        hm.activeVessels().values().stream()
                .filter(t -> t.stage() == Stage.AWAITING_TUG && t.assignedBerth() != null)
                .sorted((a, b) -> compareForTender(hm, a, b))
                .map(VesselTracking::vesselId)
                .toList()
                .forEach(id -> evaluate(hm, id));
    }

    /**
     * The ship reports that it is moored. Its own report is the truth about where it is, even
     * when a hold crossed it on the wire: a weather message handled while the ship was on the
     * last tick of its leg holds an entry whose ship then docks and ignores the hold. Refusing
     * the report as stale (the rule for a TUG's report about a cancelled escort) left a moored
     * ship shown as "held outside", and the release hired and paid a fresh escort for it.
     */
    public static void onDocked(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = vesselId == null ? null : hm.activeVessels().get(vesselId);
        if (tracking == null || tracking.stage().ordinal() >= Stage.DOCKED.ordinal()) {
            return;
        }
        if (tracking.weatherHeld()) {
            hm.movement().clearHoldRules(vesselId);
            hm.movement().takeResumeOwed(vesselId);
            log.info("{}: docked while a hold was on its way to it - the hold is void", vesselId);
        }
        sendHomeWaitingEscort(hm, tracking);
        hm.movement().clearStation(vesselId);
        hm.activeVessels().put(vesselId, tracking.withWeatherHeld(false).withStage(Stage.DOCKED));
        log.debug("{}: tracking stage -> DOCKED (the ship's own report)", vesselId);
    }

    /**
     * Sends home the tugs still assigned to a ship that will not be ordered to proceed any more
     * (it docked on its own report while waiting, or it is gone): a tug on station waits for an
     * order, and one that is never coming would keep it there for good. Only for an entry that
     * was still WAITING - a ship under way has its escort with it, and a docked one's is home.
     */
    public static void sendHomeWaitingEscort(HarbourMasterAgent hm, VesselTracking tracking) {
        if (tracking == null || tracking.stage() != Stage.AWAITING_TUG) {
            return;
        }
        for (String tugId : tracking.assignedTugs()) {
            ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
            cancel.addReceiver(new AID(tugId, AID.ISLOCALNAME));
            cancel.setContent(TerminalJson.write(Map.of(
                    "reason", "not_needed", "vessel_id", tracking.vesselId())));
            hm.sendLogged(cancel);
        }
    }

    /** The ship's service is over: its request to leave. */
    public static void onServiceComplete(HarbourMasterAgent hm, String vesselId) {
        if (vesselId == null || hm.activeVessels().get(vesselId) == null) {
            return;
        }
        hm.movement().departureRequested(vesselId);
        tryClearDeparture(hm, vesselId);
    }

    /**
     * Clears a served ship to leave if the gate allows the movement; otherwise it stays at its
     * berth — which stays occupied — and is asked again with every weather message. A ship
     * already refused once is let go with the release margin, like a held arrival.
     */
    private static void tryClearDeparture(HarbourMasterAgent hm, String vesselId) {
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        MovementBook book = hm.movement();
        if (tracking == null) {
            book.departureCleared(vesselId);
            return;
        }
        MovementGate.Verdict verdict = book.hasHoldRules(vesselId)
                ? MovementGate.release(tracking.vesselType(), reading(hm))
                : MovementGate.check(tracking.vesselType(), reading(hm));
        if (!verdict.safe()) {
            boolean first = !book.hasHoldRules(vesselId);
            book.setHoldRules(vesselId, verdict.ruleIds());
            if (first) {
                hm.eventBus().publish(new NotificationEvent(
                        displayName(tracking) + " stays at its berth: " + verdict.reason(),
                        NotificationEvent.Severity.WARNING, hm.simClock().nowSimMillis()));
                log.info("{}: departure HELD at {} ({})", vesselId, tracking.assignedBerth(), verdict.reason());
                // S17b (E1): a served ship kept at its berth is a blocked movement too, once.
                hm.eventBus().publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.BLOCKED, vesselId,
                        verdict.ruleIds(), hm.simClock().nowSimMillis()));
            }
            return;
        }
        ACLMessage cleared = MessageFactory.create(ACLMessage.INFORM);
        cleared.addReceiver(tracking.aid());
        cleared.setProtocol(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        cleared.setContent(TerminalJson.write(Map.of("event", "cleared_to_depart", "vessel_id", vesselId)));
        auditMoveOrder(hm, tracking);
        hm.sendLogged(cleared);
        book.departureCleared(vesselId);
        book.clearHoldRules(vesselId);
        hm.activeVessels().computeIfPresent(vesselId, (id, t) ->
                t.stage().ordinal() < Stage.DEPARTING.ordinal() ? t.withStage(Stage.DEPARTING) : t);
        log.debug("{}: cleared to depart", vesselId);
    }

    /** Customs cleared the ship (or there is no customs office to ask): one obstacle less. */
    public static void onCustomsCleared(HarbourMasterAgent hm, String vesselId) {
        hm.movement().inspectionOver(vesselId);
        evaluate(hm, vesselId);
    }

    /**
     * Customs refused the ship. It is turned away: it leaves without docking, its tugs go home
     * and its berth is free for the next in the queue. Not reachable with the shipped data (no
     * blacklist fact exists and every template cargo class is known to R21) — but a ship that
     * may never enter must not hold a berth for the rest of the game.
     */
    public static void onCustomsRefused(HarbourMasterAgent hm, String vesselId, String reason) {
        VesselTracking tracking = hm.activeVessels().remove(vesselId);
        if (tracking == null) {
            return;
        }
        for (String tugId : tracking.assignedTugs()) {
            ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
            cancel.addReceiver(new AID(tugId, AID.ISLOCALNAME));
            cancel.setContent(TerminalJson.write(Map.of("reason", "customs_refused", "vessel_id", vesselId)));
            hm.sendLogged(cancel);
        }
        ACLMessage away = MessageFactory.create(ACLMessage.INFORM);
        away.addReceiver(tracking.aid());
        away.setProtocol(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        away.setContent(TerminalJson.write(Map.of(
                "event", "turned_away", "vessel_id", vesselId, "reason", String.valueOf(reason))));
        hm.sendLogged(away);
        forget(hm, vesselId);
        hm.eventBus().publish(new NotificationEvent(
                "Customs refused " + displayName(tracking) + " (" + reason + ") — turned away, berth "
                        + "released", NotificationEvent.Severity.WARNING, hm.simClock().nowSimMillis()));
        log.warn("{}: customs REFUSED ({}) -> turned away, {} released", vesselId, reason,
                tracking.assignedBerth());
        ForwardWalkInToPlayerBehaviour.releaseQueuedGrants(hm);
    }

    /** The vessel is gone: every side table it had an entry in is cleared. */
    public static void forget(HarbourMasterAgent hm, String vesselId) {
        hm.movement().forget(vesselId);
        hm.priorityOverrides().remove(vesselId);
        hm.cnpRetryAttempts().remove(vesselId);
        hm.pendingCustomsChecks().remove(customsConversation(vesselId));
        hm.pendingContractGrants().remove(vesselId);
        hm.berthWaitQueue().remove(vesselId);
    }

    // =========================================================================================
    // Flooded berths (C3)
    // =========================================================================================

    /**
     * A flood: the berth is closed for {@link #FLOOD_CLOSURE_SIM_MILLIS}. Nobody is granted it
     * meanwhile, a ship already granted it waits at the channel entry, and a ship moored there
     * stays moored. Before S16 the terminal's retraction changed nothing on this side.
     */
    public static void closeBerth(HarbourMasterAgent hm, String berthId) {
        if (berthId == null) {
            return;
        }
        long reopenAt = hm.simClock().nowSimMillis() + FLOOD_CLOSURE_SIM_MILLIS;
        hm.movement().closeBerth(berthId, reopenAt);
        hm.eventBus().publish(new NotificationEvent(
                "Berth " + berthId.replace("berth_", "") + " flooded — closed until " + clockTime(reopenAt),
                NotificationEvent.Severity.WARNING, hm.simClock().nowSimMillis()));
        hm.requestPortStatus();
        log.warn("berth {} closed (flood) until sim {}", berthId, reopenAt);
    }

    /** Reopens every berth whose closure has run out; called once a sim-minute. */
    public static void reopenDueBerths(HarbourMasterAgent hm) {
        long now = hm.simClock().nowSimMillis();
        boolean reopened = false;
        for (Map.Entry<String, Long> closed : hm.movement().closedBerths().entrySet()) {
            if (now < closed.getValue()) {
                continue;
            }
            String berthId = closed.getKey();
            hm.movement().reopenBerth(berthId);
            reopened = true;
            hm.eventBus().publish(new NotificationEvent(
                    "Berth " + berthId.replace("berth_", "") + " reopened",
                    NotificationEvent.Severity.INFO, now));
            log.info("berth {} reopened", berthId);
            hm.activeVessels().values().stream()
                    .filter(t -> t.stage() == Stage.AWAITING_TUG && berthId.equals(t.assignedBerth()))
                    .map(VesselTracking::vesselId)
                    .toList()
                    .forEach(id -> evaluate(hm, id));
        }
        if (reopened) {
            ForwardWalkInToPlayerBehaviour.releaseQueuedGrants(hm);
        }
    }

    // =========================================================================================
    // Priority (R25–R30) and reporting
    // =========================================================================================

    /**
     * The class atom handed to R26 as a vessel's override: a weather-escalated {@code emergency}
     * first; otherwise {@link #WALK_IN_CLASS} for a walk-in, which ranks it below every scheduled
     * (contracted) arrival — a rank FACT under R25, not a new rule (decision C4); otherwise
     * {@code null}, and R26 classes the vessel by its type.
     */
    public static String priorityOverrideFor(HarbourMasterAgent hm, String vesselId) {
        String escalated = hm.priorityOverrides().get(vesselId);
        if (escalated != null) {
            return escalated;
        }
        VesselTracking tracking = hm.activeVessels().get(vesselId);
        return tracking != null && tracking.channel() == Channel.WALK_IN ? WALK_IN_CLASS : null;
    }

    /** The vessel's R30 {@code effective_priority}, override included. */
    public static PrologQueries.VesselPriority priorityOf(HarbourMasterAgent hm, VesselTracking tracking) {
        return PrologQueries.effectivePriority(tracking.vesselType(),
                priorityOverrideFor(hm, tracking.vesselId()));
    }

    private static String effectiveClassOf(HarbourMasterAgent hm, VesselTracking tracking) {
        return priorityOf(hm, tracking).priorityClass();
    }

    /** R27 as the ordering: {@code a} before {@code b} iff its class outranks {@code b}'s. */
    public static int compareByPriority(HarbourMasterAgent hm, VesselTracking a, VesselTracking b) {
        String classA = effectiveClassOf(hm, a);
        String classB = effectiveClassOf(hm, b);
        if (PrologQueries.higherPriority(classA, classB)) {
            return -1;
        }
        return PrologQueries.higherPriority(classB, classA) ? 1 : 0;
    }

    /**
     * Why {@code tracking} is not moving, for the Port table: the first that applies of a
     * weather hold, a departure kept at the berth, a closed berth, customs, and the escort.
     */
    public static PortStatusSnapshot.WaitInfo waitOf(HarbourMasterAgent hm, VesselTracking tracking) {
        MovementBook book = hm.movement();
        String vesselId = tracking.vesselId();
        if (tracking.weatherHeld()) {
            return new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.WEATHER, book.holdRules(vesselId));
        }
        if (book.isDepartureRequested(vesselId) && book.hasHoldRules(vesselId)) {
            return new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.DEPARTURE_WEATHER,
                    book.holdRules(vesselId));
        }
        if (tracking.stage() != Stage.AWAITING_TUG || tracking.assignedBerth() == null) {
            return PortStatusSnapshot.WaitInfo.NONE;
        }
        if (book.berthClosed(tracking.assignedBerth(), hm.simClock().nowSimMillis())) {
            return new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.BERTH_CLOSED, List.of());
        }
        if (tracking.blocked() || customsPending(hm, vesselId)) {
            return new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.CUSTOMS, book.inspectionRules(vesselId));
        }
        if (escortShortfall(tracking) > 0 || !book.allOnStation(vesselId, tracking.assignedTugs())) {
            return new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.TUGS, List.of());
        }
        return PortStatusSnapshot.WaitInfo.NONE;
    }

    /**
     * S17b (decision E1): the score's "unsafe moves carried out". Called at the two places an
     * order to move is written - {@code proceed_to_berth} and {@code cleared_to_depart} - it asks
     * the gate once more, with the reading of this very moment, whatever the code above it
     * decided. The answer is "safe" every time, because the code above asked first; that is the
     * point. If a later change ever reorders or drops one of those checks, the order still goes
     * out here, and this is where it is seen, logged as an error and taken off the score.
     * Public so that {@code MovementControlIT} can ask it under a reading the gate would never
     * let an order reach it with.
     */
    public static void auditMoveOrder(HarbourMasterAgent hm, VesselTracking tracking) {
        MovementGate.Verdict now = MovementGate.check(tracking.vesselType(), reading(hm));
        if (!now.safe()) {
            hm.eventBus().publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.UNSAFE_MOVE,
                    tracking.vesselId(), now.ruleIds(), hm.simClock().nowSimMillis()));
        }
    }

    private static String displayName(VesselTracking tracking) {
        String name = tracking.spec() == null ? null : tracking.spec().vesselName();
        return name == null || name.isBlank() ? tracking.vesselId() : name;
    }

    /** {@code HH:MM} of a sim instant, for a notification. */
    private static String clockTime(long simMillis) {
        long ofDay = Math.floorMod(simMillis, 86_400_000L);
        return String.format(Locale.ROOT, "%02d:%02d", ofDay / 3_600_000L, (ofDay % 3_600_000L) / 60_000L);
    }
}
