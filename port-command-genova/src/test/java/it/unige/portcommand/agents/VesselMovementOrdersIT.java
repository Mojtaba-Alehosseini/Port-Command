package it.unige.portcommand.agents;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.AgentDirectory;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S16 (ADR-20, decision B1 — vessel side): a ship moves only when it is ordered to.
 *
 * <p>Born red. Before S16 a vessel started its own two-sim-minute channel leg the moment its
 * berth was granted, docked, served, and sailed again — all on its own clock, whatever the
 * HarbourMaster knew or wanted. Here the HarbourMaster is a puppet that says nothing unless the
 * test tells it to, so every movement the vessel makes can be pinned to the order that allowed
 * it: no order, no movement, for as many game hours as the clock is advanced.
 *
 * <p>The clock is driven by hand ({@code advanceSimSeconds}); every wait on a message has a
 * deadline; "nothing arrives" is asserted over a bounded window after the clock jump that would
 * have produced the message on the old code.
 */
@Tag("integration")
class VesselMovementOrdersIT {

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
    @Timeout(90)
    void aGrantedShipWaitsForTheOrderToProceed_andADockedShipForClearanceToLeave() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        BlockingQueue<ACLMessage> hmOutbox = new LinkedBlockingQueue<>();
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("puppet_hm", HmProbeAgent.class,
                new Object[] {hmOutbox, hmInbox, "harbour-master"});

        VesselSpec spec = new VesselSpec("C001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        AgentDirectory directory = new AgentDirectory();
        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "CONTRACT-1",
                boot.getSimClock(), boot.getMarketHistoryArtifact(), directory);

        ACLMessage request = awaitIntent(hmInbox, "request_berth", 15_000);
        assertNotNull(request, "setup: the vessel announces itself");
        AID vessel = request.getSender();

        // --- The grant reserves a berth. It does not start the ship. ---
        ACLMessage grant = MessageFactory.reply(request, ACLMessage.ACCEPT_PROPOSAL);
        grant.setContent(TerminalJson.write(Map.of("berth_id", "berth_1", "price", 5000.0, "hours", 1)));
        hmOutbox.put(grant);
        awaitPhase(directory, "vessel_c001", BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE);
        // Three game hours pass. On the pre-S16 code the ship docked after two game MINUTES,
        // served its one hour and reported service_complete well inside this window.
        advanceSimSeconds(3 * 3_600L);
        assertNull(awaitAnyIntent(hmInbox, 2_000),
                "a granted ship that has not been told to proceed reports nothing: it has not moved");

        // --- The order starts the channel leg, and the leg lasts as long as the order says. ---
        hmOutbox.put(order(vessel, Map.of("event", "proceed_to_berth", "vessel_id", "C001",
                "berth_id", "berth_1", "transit_sim_millis", 600_000L)));
        // The leg is counted from the moment the SHIP takes the order, so the clock is moved only
        // once it has (the hand-driven clock would otherwise jump before the leg began).
        awaitPhase(directory, "vessel_c001", BaseVesselAgent.FlowPhase.TRANSIT);
        advanceSimSeconds(300L); // half the leg
        assertNull(awaitAnyIntent(hmInbox, 1_500), "half-way down the channel: not docked yet");
        advanceSimSeconds(320L);
        ACLMessage docked = awaitIntent(hmInbox, "docked", 10_000);
        assertNotNull(docked, "at the end of the leg the ship reports its own arrival");
        assertEquals("berth_1", MAPPER.readTree(docked.getContent()).path("berth_id").asText());
        // The report goes out a moment BEFORE the ship starts counting its service hour; the
        // clock is moved only once it has (its DOCKED phase), or the hour would start after it.
        awaitPhase(directory, "vessel_c001", BaseVesselAgent.FlowPhase.DOCKED);

        // --- Service over: the ship ASKS to leave, and stays until it is cleared. ---
        advanceSimSeconds(3_600L + 60L);
        assertNotNull(awaitIntent(hmInbox, "service_complete", 10_000), "the request to leave");
        advanceSimSeconds(2 * 3_600L);
        assertNull(awaitAnyIntent(hmInbox, 2_000),
                "not cleared: the ship is still at its berth two game hours later (pre-S16 it left "
                        + "one game minute after its service ended)");

        hmOutbox.put(order(vessel, Map.of("event", "cleared_to_depart", "vessel_id", "C001")));
        // The clearance has to be consumed before the minute of departure is counted from it.
        assertTrue(waitUntil(() -> vessel(directory, "vessel_c001").departureCleared(), 10_000),
                "setup: the ship takes the clearance");
        assertNull(awaitAnyIntent(hmInbox, 500), "cleared, but the departure takes a game minute");
        advanceSimSeconds(90L);
        assertNotNull(awaitIntent(hmInbox, "departed", 10_000), "cleared: the ship leaves");
    }

    @Test
    @Timeout(90)
    void aHoldSendsAShipUnderWayBackToTheChannelEntry_andTheLegStartsAgainOnRelease() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        BlockingQueue<ACLMessage> hmOutbox = new LinkedBlockingQueue<>();
        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("puppet_hm", HmProbeAgent.class,
                new Object[] {hmOutbox, hmInbox, "harbour-master"});
        VesselSpec spec = new VesselSpec("C002", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        AgentDirectory directory = new AgentDirectory();
        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "CONTRACT-1",
                boot.getSimClock(), boot.getMarketHistoryArtifact(), directory);
        ACLMessage request = awaitIntent(hmInbox, "request_berth", 15_000);
        assertNotNull(request, "setup: the vessel announces itself");
        AID vessel = request.getSender();
        ACLMessage grant = MessageFactory.reply(request, ACLMessage.ACCEPT_PROPOSAL);
        grant.setContent(TerminalJson.write(Map.of("berth_id", "berth_1", "price", 5000.0, "hours", 1)));
        hmOutbox.put(grant);
        awaitPhase(directory, "vessel_c002", BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE);

        hmOutbox.put(order(vessel, Map.of("event", "proceed_to_berth", "vessel_id", "C002",
                "transit_sim_millis", 600_000L)));
        awaitPhase(directory, "vessel_c002", BaseVesselAgent.FlowPhase.TRANSIT);
        advanceSimSeconds(400L); // two thirds of the leg done
        assertNull(awaitAnyIntent(hmInbox, 1_500), "setup: under way, not docked");

        hmOutbox.put(order(vessel, Map.of("event", "weather_hold", "reason", "wind 46 kn [R15]")));
        // The ship's own phase is the witness that it is no longer under way.
        awaitPhase(directory, "vessel_c002", BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE);
        // Far more than the 200 sim-seconds the leg had left: a held ship does not creep in.
        advanceSimSeconds(2 * 3_600L);
        assertNull(awaitAnyIntent(hmInbox, 2_000), "held: the ship is back at the channel entry and stays there");

        // weather_resume alone is news, not an order.
        hmOutbox.put(order(vessel, Map.of("event", "weather_resume", "vessel_id", "C002")));
        advanceSimSeconds(3_600L);
        assertNull(awaitAnyIntent(hmInbox, 2_000), "resumed but not ordered to proceed: still waiting");

        // Released: the WHOLE leg again, with the new escort - not the 200 seconds that were left.
        hmOutbox.put(order(vessel, Map.of("event", "proceed_to_berth", "vessel_id", "C002",
                "transit_sim_millis", 600_000L)));
        awaitPhase(directory, "vessel_c002", BaseVesselAgent.FlowPhase.TRANSIT);
        advanceSimSeconds(400L);
        assertNull(awaitAnyIntent(hmInbox, 1_500), "400 s into a fresh 600 s leg: not docked");
        advanceSimSeconds(230L);
        assertNotNull(awaitIntent(hmInbox, "docked", 10_000), "the fresh leg is complete");
    }

    /**
     * Moves game time on by {@code simSeconds}. Nothing else does: a bare {@code JadeBootstrap} runs
     * no wall-clock advancer, and {@code SimClock.advance} takes REAL milliseconds.
     */
    private void advanceSimSeconds(long simSeconds) {
        boot.getSimClock().advance(boot.getSimClock().simSecondsToWallMs(simSeconds));
    }

    private static BaseVesselAgent vessel(AgentDirectory directory, String localName) {
        return directory.byName(localName).map(BaseVesselAgent.class::cast)
                .orElseThrow(() -> new AssertionError(localName + " is not in the directory"));
    }

    /** Waits (bounded) until the ship itself reports {@code phase}. */
    private static void awaitPhase(AgentDirectory directory, String localName, BaseVesselAgent.FlowPhase phase)
            throws InterruptedException {
        assertTrue(waitUntil(() -> directory.byName(localName).map(BaseVesselAgent.class::cast)
                        .map(v -> v.flowPhase() == phase).orElse(false), 10_000),
                "the ship must reach phase " + phase + ", is "
                        + directory.byName(localName).map(BaseVesselAgent.class::cast)
                                .map(v -> String.valueOf(v.flowPhase())).orElse("not in the directory"));
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static ACLMessage order(AID vessel, Map<String, Object> content) {
        ACLMessage order = MessageFactory.create(ACLMessage.INFORM);
        order.addReceiver(vessel);
        order.setProtocol(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        order.setContent(TerminalJson.write(content));
        return order;
    }

    /** The next message whose {@code intent} is {@code wanted}, or null at the deadline. */
    private static ACLMessage awaitIntent(BlockingQueue<ACLMessage> inbox, String wanted, long millis)
            throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && wanted.equals(intent(m))) {
                return m;
            }
        }
        return null;
    }

    /** The next message that carries any {@code intent} at all, or null at the deadline. */
    private static ACLMessage awaitAnyIntent(BlockingQueue<ACLMessage> inbox, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && intent(m) != null) {
                return m;
            }
        }
        return null;
    }

    private static String intent(ACLMessage m) throws Exception {
        if (m.getContent() == null) {
            return null;
        }
        JsonNode n = MAPPER.readTree(m.getContent());
        return n.has("intent") ? n.get("intent").asText() : null;
    }
}
