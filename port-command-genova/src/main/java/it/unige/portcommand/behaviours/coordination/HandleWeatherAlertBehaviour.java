package it.unige.portcommand.behaviours.coordination;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.MovementControl;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HarbourMaster's ear for the weather: every weather message — a worsening alert
 * ({@code weather_threshold}), a clearing ({@code weather_clear}), the 15-sim-minute snapshot
 * ({@code weather_periodic}) — updates the hub's reading and runs the movement sweeps over it.
 *
 * <p><b>S16 (2026-10-13, ADR-20): the sweeps themselves moved to {@code MovementControl}.</b>
 * This class used to hold both: the hold sweep (CANCEL the tugs of an unsafe in-transit vessel,
 * tell the vessel, mark it held) and the release sweep (re-tender for the held vessels when the
 * weather clears), each with its own hand-written copy of the three Prolog weather queries and
 * its own idea of who can be held. They are now {@code MovementControl.onWeather}, which asks the
 * one {@code MovementGate} every other movement decision asks, and which differs from the old
 * sweeps in four ways the decision file names:
 * <ul>
 *   <li>EVERY ship that holds a berth and has not docked can be held — waiting or moving, with
 *       or without tugs. The old filter was "in transit with tugs", so a ferry was never held
 *       and neither was a ship still waiting for its escort;</li>
 *   <li>a release needs the wind three knots under the type's limit, not merely under it;</li>
 *   <li>a released ship hears {@code weather_resume} when its escort is hired, not before;</li>
 *   <li>both sweeps run on every message. The old code released only on a {@code weather_clear}
 *       alert or the periodic snapshot, so what was released depended on how the weather agent
 *       happened to word the change.</li>
 * </ul>
 *
 * <p>What stays here is the periodic snapshot's housekeeping, which is about the weather channel
 * being the hub's one reliable heartbeat rather than about weather: reaping tracking entries
 * whose agent has left ({@link #reapDepartedGhosts}) and releasing the berth wait queue.
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
        boolean periodic = "weather_periodic".equals(text(content, "event"));

        // Goal B (S5): every consumed weather message refreshes the hub's reading — since S16 the
        // one reading every movement decision is made from (MovementControl.reading).
        hm.recordWeather(new HarbourMasterAgent.WeatherReading(windKnots, visibility, swell, state));
        if (periodic) {
            log.debug("periodic weather snapshot: sweeping {} tracked vessel(s) (wind={} state={})",
                    hm.activeVessels().size(), windKnots, state);
            // S5 (ADR-16) / S10: the periodic snapshot is the idempotent backstop for a lost
            // departed INFORM — the ghost is reaped here, and the queue release further down
            // then hands its berth on, at worst 15 sim-minutes late instead of never.
            reapDepartedGhosts(hm);
        }
        MovementControl.onWeather(hm);
        if (periodic) {
            ForwardWalkInToPlayerBehaviour.releaseQueuedGrants(hm);
        }
    }

    /**
     * S10 (bug hunt, hunter B finding 6): the "lost {@code departed} INFORM → berth back late"
     * backstop this sweep always claimed to be. {@code releaseQueuedGrants} only re-derives
     * free berths from {@code activeVessels} — nothing anywhere removed a stale entry, so a
     * dropped departed INFORM ({@code DepartBehaviour} deletes its agent unconditionally after
     * a possibly-failed DF lookup, whose empty result {@code ServiceLocator} caches for 1 s)
     * held the berth FOREVER and stalled the whole wait queue behind it. A tracked vessel at
     * DOCKED-or-later whose agent has left the in-process directory can only be a completed
     * departure whose INFORM died — reap it with {@code handleDeparted}'s cleanup. Two windows
     * considered: a FRESH spawn's registration gap is excluded by the stage gate (a new vessel
     * is nowhere near DOCKED); a RESTORED DOCKED vessel's gap (tracking exists before its agent
     * finishes spawning) is excluded because the broadcast producing this sweep is pause-gated
     * and the load boots with the clock paused — no periodic can land until the world is fully
     * respawned. The normal race (INFORM still queued when the agent is already gone) is
     * benign — handleDeparted tolerates an absent entry. releaseQueuedGrants runs right after
     * in the periodic block, so the freed berth is granted in the same sweep.
     */
    private static void reapDepartedGhosts(HarbourMasterAgent hm) {
        if (hm.directory() == null) {
            return; // stub harnesses have no directory; every production boot does
        }
        for (VesselTracking t : List.copyOf(hm.activeVessels().values())) {
            if (t.stage().ordinal() < Stage.DOCKED.ordinal()) {
                continue;
            }
            if (hm.directory().byName(
                    it.unige.portcommand.bootstrap.AgentRoster.vesselLocalName(t.spec())).isPresent()) {
                continue;
            }
            log.warn("{}: agent gone but tracking survived at {} — its departed INFORM was "
                    + "lost; reaping the ghost (berth {} frees this sweep)",
                    t.vesselId(), t.stage(), t.assignedBerth());
            hm.activeVessels().remove(t.vesselId());
            MovementControl.sendHomeWaitingEscort(hm, t);
            MovementControl.forget(hm, t.vesselId());
        }
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
