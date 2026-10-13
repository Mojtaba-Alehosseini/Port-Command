package it.unige.portcommand.agents;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.RandomSource;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 wire gate for the CustomsAgent. Every reply asserts performative +
 * ontology + language + content. S5 (audit P2-4): the clearance path is now the
 * rules ROUTER — hazmat is high-risk (R22/R24) and CONFIRMs only after the
 * inspection, with the deciding rules cited in the {@code reason} field;
 * the blacklist round-trip (asserted/retracted directly on the live Prolog
 * engine) → INFORM(flagged {@code [R21,R22]}); non-hazmat keeps R23's seeded
 * roll — a miss pre-clears immediately ({@code [R22,R23]}), a hit inspects
 * ({@code [R23]}). The retired {@code routine_inspection} INFORM (which nothing
 * HM-side ever consumed) must never appear again.
 *
 * <p>S16 (ADR-20, decision C5): an inspection is 45 GAME minutes, on the game clock. It used to
 * be two game minutes clamped to a 250 ms - 5 s wall window — a delay nobody could see and
 * nothing waited for. It is now two messages: an {@code inspection_started} INFORM at once (so
 * the HarbourMaster can show "customs inspection" on the Port table), then the CONFIRM when the
 * 45 minutes have passed.
 *
 * <p><b>How the two timing cases really pin the game clock</b> (corrected after the S16
 * adversarial review, which showed the first version did not). The wait is a
 * {@code SimDeadlineWait}: at an undisturbed pace it simply spends the wall time the 45 minutes
 * are worth, and a hand-moved clock does not shorten that — so "advance, then look" proved
 * nothing. Two things make it a proof here. The pace ({@link #PACE}) makes 45 game minutes worth
 * HALF A REAL SECOND, so any wall-timed inspection is long over inside the waits below. And the
 * clock is PAUSED, as in a paused game: the wait then holds on game time alone, and game time
 * moves only when the test jumps it. No clearance after several real seconds at 44 minutes;
 * clearance once the 45th has passed.
 */
@Tag("integration")
class CustomsAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 16 real seconds per game day: 2,700 game seconds are 500 real milliseconds. */
    private static final long PACE = 16L;
    /** The literal, on purpose: 45 game minutes is the approved number (S16 plan). */
    private static final long INSPECTION_SIM_SECONDS = 2_700L;
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
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", PACE));
        RandomSource rng = customsRng != null ? customsRng : boot.getRandomSource();
        boot.getSpawner().spawn("customs_agent", CustomsAgent.class, new Object[] {rng, boot.getSimClock()});
    }

    @Test
    void hazmatIsInspectedForFortyFiveGameMinutesBeforeItIsCleared() throws Exception {
        bootCustoms();
        boot.getSimClock().pause(); // game time now moves only when this test jumps it
        BlockingQueue<ACLMessage> replies = ask(customsRequest("tanker", "hazmat_class_3"));

        ACLMessage started = replies.poll(15, TimeUnit.SECONDS);
        assertEnvelope(started, ACLMessage.INFORM);
        assertEquals("inspection_started", content(started).get("event").asText(),
                "the inspection is announced the moment it starts");
        // R22 (is_hazmat subtree) put it in play, R24 (high_risk ≥ 0.5) sent it to inspection.
        assertEquals("inspected [R22,R24]", content(started).get("reason").asText());
        assertEquals(INSPECTION_SIM_SECONDS, content(started).get("inspection_sim_seconds").asLong());

        // Born red: before S16 the CONFIRM came after at most five REAL seconds, whatever the
        // game clock said. Two and a half real seconds pass here - five times what 45 game
        // minutes are worth at this pace - and no game time at all.
        assertNull(replies.poll(2_500, TimeUnit.MILLISECONDS),
                "no game time has passed: the ship is still being inspected, however long we wait");
        jump(INSPECTION_SIM_SECONDS - 60L);
        assertNull(replies.poll(2_500, TimeUnit.MILLISECONDS),
                "44 game minutes in: still being inspected");

        jump(120L);
        ACLMessage reply = replies.poll(15, TimeUnit.SECONDS);
        assertEnvelope(reply, ACLMessage.CONFIRM);
        JsonNode c = content(reply);
        assertTrue(c.get("ref").asText().startsWith("CL-"), "clearance ref");
        assertEquals("inspected [R22,R24]", c.get("reason").asText(),
                "hazmat must carry the R22/R24 inspection rationale");
        assertEquals(INSPECTION_SIM_SECONDS, c.get("inspection_sim_seconds").asLong(),
                "the inspection's sim duration must ride the CONFIRM");
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
        ACLMessage confirmed = clearance(customsRequest("tanker", "hazmat_class_3"));
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
        ACLMessage reply = clearance(customsRequest("tanker", "general_cargo"));
        assertEnvelope(reply, ACLMessage.CONFIRM);
        assertTrue(content(reply).get("ref").asText().startsWith("CL-"));
        String expected = predictedRoll < 0.2 // inspection_probability(non-hazmat) = 0.2
                ? "inspected [R23]"
                : "no_inspection_required [R22,R23]";
        assertEquals(expected, content(reply).get("reason").asText(),
                "the rationale must match the seeded R23 roll (predicted " + predictedRoll + ")");
    }

    @Test
    void aCleanCargoThatIsNotDrawnIsClearedAtOnce() throws Exception {
        // The other side of the roll: a seed whose first "customs" draw is 0.2 or more. No
        // inspection, so no start notice and no wait — the CONFIRM is the first and only reply,
        // with the game clock standing still.
        long seed = 0;
        while (new RandomSource(seed).forStream("customs").nextDouble() < 0.2) {
            seed++;
        }
        bootCustomsWith(new RandomSource(seed));
        ACLMessage reply = request(customsRequest("cargo_vessel", "general_cargo"));
        assertEnvelope(reply, ACLMessage.CONFIRM);
        assertEquals("no_inspection_required [R22,R23]", content(reply).get("reason").asText());
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
        boot.getSimClock().pause();
        BlockingQueue<ACLMessage> replies = ask(customsRequest("tanker", "general_cargo"));

        ACLMessage started = replies.poll(15, TimeUnit.SECONDS);
        assertEnvelope(started, ACLMessage.INFORM);
        assertEquals("inspection_started", content(started).get("event").asText());
        assertEquals("inspected [R23]", content(started).get("reason").asText(),
                "a clean-cargo random inspection must cite R23 alone");

        jump(INSPECTION_SIM_SECONDS - 60L);
        assertNull(replies.poll(2_500, TimeUnit.MILLISECONDS),
                "a routine inspection takes the same 45 game minutes as any other");

        jump(120L);
        // S5 (D5): a hit is a DELAYED CONFIRM through the inspection stub, not the retired
        // routine_inspection INFORM (which nothing HM-side ever consumed).
        ACLMessage reply = replies.poll(15, TimeUnit.SECONDS);
        assertEnvelope(reply, ACLMessage.CONFIRM);
        JsonNode c = content(reply);
        assertTrue(c.get("ref").asText().startsWith("CL-"));
        assertEquals("inspected [R23]", c.get("reason").asText());
        assertEquals(INSPECTION_SIM_SECONDS, c.get("inspection_sim_seconds").asLong());
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
        ACLMessage next = clearance(customsRequest("tanker", "hazmat_class_3"));
        assertEnvelope(next, ACLMessage.CONFIRM);
        assertEquals("inspected [R22,R24]", content(next).get("reason").asText(),
                "the clearance behaviour must survive the malformed request");
    }

    // --- helpers -----------------------------------------------------------

    /** The FIRST reply to one request (a flag, or a clearance that needed no inspection). */
    private ACLMessage request(String content) throws Exception {
        CompletableFuture<ACLMessage> slot = new CompletableFuture<>();
        boot.getSpawner().spawn("probe_" + probeSeq.incrementAndGet(), MessageProbe.class,
                new Object[] {"customs_agent", ACLMessage.REQUEST, content, slot});
        return slot.get(15, TimeUnit.SECONDS);
    }

    /** Sends one request from a puppet that keeps EVERY reply, in arrival order. */
    private BlockingQueue<ACLMessage> ask(String content) throws Exception {
        BlockingQueue<ACLMessage> outbox = new LinkedBlockingQueue<>();
        BlockingQueue<ACLMessage> inbox = new LinkedBlockingQueue<>();
        String name = "asker_" + probeSeq.incrementAndGet();
        boot.getSpawner().spawn(name, HmProbeAgent.class, new Object[] {outbox, inbox});
        ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
        request.addReceiver(new AID("customs_agent", AID.ISLOCALNAME));
        request.setConversationId("conv-" + name);
        request.setContent(content);
        outbox.put(request);
        return inbox;
    }

    /**
     * The clearance for one request, whichever way it comes: at once, or — when the first reply
     * is the start of an inspection — once the inspection is over. For the cases that are about
     * WHAT customs decides, not when: the clock is not paused in them, so the inspection simply
     * takes the half real second it is worth at this suite's pace.
     */
    private ACLMessage clearance(String content) throws Exception {
        BlockingQueue<ACLMessage> replies = ask(content);
        ACLMessage first = replies.poll(15, TimeUnit.SECONDS);
        assertNotNull(first, "expected a reply");
        if (first.getPerformative() == ACLMessage.INFORM
                && "inspection_started".equals(content(first).path("event").asText())) {
            return replies.poll(15, TimeUnit.SECONDS);
        }
        return first;
    }

    /**
     * Moves a PAUSED clock on by {@code simSeconds} and pauses it again: resume, advance, pause.
     * ({@code SimClock.advance} is a no-op while paused.)
     */
    private void jump(long simSeconds) {
        boot.getSimClock().resume();
        boot.getSimClock().advance(boot.getSimClock().simSecondsToWallMs(simSeconds));
        boot.getSimClock().pause();
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
