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

    private void bootWeather(List<ScriptedWeather> overrides) {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        WeatherInitArgs args = new WeatherInitArgs(
                new WeatherSnapshot(18, "good", 0.5, "sunny", 0L), TransitionMatrix.defaults(), overrides);
        boot.getSpawner().spawn("weather_agent", WeatherAgent.class,
                new Object[] {args, boot.getRandomSource(), boot.getSimClock(), boot.getEventBus()});
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
        boot.getSimClock().advance(5_000);

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
        // Advance immediately (before the first ~1s evolution tick) so the override —
        // not a Markov step — is the first evolution action. 5_000 real-ms at 300
        // real-s/day = 1_440_000 sim-ms = sim-minute 24, well past the sim-minute-8 override.
        boot.getSimClock().advance(5_000);

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
