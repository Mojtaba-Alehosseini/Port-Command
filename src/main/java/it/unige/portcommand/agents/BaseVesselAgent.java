package it.unige.portcommand.agents;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.behaviours.auto_flow.DepartBehaviour;
import it.unige.portcommand.behaviours.auto_flow.DockAndServiceBehaviour;
import it.unige.portcommand.behaviours.auto_flow.TransitToBerthBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
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
 *
 * <p><b>Task 22 — flow position + restore.</b> The three post-deal flow behaviours report
 * their (phase, absolute sim deadline) here via {@link #reportPhase}, giving the save
 * snapshot an honest answer to "where in the flow is this vessel" without reaching into
 * behaviour privates. When the spawner appends a {@link VesselStateDTO} (found by type,
 * never by index), setup takes the RESTORE path instead of the arrival waker: deal terms
 * and position are adopted verbatim, the subtype hydrates its own beliefs in
 * {@link #onRestore}, and the flow re-enters at the persisted phase with the persisted
 * deadline — a docked vessel does not re-serve hours it already served.
 */
public abstract class BaseVesselAgent extends PortCommandAgent
        implements it.unige.portcommand.persistence.Mementoable {

    /** The persistable post-deal flow positions ({@code VesselStateDTO.currentPhase}). */
    public enum FlowPhase { TRANSIT, DOCKED, DEPARTING }

    protected VesselSpec spec;
    private SimClock simClock;
    private MarketHistoryArtifact marketHistory;

    private Position position = new Position(0.0, 0.0, 0.0);
    protected String assignedBerth;
    private double dealPrice;
    private int dealHours;

    /** Volatile: written on the agent thread at phase transitions, read by the save snapshot. */
    private volatile FlowPhase flowPhase;
    private volatile long phaseDeadlineSimMillis;

    @Override
    protected final void registerServices() {
        registerDfService("vessel", getLocalName());
    }

    @Override
    protected void onSetup() {
        this.spec = initArg(VesselSpec.class);
        this.simClock = argAt(1, SimClock.class);
        this.marketHistory = argAt(2, MarketHistoryArtifact.class);
        VesselStateDTO restored = optionalArgOfType(VesselStateDTO.class);
        if (restored != null) {
            restoreFrom(restored);
            return;
        }
        long delayWallMs = arrivalDelayWallMs(spec.etaArrivalSimMillis(), simClock.nowSimMillis(), simClock);
        addBehaviour(new WakerBehaviour(this, delayWallMs) {
            @Override
            protected void onWake() {
                onArrival();
            }
        });
    }

    /**
     * Rebuilds this vessel from a save (task 22): no arrival waker, no announcement, no
     * negotiation — the deal already closed in the saved timeline. Adopts deal + position,
     * lets the subtype hydrate its own beliefs, then re-enters the flow at the persisted
     * phase with the persisted ABSOLUTE deadline (the resume-not-restart rule).
     */
    private void restoreFrom(VesselStateDTO dto) {
        setDeal(dto.assignedBerth(), dto.dealPrice(), dto.dealHours());
        setPosition(new Position(dto.posX(), dto.posY(), dto.posHeading()));
        onRestore(dto);
        FlowPhase phase = FlowPhase.valueOf(dto.currentPhase());
        switch (phase) {
            case TRANSIT -> addBehaviour(TransitToBerthBehaviour.resuming(this, simClock,
                    dto.phaseDeadlineSimMillis()));
            case DOCKED -> addBehaviour(DockAndServiceBehaviour.resuming(this, simClock,
                    dto.phaseDeadlineSimMillis()));
            case DEPARTING -> addBehaviour(DepartBehaviour.resuming(this, simClock,
                    dto.phaseDeadlineSimMillis()));
        }
        log.info("{} restored: phase={} deadline={} berth={} ({}h at {})", getLocalName(), phase,
                dto.phaseDeadlineSimMillis(), assignedBerth, dealHours, dealPrice);
    }

    /** Subtype hook: hydrate channel-specific beliefs from the persisted DTO (task 22). */
    protected abstract void onRestore(VesselStateDTO dto);

    /** The vessel-side half of the flat persisted record (task 22); HM tracking merged later. */
    public abstract VesselStateDTO snapshotDto();

    @Override
    public final com.fasterxml.jackson.databind.JsonNode snapshot() {
        return persistenceMapper().valueToTree(snapshotDto());
    }

    @Override
    public final void restore(com.fasterxml.jackson.databind.JsonNode node) {
        onRestore(persistenceMapper().convertValue(node, VesselStateDTO.class));
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

    // --- task 22: flow position, reported by the auto-flow behaviours, read by the snapshot ---

    /** Called by each flow behaviour's constructor — the single write point per transition. */
    public void reportPhase(FlowPhase phase, long deadlineSimMillis) {
        this.flowPhase = phase;
        this.phaseDeadlineSimMillis = deadlineSimMillis;
    }

    /** The current flow phase, or {@code null} before the berth grant reached this vessel. */
    public FlowPhase flowPhase() {
        return flowPhase;
    }

    /** The absolute sim-millis instant the current phase completes at. */
    public long phaseDeadlineSimMillis() {
        return phaseDeadlineSimMillis;
    }
}
