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
import static org.junit.jupiter.api.Assertions.assertNull;
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
            // Whole-cent expectation since checkpoint-#6 F6 (2026-07-18): BidInCNPBehaviour
            // rounds the money value at bid construction, so the quoted cost is
            // round(fare + fuel*km) to 2 dp — not the raw float product. Same formula,
            // plus the rounding the wallet now relies on.
            double expectedCost = Math.round((BASE_FARE + FUEL_COST_PER_KM * distanceKm) * 100.0) / 100.0;
            double expectedEta = TugMath.etaMinutes(distanceKm, TOP_SPEED_KNOTS);
            assertEquals(expectedCost, bid.get("cost").asDouble(), 1e-6, id + " cost = round2(baseFare + fuelCostPerKm*km)");
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

    /**
     * S16 (ADR-20, decision B1 — tug side): the whole job, with the wait in the middle. The tug
     * reaches the ship, says so ({@code tug_on_station}) and STAYS there until the HarbourMaster
     * orders the escort to begin; only then does it take the ship to its berth.
     *
     * <p>Born red: before S16 the tug went from the pickup straight on to the berth, on its own,
     * and reported {@code escort_complete} — whether or not the ship had moved, been cleared by
     * customs or been held for weather. Here eight game hours pass with the tug on station and
     * no order: it must still be there, and nothing must have been completed.
     */
    @Test
    void acceptDrivesTransitWaitsOnStationThenEscortsOnOrderAndReturnsIdle() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-d", "tug_1"));
        ACLMessage proposal = pollReply(5000);
        assertEnvelope(proposal, ACLMessage.PROPOSE);
        sendAccept(proposal, "V1", BERTH);

        // --- To the ship. ---
        ACLMessage onStation = null;
        for (int i = 0; i < 20 && onStation == null; i++) {
            boot.getSimClock().advance(5_000); // 24 game minutes per step; the leg is about 27
            onStation = pollEvent("tug_on_station", 300);
        }
        assertNotNull(onStation, "the tug must report that it has reached the ship");
        assertEnvelope(onStation, ACLMessage.INFORM);
        assertEquals("V1", node(onStation).get("vessel_id").asText());
        assertEquals("cnp-d", onStation.getConversationId(),
                "the report rides the job's conversation, which is how the order finds its way back");
        PortStateUpdate.TugDelta waiting = awaitTug("tug_1", d -> d.status() == TugStatus.ON_STATION, 40);
        assertNotNull(waiting, "on station is a status of its own");
        assertTrue(distancePx(waiting.position(), TARGET) < 0.5, "and the tug is AT the ship");

        // --- No order: no escort, however long it takes. ---
        tugUpdates.clear();
        for (int i = 0; i < 4; i++) {
            boot.getSimClock().advance(25_000); // two game hours per step
            assertNull(pollEvent("escort_complete", 300),
                    "a tug that was never ordered to begin must not complete an escort");
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(20, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1")) {
                    assertEquals(TugStatus.ON_STATION, d.status(), "still on station, not escorting");
                    assertTrue(distancePx(d.position(), TARGET) < 0.5, "and it has not left the ship");
                }
            }
        }
        // Committed while it waits: another tender is refused, the tug is not a free tug.
        send(cfp("cnp-d2", "tug_1"));
        ACLMessage busy = pollReply(5000);
        assertEnvelope(busy, ACLMessage.REFUSE);
        assertEquals("busy", node(busy).get("reason").asText());

        // An order on some OTHER conversation is not this job's order.
        sendBeginEscort("cnp-someone-else", "tug_1", "V1");
        boot.getSimClock().advance(25_000);
        assertNull(pollEvent("escort_complete", 500), "a stale order must not start the escort");

        // --- The order: escort, complete, home. ---
        sendBeginEscort("cnp-d", "tug_1", "V1");
        boolean escortComplete = false;
        TugStatus status = null;
        Position position = null;
        for (int i = 0; i < 60 && !(escortComplete && status == TugStatus.IDLE); i++) {
            boot.getSimClock().advance(5_000);
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

    /**
     * S16 (decision B3): a tug that is cancelled, and wins a new job while it is still on its way
     * home, does the new job.
     *
     * <p>Born red: the homeward leg was never stopped when the new job started. Two legs moved
     * the same tug at once, and when the old one reached base it cleared the tug's job and set it
     * idle — with the new ship still waiting for a tug the HarbourMaster had paid for and that
     * would never report. That is the "tug lost from the fleet" of the S16 brief.
     */
    @Test
    void aCancelledTugThatWinsANewJobOnItsWayHomeCompletesIt() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-old", "tug_1"));
        ACLMessage first = pollReply(5000);
        assertEnvelope(first, ACLMessage.PROPOSE);
        sendAccept(first, "V1", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40));
        // Most of the way out, so the way home is long enough to win a job on.
        PortStateUpdate.TugDelta out = null;
        for (int i = 0; i < 10 && out == null; i++) {
            boot.getSimClock().advance(1_000);
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1") && d.status() == TugStatus.EN_ROUTE_TO_VESSEL
                        && distancePx(d.position(), BASES[0]) > 300.0) {
                    out = d;
                }
            }
        }
        assertNotNull(out, "staging: the tug is well away from its base and not yet at the ship");
        sendCancel("cnp-old", "tug_1");
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.RETURNING, 40),
                "staging: cancelled, on its way home");

        // The new job, won on the way home (no clock advance: the tug has not moved since).
        inbox.clear();
        send(cfp("cnp-new", "tug_1"));
        ACLMessage second = pollReply(5000);
        assertEnvelope(second, ACLMessage.PROPOSE);
        sendAccept(second, "V2", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40),
                "the new award is taken");

        // To the new ship. The old homeward leg would have needed about 14 game minutes to reach
        // base; these steps go well past that, and the tug must never be seen idle or at base.
        ACLMessage onStation = null;
        tugUpdates.clear();
        for (int i = 0; i < 20 && onStation == null; i++) {
            boot.getSimClock().advance(2_500); // 12 game minutes per step
            onStation = pollEvent("tug_on_station", 300);
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(20, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1")) {
                    assertTrue(d.status() == TugStatus.EN_ROUTE_TO_VESSEL || d.status() == TugStatus.ON_STATION,
                            "the superseded homeward leg must not move or idle the tug, saw " + d.status());
                }
            }
        }
        assertNotNull(onStation, "the tug must reach the new ship - the old leg did not take the job away");
        assertEquals("V2", node(onStation).get("vessel_id").asText());
        assertEquals("cnp-new", onStation.getConversationId());

        // Still committed to V2 long after the old leg would have ended.
        boot.getSimClock().advance(25_000);
        send(cfp("cnp-probe", "tug_1"));
        ACLMessage busy = pollReply(5000);
        assertEnvelope(busy, ACLMessage.REFUSE);
        assertEquals("busy", node(busy).get("reason").asText(), "the job was not cleared behind its back");

        sendBeginEscort("cnp-new", "tug_1", "V2");
        ACLMessage complete = null;
        for (int i = 0; i < 20 && complete == null; i++) {
            boot.getSimClock().advance(5_000);
            complete = pollEvent("escort_complete", 300);
        }
        assertNotNull(complete, "and the new job is carried through to the berth");
        assertEquals("V2", node(complete).get("vessel_id").asText());
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

    /**
     * S10 (bug hunt, hunter B finding 2): a CANCEL sent right behind its ACCEPT can be DEQUEUED
     * first — {@code receive()} is template-selective, and the scheduler rotation decides which
     * cyclic drains its message first. Pre-fix, the cancel-first rotation dropped the CANCEL as
     * "idle — nothing to abort" and the tug then honoured the stale ACCEPT: it escorted a vessel
     * the port believed held (uncancellable — no longer in the escort set) and INFORMed a
     * phantom escort_complete. Post-fix both rotations converge — the award dies either way
     * (aborted mid-leg, or discarded before it started) — and the tug stays biddable. The
     * rotation is not controllable from a test, so this asserts the CONVERGED outcome; pre-fix
     * it failed on whichever run let the cancel handler go first (this is exactly the shipped
     * storm's award-vs-hold timing, per MIN_CNP_WINDOW_WALL_MS's javadoc).
     */
    @Test
    void cancelImmediatelyBehindAcceptNeverEscorts() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-race", "tug_1"));
        ACLMessage proposal = pollReply(5000);
        assertEnvelope(proposal, ACLMessage.PROPOSE);
        sendAccept(proposal, "V1", BERTH);
        sendCancel("cnp-race", "tug_1"); // no EN_ROUTE wait between the two — the overtaking window

        TugStatus status = null;
        for (int i = 0; i < 30; i++) {
            boot.getSimClock().advance(5_000);
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1")) {
                    status = d.status();
                }
            }
            ACLMessage m = inbox.poll(20, TimeUnit.MILLISECONDS);
            assertTrue(m == null || !"escort_complete".equals(node(m).path("event").asText()),
                    "a cancelled award must never escort to completion — the CANCEL was lost");
        }
        if (status != null) {
            assertEquals(TugStatus.IDLE, status, "tug ends IDLE whichever rotation won");
        }
        // And the award is truly gone: a fresh CFP draws a PROPOSE, not REFUSE(busy).
        inbox.clear();
        send(cfp("cnp-race2", "tug_1"));
        ACLMessage reply = pollReply(5000);
        assertEnvelope(reply, ACLMessage.PROPOSE);
    }

    /**
     * S16 (adversarial review): the CANCEL-overtakes-ACCEPT guard also covers a tug that is on
     * its way HOME. Since decision B3 such a tug may bid, so an award and its cancellation can
     * reach it back to back there too. The handler used to treat "has a movement" as "has a job"
     * and fell through to "abort": it restarted the homeward leg, left the queued award in the
     * queue, and the tug then set off on a job the port had already called off. Like the two
     * cases above this asserts the CONVERGED outcome - whichever message is read first, the tug
     * never reaches the ship.
     */
    @Test
    void cancelImmediatelyBehindAcceptNeverEscorts_alsoForATugOnItsWayHome() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        // Out, cancelled, on its way home with no job.
        send(cfp("cnp-h1", "tug_1"));
        ACLMessage first = pollReply(5000);
        assertEnvelope(first, ACLMessage.PROPOSE);
        sendAccept(first, "V1", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40));
        boot.getSimClock().advance(3_000); // about 14 game minutes out
        sendCancel("cnp-h1", "tug_1", "V1");
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.RETURNING, 40),
                "staging: cancelled, returning, no job");

        // A new award and its cancellation, back to back, while it is still returning.
        inbox.clear();
        send(cfp("cnp-h2", "tug_1"));
        ACLMessage second = pollReply(5000);
        assertEnvelope(second, ACLMessage.PROPOSE);
        sendAccept(second, "V2", BERTH);
        sendCancel("cnp-h2", "tug_1", "V2"); // no wait in between: the overtaking window

        TugStatus status = null;
        for (int i = 0; i < 20; i++) {
            boot.getSimClock().advance(5_000);
            PortStateUpdate.TugDelta d;
            while ((d = tugUpdates.poll(50, TimeUnit.MILLISECONDS)) != null) {
                if (d.tugId().equals("tug_1")) {
                    status = d.status();
                }
            }
            ACLMessage m = inbox.poll(20, TimeUnit.MILLISECONDS);
            if (m != null) {
                String event = node(m).path("event").asText();
                assertTrue(!"tug_on_station".equals(event) && !"escort_complete".equals(event),
                        "a cancelled award must not be carried out - the tug reported " + event);
            }
        }
        assertEquals(TugStatus.IDLE, status, "the tug ends idle at its base whichever message was read first");
        inbox.clear();
        send(cfp("cnp-h3", "tug_1"));
        assertEnvelope(pollReply(5000), ACLMessage.PROPOSE); // and it is free to bid again
    }

    /**
     * S11 (fix review, R1): the CANCEL is KEYED by vessel. One addressed to a vessel this tug
     * is NOT escorting targets a queued award, never the live escort — pre-S11 the busy branch
     * aborted on ANY CANCEL, so a hold CANCEL for a stale assignment would have stranded the
     * vessel actually under tow mid-channel. Born red on the pre-S11 tree: the mismatched
     * CANCEL sent the tug RETURNING.
     */
    @Test
    void cancelKeyedToADifferentVesselNeverAbortsTheLiveEscort() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-key1", "tug_1"));
        ACLMessage bid = pollReply(5000);
        assertEnvelope(bid, ACLMessage.PROPOSE);
        sendAccept(bid, "VA", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40),
                "staging: the award is taken and the escort is live");

        sendCancel("cnp-key1", "tug_1", "VB"); // a hold CANCEL for some OTHER vessel

        assertNull(awaitTug("tug_1", d -> d.status() == TugStatus.RETURNING, 10),
                "a CANCEL keyed to a vessel this tug is not escorting must not abort the live escort");
        // Still committed to VA: a probe CFP draws REFUSE(busy), not a bid.
        inbox.clear();
        send(cfp("cnp-key2", "tug_1"));
        ACLMessage reply = pollReply(5000);
        assertEnvelope(reply, ACLMessage.REFUSE);
    }

    /**
     * S11 (fix review, R1, the queue half): with an unrelated award QUEUED, a keyed CANCEL for
     * an absent vessel must not eat it. Pre-S11 the idle-branch consume took whichever
     * ACCEPT_PROPOSAL sat first in the queue; post-fix the scan matches by vessel_id and puts
     * everything else back. Rotation (cancel-first vs accept-first) is not controllable from a
     * test, so — like {@link #cancelImmediatelyBehindAcceptNeverEscorts} — this asserts the
     * CONVERGED outcome: VB's escort survives either rotation.
     */
    @Test
    void keyedCancelForAnAbsentVesselLeavesAQueuedAwardUntouched() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-put1", "tug_1"));
        ACLMessage bid = pollReply(5000);
        assertEnvelope(bid, ACLMessage.PROPOSE);
        sendAccept(bid, "VB", BERTH);
        sendCancel("cnp-put1", "tug_1", "VA"); // keyed to a vessel with NO queued award

        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40),
                "VB's queued award must survive a keyed CANCEL for a different vessel");
    }

    // --- S9 (P1-8): the post-ACCEPT decline finally SENDS — §4.4 tug plan 3 -----------------

    /**
     * Born red on the pre-S9 tree: the busy branch logged "declining" and sent NOTHING — the
     * HarbourMaster kept a phantom escort it had already paid for. Now a double-award drives a
     * protocol-tagged REFUSE (reason busy) back to the awarder.
     */
    @Test
    void postAwardBusyDecline_sendsProtocolTaggedRefuse() throws Exception {
        spawnTug("tug_1", BASES[0], 1.0);

        send(cfp("cnp-pa1", "tug_1"));
        ACLMessage bid1 = pollReply(5000);
        assertEnvelope(bid1, ACLMessage.PROPOSE);
        send(cfp("cnp-pa2", "tug_1"));
        ACLMessage bid2 = pollReply(5000);
        assertEnvelope(bid2, ACLMessage.PROPOSE);

        sendAccept(bid1, "V1", BERTH);
        assertNotNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 40),
                "staging: the first award is taken");

        inbox.clear();
        sendAccept(bid2, "V2", BERTH); // the second award arrives while committed to V1
        ACLMessage decline = pollReply(5000);
        assertNotNull(decline, "the losing award must be DECLINED on the wire, not silently dropped");
        assertEnvelope(decline, ACLMessage.REFUSE);
        assertEquals("busy", node(decline).get("reason").asText());
        assertEquals("cnp-pa2", decline.getConversationId(), "the decline rides the award's conversation");
        assertEquals(it.unige.portcommand.behaviours.cnp.RefuseAssignmentBehaviour.POST_AWARD_DECLINE_PROTOCOL,
                decline.getProtocol(), "a DISTINCT post-award tag — never the CFP-refuse shape");
        assertEquals("V2", node(decline).get("vessel_id").asText());
    }

    /**
     * Born red likewise: the fuel-drop-between-bid-and-award seam (§4.4 CNP edge case 2 at the
     * AWARD stage). The tug bids healthy, burns below the low-fuel threshold before the ACCEPT
     * lands, and must decline rather than take a job it cannot run.
     */
    @Test
    void postAwardLowFuelDecline_sendsProtocolTaggedRefuse() throws Exception {
        it.unige.portcommand.persistence.AgentDirectory directory =
                new it.unige.portcommand.persistence.AgentDirectory();
        boot.getSpawner().spawn("tug_1", TugAgent.class, new Object[] {
                new InitArgs.TugInitArgs("tug_1", BASES[0], BASE_FARE, FUEL_COST_PER_KM,
                        TOP_SPEED_KNOTS, 0.25),
                portState, boot.getSimClock(), directory});

        send(cfp("cnp-pf1", "tug_1"));
        ACLMessage bid = pollReply(5000);
        assertEnvelope(bid, ACLMessage.PROPOSE);
        assertEquals(0.25, node(bid).get("fuel_state").asDouble(), 1e-9, "bids healthy at 0.25");

        // The bid-to-award window: the tank drains below LOW_FUEL_THRESHOLD (0.20).
        TugAgent tug = directory.byName("tug_1").map(TugAgent.class::cast).orElseThrow();
        tug.consumeFuel(2.5); // 2.5 km * 0.03/km (the S16 scale) = 0.075 -> 0.175

        inbox.clear();
        sendAccept(bid, "V1", BERTH);
        ACLMessage decline = pollReply(5000);
        assertNotNull(decline, "an award the tank can no longer honour must be declined");
        assertEnvelope(decline, ACLMessage.REFUSE);
        assertEquals("low_fuel", node(decline).get("reason").asText());
        assertEquals(it.unige.portcommand.behaviours.cnp.RefuseAssignmentBehaviour.POST_AWARD_DECLINE_PROTOCOL,
                decline.getProtocol());
        assertNull(awaitTug("tug_1", d -> d.status() == TugStatus.EN_ROUTE_TO_VESSEL, 3),
                "the declining tug must NOT take the job");
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

    /** S11 (R1): the keyed variant — the shape the weather hold sends since the CANCEL was keyed. */
    private void sendCancel(String conversationId, String tugId, String vesselId) throws InterruptedException {
        ACLMessage cancel = MessageFactory.create(ACLMessage.CANCEL);
        cancel.setConversationId(conversationId);
        cancel.addReceiver(new AID(tugId, AID.ISLOCALNAME));
        cancel.setContent(TerminalJson.write(Map.of("reason", "weather_hold", "vessel_id", vesselId)));
        outbox.put(cancel);
    }

    /** S16: the HarbourMaster's order to a tug on station — begin the escort of this job. */
    private void sendBeginEscort(String conversationId, String tugId, String vesselId) throws InterruptedException {
        ACLMessage order = MessageFactory.create(ACLMessage.INFORM);
        order.setConversationId(conversationId);
        order.setProtocol(it.unige.portcommand.behaviours.coordination.TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL);
        order.addReceiver(new AID(tugId, AID.ISLOCALNAME));
        order.setContent(TerminalJson.write(Map.of("event", "begin_escort", "vessel_id", vesselId)));
        outbox.put(order);
    }

    /** The next INFORM whose {@code event} is {@code wanted}, or null at the deadline. */
    private ACLMessage pollEvent(String wanted, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = inbox.poll(50, TimeUnit.MILLISECONDS);
            if (m != null && m.getPerformative() == ACLMessage.INFORM
                    && wanted.equals(node(m).path("event").asText())) {
                return m;
            }
        }
        return null;
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
