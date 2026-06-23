package it.unige.portcommand.agents;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.ontology.VesselSpec;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 wire gate for the contracted auto-flow: arrival → REQUEST → (auto) ACCEPT →
 * transit → dock → INFORM(service_complete) → depart. The sim-time phases are driven by
 * advancing the injected SimClock — no wall-clock sleeps. Every reply asserts
 * performative + ontology + language + parsed content.
 */
@Tag("integration")
class ContractedVesselAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void contractedVesselRunsArrivalToDeparture() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", AutoAcceptHarbourMaster.class,
                new Object[] {hmInbox, "berth_1", 5000.0, 1}); // grants berth_1, price 5000, 1 sim-hour

        VesselSpec spec = new VesselSpec("C001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "CONTRACT-1",
                boot.getSimClock(), boot.getMarketHistoryArtifact());

        // 1) arrival announcement
        ACLMessage request = hmInbox.poll(10, TimeUnit.SECONDS);
        assertNotNull(request, "vessel must REQUEST a berth on arrival");
        assertEnvelope(request, ACLMessage.REQUEST);
        assertEquals("request_berth", intent(request));

        // 2) drive transit -> dock(1h) -> depart by advancing the SimClock (~4 sim-hours/step)
        boolean serviceComplete = false;
        boolean departed = false;
        for (int i = 0; i < 40 && !departed; i++) {
            boot.getSimClock().advance(50_000);
            ACLMessage m = hmInbox.poll(300, TimeUnit.MILLISECONDS);
            if (m == null) {
                continue;
            }
            String intent = intent(m);
            if ("service_complete".equals(intent)) {
                assertEnvelope(m, ACLMessage.INFORM);
                serviceComplete = true;
            } else if ("departed".equals(intent)) {
                assertEnvelope(m, ACLMessage.INFORM);
                departed = true;
            }
        }
        assertTrue(serviceComplete, "vessel must INFORM service_complete after docking");
        assertTrue(departed, "vessel must INFORM departed and terminate");
    }

    private static void assertEnvelope(ACLMessage m, int performative) {
        assertEquals(performative, m.getPerformative(),
                "performative (got " + ACLMessage.getPerformative(m.getPerformative()) + ")");
        assertEquals("port_command_v1", m.getOntology());
        assertEquals("json", m.getLanguage());
    }

    private static String intent(ACLMessage m) throws Exception {
        JsonNode n = MAPPER.readTree(m.getContent());
        return n.has("intent") ? n.get("intent").asText() : null;
    }
}
