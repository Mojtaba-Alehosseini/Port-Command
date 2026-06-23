package it.unige.portcommand.agents;

import it.unige.portcommand.behaviours.auto_flow.AnnounceArrivalBehaviour;

/**
 * Auto-flow vessel with a pre-negotiated contract (Channel A). On arrival it
 * announces to the HarbourMaster and awaits a berth grant; the rest of the flow
 * (transit → dock → depart) is driven by the auto-flow behaviours. {@code contractId}
 * is injected at {@code args[3]}.
 */
public final class ContractedVesselAgent extends BaseVesselAgent {

    private String contractId;

    @Override
    protected void onSetup() {
        this.contractId = argAt(3, String.class);
        super.onSetup();
    }

    @Override
    protected void onArrival() {
        log.info("{} arrived (contracted, {}) -> announce arrival", getLocalName(), contractId);
        addBehaviour(new AnnounceArrivalBehaviour(this, contractId));
    }

    String contractId() {
        return contractId;
    }
}
