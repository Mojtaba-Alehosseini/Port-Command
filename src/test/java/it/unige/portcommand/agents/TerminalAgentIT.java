package it.unige.portcommand.agents;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.agents.InitArgs.TerminalInitArgs;
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
 * Phase-2 wire gate for the terminal protocol. On EVERY reply it asserts the FIPA
 * performative AND {@code ontology=port_command_v1} AND {@code language=json} AND
 * the parsed JSON content — proving the envelope is on the wire, not merely that a
 * message was sent. The cargo-completion INFORM additionally proves the terminal's
 * DF lookup ({@code findUnique("harbour-master")}) resolves on its own thread.
 */
@Tag("integration")
class TerminalAgentIT {

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

    private void bootWithTerminals() {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        var portState = boot.getPortStateArtifact();
        var simClock = boot.getSimClock();
        var spawner = boot.getSpawner();
        spawner.spawn("terminal_general", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_general", List.of("berth_1", "berth_3", "berth_4"), 6),
                portState, simClock});
        spawner.spawn("terminal_container", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_container", List.of("berth_2"), 4), portState, simClock});
        spawner.spawn("terminal_nocrane", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_nocrane", List.of("berth_1"), 0), portState, simClock});
    }

    // --- the eight wire-gate cases ----------------------------------------

    @Test
    void compatibleRequestGetsConfirmWithCrane() throws Exception {
        bootWithTerminals();
        ACLMessage reply = request("terminal_general", tankerBerthRequest("berth_1", 0L, 2));
        assertEnvelope(reply, ACLMessage.CONFIRM);
        JsonNode c = content(reply);
        assertEquals("berth_1", c.get("berth_id").asText());
        assertTrue(c.get("crane_assigned").asInt() >= 1 && c.get("crane_assigned").asInt() <= 6);
    }

    @Test
    void requestForUnmanagedBerthIsWrongTerminal() throws Exception {
        bootWithTerminals();
        // terminal_container manages only berth_2 → berth_1 is not its berth
        ACLMessage reply = request("terminal_container", tankerBerthRequest("berth_1", 0L, 2));
        assertRefuse(reply, "wrong_terminal");
    }

    @Test
    void incompatibleVesselIsRefused() throws Exception {
        bootWithTerminals();
        // tanker draft 14 on berth_3 (general cargo, max draft 12) → incompatible
        ACLMessage reply = request("terminal_general",
                berthRequest("tanker", 14.0, 140.0, 20000, "berth_3", 0L, 2));
        assertRefuse(reply, "incompatible");
    }

    @Test
    void malformedContentIsRefused() throws Exception {
        bootWithTerminals();
        ACLMessage reply = request("terminal_general", null); // null content
        assertRefuse(reply, "malformed");
    }

    @Test
    void secondRequestForSameBerthIsBusy() throws Exception {
        bootWithTerminals();
        assertEnvelope(request("terminal_general", tankerBerthRequest("berth_1", 0L, 2)), ACLMessage.CONFIRM);
        ACLMessage reply = request("terminal_general", tankerBerthRequest("berth_1", 0L, 2));
        assertRefuse(reply, "berth_busy");
        // berth_busy carries the incumbent's free time (eta 0 + 2h = 7_200_000 sim-ms)
        assertEquals(7_200_000L, content(reply).get("free_at_sim").asLong());
    }

    @Test
    void requestWhenNoCraneIsRefused() throws Exception {
        bootWithTerminals();
        // terminal_nocrane manages berth_1 with 0 cranes → compatible request still refused
        ACLMessage reply = request("terminal_nocrane", tankerBerthRequest("berth_1", 0L, 2));
        assertRefuse(reply, "no_crane");
    }

    @Test
    void cargoCompletionInformsHarbourMasterOnAgentThread() throws Exception {
        bootWithTerminals();
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", FakeHarbourMaster.class, new Object[] {hmInbox});

        assertEnvelope(request("terminal_general", tankerBerthRequest("berth_1", 0L, 1)), ACLMessage.CONFIRM);
        // push sim time past expectedFreeAtSim (eta 0 + 1h = 3_600_000 sim-ms)
        boot.getSimClock().advance(20_000);

        ACLMessage inform = hmInbox.poll(10, TimeUnit.SECONDS);
        assertNotNull(inform, "terminal must INFORM the HM on cargo completion (DF lookup on its own thread)");
        assertEnvelope(inform, ACLMessage.INFORM);
        JsonNode c = content(inform);
        assertEquals("handling_complete", c.get("notice").asText());
        assertEquals("berth_1", c.get("berth").asText());
    }

    @Test
    void floodInformTriggersDisconfirm() throws Exception {
        bootWithTerminals();
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", FakeHarbourMaster.class, new Object[] {hmInbox});

        assertEnvelope(request("terminal_general", tankerBerthRequest("berth_1", 0L, 2)), ACLMessage.CONFIRM);
        // fire-and-forget flood INFORM to the terminal
        fireAndForget("terminal_general", ACLMessage.INFORM,
                MAPPER.writeValueAsString(java.util.Map.of("event", "flood", "berth_id", "berth_1")));

        ACLMessage disconfirm = hmInbox.poll(10, TimeUnit.SECONDS);
        assertNotNull(disconfirm, "terminal must DISCONFIRM the prior CONFIRM on flood");
        assertEnvelope(disconfirm, ACLMessage.DISCONFIRM);
        JsonNode c = content(disconfirm);
        assertEquals("retracted", c.get("notice").asText());
        assertEquals("berth_1", c.get("berth_id").asText());
    }

    // --- helpers -----------------------------------------------------------

    private ACLMessage request(String target, String content) throws Exception {
        CompletableFuture<ACLMessage> slot = new CompletableFuture<>();
        boot.getSpawner().spawn("probe_" + probeSeq.incrementAndGet(), MessageProbe.class,
                new Object[] {target, ACLMessage.REQUEST, content, slot});
        return slot.get(15, TimeUnit.SECONDS);
    }

    private void fireAndForget(String target, int performative, String content) {
        boot.getSpawner().spawn("probe_" + probeSeq.incrementAndGet(), MessageProbe.class,
                new Object[] {target, performative, content, null});
    }

    private static String tankerBerthRequest(String berthId, long etaSim, int durationHours) throws Exception {
        return berthRequest("tanker", 14.0, 140.0, 20000, berthId, etaSim, durationHours);
    }

    private static String berthRequest(String vesselType, double draft, double length, int tonnage,
                                       String berthId, long etaSim, int durationHours) throws Exception {
        return MAPPER.writeValueAsString(new BerthRequest(
                "v1", vesselType, draft, length, tonnage, berthId, etaSim, durationHours, "liquid_bulk"));
    }

    private static void assertEnvelope(ACLMessage reply, int expectedPerformative) {
        assertNotNull(reply, "expected a reply");
        assertEquals(expectedPerformative, reply.getPerformative(),
                "performative (got " + ACLMessage.getPerformative(reply.getPerformative()) + ")");
        assertEquals("port_command_v1", reply.getOntology(), "ontology on the wire");
        assertEquals("json", reply.getLanguage(), "language on the wire");
    }

    private static void assertRefuse(ACLMessage reply, String expectedReason) throws Exception {
        assertEnvelope(reply, ACLMessage.REFUSE);
        assertEquals(expectedReason, content(reply).get("reason").asText());
    }

    private static JsonNode content(ACLMessage msg) throws Exception {
        return MAPPER.readTree(msg.getContent());
    }
}
