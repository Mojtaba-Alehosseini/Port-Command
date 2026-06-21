package it.unige.portcommand.agents;

import it.unige.portcommand.behaviours.assistant.RecommendOnDemandBehaviour;

/**
 * The ChatBDI-pattern assistant (one instance) — the project centerpiece. Stub:
 * registers DF {@code assistant} and attaches the on-demand recommender. The
 * autopilot/policy-parse/explain behaviours are wired by task 10.
 */
public final class AssistantAgent extends PortCommandAgent {

    @Override
    protected void registerServices() {
        registerDfService("assistant", getLocalName());
    }

    @Override
    protected void onSetup() {
        addBehaviour(new RecommendOnDemandBehaviour(this));
    }
}
