package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
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
 * The v1.1 weather safety gate — since task 24, in BOTH directions.
 *
 * <p><b>{@code weather_threshold}</b> (worsening): re-evaluates every in-transit
 * vessel against RULES R15/R16 ({@code operationSafe}), R17
 * ({@code swellWithinLimit}), and R19 ({@code weatherStateUnsafe}) from the SAME
 * reading (all four fields ride in one alert — see
 * {@code PeriodicWeatherBroadcastBehaviour}'s enrichment). Unsafe → CANCEL every
 * assigned tug + INFORM the vessel + mark the tracking {@code weatherHeld}, never
 * a silent proceed.
 *
 * <p><b>{@code weather_clear}</b> (recovery — task 24, closing the stranded-vessel
 * gap filed at planning/24): re-evaluates every {@code weatherHeld} vessel against
 * the SAME three-predicate gate with the recovered reading; safe again → INFORM
 * the vessel ({@code weather_resume}) and re-initiate the tug Contract Net through
 * {@code cnpCoordinator().initiate(...)} — a fresh CFP with a fresh
 * {@code cfpId}, exactly the deal-confirmed dispatch shape. The old award was
 * CANCELled, so the fresh conversation id is a genuinely new job for the
 * {@code (conversationId, tugId)} ledger key: the re-dispatched escort charges
 * exactly once, and no refund of the cancelled leg exists by design (task 20's
 * charge-at-award decision). The {@code weatherHeld} flag is what separates held
 * vessels from ones whose FIRST CNP is still in flight — sweeping on
 * {@code AWAITING_TUG}+no-tugs alone would double-CFP those (ADR-14).
 *
 * <p>Matched by protocol tag, not sender AID: an earlier AID-based
 * {@code MatchSender(weatherAid)} design had a real gap — {@code ServiceLocator}
 * negatively caches an empty DF search for 1 s, so a threshold alert arriving
 * before the HarbourMaster's first successful weather-AID lookup would fall
 * through to {@code ForwardWalkInToPlayerBehaviour}'s broader template and be
 * silently dropped (adversarial review finding, task 11). Tagging the alert itself
 * with {@link #WEATHER_ALERT_PROTOCOL} removes the timing dependency entirely.
 */
public final class HandleWeatherAlertBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleWeatherAlertBehaviour.class);

    /** Set on both alert directions' INFORMs by {@code PeriodicWeatherBroadcastBehaviour}. */
    public static final String WEATHER_ALERT_PROTOCOL = "port-command-weather-alert";
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(WEATHER_ALERT_PROTOCOL), MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    public HandleWeatherAlertBehaviour(Agent agent) {
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
        String visibility = text(content, "visibility");
        String state = text(content, "state");
        if (content == null || visibility == null || state == null) {
            log.warn("malformed weather alert (missing visibility/state) — ignoring: {}", msg.getContent());
            return;
        }
        double windKnots = num(content, "wind_knots");
        double swell = num(content, "swell");
        // Absent event field = the pre-task-24 alert shape, which was always a worsening.
        String event = text(content, "event");
        boolean clearing = "weather_clear".equals(event);
        // "weather_periodic" (audit P1-4) is the 15-sim-minute snapshot, not a transition. It runs
        // the SAME hold sweep the worsening branch runs — deliberately, because that sweep is
        // precisely what a vessel entering transit into already-bad weather never gets: alerts
        // fire on transitions, and its storm had already begun. It must NOT take the clearing
        // branch (that would re-dispatch held vessels every tick regardless of conditions), and it
        // must not be mistaken for the legacy absent-event shape either — hence the explicit name.
        boolean periodic = "weather_periodic".equals(event);

        // Goal B (S5): every consumed weather message refreshes the hub's last-known reading —
        // the data source for the player-command dispatch gate. Freshness is bounded by the
        // 15-sim-minute periodic snapshot.
        hm.recordWeather(new HarbourMasterAgent.WeatherReading(windKnots, visibility, swell, state));

        if (periodic) {
            log.debug("periodic weather snapshot: sweeping {} tracked vessel(s) (wind={} state={})",
                    hm.activeVessels().size(), windKnots, state);
        }
        if (clearing) {
            // weatherHeld AND still genuinely waiting (checkpoint-#5 B1): a held vessel can
            // only be AWAITING_TUG; anything else (or a stale flag on an entry some other
            // path advanced) must never trigger a re-dispatch. Departure removes the entry
            // entirely (ForwardWalkInToPlayerBehaviour.handleDeparted) — this stage filter
            // is the defence-in-depth layer under that fix.
            //
            // S5 (R28/R29): re-dispatches run EMERGENCY-CLASS FIRST. Held vessels were escalated
            // to the emergency priority class when the storm caught them (see holdVessel), so
            // when the sky clears the recovery order is decided by the priority module:
            // emergency_overrides_all (R29) IS the partition below. Mind its direction — the
            // predicate holds for every class the emergency class OVERRIDES (and fails for
            // emergency itself), so the vessels it holds for wait in the second loop and the
            // ones it rejects (the emergency peers) go first. On the R27-sorted input the
            // concatenation equals the sort (emergency already ranks first), but each rule
            // decides what it states: R27 the pairwise order, R29 the partition. The escalation
            // survives into the fresh CnpRequest, so the coordinator's R28 dequeue also ranks
            // it ahead of any queued ordinary session.
            List<VesselTracking> eligible = hm.activeVessels().values().stream()
                    .filter(t -> t.weatherHeld() && t.stage() == Stage.AWAITING_TUG)
                    .filter(t -> safeFor(t, windKnots, visibility, swell, state))
                    .sorted((a, b) -> compareByPriority(hm, a, b))
                    .toList();
            Map<Boolean, List<VesselTracking>> byOverride = eligible.stream().collect(
                    Collectors.partitioningBy(t -> !PrologQueries.emergencyOverridesAll(effectiveClassOf(hm, t))));
            for (VesselTracking tracking : byOverride.get(true)) {
                redispatch(hm, tracking, windKnots, state, true);
            }
            for (VesselTracking tracking : byOverride.get(false)) {
                redispatch(hm, tracking, windKnots, state, false);
            }
        } else {
            // S5 (R25/R27/R30): when one alert holds SEVERAL vessels, the cancel cascade runs in
            // priority order — the priority module's higher_priority (R27) is the comparator over
            // each vessel's effective_priority class (R30 through the R26 bridge), so the most
            // critical escort is recalled first. With one unsafe vessel (the shipped storm) the
            // sort is a no-op and the behaviour is exactly the pre-S5 cascade.
            List<VesselTracking> unsafe = hm.activeVessels().values().stream()
                    .filter(t -> t.stage() == Stage.IN_TRANSIT && !t.assignedTugs().isEmpty())
                    .filter(t -> !safeFor(t, windKnots, visibility, swell, state))
                    .sorted((a, b) -> compareByPriority(hm, a, b))
                    .toList();
            for (VesselTracking tracking : unsafe) {
                holdVessel(hm, tracking, windKnots, swell, state);
            }
        }

        // S5 (ADR-16): the periodic snapshot doubles as the berth wait queue's idempotent
        // backstop — if a departed INFORM was ever lost, the freed berth is noticed here at
        // worst 15 sim-minutes late instead of never.
        if (periodic) {
            ForwardWalkInToPlayerBehaviour.releaseQueuedGrants(hm);
        }
    }

    /**
     * R27 as the ordering predicate, genuinely called: ranks {@code a} before {@code b} iff
     * {@code higher_priority(ClassA, ClassB)} holds for their R30 effective classes (overrides
     * included). Equal classes tie (0), preserving encounter order.
     */
    private static int compareByPriority(HarbourMasterAgent hm, VesselTracking a, VesselTracking b) {
        String classA = effectiveClassOf(hm, a);
        String classB = effectiveClassOf(hm, b);
        if (PrologQueries.higherPriority(classA, classB)) {
            return -1;
        }
        return PrologQueries.higherPriority(classB, classA) ? 1 : 0;
    }

    /** The vessel's R30 {@code effective_priority} class, overrides included (R25/R26 inside). */
    private static String effectiveClassOf(HarbourMasterAgent hm, VesselTracking t) {
        return PrologQueries.effectivePriority(
                t.vesselType(), hm.priorityOverrides().get(t.vesselId())).priorityClass();
    }

    /** The binding safety gate — Prolog first, always (design rule 4). */
    private static boolean safeFor(VesselTracking tracking, double windKnots, String visibility,
                                   double swell, String state) {
        return PrologQueries.operationSafe(tracking.vesselType(), windKnots, visibility)
                && PrologQueries.swellWithinLimit(swell)
                && !PrologQueries.weatherStateUnsafe(state);
    }

    private void holdVessel(HarbourMasterAgent hm, VesselTracking tracking, double windKnots, double swell,
                            String state) {
        List<AID> tugs = hm.serviceLocator().findAll("tug-escort");
        for (String tugId : tracking.assignedTugs()) {
            tugs.stream().filter(aid -> aid.getLocalName().equals(tugId)).findFirst().ifPresent(tugAid -> {
                ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
                cancel.addReceiver(tugAid);
                cancel.setContent(TerminalJson.write(Map.of("reason", "weather_hold")));
                hm.sendLogged(cancel);
            });
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(tracking.aid());
        inform.setContent(TerminalJson.write(Map.of(
                "event", "weather_hold", "wind_knots", windKnots, "swell", swell, "state", state)));
        hm.sendLogged(inform);

        hm.activeVessels().put(tracking.vesselId(),
                tracking.withTugs(List.of()).withStage(Stage.AWAITING_TUG).withWeatherHeld(true));
        // S5 (R26 clause 1 / R29 producer): a vessel caught in transit by unsafe weather IS the
        // emergency — the escalation is driven by the R15-R19 verdict that just held it, not by
        // invented data. Read back by effectivePriority at the clearing re-dispatch (which
        // processes emergency-class vessels first, R29) and by the CNP dequeue (R28); cleared
        // at departure, NOT at re-dispatch — the re-dispatch CNP is what it exists to outrank.
        hm.priorityOverrides().put(tracking.vesselId(), "emergency");
        log.info("{}: weather HOLD (wind={} swell={} state={}) -> CANCEL {} tug(s), priority "
                        + "escalated to emergency [R26,R29]",
                tracking.vesselId(), windKnots, swell, state, tracking.assignedTugs().size());
    }

    /**
     * The recovery half of the cascade. Clears {@code weatherHeld} BEFORE initiating, so a
     * second clear alert arriving while the fresh CNP is still collecting bids finds nothing
     * to re-dispatch — the exactly-once guard on the HM side, complementing the ledger's
     * {@code (conversationId, tugId)} key on the money side.
     */
    private void redispatch(HarbourMasterAgent hm, VesselTracking tracking, double windKnots, String state,
                            boolean emergencyClass) {
        hm.activeVessels().put(tracking.vesselId(), tracking.withWeatherHeld(false));
        // The escalation deliberately SURVIVES the re-dispatch: the fresh CnpRequest below must
        // still read as emergency when the coordinator's R28 dequeue ranks the backlog (clearing
        // here would demote it before the CNP it was escalated FOR ever launches). It ends at
        // departure (ForwardWalkInToPlayerBehaviour.handleDeparted), with the tracking entry.
        VesselSpec spec = tracking.spec();
        int tugsRequired = PrologQueries.tugsRequired(spec.vesselType(), spec.tonnage(), spec.length());
        String berthId = tracking.assignedBerth();
        if (tugsRequired <= 0 || berthId == null) {
            // Unreachable today: a hold requires assigned tugs, which required tugsRequired > 0
            // and a granted berth. Kept as a guard because CnpRequest rejects both conditions.
            log.warn("{}: weather cleared but nothing to re-dispatch (tugsRequired={} berth={})",
                    tracking.vesselId(), tugsRequired, berthId);
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(tracking.aid());
        Map<String, Object> content = new java.util.LinkedHashMap<>(Map.of(
                "event", "weather_resume", "vessel_id", tracking.vesselId(),
                "wind_knots", windKnots, "state", state));
        if (emergencyClass) {
            // The R29 partition put this vessel in the first loop; say so where the player reads.
            content.put("reason", "emergency_override [R29]");
        }
        inform.setContent(TerminalJson.write(content));
        hm.sendLogged(inform);

        Position berthPosition = BerthPositions.position(berthId);
        hm.cnpCoordinator().initiate(new CnpRequest(tracking.vesselId(), tracking.aid(),
                AutoFlowDispatcherBehaviour.CHANNEL_ENTRY, berthPosition, tugsRequired,
                tracking.spec().vesselType()));
        log.info("{}: weather CLEAR (wind={} state={}) -> fresh tug CNP ({} tug(s)) toward {}{}",
                tracking.vesselId(), windKnots, state, tugsRequired, berthId,
                emergencyClass ? " — emergency class re-dispatched first [R29]" : "");
    }

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
