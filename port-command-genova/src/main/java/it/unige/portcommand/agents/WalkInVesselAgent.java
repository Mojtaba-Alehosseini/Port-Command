package it.unige.portcommand.agents;

/**
 * Vessel arriving without a contract (Channel B); negotiates price/duration with
 * the player. Stub: inherits the {@code vessel} DF registration. The hidden
 * beliefs (PROJECT_DEFINITION §4.3) are populated by task 07.
 */
public final class WalkInVesselAgent extends BaseVesselAgent {

    private double minAcceptablePrice;
    private double targetPrice;
    private int maxWaitMinutes;
    private int roundsRemaining;
    private String personality;

    @Override
    protected void onSetup() {
        super.onSetup();
    }

    @Override
    protected void onArrival() {
        log.debug("{} arrived (walk-in)", getLocalName());
    }

    double minAcceptablePrice() {
        return minAcceptablePrice;
    }

    double targetPrice() {
        return targetPrice;
    }

    int maxWaitMinutes() {
        return maxWaitMinutes;
    }

    int roundsRemaining() {
        return roundsRemaining;
    }

    String personality() {
        return personality;
    }
}
