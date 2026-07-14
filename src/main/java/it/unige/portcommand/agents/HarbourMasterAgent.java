package it.unige.portcommand.agents;

import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;

/**
 * Central coordinator and the player's proxy (there is no PlayerAgent). Stub:
 * registers DF {@code harbour-master} and attaches the auto-flow dispatcher.
 * Belief fields (wallet, reputation) are populated by tasks 11/24.
 */
public final class HarbourMasterAgent extends PortCommandAgent {

    private double wallet;
    private double reputation;

    @Override
    protected void registerServices() {
        registerDfService("harbour-master", getLocalName());
    }

    @Override
    protected void onSetup() {
        addBehaviour(new AutoFlowDispatcherBehaviour(this));
    }

    double wallet() {
        return wallet;
    }

    double reputation() {
        return reputation;
    }
}
