package it.unige.portcommand.harbourmaster;

import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;

/**
 * Real (not hardcoded) tug Contract Net: DF-discovers tugs and runs a non-blocking
 * CFP/collect/award cycle scored by Prolog {@code best_n_bids}. Keeps the
 * {@code StubCnpCoordinator} name task 15 already expects to replace with
 * {@code RealCnpCoordinator} (which adds zero-bid retry, Settings-driven timing, and
 * post-ACCEPT-failure handling — this task's zero-bid path is a one-shot hold, no
 * retry, per the session brief).
 */
public final class StubCnpCoordinator implements CnpCoordinator {

    private final Agent agent;
    private final ServiceLocator serviceLocator;
    private final SimClock simClock;

    public StubCnpCoordinator(Agent agent, ServiceLocator serviceLocator, SimClock simClock) {
        this.agent = agent;
        this.serviceLocator = serviceLocator;
        this.simClock = simClock;
    }

    @Override
    public void initiate(CnpRequest req) {
        // Must run on the agent thread (task-15's own documented common mistake) —
        // addBehaviour is safe to call from within another behaviour's action().
        agent.addBehaviour(new InitiateCNPBehaviour(agent, req, serviceLocator, simClock));
    }
}
