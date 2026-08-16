package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The serialized-CNP queue (checkpoint-#5 B2) in isolation: one session in flight,
 * later {@code initiate()} calls wait for {@code completed()}. The launched
 * behaviour itself is not under test — a recording stub agent captures the
 * {@code addBehaviour} calls (javap-verified overridable, unlike send/receive).
 *
 * <p>S5: {@code completed()} now dequeues by PRIORITY (R28 {@code preempts/2} over R30
 * {@code effective_priority} classes), so the suite boots the Prolog engine and asserts
 * launch ORDER, not just counts — FIFO must survive as the all-equal-classes special case.
 */
class RealCnpCoordinatorTest {

    @BeforeAll
    static void bootProlog() {
        PrologEngine.getInstance().init();
    }

    /** Records launches instead of scheduling them (no container needed). */
    private static final class RecordingAgent extends Agent {
        private final List<Behaviour> launched = new ArrayList<>();

        @Override
        public void addBehaviour(Behaviour b) {
            launched.add(b);
        }
    }

    private RecordingAgent agent;
    private RealCnpCoordinator coordinator;

    @BeforeEach
    void setUp() {
        agent = new RecordingAgent();
        coordinator = new RealCnpCoordinator(agent, null, new SimClock(300));
    }

    private static CnpRequest request(String vesselId) {
        return new CnpRequest(vesselId, new AID(vesselId + "@test", AID.ISGUID),
                new Position(700.0, 200.0, 180.0), new Position(120.0, 260.0, 0.0), 1, "cargo_vessel");
    }

    @Test
    void onlyOneSessionLaunchesUntilTheFirstCompletes() {
        coordinator.initiate(request("V1"));
        coordinator.initiate(request("V2"));
        coordinator.initiate(request("V3"));

        assertEquals(1, agent.launched.size(), "V2/V3 must queue behind the in-flight session");

        coordinator.completed();
        assertEquals(2, agent.launched.size(), "V2 launches only after V1's session closes");
        coordinator.completed();
        assertEquals(3, agent.launched.size());
    }

    @Test
    void anIdleCompletionLeavesTheQueueReadyForImmediateLaunch() {
        coordinator.initiate(request("V1"));
        coordinator.completed(); // queue empty -> idle
        coordinator.completed(); // spurious extra completion must not corrupt the state

        coordinator.initiate(request("V2"));
        assertEquals(2, agent.launched.size(), "an idle coordinator launches immediately");
    }

    @Test
    void sessionsLaunchInArrivalOrder() {
        coordinator.initiate(request("V1"));
        coordinator.initiate(request("V2"));
        coordinator.initiate(request("V3"));
        coordinator.completed();
        coordinator.completed();

        assertEquals(3, agent.launched.size(), "FIFO: every queued request eventually launches");
        // Equal effective classes -> preempts/2 fails pairwise -> encounter order preserved.
        // The old byte-for-byte FIFO is the all-ordinary special case of the priority dequeue.
        assertEquals(List.of("V1", "V2", "V3"), launchedVesselIds(),
                "same-class backlog must keep arrival order (R28 tie = FIFO)");
    }

    @Test
    void anEmergencyEscalatedRequestPreemptsTheBacklog() {
        // The storm shape (D9/R28): V3's re-dispatch CNP arrives LAST but carries the
        // emergency override the weather hold put in the HM's escalation map. preempts/2
        // over the R30 effective classes must pull it out of the backlog ahead of V2.
        Map<String, String> escalations = new HashMap<>();
        RealCnpCoordinator prioritized =
                new RealCnpCoordinator(agent, null, new SimClock(300), escalations::get);

        prioritized.initiate(request("V1"));            // in flight — head-of-line, never re-ranked
        prioritized.initiate(request("V2"));            // backlog, ordinary (scheduled_arrival)
        escalations.put("V3", "emergency");
        prioritized.initiate(request("V3"));            // backlog, emergency

        prioritized.completed();
        prioritized.completed();

        assertEquals(List.of("V1", "V3", "V2"), launchedVesselIds(),
                "the emergency-class request must preempt the earlier ordinary one [R28,R30]");
    }

    private List<String> launchedVesselIds() {
        return agent.launched.stream()
                .map(b -> ((InitiateCNPBehaviour) b).request().vesselId())
                .toList();
    }
}
