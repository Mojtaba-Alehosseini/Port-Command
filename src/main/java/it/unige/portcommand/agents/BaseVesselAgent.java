package it.unige.portcommand.agents;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.SimClock;
import jade.core.behaviours.WakerBehaviour;

/**
 * Abstract base for the two vessel subtypes (Contracted, Walk-in). Registers DF
 * {@code vessel}, parses the common init-args ({@link VesselSpec} at [0], a
 * {@link SimClock} at [1], a {@link MarketHistoryArtifact} at [2]), and schedules a
 * {@link WakerBehaviour} that fires {@link #onArrival()} at the vessel's sim-time ETA.
 *
 * <p>Holds the vessel's live, <i>non-hidden</i> deal/position state (berth, price,
 * hours, position) behind public accessors so the cross-package auto-flow behaviours
 * can read/write it via {@code (BaseVesselAgent) myAgent}. Walk-in <b>hidden beliefs</b>
 * are NOT here — they live in a {@code WalkInState} the negotiation behaviours receive
 * by constructor, never on the agent's public surface (CLAUDE.md / task-07 §29).
 */
public abstract class BaseVesselAgent extends PortCommandAgent {

    protected VesselSpec spec;
    private SimClock simClock;
    private MarketHistoryArtifact marketHistory;

    private Position position = new Position(0.0, 0.0, 0.0);
    protected String assignedBerth;
    private double dealPrice;
    private int dealHours;

    @Override
    protected final void registerServices() {
        registerDfService("vessel", getLocalName());
    }

    @Override
    protected void onSetup() {
        this.spec = initArg(VesselSpec.class);
        this.simClock = argAt(1, SimClock.class);
        this.marketHistory = argAt(2, MarketHistoryArtifact.class);
        long delayWallMs = arrivalDelayWallMs(spec.etaArrivalSimMillis(), simClock.nowSimMillis(), simClock);
        addBehaviour(new WakerBehaviour(this, delayWallMs) {
            @Override
            protected void onWake() {
                onArrival();
            }
        });
    }

    /** Invoked when the vessel logically arrives; the subtype attaches its flow. */
    protected abstract void onArrival();

    /** Wall-ms until the sim-time ETA (0 if already due). Pure — unit-tested. */
    static long arrivalDelayWallMs(long etaSimMillis, long nowSimMillis, SimClock simClock) {
        long deltaSim = etaSimMillis - nowSimMillis;
        if (deltaSim <= 0) {
            return 0L;
        }
        return simClock.simSecondsToWallMs(deltaSim / 1000L);
    }

    /** Read a required injected dependency from {@code args[index]} (initArg only reads [0]). */
    protected final <T> T argAt(int index, Class<T> type) {
        Object[] a = getArguments();
        if (a == null || a.length <= index || a[index] == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " " + getLocalName()
                    + " requires " + type.getSimpleName() + " at args[" + index + "]");
        }
        return type.cast(a[index]);
    }

    // --- infra + non-hidden deal/position, read by the (cross-package) behaviours ---

    public VesselSpec spec() {
        return spec;
    }

    public SimClock simClock() {
        return simClock;
    }

    public MarketHistoryArtifact marketHistory() {
        return marketHistory;
    }

    public ServiceLocator serviceLocator() {
        return getServiceLocator();
    }

    public Position position() {
        return position;
    }

    public String assignedBerth() {
        return assignedBerth;
    }

    public double dealPrice() {
        return dealPrice;
    }

    public int dealHours() {
        return dealHours;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public void setDeal(String berthId, double price, int hours) {
        this.assignedBerth = berthId;
        this.dealPrice = price;
        this.dealHours = hours;
    }
}
