package it.unige.portcommand.harbourmaster.cnp;

import it.unige.portcommand.harbourmaster.CnpCoordinator;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.ontology.Position;
import jade.core.AID;
import jade.core.Agent;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link CnpRetryBehaviour} is deliberately just "wait, then re-initiate" -- the retry cap
 * (task 15, {@link CnpRetryBehaviour#MAX_RETRIES}) is enforced by the caller before ever
 * constructing this behaviour (see {@code AwardContractBehaviour.scheduleRetryIfWithinCap}), so
 * the only thing worth pinning here is that {@code onWake} re-enters the coordinator with the
 * exact same {@link CnpRequest} -- no container/scheduling needed, {@code onWake} is called
 * directly.
 */
class CnpRetryBehaviourTest {

    @Test
    void onWakeReInitiatesTheSameRequestThroughTheCoordinator() {
        CnpCoordinator coordinator = mock(CnpCoordinator.class);
        // AID.ISGUID: a bare unit test has no live JADE platform to resolve a local name
        // against -- ISLOCALNAME's constructor throws ("Unknown Platform Name") outside a
        // running container. ISGUID treats the string as already-complete, no lookup needed.
        CnpRequest req = new CnpRequest("V001", new AID("vessel1", AID.ISGUID),
                new Position(0.0, 0.0, 0.0), new Position(10.0, 10.0, 0.0), 1);
        CnpRetryBehaviour behaviour = new CnpRetryBehaviour(new Agent(), coordinator, req, 1000L);

        behaviour.onWake();

        verify(coordinator).initiate(same(req));
        verifyNoMoreInteractions(coordinator);
    }
}
