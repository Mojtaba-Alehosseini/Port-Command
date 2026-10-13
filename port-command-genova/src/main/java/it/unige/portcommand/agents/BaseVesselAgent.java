package it.unige.portcommand.agents;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.behaviours.auto_flow.AwaitBerthBehaviour;
import it.unige.portcommand.behaviours.auto_flow.DepartBehaviour;
import it.unige.portcommand.behaviours.auto_flow.DockAndServiceBehaviour;
import it.unige.portcommand.behaviours.auto_flow.TransitToBerthBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.util.SimClock;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.MessageTemplate;

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
 * by constructor, never on the agent's public surface (the design rules / task-07 §29).
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
    /**
     * {@code AWAITING_BERTH} (S5, ADR-16): between the deal/announce and the HarbourMaster's
     * berth ACCEPT. Before it existed this window had {@code flowPhase == null}, which refused
     * every manual save and made the EOD autosave DROP the vessel — tolerable while grants were
     * instantaneous, fatal once the berth wait-queue made the window minutes long.
     */
    public enum FlowPhase {
        AWAITING_BERTH,
        /**
         * S16 (ADR-20): the berth is granted and the ship waits at the channel entry for the
         * HarbourMaster's {@code proceed_to_berth} — its escort to reach it, customs to clear it,
         * the weather to allow it. No deadline. Before S16 the ship went straight from the grant
         * into TRANSIT and was at its berth two sim-minutes later, tugs or no tugs.
         */
        AWAITING_CLEARANCE,
        TRANSIT,
        DOCKED,
        DEPARTING
    }

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

    /**
     * S16 (ADR-20): the channel leg in progress, kept so a hold can stop it. Null whenever the
     * ship is not under way. Agent thread only.
     */
    private jade.core.behaviours.Behaviour activeTransit;
    /**
     * S16: a {@code proceed_to_berth} that overtook the berth grant. The two are separate
     * messages on separate templates, and a ship that needs no escort is cleared in the very
     * action that grants it — so the order can be consumed first. It is kept here (the leg's
     * length in sim millis, -1 = none) and honoured the moment the grant lands.
     */
    private long pendingProceedSimMillis = -1L;
    /** S16: the HarbourMaster has cleared this ship to leave its berth. Read by the service ticker. */
    private volatile boolean departureCleared;

    @Override
    protected final void registerServices() {
        registerDfService("vessel", getLocalName());
    }

    @Override
    protected void onSetup() {
        this.spec = initArg(VesselSpec.class);
        this.simClock = argAt(1, SimClock.class);
        this.marketHistory = argAt(2, MarketHistoryArtifact.class);
        // B-01 (S8): the weather hold/resume receiver rides BOTH setup paths — a restored
        // mid-hold vessel must hear the eventual resume exactly like a live one.
        addBehaviour(new it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour(this));
        VesselStateDTO restored = optionalArgOfType(VesselStateDTO.class);
        if (restored != null) {
            restoreFrom(restored);
            return;
        }
        // S14: the ETA is a SIM instant, and a contracted vessel is spawned two sim-hours ahead of
        // it — a five-minute wall wait on a 60-minute day, during which the player may well pause
        // or change speed. SimDeadlineWait keeps the waker honest about that; with an untouched
        // pace its chunks add up to exactly the single arrivalDelayWallMs delay used before.
        it.unige.portcommand.util.SimDeadlineWait arrival =
                new it.unige.portcommand.util.SimDeadlineWait(simClock);
        addBehaviour(new WakerBehaviour(this, arrival.armUntil(spec.etaArrivalSimMillis())) {
            @Override
            protected void onWake() {
                long more = arrival.next();
                if (more > 0) {
                    reset(more); // not there yet in game time: paused, re-paced, or mid-wait
                    return;
                }
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
        // S16 (ADR-20): no vessel-side "held" flag to seed any more. A ship that was held, or was
        // waiting for tugs or customs, was saved in AWAITING_CLEARANCE and simply waits again;
        // the rebuilt HarbourMaster re-evaluates it and sends the order when it may move.
        onRestore(dto);
        FlowPhase phase = FlowPhase.valueOf(dto.currentPhase());
        switch (phase) {
            // A queued vessel resumes exactly where it parked: listening for the berth grant.
            // The HarbourMaster side re-enters it into the wait queue (RestoreDispatchBehaviour's
            // AWAITING_BERTH sweep), so the ACCEPT this behaviour waits for will really arrive.
            case AWAITING_BERTH -> addBehaviour(
                    new AwaitBerthBehaviour(this, "berth-" + getLocalName()));
            // Waiting at the channel entry needs no behaviour of its own: the permanent order
            // receiver (HandleWeatherHoldBehaviour) starts the leg when the order comes.
            case AWAITING_CLEARANCE -> reportPhase(FlowPhase.AWAITING_CLEARANCE, 0L);
            case TRANSIT -> {
                this.activeTransit = TransitToBerthBehaviour.resuming(this, simClock,
                        dto.phaseDeadlineSimMillis());
                addBehaviour(activeTransit);
            }
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

    // --- S16 (ADR-20): the HarbourMaster's movement orders. Agent thread only: every caller is
    // one of this agent's own behaviours (AwaitBerthBehaviour, HandleWeatherHoldBehaviour, the
    // transit ticker).

    /** The berth grant has landed ({@code AwaitBerthBehaviour}): wait for the order to proceed. */
    public void onBerthGranted() {
        if (pendingProceedSimMillis >= 0L) {
            long leg = pendingProceedSimMillis;
            pendingProceedSimMillis = -1L;
            startTransit(leg);
            return;
        }
        reportPhase(FlowPhase.AWAITING_CLEARANCE, 0L);
    }

    /** {@code proceed_to_berth}: start the channel leg, {@code transitSimMillis} long. */
    public void onProceedToBerth(long transitSimMillis) {
        if (flowPhase == FlowPhase.AWAITING_CLEARANCE) {
            startTransit(transitSimMillis);
        } else if (flowPhase == null || flowPhase == FlowPhase.AWAITING_BERTH) {
            pendingProceedSimMillis = transitSimMillis; // overtook the grant; see the field
        } else {
            log.debug("{}: proceed order in phase {} — already past the channel entry, ignored",
                    getLocalName(), flowPhase);
        }
    }

    /**
     * {@code weather_hold}: a ship under way goes back to the channel entry (its leg restarts
     * when it is released, with its new escort); a ship that is waiting keeps waiting. A docked
     * or departing ship is not moved by a hold — it is at its berth, or already leaving.
     */
    public void onHold() {
        pendingProceedSimMillis = -1L;
        if (flowPhase == FlowPhase.TRANSIT && activeTransit != null) {
            removeBehaviour(activeTransit);
            activeTransit = null;
            reportPhase(FlowPhase.AWAITING_CLEARANCE, 0L);
        }
    }

    /** {@code cleared_to_depart}: the service ticker, waiting at the berth, may now leave. */
    public void onClearedToDepart() {
        this.departureCleared = true;
    }

    public boolean departureCleared() {
        return departureCleared;
    }

    /** The transit ticker reached the berth and is handing over to the service ticker. */
    public void transitEnded() {
        this.activeTransit = null;
    }

    private void startTransit(long transitSimMillis) {
        this.activeTransit = new TransitToBerthBehaviour(this, simClock, transitSimMillis);
        addBehaviour(activeTransit);
    }

    /**
     * Audit catch-all union, by CONVERSATION ID rather than by template object: a vessel's
     * receivers ({@code AwaitBerthBehaviour}, and on the walk-in path
     * {@code EvaluateCounterOfferBehaviour}) build their templates at runtime from a conversation
     * id, so they cannot be named statically — but the ids themselves are derivable from the
     * agent's own identity, which is what makes this safe. The berth grant conversation is
     * {@code "berth-"+localName} on BOTH channels. Since S8 (B-01) the union also carries the
     * protocol-tagged weather hold/resume INFORMs, which {@code HandleWeatherHoldBehaviour}
     * consumes — before that they were deliberately-logged unhandled traffic.
     */
    @Override
    protected MessageTemplate handledTemplate() {
        return MessageTemplate.or(
                MessageTemplate.MatchConversationId("berth-" + getLocalName()),
                it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour.TEMPLATE);
    }

}
