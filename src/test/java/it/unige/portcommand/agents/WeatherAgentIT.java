package it.unige.portcommand.agents;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.agents.InitArgs.WeatherInitArgs;
import it.unige.portcommand.agents.InitArgs.WeatherInitArgs.ScriptedWeather;
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
                new Object[] {args, boot.getRandomSource(), boot.getSimClock()});
    }

    @Test
    void broadcastsCarryTheEnvelope() throws Exception {
        bootWeather(List.of());
        BlockingQueue<ACLMessage> broadcasts = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("wx_sub", SubscriberProbe.class,
                new Object[] {"weather-subscriber", broadcasts});

        for (int i = 0; i < 3; i++) {
            ACLMessage b = broadcasts.poll(10, TimeUnit.SECONDS);
            assertNotNull(b, "expected broadcast #" + (i + 1));
            assertEquals(ACLMessage.INFORM, b.getPerformative());
            assertEquals("port_command_v1", b.getOntology());
            assertEquals("json", b.getLanguage());
            JsonNode c = MAPPER.readTree(b.getContent());
            assertTrue(c.has("wind_knots") && c.has("state") && c.has("visibility"),
                    "broadcast content: " + b.getContent());
        }
    }

    @Test
    void scriptedStormTripsTankerThresholdToHarbourMaster() throws Exception {
        // scripted storm at sim-minute 8: wind 32 kn, stormy
        bootWeather(List.of(new ScriptedWeather(8, new WeatherSnapshot(32, "poor", 3.5, "stormy", 0L))));
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", SubscriberProbe.class, new Object[] {"harbour-master", hmInbox});
        BlockingQueue<ACLMessage> broadcasts = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("wx_sub", SubscriberProbe.class,
                new Object[] {"weather-subscriber", broadcasts});

        // Advance immediately (before the first ~1s evolution tick) so the override —
        // not a Markov step — is the first evolution action. 5_000 real-ms at 300
        // real-s/day = 1_440_000 sim-ms = sim-minute 24, well past the sim-minute-8 override.
        boot.getSimClock().advance(5_000);

        ACLMessage alert = hmInbox.poll(15, TimeUnit.SECONDS);
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
    }
}
