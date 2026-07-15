package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The v1.1 weather safety gate. On every threshold-crossing INFORM from the
 * WeatherAgent (the ONLY message it ever sends the HarbourMaster directly — HM is
 * not a {@code weather-subscriber}), re-evaluates every in-transit vessel against
 * RULES R15/R16 ({@code operationSafe}), R17 ({@code swellWithinLimit}), and R19
 * ({@code weatherStateUnsafe}) from the SAME reading (all four fields ride in one
 * alert — see {@code PeriodicWeatherBroadcastBehaviour}'s enrichment). Unsafe ->
 * CANCEL every assigned tug + INFORM the vessel, never a silent proceed. Each new
 * alert re-runs this evaluation (no separate "re-evaluate" hook needed — it is just
 * what a persistent listener does on every broadcast).
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

    /** Set on the threshold-alert INFORM by {@code PeriodicWeatherBroadcastBehaviour}. */
    public static final String WEATHER_ALERT_PROTOCOL = "port-command-weather-alert";
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(WEATHER_ALERT_PROTOCOL), MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    public HandleWeatherAlertBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        ACLMessage msg = myAgent.receive(TEMPLATE);
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

        for (VesselTracking tracking : hm.activeVessels().values()) {
            if (tracking.stage() != Stage.IN_TRANSIT || tracking.assignedTugs().isEmpty()) {
                continue;
            }
            boolean safe = PrologQueries.operationSafe(tracking.vesselType(), windKnots, visibility)
                    && PrologQueries.swellWithinLimit(swell)
                    && !PrologQueries.weatherStateUnsafe(state);
            if (!safe) {
                holdVessel(hm, tracking, windKnots, swell, state);
            }
        }
    }

    private void holdVessel(HarbourMasterAgent hm, VesselTracking tracking, double windKnots, double swell,
                            String state) {
        List<AID> tugs = hm.serviceLocator().findAll("tug-escort");
        for (String tugId : tracking.assignedTugs()) {
            tugs.stream().filter(aid -> aid.getLocalName().equals(tugId)).findFirst().ifPresent(tugAid -> {
                ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
                cancel.addReceiver(tugAid);
                cancel.setContent(TerminalJson.write(Map.of("reason", "weather_hold")));
                myAgent.send(cancel);
            });
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(tracking.aid());
        inform.setContent(TerminalJson.write(Map.of(
                "event", "weather_hold", "wind_knots", windKnots, "swell", swell, "state", state)));
        myAgent.send(inform);

        hm.activeVessels().put(tracking.vesselId(), tracking.withTugs(List.of()).withStage(Stage.AWAITING_TUG));
        log.info("{}: weather HOLD (wind={} swell={} state={}) -> CANCEL {} tug(s)",
                tracking.vesselId(), windKnots, swell, state, tracking.assignedTugs().size());
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
