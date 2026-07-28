package it.unige.portcommand.agents;

import java.util.HashMap;
import java.util.Map;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.behaviours.cnp.BidInCNPBehaviour;
import it.unige.portcommand.behaviours.cnp.HandleAcceptRejectBehaviour;
import it.unige.portcommand.behaviours.coordination.EscortToBerthBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleCancelBehaviour;
import it.unige.portcommand.behaviours.coordination.RefuelIfLowBehaviour;
import it.unige.portcommand.behaviours.coordination.ReturnToBaseBehaviour;
import it.unige.portcommand.behaviours.coordination.TransitToVesselBehaviour;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import it.unige.portcommand.util.SimClock;
import jade.core.behaviours.Behaviour;

/**
 * Tug escort agent (four instances, {@code tug_1}..{@code tug_4}, DF service
 * {@code tug-escort}). Participates in the HarbourMaster's Contract Net: bids on a
 * {@code CFP}, and on {@code ACCEPT_PROPOSAL} transits to the vessel, escorts it to
 * the assigned berth, returns to base, and refuels when low. Full state per the
 * TugAgent section of {@code PROJECT_DEFINITION.md} + task 08.
 *
 * <h2>Threading</h2>
 * A JADE agent runs on a single thread; every behaviour of this tug (bidder,
 * accept/reject handler, cancel handler, the movement tickers, the refuel waker)
 * executes on that one thread. So {@link #fuelState}, {@link #position},
 * {@link #status}, {@link #currentJob}, {@link #activeMovement} and
 * {@link #pendingBids} are plain fields with no locking — mutated only from the
 * agent's behaviour thread (task 08 concurrency constraint). The shared
 * {@link PortStateArtifact} it publishes to is itself thread-safe for the GUI reader.
 *
 * <h2>Units</h2>
 * The map/{@link Position} are in pixels; all cost/ETA/fuel/movement math is in km via
 * {@link TugMath} (the single conversion boundary). {@link #fuelState} is a 0..1
 * fraction; € is never computed here — {@code WalletLedger} (task 20) invoices fuel.
 */
public final class TugAgent extends PortCommandAgent implements it.unige.portcommand.persistence.Mementoable {

    /** A leg consumes this many fuel units per km (200 km empties a full tank). */
    public static final double FUEL_DECAY_PER_KM = 0.005;
    /** Bids are REFUSEd below this fuel fraction; also the refuel trigger threshold. */
    public static final double LOW_FUEL_THRESHOLD = 0.20;

    private String tugId;
    private Position basePosition;
    private double baseFare;
    private double fuelCostPerKm;
    private double topSpeedKnots;

    private PortStateArtifact portState;
    private SimClock simClock;

    // --- live, mutable state: single WRITER (the agent thread), but volatile since task 22 —
    // GameStateBuilder reads these fields from the HarbourMaster thread at snapshot time and
    // needs the happens-before edge (adversarial review #2). Writes stay lock-free.
    private volatile double fuelState;
    private volatile Position position;
    private volatile TugStatus status = TugStatus.IDLE;
    private volatile TugJob currentJob;
    private Behaviour activeMovement;
    /** Outstanding bids by CNP conversation-id → the vessel pickup point we quoted. */
    private final Map<String, Position> pendingBids = new HashMap<>();
    /** The pickup the EN_ROUTE_TO_VESSEL leg is heading to — persisted by task 22 so a
     * loaded tug can resume the leg (the pickup otherwise lives only in the consumed bid). */
    private volatile Position pickupTarget;

    @Override
    protected void registerServices() {
        registerDfService("tug-escort", getLocalName());
    }

    @Override
    protected void onSetup() {
        InitArgs.TugInitArgs args = initArg(InitArgs.TugInitArgs.class);
        if (args == null) {
            throw new IllegalStateException(
                    "TugAgent " + getLocalName() + " requires TugInitArgs at args[0]");
        }
        this.tugId = args.tugId();
        this.basePosition = args.basePosition();
        this.baseFare = args.baseFare();
        this.fuelCostPerKm = args.fuelCostPerKm();
        this.topSpeedKnots = args.topSpeedKnots();
        this.fuelState = args.initialFuel();
        this.position = args.basePosition();

        this.portState = argAt(1, PortStateArtifact.class);
        this.simClock = argAt(2, SimClock.class);

        addBehaviour(new BidInCNPBehaviour(this));
        addBehaviour(new HandleAcceptRejectBehaviour(this));
        addBehaviour(new HandleCancelBehaviour(this));
        addBehaviour(new RefuelIfLowBehaviour(this));

        TugStateDTO restored = optionalArgOfType(TugStateDTO.class);
        if (restored != null) {
            restoreFrom(restored);
        }

        pushState(); // register the tug's initial (or restored) snapshot with the artefact
        log.info("Tug {} ready: base={} fuel={} topSpeed={}kn status={}",
                tugId, basePosition, fuelState, topSpeedKnots, status);
    }

    /**
     * Task 22 restore: adopt fuel/position/job and RESUME the movement leg the save caught
     * this tug in. No re-bid, no re-award — the job (and its charge) happened in the saved
     * timeline; the ledger never sees a second {@code TugJobAwardedEvent} for it, which is
     * what keeps the mid-transit round-trip charged exactly once. REFUELING and BIDDING
     * normalise to IDLE ({@code RefuelIfLowBehaviour} re-triggers off the persisted low
     * tank; an un-awarded bid's Contract Net died with the save).
     */
    private void restoreFrom(TugStateDTO dto) {
        this.fuelState = dto.fuelState();
        this.position = dto.position() != null ? dto.position() : basePosition;
        this.pickupTarget = dto.pickupTarget();
        if (dto.job() != null) {
            // The client AID is always the HarbourMaster; rebuilt by local name inside the
            // live container (AID.ISLOCALNAME is container-resolved — the task-20 unit-test
            // caveat does not apply here).
            this.currentJob = new TugJob(dto.job().vesselId(), dto.job().berthPosition(),
                    new jade.core.AID("harbour_master", jade.core.AID.ISLOCALNAME),
                    dto.job().conversationId());
        }
        TugStatus restoredStatus = TugStatus.valueOf(dto.status());
        switch (restoredStatus) {
            case EN_ROUTE_TO_VESSEL -> {
                if (currentJob != null && pickupTarget != null) {
                    this.status = TugStatus.EN_ROUTE_TO_VESSEL;
                    TransitToVesselBehaviour leg = new TransitToVesselBehaviour(this, pickupTarget);
                    setActiveMovement(leg);
                    addBehaviour(leg);
                } else {
                    this.status = TugStatus.IDLE; // job/pickup incoherent — fail safe to idle
                    this.currentJob = null;
                }
            }
            case ESCORTING -> {
                if (currentJob != null) {
                    this.status = TugStatus.ESCORTING;
                    EscortToBerthBehaviour leg = new EscortToBerthBehaviour(this);
                    setActiveMovement(leg);
                    addBehaviour(leg);
                } else {
                    this.status = TugStatus.IDLE;
                }
            }
            case RETURNING -> {
                this.status = TugStatus.RETURNING;
                this.currentJob = null;
                ReturnToBaseBehaviour leg = new ReturnToBaseBehaviour(this);
                setActiveMovement(leg);
                addBehaviour(leg);
            }
            default -> {
                this.status = TugStatus.IDLE;
                this.currentJob = null;
            }
        }
        log.info("{} restored: status={} fuel={} job={}", tugId, status, fuelState,
                currentJob == null ? "-" : currentJob.vesselId());
    }

    /** The persisted tug record (task 22). BIDDING normalises to IDLE — see {@link TugStateDTO}. */
    public TugStateDTO snapshotDto() {
        TugStatus persistedStatus = status == TugStatus.BIDDING ? TugStatus.IDLE : status;
        TugStateDTO.TugJobDTO job = currentJob == null ? null
                : new TugStateDTO.TugJobDTO(currentJob.vesselId(), currentJob.berthPosition(),
                        currentJob.conversationId());
        return new TugStateDTO(tugId, persistedStatus.name(), position, fuelState, job, pickupTarget);
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode snapshot() {
        return persistenceMapper().valueToTree(snapshotDto());
    }

    @Override
    public void restore(com.fasterxml.jackson.databind.JsonNode node) {
        restoreFrom(persistenceMapper().convertValue(node, TugStateDTO.class));
    }

    private <T> T argAt(int index, Class<T> type) {
        Object[] a = getArguments();
        if (a == null || a.length <= index || a[index] == null) {
            throw new IllegalStateException("TugAgent " + getLocalName() + " requires "
                    + type.getSimpleName() + " at args[" + index + "]");
        }
        return type.cast(a[index]);
    }

    // ---------------------------------------------------------------------------
    // Movement (agent-thread only): integrate velocity over elapsed SIM time, so a
    // test/production driver advances motion purely by advancing the SimClock.
    // ---------------------------------------------------------------------------

    /**
     * Moves the tug toward {@code target} by the distance coverable in
     * {@code dtSimSeconds} at top speed (clamped so it never overshoots), consuming
     * fuel for the actual km moved. Returns {@code true} once {@code target} is
     * reached. Uses the SAME {@link TugMath} conversions as the bid ETA, so simulated
     * arrival time matches the quoted {@code etaMinutes}.
     */
    public boolean advanceToward(Position target, double dtSimSeconds) {
        double remainingKm = TugMath.distanceKm(position, target);
        if (remainingKm <= TugMath.ARRIVAL_EPS_KM) {
            return true;
        }
        double stepKm = Math.min(TugMath.stepKm(topSpeedKnots, dtSimSeconds), remainingKm);
        this.position = TugMath.moveToward(position, target, stepKm * TugMath.PIXELS_PER_KM);
        consumeFuel(stepKm);
        return TugMath.distanceKm(position, target) <= TugMath.ARRIVAL_EPS_KM;
    }

    /** Publishes the current {position, status} to the shared artefact (task 08: every tick/status change). */
    public void pushState() {
        if (portState != null) {
            portState.updateTug(tugId, position, status);
        }
    }

    /** {@code true} when the tug is within arrival tolerance of its home base. */
    public boolean atBase() {
        return TugMath.distanceKm(position, basePosition) <= TugMath.ARRIVAL_EPS_KM;
    }

    // ---------------------------------------------------------------------------
    // Fuel (agent-thread only). Pure fuel-unit math; no currency (task 08 constraint).
    // ---------------------------------------------------------------------------

    /** Consumes {@code km * FUEL_DECAY_PER_KM} fuel units, clamped at empty. */
    public void consumeFuel(double km) {
        this.fuelState = Math.max(0.0, fuelState - km * FUEL_DECAY_PER_KM);
    }

    /** Restores a full tank (refuel-cycle completion). */
    public void refuelFull() {
        this.fuelState = 1.0;
    }

    // ---------------------------------------------------------------------------
    // Bid memory (CNP): remember the pickup we quoted per conversation, so an
    // ACCEPT can be honoured without the vessel echoing its position back.
    // ---------------------------------------------------------------------------

    public void rememberBid(String conversationId, Position pickup) {
        pendingBids.put(conversationId, pickup);
    }

    /** The pickup the current EN_ROUTE leg targets; set on job take, cleared at pickup arrival. */
    public Position pickupTarget() {
        return pickupTarget;
    }

    public void setPickupTarget(Position pickup) {
        this.pickupTarget = pickup;
    }

    /** Removes and returns the remembered pickup for a conversation, or {@code null} if we never bid / already consumed it. */
    public Position takeBid(String conversationId) {
        return pendingBids.remove(conversationId);
    }

    public void forgetBid(String conversationId) {
        pendingBids.remove(conversationId);
    }

    /** Drops all outstanding bids — called once a job is accepted (the tug can serve only one). */
    public void clearPendingBids() {
        pendingBids.clear();
    }

    // ---------------------------------------------------------------------------
    // Status / job accessors (public: behaviours live in sibling packages).
    // ---------------------------------------------------------------------------

    /** Sets the lifecycle status and republishes to the artefact (single emit point for status changes). */
    public void setStatus(TugStatus newStatus) {
        this.status = newStatus;
        pushState();
    }

    public TugStatus status() {
        return status;
    }

    public TugJob currentJob() {
        return currentJob;
    }

    public void setCurrentJob(TugJob job) {
        this.currentJob = job;
    }

    public void clearJob() {
        this.currentJob = null;
    }

    public Behaviour activeMovement() {
        return activeMovement;
    }

    public void setActiveMovement(Behaviour behaviour) {
        this.activeMovement = behaviour;
    }

    // --- immutable config + live reads ---

    public SimClock simClock() {
        return simClock;
    }

    public PortStateArtifact portState() {
        return portState;
    }

    public String tugId() {
        return tugId;
    }

    public Position position() {
        return position;
    }

    public Position basePosition() {
        return basePosition;
    }

    public double topSpeedKnots() {
        return topSpeedKnots;
    }

    public double baseFare() {
        return baseFare;
    }

    public double fuelCostPerKm() {
        return fuelCostPerKm;
    }

    public double fuelState() {
        return fuelState;
    }
}
