package it.unige.portcommand.agents;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Position;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 wire gate for the tug CNP + escort lifecycle. A {@link CnpProbeAgent}
 * stands in for the HarbourMaster initiator; sim-time phases are driven purely by
 * advancing the injected {@link it.unige.portcommand.util.SimClock} (no wall sleeps),
 * and tug motion/status is observed through the {@link PortStateArtifact} subscription
 * — never by reaching into the agent. Every reply asserts performative + ontology +
 * language + parsed content.
 */
@Tag("integration")
class TugAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final double BASE_FARE = 350.0;
    private static final double FUEL_COST_PER_KM = 2.0;
    private static final double TOP_SPEED_KNOTS = 12.0;
    private static final Position TARGET = new Position(500, 500, 0);
    private static final Position BERTH = new Position(900, 300, 0);
    private static final Position[] BASES = {
            new Position(50, 100, 0), new Position(50, 150, 0),
            new Position(50, 200, 0), new Position(50, 250, 0),
    };

    private JadeBootstrap boot;
    private PortStateArtifact portState;
    private BlockingQueue<ACLMessage> outbox;
    private BlockingQueue<ACLMessage> inbox;
    private BlockingQueue<PortStateUpdate.TugDelta> tugUpdates;

    @BeforeEach
    void setUp() {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        portState = boot.getPortStateArtifact();
        tugUpdates = new LinkedBlockingQueue<>();
        portState.subscribe(u -> {
            if (u instanceof PortStateUpdate.TugDelta d) {
                tugUpdates.add(d);
            }
        });
        outbox = new LinkedBlockingQueue<>();
        inbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("cnp_probe", CnpProbeAgent.class, new Object[] {outbox, inbox});
    }

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void fourAvailableTugsProposeWithCorrectBidContent() throws Exception {
        for (int i = 0; i < 4; i++) {
            spawnTug("tug_" + (i + 1), BASES[i], 1.0);
        }
        send(cfp("cnp-a", "tug_1", "tug_2", "tug_3", "tug_4"));

        Map<String, ACLMessage> proposals = new HashMap<>();
        for (int k = 0; k < 4; k++) {
            ACLMessage p = pollReply(8000);
            assertNotNull(p, "expected 4 PROPOSEs, got " + proposals.size());
            assertEnvelope(p, ACLMessage.PROPOSE);
            proposals.put(p.getSender().getLocalName(), p);
        }
        assertEquals(4, proposals.size(), "each tug proposes exactly once");

        for (int i = 0; i < 4; i++) {
            String id = "tug_" + (i + 1);
            JsonNode bid = node(proposals.get(id));
            double distanceKm = TugMath.distanceKm(BASES[i], TARGET);
            double expectedCost = BASE_FARE + FUEL_COST_PER_KM * distanceKm;
            double expectedEta = TugMath.etaMinutes(distanceKm, TOP_SPEED_KNOTS);
            assertEquals(expectedCost, bid.get("cost").asDouble(), 1e-6, id + " cost = baseFare + fuelCostPerKm*km");
            assertEquals(expectedEta, bid.get("eta_minutes").asDouble(), 1e-6, id + " eta_minutes");
            assertEquals(1.0, bid.get("fuel_state").asDouble(), 1e-9, id + " full fuel");
            assertEquals(BASES[i].x(), bid.get("position").get("x").asDouble(), 1e-9, id + " bids from its base");
        }
    }

    @Test
    void lowFuelTugRefusesThenRefuelsAndProposesFull() throws Exception {
        spawnTug("tug_1", BASES[0], 0.10);

        send(cfp("cnp-b0", "tug_1"));
        ACLMessage refusal = pollReply(5000);
        assertEnvelope(refusal, ACLMessage.REFUSE);
        assertEquals("low_fuel", node(refusal).get("reason").asText());

        // The 60-sim-s refuel cycle auto-triggers (idle, at base, fuel < 0.20). Wait on the
        // artefact for REFUELING -> IDLE rather than racing the reply loop against wall time.
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.REFUELING, 60),
                "low-fuel tug at base must enter REFUELING");
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.IDLE, 60),
                "tug must return to IDLE once the refuel completes");

        send(cfp("cnp-b1", "tug_1"));
        ACLMessage proposal = pollReply(5000);
        assertEnvelope(proposal, ACLMessage.PROPOSE);
        assertEquals(1.0, node(proposal).get("fuel_state").asDouble(), 1e-9, "refuel restored a full tank");
    }

    @Test
    void engagedTugRefusesBusy() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-c1", "tug_1"));
        ACLMessage proposal = pollReply(5000);
        assertEnvelope(proposal, ACLMessage.PROPOSE);
        sendAccept(proposal, "V1", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40),
                "tug should be EN_ROUTE after ACCEPT");

        inbox.clear();
        send(cfp("cnp-c2", "tug_1")); // no clock advance -> still mid-job
        ACLMessage refusal = pollReply(5000);
        assertEnvelope(refusal, ACLMessage.REFUSE);
        assertEquals("busy", node(refusal).get("reason").asText());
    }

    @Test
    void acceptDrivesTransitEscortThenReturnsIdle() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-d", "tug_1"));
        ACLMessage proposal = pollReply(5000);
        assertEnvelope(proposal, ACLMessage.PROPOSE);
        sendAccept(proposal, "V1", BERTH);

        boolean escortComplete = false;
        TugStatus status = null;
        Position position = null;
        for (int i = 0; i < 60 && !(escortComplete && status == TugStatus.IDLE); i++) {
            boot.getSimClock().advance(50_000); // ~4 sim-hours per step
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1")) {
                    status = d.status();
                    position = d.position();
                }
            }
            ACLMessage m = inbox.poll(50, TimeUnit.MILLISECONDS);
            if (m != null && m.getPerformative() == ACLMessage.INFORM
                    && "escort_complete".equals(node(m).path("event").asText())) {
                assertEnvelope(m, ACLMessage.INFORM);
                assertEquals("V1", node(m).get("vessel_id").asText());
                escortComplete = true;
            }
        }
        assertTrue(escortComplete, "tug must INFORM escort_complete on berth arrival");
        assertEquals(TugStatus.IDLE, status, "tug returns to IDLE after the job");
        assertNotNull(position);
        assertTrue(distancePx(position, BASES[0]) < 0.5, "tug ends at its home base");
    }

    @Test
    void cancelMidTransitRemovesBehaviourAndReturnsToBase() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-e", "tug_1"));
        ACLMessage proposal = pollReply(5000);
        assertEnvelope(proposal, ACLMessage.PROPOSE);
        sendAccept(proposal, "V1", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40));

        // Nudge transit forward in small steps until the tug has visibly left base (still
        // EN_ROUTE, not yet arrived). Small steps keep it mid-transit; the drain loop is robust
        // to when the tick actually fires.
        PortStateUpdate.TugDelta moved = null;
        for (int i = 0; i < 8 && moved == null; i++) {
            boot.getSimClock().advance(2_000); // ~0.16 sim-hours/step
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1") && d.status() == TugStatus.EN_ROUTE_TO_VESSEL
                        && distancePx(d.position(), BASES[0]) > 1.0) {
                    moved = d;
                }
            }
        }
        assertNotNull(moved, "tug should be transiting away from base");
        assertTrue(distancePx(moved.position(), TARGET) > 1.0, "and not yet at the vessel");

        double cancelDistToTarget = distancePx(moved.position(), TARGET);

        sendCancel("cnp-e", "tug_1");
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.RETURNING, 40),
                "CANCEL flips the tug to RETURNING");

        // Drive the return in small steps. This is the non-vacuous leak guard: had the transit
        // behaviour NOT been removed, the tug would keep crawling toward TARGET (so its
        // distance-to-target would drop below the cancel point) and eventually INFORM
        // escort_complete. Assert neither ever happens, and it ends IDLE at base.
        TugStatus status = TugStatus.RETURNING;
        Position position = moved.position();
        for (int i = 0; i < 30; i++) {
            boot.getSimClock().advance(5_000); // ~0.4 sim-hours/step -> several return increments
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1")) {
                    status = d.status();
                    position = d.position();
                    assertTrue(distancePx(position, TARGET) >= cancelDistToTarget - 0.5,
                            "cancelled tug progressed toward the abandoned target — transit behaviour leaked");
                }
            }
            ACLMessage m = inbox.poll(20, TimeUnit.MILLISECONDS);
            assertTrue(m == null || !"escort_complete".equals(node(m).path("event").asText()),
                    "a cancelled escort must never INFORM escort_complete — transit behaviour leaked");
        }
        assertEquals(TugStatus.IDLE, status, "cancelled tug returns to IDLE at base");
        assertTrue(distancePx(position, BASES[0]) < 0.5, "tug is at base, not the abandoned target");

        // currentJob was cleared: it bids again instead of REFUSE(busy).
        inbox.clear();
        send(cfp("cnp-e2", "tug_1"));
        ACLMessage reply = pollReply(5000);
        assertEnvelope(reply, ACLMessage.PROPOSE);
    }

    // --- helpers -------------------------------------------------------------

    private void spawnTug(String id, Position base, double fuel) {
        boot.getSpawner().spawn(id, TugAgent.class, new Object[] {
                new InitArgs.TugInitArgs(id, base, BASE_FARE, FUEL_COST_PER_KM, TOP_SPEED_KNOTS, fuel),
                portState, boot.getSimClock()});
    }

    private ACLMessage cfp(String conversationId, String... tugIds) {
        ACLMessage cfp = MessageFactory.create(ACLMessage.CFP);
        cfp.setConversationId(conversationId);
        for (String id : tugIds) {
            cfp.addReceiver(new AID(id, AID.ISLOCALNAME));
        }
        cfp.setContent(TerminalJson.write(Map.of(
                "cfp_id", conversationId, "target_vessel_position", TARGET)));
        return cfp;
    }

    private void sendAccept(ACLMessage proposal, String vesselId, Position berth) throws InterruptedException {
        ACLMessage accept = MessageFactory.reply(proposal, ACLMessage.ACCEPT_PROPOSAL);
        accept.setContent(TerminalJson.write(Map.of("vessel_id", vesselId, "berth_position", berth)));
        outbox.put(accept);
    }

    private void sendCancel(String conversationId, String tugId) throws InterruptedException {
        ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
        cancel.setConversationId(conversationId);
        cancel.addReceiver(new AID(tugId, AID.ISLOCALNAME));
        cancel.setContent(TerminalJson.write(Map.of("reason", "operator_abort")));
        outbox.put(cancel);
    }

    private void send(ACLMessage msg) throws InterruptedException {
        outbox.put(msg);
    }

    private ACLMessage pollReply(long timeoutMs) throws InterruptedException {
        return inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private PortStateUpdate.TugDelta awaitTug(String id, Predicate<PortStateUpdate.TugDelta> pred, int maxPolls)
            throws InterruptedException {
        for (int i = 0; i < maxPolls; i++) {
            PortStateUpdate.TugDelta d = tugUpdates.poll(100, TimeUnit.MILLISECONDS);
            if (d != null && d.tugId().equals(id) && pred.test(d)) {
                return d;
            }
        }
        return null;
    }

    private static double distancePx(Position a, Position b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static void assertEnvelope(ACLMessage m, int performative) {
        assertNotNull(m, "expected a reply");
        assertEquals(performative, m.getPerformative(),
                "performative (got " + ACLMessage.getPerformative(m.getPerformative()) + ")");
        assertEquals(MessageFactory.ONTOLOGY, m.getOntology());
        assertEquals(MessageFactory.LANGUAGE, m.getLanguage());
    }

    private static JsonNode node(ACLMessage m) throws Exception {
        return MAPPER.readTree(m.getContent());
    }
}
