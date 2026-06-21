package it.unige.portcommand.agents;

/**
 * Auto-flow vessel with a pre-negotiated contract (Channel A). Stub: inherits the
 * {@code vessel} DF registration; {@code contractId} is bound by task 07.
 */
public final class ContractedVesselAgent extends BaseVesselAgent {

    private String contractId;

    @Override
    protected void onSetup() {
        super.onSetup();
        // contractId is assigned from the contract record by task 07.
    }

    @Override
    protected void onArrival() {
        log.debug("{} arrived (contracted)", getLocalName());
    }

    String contractId() {
        return contractId;
    }
}
