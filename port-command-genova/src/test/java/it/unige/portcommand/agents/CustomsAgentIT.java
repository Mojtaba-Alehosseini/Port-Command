package it.unige.portcommand.agents;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.RandomSource;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 wire gate for the CustomsAgent. Every reply asserts performative +
 * ontology + language + content. Hazmat → CONFIRM with a {@code CL-} ref; a
 * blacklist round-trip (asserted/retracted directly on the live Prolog engine) →
 * INFORM(flagged) then CONFIRM; non-hazmat → the inspection roll is PREDICTED by
 * replicating the seeded {@code forStream("customs")} stream (deterministic).
 */
@Tag("integration")
class CustomsAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AtomicInteger probeSeq = new AtomicInteger();

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    private void bootCustoms() {
        bootCustomsWith(null);
    }

    /** Boot with an explicit RNG for the customs agent (null → the default master RNG). */
    private void bootCustomsWith(RandomSource customsRng) {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        RandomSource rng = customsRng != null ? customsRng : boot.getRandomSource();
        boot.getSpawner().spawn("customs_agent", CustomsAgent.class, new Object[] {rng});
    }

    @Test
    void hazmatClearanceConfirmsWithRef() throws Exception {
        bootCustoms();
        ACLMessage reply = request(customsRequest("tanker", "hazmat_class_3"));
        assertEnvelope(reply, ACLMessage.CONFIRM);
        assertTrue(content(reply).get("ref").asText().startsWith("CL-"), "clearance ref");
    }

    @Test
    void blacklistedHazmatFlaggedThenClearedAfterRetract() throws Exception {
        bootCustoms();
        PrologEngine engine = PrologEngine.getInstance();
        engine.assertFact("blacklisted_combo(tanker, hazmat_class_3)");
        try {
            ACLMessage flagged = request(customsRequest("tanker", "hazmat_class_3"));
            assertEnvelope(flagged, ACLMessage.INFORM);
            assertEquals("flagged", content(flagged).get("event").asText());
            assertEquals("high", flagged.getUserDefinedParameter("priority"));
        } finally {
            engine.retractAllMatching("blacklisted_combo(tanker, hazmat_class_3)");
        }
        ACLMessage confirmed = request(customsRequest("tanker", "hazmat_class_3"));
        assertEnvelope(confirmed, ACLMessage.CONFIRM);
        assertTrue(content(confirmed).get("ref").asText().startsWith("CL-"));
    }

    @Test
    void nonHazmatRollMatchesTheSeededStream() throws Exception {
        bootCustoms();
        // Replicate the customs agent's first roll (same master seed → same sub-stream).
        double predictedRoll = boot.getRandomSource().forStream("customs").nextDouble();
        ACLMessage reply = request(customsRequest("tanker", "general_cargo"));
        if (predictedRoll < 0.2) { // inspection_probability(non-hazmat) = 0.2
            assertEnvelope(reply, ACLMessage.INFORM);
            assertEquals("routine_inspection", content(reply).get("event").asText());
        } else {
            assertEnvelope(reply, ACLMessage.CONFIRM);
            assertTrue(content(reply).get("ref").asText().startsWith("CL-"));
        }
    }

    @Test
    void nonHazmatLowRollTriggersRoutineInspection() throws Exception {
        // Pick a seed whose "customs" sub-stream first roll is < 0.2, forcing the
        // inspection branch (the default master seed rolls 0.234 → CONFIRM, leaving
        // this path uncovered). Deterministic: the same seed is found every run.
        long seed = 0;
        while (new RandomSource(seed).forStream("customs").nextDouble() >= 0.2) {
            seed++;
        }
        bootCustomsWith(new RandomSource(seed));
        ACLMessage reply = request(customsRequest("tanker", "general_cargo"));
        assertEnvelope(reply, ACLMessage.INFORM);
        assertEquals("routine_inspection", content(reply).get("event").asText());
    }

    // --- helpers -----------------------------------------------------------

    private ACLMessage request(String content) throws Exception {
        CompletableFuture<ACLMessage> slot = new CompletableFuture<>();
        boot.getSpawner().spawn("probe_" + probeSeq.incrementAndGet(), MessageProbe.class,
                new Object[] {"customs_agent", ACLMessage.REQUEST, content, slot});
        return slot.get(15, TimeUnit.SECONDS);
    }

    private static String customsRequest(String vesselType, String cargoClass) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "vessel_id", "v1", "vessel_type", vesselType, "cargo_class", cargoClass));
    }

    private static void assertEnvelope(ACLMessage reply, int performative) {
        assertNotNull(reply, "expected a reply");
        assertEquals(performative, reply.getPerformative(),
                "performative (got " + ACLMessage.getPerformative(reply.getPerformative()) + ")");
        assertEquals("port_command_v1", reply.getOntology());
        assertEquals("json", reply.getLanguage());
    }

    private static JsonNode content(ACLMessage msg) throws Exception {
        return MAPPER.readTree(msg.getContent());
    }
}
