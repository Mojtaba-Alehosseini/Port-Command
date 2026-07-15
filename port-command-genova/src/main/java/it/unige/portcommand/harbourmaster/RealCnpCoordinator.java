package it.unige.portcommand.harbourmaster;

import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;

/**
 * Real (not hardcoded) tug Contract Net: DF-discovers tugs and runs a non-blocking
 * CFP/collect/award cycle scored by Prolog {@code best_n_bids}. Renamed from
 * {@code StubCnpCoordinator} (task 15) — this class was already the real cycle as of task 11/
 * ADR-08; only the name was stale. Zero-bid retry is now bounded via task 15's
 * {@code CnpRetryBehaviour}, scheduled from {@code AwardContractBehaviour.holdVessel}; the CFP
 * reply-by window is Settings-driven ({@code InitiateCNPBehaviour}). Post-ACCEPT-failure handling
 * (a winning tug REFUSEs) is not implemented — the documented, accepted exit ramp.
 */
public final class RealCnpCoordinator implements CnpCoordinator {

    private final Agent agent;
    private final ServiceLocator serviceLocator;
    private final SimClock simClock;

    public RealCnpCoordinator(Agent agent, ServiceLocator serviceLocator, SimClock simClock) {
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
