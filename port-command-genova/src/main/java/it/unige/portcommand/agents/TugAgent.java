package it.unige.portcommand.agents;

import it.unige.portcommand.behaviours.cnp.BidInCNPBehaviour;
import it.unige.portcommand.ontology.Position;

/**
 * Tug escort (four instances). Stub: registers DF {@code tug-escort}, reads its
 * {@link InitArgs.TugInitArgs}, and attaches the CNP bidder. The live mutable
 * {@code fuelState} is seeded from {@code initialFuel}.
 */
public final class TugAgent extends PortCommandAgent {

    private String tugId;
    private Position basePosition;
    private double baseFare;
    private double fuelCostPerKm;
    private double topSpeedKnots;
    private double fuelState;

    @Override
    protected void registerServices() {
        registerDfService("tug-escort", getLocalName());
    }

    @Override
    protected void onSetup() {
        InitArgs.TugInitArgs args = initArg(InitArgs.TugInitArgs.class);
        if (args != null) {
            this.tugId = args.tugId();
            this.basePosition = args.basePosition();
            this.baseFare = args.baseFare();
            this.fuelCostPerKm = args.fuelCostPerKm();
            this.topSpeedKnots = args.topSpeedKnots();
            this.fuelState = args.initialFuel();
        }
        addBehaviour(new BidInCNPBehaviour(this));
    }

    String tugId() {
        return tugId;
    }

    double fuelState() {
        return fuelState;
    }

    double baseFare() {
        return baseFare;
    }

    Position basePosition() {
        return basePosition;
    }
}
