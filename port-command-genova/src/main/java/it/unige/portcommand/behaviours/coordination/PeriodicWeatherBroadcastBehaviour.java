package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.WeatherAlertPolicy;
import it.unige.portcommand.agents.WeatherAlertPolicy.Direction;
import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.harbourmaster.MovementGate;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the HarbourMaster a weather snapshot every 15 sim-minutes (audit P1-4 — it used to
 * address a {@code weather-subscriber} DF type nothing registers, so the channel was dead;
 * see {@link #broadcast}), publishes the GUI's {@link WeatherChangeEvent} (task 24 — it feeds
 * the top bar's weather), and — when {@link WeatherAlertPolicy} reports a genuine
 * severity transition — sends the HarbourMaster an alert on the
 * {@code WEATHER_ALERT_PROTOCOL}, in BOTH directions:
 * <ul>
 *   <li>{@code weather_threshold}, {@code priority=high} — conditions crossed a
 *       limit upward; {@code HandleWeatherAlertBehaviour} runs the hold sweep.</li>
 *   <li>{@code weather_clear} (normal priority) — the weather is calm again for every
 *       vessel type; the hold behaviour re-evaluates weather-held vessels and
 *       re-dispatches their tug CNP. This is the signal whose absence permanently
 *       stranded held vessels (task-19 finding, filed at planning/24).</li>
 * </ul>
 * The current snapshot is shared (read-only here) with
 * {@code WeatherEvolutionBehaviour} via the {@link AtomicReference}.
 *
 * <p><b>S16 (2026-10-13, ADR-20).</b> Two things changed around this class, neither in the
 * shape of what it sends. (1) A cycle now also runs at once after every weather step that took
 * effect ({@code WeatherEvolutionBehaviour} → {@link #broadcastNow()}), so on top of the
 * 15-sim-minute rhythm there is one broadcast on each full game hour whose step changed the
 * reading. (2) {@code weather_clear} and the "Weather clearing" notification used to follow any
 * recovering dimension, a wind-band drop inside a storm included; they now follow
 * {@link WeatherAlertPolicy}'s S16 rule and are sent only when the reading has become safe for
 * every vessel type, release margin included (decision B4). The order inside a cycle is
 * unchanged and load-bearing: the periodic snapshot first, so the HarbourMaster's reading is
 * current before an alert makes it act.
 *
 * <p><b>S16, two rules about WHAT is announced</b> (the decision of a cycle is
 * {@link #announce}, pure and unit-tested without an agent):
 * <ul>
 *   <li><b>The baseline is the reading the agent boots with</b>, not {@code null}. The first
 *       cycle after a new game, a scenario start or a load compares against the opening or
 *       restored snapshot. An unchanged reading is silent, exactly as before — a load taken in
 *       mid-storm re-fires no alert, which was the whole reason for the old null baseline — and
 *       a real change in the first minutes is announced. It used not to be: a step is broadcast
 *       at once, so a first hourly step that fell before the first 15-sim-minute tick became
 *       the baseline itself and its worsening was never announced.</li>
 *   <li><b>A clearing is announced only after an announced worsening.</b> The policy is
 *       stateless: it calls 28&nbsp;→&nbsp;26&nbsp;kn a clear (28 is inside a tanker's release
 *       margin, 26 is calm) whether the wind came down from 31 or merely wandered up from 26.
 *       In the second case nothing was ever announced and nobody was held, and "Weather
 *       clearing" was news without cause. One flag, {@code worseningOutstanding}, tells the two
 *       apart: set when a worsening alert goes out, and at boot when the boot reading is not
 *       calm (a world that starts or loads in a storm must still announce its end); a clear
 *       transition is announced only while it is set, and lowers it. With the flag down the
 *       cycle is routine: the periodic snapshot as always, no alert, no notification,
 *       {@code thresholdCrossed=false}. Nobody waits for the clear alert — the HarbourMaster
 *       runs its hold and release checks on every weather message — so this removes only the
 *       unfounded news.</li>
 * </ul>
 *
 * <p><b>Task 19 cadence fix.</b> The tick was 60 sim-seconds, which at the default
 * 300-real-second game day compresses to ~0.2&nbsp;s of wall time — hundreds of
 * broadcasts per real second, needless load. It is now 15 sim-minutes (~3&nbsp;s wall),
 * still finer-grained than {@code WeatherEvolutionBehaviour}'s 60-sim-minute evolution
 * so no weather change is ever missed between broadcasts.
 *
 * <p><b>Task 24 threshold/priority fix.</b> Alerts fire only on genuine 30/35/40/45
 * (or swell/state) crossings between consecutive readings, and only the WORSENING
 * direction carries {@code priority=high} — the task-18 checkpoint's
 * "every broadcast paraphrases as URGENT" is structurally gone. Paused game
 * (SimClock paused) → no broadcasts, no alerts, no GUI events.
 */
public final class PeriodicWeatherBroadcastBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PeriodicWeatherBroadcastBehaviour.class);
    private static final long TICK_SIM_MILLIS = 900_000L; // 15 sim-minutes

    private final AtomicReference<WeatherSnapshot> current;
    private final ServiceLocator locator;
    private final EventBus eventBus;
    /**
     * The previous reading — {@link WeatherAlertPolicy}'s baseline. S16: seeded at construction
     * with the reading the agent boots with (it was {@code null} until the first tick); see the
     * class comment.
     */
    private WeatherSnapshot lastObserved;
    /** S16: a worsening has been announced (or the world booted in bad weather) and no clearing
     * has been announced since. See the class comment and {@link #announce}. */
    private boolean worseningOutstanding;

    /**
     * @param current the agent's shared cell. It must ALREADY hold the reading the world boots
     *                with — the init-args snapshot, or the restored one on a load — because that
     *                reading is taken here as the alert baseline ({@code WeatherAgent.onSetup}
     *                fills the cell before it builds this behaviour for exactly this reason)
     */
    public PeriodicWeatherBroadcastBehaviour(Agent agent, SimClock simClock,
                                             AtomicReference<WeatherSnapshot> current, ServiceLocator locator,
                                             EventBus eventBus) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.current = current;
        this.locator = locator;
        this.eventBus = eventBus;
        this.lastObserved = current.get();
        this.worseningOutstanding = worseningOutstandingAtBoot(lastObserved);
    }

    /**
     * What one broadcast cycle announces, and the flag it leaves behind.
     *
     * @param alert                the alert to send, or empty for a routine cycle
     * @param worseningOutstanding the value of the behaviour's flag after this cycle
     */
    public record Announcement(Optional<Direction> alert, boolean worseningOutstanding) {
    }

    /**
     * The announcement rule of one cycle (S16), pure: {@link WeatherAlertPolicy#transition}
     * decides the direction of the change {@code previous → reading}; this decides whether it
     * is news.
     * <ul>
     *   <li>no transition → nothing, the flag stays as it is;</li>
     *   <li>WORSENED → announced, always; the flag is raised (and stays raised through any
     *       further worsening);</li>
     *   <li>CLEARED → announced only if the flag is up, and lowers it; with the flag down it is
     *       a routine cycle.</li>
     * </ul>
     * Public (with {@link #worseningOutstandingAtBoot}) only so that it can be unit-tested: this
     * package is one of the catalogue-counted behaviour packages and can hold no test class.
     *
     * @param worseningOutstanding the behaviour's flag before this cycle
     * @param previous             the baseline: the last reading broadcast, or the boot reading
     * @param reading              the reading being broadcast now
     */
    public static Announcement announce(boolean worseningOutstanding, WeatherSnapshot previous,
                                        WeatherSnapshot reading) {
        Optional<Direction> transition = WeatherAlertPolicy.transition(previous, reading);
        if (transition.isEmpty()) {
            return new Announcement(Optional.empty(), worseningOutstanding);
        }
        if (transition.get() == Direction.WORSENED) {
            return new Announcement(transition, true);
        }
        return worseningOutstanding
                ? new Announcement(transition, false)
                : new Announcement(Optional.empty(), false);
    }

    /**
     * The flag's value at boot (S16): raised when the reading the world boots with is not calm
     * for every vessel type ({@link MovementGate#calmForEveryType}, the same test the policy's
     * clear uses). No alert preceded that reading in THIS world, but ships may be held under
     * it, so its end is news. A missing reading counts as calm.
     */
    public static boolean worseningOutstandingAtBoot(WeatherSnapshot bootReading) {
        return bootReading != null && !MovementGate.calmForEveryType(new MovementGate.Reading(
                bootReading.wind(), bootReading.visibility(), bootReading.swell(), bootReading.state()));
    }

    /**
     * Runs one broadcast cycle immediately, off the ticker.
     *
     * <p><b>2026-07-27 (task 26).</b> A scripted {@code WeatherChange} adopts its snapshot into
     * the shared cell synchronously, but the ALERT that actually holds vessels only left on the
     * next periodic tick — up to 15 sim-minutes later. A vessel's channel-to-berth transit is
     * {@code TransitToBerthBehaviour.TRANSIT_SIM_MILLIS} = 2 sim-minutes, so the alert essentially
     * never caught a vessel in transit, and the storm scenario's CANCEL cascade — the one the
     * scenario's own EMERGENCY banner announces as "Escorts cancelled, vessels held offshore",
     * and the one PROJECT_DEFINITION §13 counts on for the CANCEL performative — could not fire.
     * The scripted event now calls this so the change takes effect at the instant it is scripted
     * for. Idempotent with respect to the ticker: {@code lastObserved} advances here, so the next
     * periodic tick simply sees no transition.
     *
     * <p><b>S16.</b> Also called after every weather step that took effect, for the same reason:
     * a step on the hour could leave the HarbourMaster working from the previous reading for up
     * to fifteen game minutes. Like every cycle it does nothing while the game is paused; the
     * caller only steps while the clock runs.
     */
    public void broadcastNow() {
        onSimTick();
    }

    @Override
    protected void onSimTick() {
        if (simClock().isPaused()) {
            return; // pause gate (task 24): a paused game broadcasts nothing
        }
        WeatherSnapshot w = current.get();
        if (w == null) {
            return;
        }
        broadcast(w);
        // S16: the flag follows the DECISION to announce. (If the hub is not in the DF yet, the
        // alert is dropped with a warning, as it always was — a boot race at very fast paces.
        // The flag is raised all the same: the weather did worsen, so its end is still news.)
        Announcement announcement = announce(worseningOutstanding, lastObserved, w);
        announcement.alert().ifPresent(direction -> alertHarbourMaster(w, direction));
        eventBus.publish(new WeatherChangeEvent(w.wind(), w.visibility(), w.swell(), w.state(),
                announcement.alert().isPresent()));
        worseningOutstanding = announcement.worseningOutstanding();
        lastObserved = w;
    }

    /**
     * The 15-sim-minute weather snapshot, sent to the HarbourMaster hub (2026-08-10, audit P1-4).
     *
     * <p>It used to be addressed to a DF service type {@code "weather-subscriber"} that <b>no agent
     * has ever registered</b>, so {@code findAll} always came back empty and the INFORM was never
     * built, let alone sent — the whole periodic channel was dead, and only the transition-driven
     * alert path below did anything.
     *
     * <p>Routed to the hub rather than to vessels or tugs because nothing on those agents can
     * consume weather cheaply and honestly: a tug's {@code topSpeedKnots} is fixed at setup and
     * scaling only one of the bid-ETA/transit pair would break the agreement {@code TugMathTest}
     * pins, while vessel transit is duration-driven off a {@code final} arrival instant the
     * save/resume contract reads. The hub, by contrast, has a real use for it — see below — and
     * the HarbourMaster's send/receive is the comm log's single instrumentation point, so a
     * Weather-to-vessel message would have been invisible there anyway.
     *
     * <p><b>What it actually does:</b> alerts fire only on {@link WeatherAlertPolicy} TRANSITIONS,
     * so a vessel that enters transit while the weather is <em>already</em> bad is never held —
     * the storm began before it arrived and no further transition is coming. This periodic
     * snapshot re-runs the hub's existing hold gate over every tracked vessel, which is exactly
     * the set the transition path misses. It carries the {@code weather_periodic} discriminator so
     * the handler can tell it from a real alert, and rides the SAME protocol tag, so no new
     * template, protocol or behaviour is needed to receive it.
     */
    private void broadcast(WeatherSnapshot w) {
        Optional<AID> hub = locator.findUnique("harbour-master");
        if (hub.isEmpty()) {
            log.debug("no harbour-master in DF yet; skipping the periodic weather snapshot");
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hub.get());
        inform.setContent(TerminalJson.write(Map.of(
                "event", "weather_periodic",
                "wind_knots", w.wind(),
                "visibility", w.visibility(),
                "swell", w.swell(),
                "state", w.state(),
                "sim_time", w.simTime())));
        inform.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        myAgent.send(inform);
        log.debug("periodic weather snapshot wind={} state={} -> harbour-master", w.wind(), w.state());
    }

    private void alertHarbourMaster(WeatherSnapshot w, Direction direction) {
        Optional<AID> hm = locator.findUnique("harbour-master");
        if (hm.isEmpty()) {
            log.warn("no harbour-master in DF; dropping weather {} alert (wind {})", direction, w.wind());
            return;
        }
        boolean worsened = direction == Direction.WORSENED;
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.get());
        // v1.1: visibility + swell ride along so the HarbourMaster can evaluate
        // operationSafe/swellWithinLimit/weatherStateUnsafe from this SAME reading,
        // instead of risking a second, possibly-stale query (task 11).
        // S5 (R18 wired): the alert names the rule that produced it. The band comparison that
        // decided this transition IS threshold_crossed/2's enumeration (WeatherAlertPolicy.windBand
        // delegates to PrologQueries.windThresholdsCrossed), so the citation is the rule actually
        // consulted, and the Paraphraser renders it into the comm log line.
        // S16: that is the whole truth for the worsening direction only. A clear is now decided
        // by MovementGate.calmForEveryType (R15 with the release margin, R16, R17, R19), not by
        // a band comparison. Its reason string is left as it was: it is wire content that the
        // Paraphraser prints into the comm log and that the comm-log tests carry as fixtures.
        alert.setContent(TerminalJson.write(Map.of(
                "event", worsened ? "weather_threshold" : "weather_clear",
                "reason", worsened ? "threshold_crossed [R18]" : "threshold_cleared [R18]",
                "wind_knots", w.wind(), "visibility", w.visibility(),
                "swell", w.swell(), "state", w.state())));
        if (worsened) {
            // Only the worsening direction is URGENT (task 24 priority fix).
            alert.addUserDefinedParameter("priority", "high");
        }
        // Lets HandleWeatherAlertBehaviour match by protocol instead of sender AID —
        // avoids a DF-lookup timing race (task 11 adversarial review finding).
        // Same package, no import needed.
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        myAgent.send(alert);
        publishNotification(w, worsened);
        log.info("weather {} (wind {} kn, {}) -> INFORM to harbour-master", direction, w.wind(), w.state());
    }

    private void publishNotification(WeatherSnapshot w, boolean worsened) {
        String text = String.format(Locale.ROOT, "%s: %d kn, %s, swell %.1f m",
                worsened ? "Weather worsening" : "Weather clearing", w.wind(), w.state(), w.swell());
        // S18b (PT10): one weather chip at a time — the clearing replaces the worsening.
        eventBus.publish(new NotificationEvent(text,
                worsened ? NotificationEvent.Severity.WARNING : NotificationEvent.Severity.INFO,
                w.simTime(), NotificationEvent.WEATHER_TOPIC));
    }
}
