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
 * ontology + language + content. S5 (audit P2-4): the clearance path is now the
 * rules ROUTER — hazmat is high-risk (R22/R24) and CONFIRMs only after the
 * inspection delay, with the deciding rules cited in the {@code reason} field;
 * the blacklist round-trip (asserted/retracted directly on the live Prolog
 * engine) → INFORM(flagged {@code [R21,R22]}); non-hazmat keeps R23's seeded
 * roll — a miss pre-clears immediately ({@code [R22,R23]}), a hit inspects
 * ({@code [R23]}). The retired {@code routine_inspection} INFORM (which nothing
 * HM-side ever consumed) must never appear again.
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
        boot.getSpawner().spawn("customs_agent", CustomsAgent.class, new Object[] {rng, boot.getSimClock()});
    }

    @Test
    void hazmatClearanceConfirmsAfterInspection() throws Exception {
        bootCustoms();
        long before = System.currentTimeMillis();
        ACLMessage reply = request(customsRequest("tanker", "hazmat_class_3"));
        long elapsed = System.currentTimeMillis() - before;
        assertEnvelope(reply, ACLMessage.CONFIRM);
        JsonNode c = content(reply);
        assertTrue(c.get("ref").asText().startsWith("CL-"), "clearance ref");
        // R22 (is_hazmat subtree) put it in play, R24 (high_risk ≥ 0.5) sent it to inspection.
        assertEquals("inspected [R22,R24]", c.get("reason").asText(),
                "hazmat must carry the R22/R24 inspection rationale");
        assertEquals(120, c.get("inspection_sim_seconds").asLong(),
                "the inspection's sim duration must ride the CONFIRM");
        assertTrue(elapsed >= 250,
                "the CONFIRM must arrive only after the wall-clamped inspection delay (≥250 ms floor), "
                        + "took " + elapsed + " ms");
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
            assertEquals("blacklisted [R21,R22]", content(flagged).get("reason").asText(),
                    "the flag must cite the deciding rules (R21 blacklist under R22's gate)");
            assertEquals("high", flagged.getUserDefinedParameter("priority"));
        } finally {
            engine.retractAllMatching("blacklisted_combo(tanker, hazmat_class_3)");
        }
        ACLMessage confirmed = request(customsRequest("tanker", "hazmat_class_3"));
        assertEnvelope(confirmed, ACLMessage.CONFIRM);
        assertTrue(content(confirmed).get("ref").asText().startsWith("CL-"));
        assertEquals("inspected [R22,R24]", content(confirmed).get("reason").asText(),
                "off the blacklist, hazmat still goes through the R24 inspection path");
    }

    @Test
    void nonHazmatRollMatchesTheSeededStream() throws Exception {
        bootCustoms();
        // Replicate the customs agent's first roll (same master seed → same sub-stream).
        // S5: BOTH outcomes are now a CONFIRM — the roll decides between an immediate
        // pre-clearance and a delayed inspection, and the reason names which.
        double predictedRoll = boot.getRandomSource().forStream("customs").nextDouble();
        ACLMessage reply = request(customsRequest("tanker", "general_cargo"));
        assertEnvelope(reply, ACLMessage.CONFIRM);
        assertTrue(content(reply).get("ref").asText().startsWith("CL-"));
        String expected = predictedRoll < 0.2 // inspection_probability(non-hazmat) = 0.2
                ? "inspected [R23]"
                : "no_inspection_required [R22,R23]";
        assertEquals(expected, content(reply).get("reason").asText(),
                "the rationale must match the seeded R23 roll (predicted " + predictedRoll + ")");
    }

    @Test
    void nonHazmatLowRollInspectsBeforeClearing() throws Exception {
        // Pick a seed whose "customs" sub-stream first roll is < 0.2, forcing the
        // inspection branch (the default master seed rolls 0.234 → pre-clear, leaving
        // this path uncovered). Deterministic: the same seed is found every run.
        long seed = 0;
        while (new RandomSource(seed).forStream("customs").nextDouble() >= 0.2) {
            seed++;
        }
        bootCustomsWith(new RandomSource(seed));
        long before = System.currentTimeMillis();
        ACLMessage reply = request(customsRequest("tanker", "general_cargo"));
        long elapsed = System.currentTimeMillis() - before;
        // S5 (D5): a hit is a DELAYED CONFIRM through the inspection stub, not the retired
        // routine_inspection INFORM (which nothing HM-side ever consumed).
        assertEnvelope(reply, ACLMessage.CONFIRM);
        JsonNode c = content(reply);
        assertTrue(c.get("ref").asText().startsWith("CL-"));
        assertEquals("inspected [R23]", c.get("reason").asText(),
                "a clean-cargo random inspection must cite R23 alone");
        assertEquals(120, c.get("inspection_sim_seconds").asLong());
        assertTrue(elapsed >= 250,
                "the inspection delay must be observable (≥250 ms floor), took " + elapsed + " ms");
    }

    /**
     * S5 adversarial finding 2: an unrecognised cargo class must FLAG (R21 validates the atoms,
     * not just the blacklist), never fall through to {@code inspectionProbability}'s
     * {@code orElseThrow} — which killed the agent's only clearance behaviour. Reachable since
     * D7 sends EVERY contracted vessel here and {@code SaveLoadManager.validateState} does not
     * validate {@code cargoClass}, so a hand-edited save delivers exactly this shape. The
     * follow-up request proves the behaviour SURVIVED.
     */
    @Test
    void unknownCargoClassFlagsInsteadOfKillingTheAgent() throws Exception {
        bootCustoms();
        ACLMessage flagged = request(customsRequest("tanker", "mystery_goo"));
        assertEnvelope(flagged, ACLMessage.INFORM);
        assertEquals("flagged", content(flagged).get("event").asText());
        assertEquals("clearance_refused [R21]", content(flagged).get("reason").asText());

        // The behaviour must still be alive to serve the next (valid) request.
        ACLMessage next = request(customsRequest("tanker", "hazmat_class_3"));
        assertEnvelope(next, ACLMessage.CONFIRM);
        assertEquals("inspected [R22,R24]", content(next).get("reason").asText(),
                "the clearance behaviour must survive the malformed request");
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
