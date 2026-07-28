package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;

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
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
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
        boolean clearing = "weather_clear".equals(text(content, "event"));

        for (VesselTracking tracking : hm.activeVessels().values()) {
            if (clearing) {
                // weatherHeld AND still genuinely waiting (checkpoint-#5 B1): a held vessel can
                // only be AWAITING_TUG; anything else (or a stale flag on an entry some other
                // path advanced) must never trigger a re-dispatch. Departure removes the entry
                // entirely (ForwardWalkInToPlayerBehaviour.handleDeparted) — this stage filter
                // is the defence-in-depth layer under that fix.
                if (!tracking.weatherHeld() || tracking.stage() != Stage.AWAITING_TUG) {
                    continue;
                }
                if (safeFor(tracking, windKnots, visibility, swell, state)) {
                    redispatch(hm, tracking, windKnots, state);
                }
            } else {
                if (tracking.stage() != Stage.IN_TRANSIT || tracking.assignedTugs().isEmpty()) {
                    continue;
                }
                if (!safeFor(tracking, windKnots, visibility, swell, state)) {
                    holdVessel(hm, tracking, windKnots, swell, state);
                }
            }
        }
    }

    /** The binding safety gate — Prolog first, always (CLAUDE.md rule 4). */
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
        log.info("{}: weather HOLD (wind={} swell={} state={}) -> CANCEL {} tug(s)",
                tracking.vesselId(), windKnots, swell, state, tracking.assignedTugs().size());
    }

    /**
     * The recovery half of the cascade. Clears {@code weatherHeld} BEFORE initiating, so a
     * second clear alert arriving while the fresh CNP is still collecting bids finds nothing
     * to re-dispatch — the exactly-once guard on the HM side, complementing the ledger's
     * {@code (conversationId, tugId)} key on the money side.
     */
    private void redispatch(HarbourMasterAgent hm, VesselTracking tracking, double windKnots, String state) {
        hm.activeVessels().put(tracking.vesselId(), tracking.withWeatherHeld(false));
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
        inform.setContent(TerminalJson.write(Map.of(
                "event", "weather_resume", "vessel_id", tracking.vesselId(),
                "wind_knots", windKnots, "state", state)));
        hm.sendLogged(inform);

        Position berthPosition = BerthPositions.position(berthId);
        hm.cnpCoordinator().initiate(new CnpRequest(tracking.vesselId(), tracking.aid(),
                AutoFlowDispatcherBehaviour.CHANNEL_ENTRY, berthPosition, tugsRequired));
        log.info("{}: weather CLEAR (wind={} state={}) -> fresh tug CNP ({} tug(s)) toward {}",
                tracking.vesselId(), windKnots, state, tugsRequired, berthId);
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
