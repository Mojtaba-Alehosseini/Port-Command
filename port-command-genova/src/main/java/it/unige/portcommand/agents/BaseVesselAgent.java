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
    public enum FlowPhase { AWAITING_BERTH, TRANSIT, DOCKED, DEPARTING }

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
     * B-01 (S8, ADR-17): the vessel-side MIRROR of the HarbourMaster's {@code weatherHeld}
     * tracking flag — set/cleared by {@code HandleWeatherHoldBehaviour} from the protocol-tagged
     * hold/resume INFORMs, seeded from the persisted DTO on restore. While true, the transit
     * ticker SLIDES its deadline instead of counting down (progress frozen). Volatile: written
     * on the agent thread, read by the ticker on the same thread today, but the snapshot walk
     * reads cross-thread like the phase fields above.
     */
    private volatile boolean weatherHeld;

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
        // B-01 (S8, ADR-17): a held vessel restores HELD — the resumed transit ticker stays
        // frozen until the rebuilt HarbourMaster's weather_resume (or the periodic release
        // backstop) reaches it. The flag rides the DTO field that has persisted since task 22.
        this.weatherHeld = dto.weatherHeld();
        onRestore(dto);
        FlowPhase phase = FlowPhase.valueOf(dto.currentPhase());
        switch (phase) {
            // A queued vessel resumes exactly where it parked: listening for the berth grant.
            // The HarbourMaster side re-enters it into the wait queue (RestoreDispatchBehaviour's
            // AWAITING_BERTH sweep), so the ACCEPT this behaviour waits for will really arrive.
            case AWAITING_BERTH -> addBehaviour(
                    new AwaitBerthBehaviour(this, "berth-" + getLocalName()));
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

    /** B-01 (S8): true while the HarbourMaster holds this vessel offshore — transit frozen. */
    public boolean weatherHeld() {
        return weatherHeld;
    }

    /** Written by {@code HandleWeatherHoldBehaviour} (live) and {@code restoreFrom} (load). */
    public void setWeatherHeld(boolean held) {
        this.weatherHeld = held;
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
