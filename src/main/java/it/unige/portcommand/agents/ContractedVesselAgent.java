package it.unige.portcommand.agents;

import it.unige.portcommand.behaviours.auto_flow.AnnounceArrivalBehaviour;
import it.unige.portcommand.persistence.dto.VesselStateDTO;

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

    /** Task 22 restore: the granted contract came from the save; no re-announcement happens. */
    @Override
    protected void onRestore(VesselStateDTO dto) {
        this.contractId = dto.contractRef();
    }

    /** The flat persisted record, contracted side (task 22); HM-side fields filled by the builder. */
    @Override
    public VesselStateDTO snapshotDto() {
        return new VesselStateDTO(
                spec.vesselId(), spec.vesselType(), VesselStateDTO.CHANNEL_CONTRACTED,
                spec.draft(), spec.length(), spec.tonnage(), spec.cargoClass(),
                spec.etaArrivalSimMillis(),
                null, flowPhase() == null ? null : flowPhase().name(), phaseDeadlineSimMillis(),
                assignedBerth(), dealPrice(), dealHours(),
                null, false, false,
                position().x(), position().y(), position().headingDeg(),
                contractId,
                null, null, null, null, null, null, null);
    }

    String contractId() {
        return contractId;
    }
}
