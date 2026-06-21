package it.unige.portcommand.agents;

import it.unige.portcommand.ontology.VesselSpec;

/**
 * Abstract base for the two vessel subtypes (Contracted, Walk-in). Registers DF
 * {@code vessel} and parses the {@link VesselSpec} init-arg. No behaviours at the
 * stub stage — task 07 attaches the auto-flow / negotiation behaviours.
 */
public abstract class BaseVesselAgent extends PortCommandAgent {

    protected VesselSpec spec;
    protected String assignedBerth;

    @Override
    protected final void registerServices() {
        registerDfService("vessel", getLocalName());
    }

    @Override
    protected void onSetup() {
        this.spec = initArg(VesselSpec.class);
    }

    /** Invoked when the vessel logically arrives; wired by task-07 behaviours. */
    protected abstract void onArrival();

    VesselSpec spec() {
        return spec;
    }
}
