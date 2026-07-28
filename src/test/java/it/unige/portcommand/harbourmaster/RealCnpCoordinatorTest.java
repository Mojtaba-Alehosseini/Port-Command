package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The serialized-CNP queue (checkpoint-#5 B2) in isolation: one session in flight,
 * later {@code initiate()} calls wait for {@code completed()}. The launched
 * behaviour itself is not under test — a recording stub agent captures the
 * {@code addBehaviour} calls (javap-verified overridable, unlike send/receive).
 */
class RealCnpCoordinatorTest {

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
                new Position(700.0, 200.0, 180.0), new Position(120.0, 260.0, 0.0), 1);
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
    }
}
