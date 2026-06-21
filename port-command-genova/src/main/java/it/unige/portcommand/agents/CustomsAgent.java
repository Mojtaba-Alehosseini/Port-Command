package it.unige.portcommand.agents;

/**
 * Customs pre-clearance (one instance). Stub: registers DF {@code customs}. Its
 * behaviours (PreClear, FlagForInspection, clearance-request handling) are
 * attached by task 09.
 */
public final class CustomsAgent extends PortCommandAgent {

    @Override
    protected void registerServices() {
        registerDfService("customs", getLocalName());
    }

    @Override
    protected void onSetup() {
        // No behaviours at the stub stage.
    }
}
