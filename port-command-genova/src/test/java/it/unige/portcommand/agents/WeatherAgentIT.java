package it.unige.portcommand.agents;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.agents.InitArgs.WeatherInitArgs;
import it.unige.portcommand.agents.InitArgs.WeatherInitArgs.ScriptedWeather;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.persistence.AgentDirectory;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 wire gate for the WeatherAgent: every broadcast carries the envelope, and
 * a scripted storm trips the tanker-30 limit into a {@code priority=high} INFORM to
 * the HarbourMaster. Timing is driven by advancing the INJECTED SimClock — no
 * wall-clock sleeps. The storm is a scripted override (not a Markov draw), so the
 * trip is deterministic regardless of seed (asserted by wind == 32 exactly).
 *
 * <p><b>S16 (2026-10-13, decision B6) — what changed here and why.</b> The weather no longer
 * steps once per wall tick of a ticker armed with the wall length of a game hour; it steps on
 * the full game hours of the SIM clock. Nothing in this class runs a wall-clock advancer, so
 * the clock stands at 00:00 until a test moves it, and the first step is 01:00. The two
 * override tests used to advance to sim-minute 24 and then wait for the next wall tick
 * (12.5&nbsp;s after boot at this pacing, against a 15-second poll); under the new cadence
 * that advance crosses no hour boundary and nothing would ever happen. They now (1) wait for
 * the first periodic snapshot and (2) advance the clock just past 01:00, where the override
 * replaces that hour's step. The periodic test needed no change: the broadcast still ticks in
 * wall time whether or not the clock moves.
 * {@link #aStepOnTheHourIsStampedOnItsBoundaryAndBroadcastAtOnce} is new.
 *
 * <p><b>Why the override tests still wait for a periodic snapshot.</b> Not for a baseline any
 * more: since the S16 follow-up the alert baseline is the reading the agent BOOTS with (it was
 * {@code null} until the first broadcast, and a first broadcast never alerted), so no case here
 * depends on a first broadcast being silent. The wait is for the probe hub: a step is broadcast
 * at once and its alert is sent exactly once, and a hub that has not finished registering in
 * the DF at that instant would never see it. A periodic snapshot in the probe's inbox proves it
 * is registered. {@link #aChangeBeforeTheFirstBroadcastIsAnnouncedAgainstTheBootReading} pins
 * the new baseline itself.
 */
@Tag("integration")
class WeatherAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    /** At 300 real-s/day, 13 real seconds are 01:02:24 of game time: just past the first hour. */
    private static final long JUST_PAST_THE_FIRST_HOUR_REAL_MS = 13_000L;

    private void bootWeather(List<ScriptedWeather> overrides) {
        bootWeather(overrides, 300);
    }

    private void bootWeather(List<ScriptedWeather> overrides, long realSecondsPerGameDay) {
        bootWeather(overrides, realSecondsPerGameDay, new AgentDirectory());
    }

    /** {@code directory}: found by type among the args; the agent registers itself in it. */
    private void bootWeather(List<ScriptedWeather> overrides, long realSecondsPerGameDay,
                             AgentDirectory directory) {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", realSecondsPerGameDay));
        WeatherInitArgs args = new WeatherInitArgs(
                new WeatherSnapshot(18, "good", 0.5, "sunny", 0L), TransitionMatrix.defaults(), overrides);
        boot.getSpawner().spawn("weather_agent", WeatherAgent.class,
                new Object[] {args, boot.getRandomSource(), boot.getSimClock(), boot.getEventBus(), directory});
    }

    /** The committed bounded sleep-poll (design rule 5): a condition, with a deadline. */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /**
     * Audit P1-4: the periodic snapshot reaches the HARBOUR MASTER, tagged and discriminated.
     *
     * <p>It used to be addressed to DF service type {@code "weather-subscriber"}, which no agent
     * in the shipped game has ever registered — so this test could only pass by spawning a probe
     * under a service type that exists nowhere in {@code src/main}, and the production channel was
     * dead. Now it goes to the hub, on the same {@code WEATHER_ALERT_PROTOCOL} the transition alert
     * uses, carrying {@code event=weather_periodic} so the handler can tell the two apart.
     */
    @Test
    void periodicSnapshotsReachTheHarbourMasterTaggedAndDiscriminated() throws Exception {
        bootWeather(List.of());
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", SubscriberProbe.class, new Object[] {"harbour-master", hmInbox});

        for (int i = 0; i < 3; i++) {
            ACLMessage b = pollFor(hmInbox, "weather_periodic", 15);
            assertNotNull(b, "expected periodic snapshot #" + (i + 1) + " at the harbour master");
            assertEquals(ACLMessage.INFORM, b.getPerformative());
            assertEquals("port_command_v1", b.getOntology());
            assertEquals("json", b.getLanguage());
            assertEquals(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL, b.getProtocol(),
                    "the snapshot must ride the tag the hub's existing template matches");
            JsonNode c = MAPPER.readTree(b.getContent());
            assertTrue(c.has("wind_knots") && c.has("state") && c.has("visibility"),
                    "snapshot content: " + b.getContent());
        }
    }

    /**
     * Drains until a message whose {@code event} field equals {@code event} arrives, or the
     * deadline passes. The hub now receives BOTH weather message kinds on one protocol tag —
     * 15-sim-minute snapshots and transition alerts — so a bare {@code poll} would return whichever
     * happened to be first and make these tests order-dependent.
     */
    private ACLMessage pollFor(BlockingQueue<ACLMessage> inbox, String event, int timeoutSeconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (m == null) {
                continue;
            }
            JsonNode c = MAPPER.readTree(m.getContent());
            if (c.has("event") && event.equals(c.get("event").asText())) {
                return m;
            }
        }
        return null;
    }

    /** As {@link #pollFor}, for the periodic snapshot of the reading stamped {@code simTime}. */
    private ACLMessage pollForSnapshotStamped(BlockingQueue<ACLMessage> inbox, long simTime, int timeoutSeconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (m == null) {
                continue;
            }
            JsonNode c = MAPPER.readTree(m.getContent());
            if (c.has("event") && "weather_periodic".equals(c.get("event").asText())
                    && c.has("sim_time") && c.get("sim_time").asLong() == simTime) {
                return m;
            }
        }
        return null;
    }

    /**
     * S16 (decision B6), the wiring the pure {@code WeatherStepperTest} cannot see: the live
     * agent takes its step on the hour boundary of the SIM clock, stamps the reading with that
     * boundary, and broadcasts it at once.
     *
     * <p>Paced at 720 real-s/day so the two cadences are far apart: the evolution behaviour
     * looks at the clock every sim-minute (0.5&nbsp;s of wall time) and the periodic broadcast
     * ticks every 15 sim-minutes (7.5&nbsp;s). The clock is moved to 01:02:00 right after a
     * periodic snapshot has arrived, so the next periodic tick is 7.5&nbsp;s away; the stepped
     * reading must reach the hub within 5. Pre-S16 this is red twice over: the reading was
     * stamped with the tick's own time (01:02:00, never the 01:00:00 boundary), and a step was
     * only ever broadcast by the next periodic tick.
     */
    @Test
    void aStepOnTheHourIsStampedOnItsBoundaryAndBroadcastAtOnce() throws Exception {
        bootWeather(List.of(), 720);
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", SubscriberProbe.class, new Object[] {"harbour-master", hmInbox});

        ACLMessage opening = pollFor(hmInbox, "weather_periodic", 20);
        assertNotNull(opening, "fixture: a periodic snapshot must arrive before the clock is moved");
        assertEquals(0L, MAPPER.readTree(opening.getContent()).get("sim_time").asLong(),
                "the clock has not moved, so this is still the opening reading: no step yet");

        // 31_000 real-ms at 720 real-s/day = 3_720_000 sim-ms = 01:02:00 — one hour boundary.
        boot.getSimClock().advance(31_000);

        ACLMessage stepped = pollForSnapshotStamped(hmInbox, 3_600_000L, 5);
        assertNotNull(stepped, "the 01:00 step must be stamped 3_600_000 (its boundary, not the "
                + "01:02:00 it was noticed at) and broadcast at once, not on the next periodic tick");
    }

    /**
     * S16 follow-up: the alert baseline is the reading the agent BOOTS with, so a change that is
     * already in the cell when the first broadcast runs is announced.
     *
     * <p>Paced at 720 real-s/day, where the first periodic broadcast is 7.5&nbsp;s of wall time
     * after the agent starts. Well inside that, a 32-kn reading (everything else calm) is forced
     * into the agent's cell through the same bus event the Debug menu and a scripted weather
     * change use. The first broadcast the hub ever receives must then carry 32 kn AND be
     * followed by the worsening alert, judged against the 18-kn reading the agent booted with.
     * Before the follow-up this is red: the baseline was {@code null} until the first broadcast,
     * a first broadcast was therefore never an alert, and every later one compared 32 kn with
     * 32 kn.
     */
    @Test
    void aChangeBeforeTheFirstBroadcastIsAnnouncedAgainstTheBootReading() throws Exception {
        AgentDirectory directory = new AgentDirectory();
        bootWeather(List.of(), 720, directory);
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", SubscriberProbe.class, new Object[] {"harbour-master", hmInbox});

        // The override plan subscribes to the bus on the agent's own thread, a moment after its
        // setup; publishing is idempotent, so publish until the cell shows the forced reading.
        DebugWeatherEvent gale = new DebugWeatherEvent(32, "good", 0.5, "sunny");
        assertTrue(waitUntil(() -> {
            boot.getEventBus().publish(gale);
            return directory.byType(WeatherAgent.class).stream()
                    .anyMatch(weather -> weather.current().wind() == 32);
        }, 5_000), "fixture: the forced reading must land in the weather agent's cell");

        ACLMessage first = pollFor(hmInbox, "weather_periodic", 20);
        assertNotNull(first, "fixture: the first periodic snapshot must reach the hub");
        assertEquals(32, MAPPER.readTree(first.getContent()).get("wind_knots").asInt(),
                "fixture: the FIRST broadcast must already carry the forced reading — it was forced "
                        + "before the first tick");

        ACLMessage alert = pollFor(hmInbox, "weather_threshold", 5);
        assertNotNull(alert, "18 kn at boot, 32 kn at the first broadcast: the worsening must be "
                + "announced by that broadcast, not swallowed as a baseline");
        assertEquals(32, MAPPER.readTree(alert.getContent()).get("wind_knots").asInt());
        assertEquals("high", alert.getUserDefinedParameter("priority"));
    }

    /**
     * S5 (audit P2-4), R18 isolated: a WIND-ONLY crossing — visibility/swell/state all stay at
     * their calm initial values — so the worsening verdict can come from nothing but
     * {@code threshold_crossed/2}'s solution count (the Java threshold array is deleted). The
     * scripted-storm case below cannot pin this: its "stormy"/"poor" override crosses on three
     * dimensions at once, so R18 could go dead and that alert would still fire.
     */
    @Test
    void windOnlyCrossing_isDecidedByR18Alone() throws Exception {
        bootWeather(List.of(new ScriptedWeather(8, new WeatherSnapshot(32, "good", 0.5, "sunny", 0L))));
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", SubscriberProbe.class, new Object[] {"harbour-master", hmInbox});
        // S16: first a periodic snapshot in the probe's inbox (it proves the probe hub is in the
        // DF; the alert is sent once), then the hour boundary. See the class comment.
        assertNotNull(pollFor(hmInbox, "weather_periodic", 15),
                "fixture: a periodic snapshot must reach the probe hub before the clock is moved");
        boot.getSimClock().advance(JUST_PAST_THE_FIRST_HOUR_REAL_MS);

        ACLMessage alert = pollFor(hmInbox, "weather_threshold", 15);
        assertNotNull(alert,
                "an 18→32 kn rise with everything else calm must alert — R18 is the only judge");
        JsonNode c = MAPPER.readTree(alert.getContent());
        assertEquals(32, c.get("wind_knots").asInt());
        assertEquals("threshold_crossed [R18]", c.get("reason").asText());
    }

    @Test
    void scriptedStormTripsTankerThresholdToHarbourMaster() throws Exception {
        // scripted storm at sim-minute 8: wind 32 kn, stormy
        bootWeather(List.of(new ScriptedWeather(8, new WeatherSnapshot(32, "poor", 3.5, "stormy", 0L))));
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", SubscriberProbe.class, new Object[] {"harbour-master", hmInbox});
        // S16: the override — not a Markov step — is the first evolution action because it
        // REPLACES the first hourly step, the one at 01:00; there is no longer a race against a
        // wall tick. First a periodic snapshot in the probe's inbox (the probe hub is then in the
        // DF, and the alert is sent only once), then 13_000 real-ms at 300 real-s/day =
        // 3_744_000 sim-ms = 01:02:24: past the first hour boundary, and past the override's
        // sim-minute 8. It used to be 5_000 real-ms (sim-minute 24) and a 12.5-second wait for
        // the wall tick; that advance crosses no hour and would now apply nothing.
        assertNotNull(pollFor(hmInbox, "weather_periodic", 15),
                "fixture: a periodic snapshot must reach the probe hub before the clock is moved");
        boot.getSimClock().advance(JUST_PAST_THE_FIRST_HOUR_REAL_MS);

        // Filtered by event (audit P1-4): the hub also receives periodic snapshots on this same
        // protocol tag now, and they carry no priority parameter — an unfiltered poll would return
        // one of those and fail on the priority assertion below.
        ACLMessage alert = pollFor(hmInbox, "weather_threshold", 15);
        assertNotNull(alert, "scripted storm must trip a priority=high INFORM to the HarbourMaster");
        assertEquals(ACLMessage.INFORM, alert.getPerformative());
        assertEquals("port_command_v1", alert.getOntology());
        assertEquals("json", alert.getLanguage());
        assertEquals("high", alert.getUserDefinedParameter("priority"), "threshold alert is high priority");
        JsonNode c = MAPPER.readTree(alert.getContent());
        assertEquals("weather_threshold", c.get("event").asText());
        // wind==32 AND state==stormy pins it to the scripted override: a Markov draw
        // reaching 32 from cloudy [15,32] would carry state "cloudy", not "stormy".
        assertEquals(32, c.get("wind_knots").asInt(), "the scripted 32-kn storm tripped it (not a Markov fluke)");
        assertEquals("stormy", c.get("state").asText(), "scripted storm state");
        // S5 (audit P2-4): the crossing verdict is R18's — WeatherAlertPolicy counts
        // threshold_crossed/2 solutions (the Java threshold array is deleted) and the alert
        // says so where the Paraphraser renders it.
        assertEquals("threshold_crossed [R18]", c.get("reason").asText(),
                "a worsening alert must carry the R18 crossing rationale");
    }
}
