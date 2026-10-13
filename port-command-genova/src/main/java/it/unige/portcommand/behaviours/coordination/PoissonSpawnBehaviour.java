package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Random;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import it.unige.portcommand.util.SimDeadlineWait;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poisson-distributed walk-in vessel arrival driver — lives on the HarbourMaster
 * since there is no separate PlayerAgent (design rule 2). Self-reschedules via
 * {@code WakerBehaviour.reset} with an exponentially-distributed sim-second gap
 * (the standard Poisson-process inter-arrival construction), drawn from ONE seeded
 * {@code RandomSource} sub-stream so a run replays deterministically from a save
 * seed.
 *
 * <p>Spawns through {@link AgentRoster#spawnWalkIn}, constructing a fresh
 * {@link RealNegotiationEngine} per vessel (task 15) — {@code roundLimit} from
 * {@link Settings#load()}, its personality-roll {@link java.util.Random} from an
 * independent {@code "nego-"+vesselId} sub-stream (kept separate from the
 * {@code "vessel-"+vesselId} belief-sampling stream {@code WalkInVesselAgent} already uses, so
 * neither draw sequence shifts the other).
 *
 * <p>No scenario config exists yet (task 23 owns that); the arrival rate is a
 * fixed default, isolated in one constant so wiring it to config later is a
 * one-line change.
 *
 * <p><b>Task 19 STEP 1 flood fix.</b> Two independent guards in {@link #onWake()},
 * checked every wake regardless of whether this wake spawns: (1) no spawn while
 * {@link SimClock#isPaused()} — "frozen" means explicitly paused, not merely
 * "hasn't advanced since last check", since the live wall-clock advancer is task
 * 24's and the clock never advances in today's build; gating on "hasn't advanced"
 * would make walk-ins spawn zero times, ever. (2) a hard cap of
 * {@link #MAX_ACTIVE_WALKINS} concurrent {@code WALK_IN} entries in the
 * HarbourMaster's own {@code activeVessels()} — this is what actually kills the
 * reported flood (hundreds of agents over a long session), independent of clock
 * semantics. The behaviour still reschedules itself on a skipped wake, so it keeps
 * checking rather than dying. The deeper "wall ticks assume sim-time lockstep"
 * mismatch stays filed for task 24, exactly as planning/19 says.
 *
 * <p><b>S14 (2026-10-11, playability/timing).</b> Three changes, all Moji's decisions after the
 * live playtest. (1) The rate drops from one arrival every two sim-hours to one every three —
 * about eight a game day. (2) A second limit: at most {@link #MAX_NEGOTIATING_WALKINS} walk-ins
 * may be at stage NEGOTIATING at once, so the player is never asked to hold three conversations;
 * the in-port limit is {@link #MAX_ACTIVE_WALKINS}. Scripted scenario spawns go through
 * {@code AgentRoster.spawnWalkIn} directly; since S17b (T1) the scenario engine holds one back
 * while two walk-ins are negotiating, and the in-port limit is still not theirs. (3) The gap is a
 * sim-time gap, so it is waited with {@link SimDeadlineWait}: a pause HOLDS the pending arrival
 * instead of dropping it (the old wake-while-paused threw the arrival away and drew a new gap),
 * and a speed change re-times it. With an untouched pace the wall wait is exactly what it was.
 */
public final class PoissonSpawnBehaviour extends WakerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PoissonSpawnBehaviour.class);
    /** S14: one walk-in every three sim-hours on average — about eight a game day (was 0.5). */
    public static final double DEFAULT_RATE_PER_SIM_HOUR = 1.0 / 3.0;
    private static final String CARGO_CLASS = "general_cargo"; // task 23: sample a real cargo mix
    /** At most this many walk-ins in port at once, whatever their stage (task 19). S17b: 3 -> 4 -
     * one per berth. With three, S14's count was about five walk-ins a game day: the in-port limit,
     * not the arrival rate, was what kept ships away. */
    public static final int MAX_ACTIVE_WALKINS = 4;
    /** S14: at most this many walk-ins NEGOTIATING at once — what a human can actually hold. */
    public static final int MAX_NEGOTIATING_WALKINS = 2;

    private final SimClock simClock;
    private final RandomSource randomSource;
    private final JadeAgentSpawner spawner;
    private final MarketHistoryArtifact marketHistory;
    private final Random draw;
    /** S14: the pace-aware wait for the next arrival's sim-time deadline. */
    private final SimDeadlineWait gap;
    private int spawnCount;

    public PoissonSpawnBehaviour(Agent agent, SimClock simClock, RandomSource randomSource,
                                 JadeAgentSpawner spawner, MarketHistoryArtifact marketHistory) {
        this(agent, simClock, randomSource, spawner, marketHistory, 0);
    }

    /**
     * Task 22 restore: continue the walk-in id sequence from a save's persisted count, so
     * post-load spawns get fresh {@code WALKIN-<n>} ids (and fresh per-vessel RNG stream
     * names) instead of colliding with restored vessels.
     */
    public PoissonSpawnBehaviour(Agent agent, SimClock simClock, RandomSource randomSource,
                                 JadeAgentSpawner spawner, MarketHistoryArtifact marketHistory,
                                 int startingSpawnCount) {
        this(agent, simClock, randomSource, spawner, marketHistory,
                randomSource.forStream("poisson-spawn"), startingSpawnCount);
    }

    private PoissonSpawnBehaviour(Agent agent, SimClock simClock, RandomSource randomSource,
                                  JadeAgentSpawner spawner, MarketHistoryArtifact marketHistory, Random draw,
                                  int startingSpawnCount) {
        this(agent, simClock, randomSource, spawner, marketHistory, draw, startingSpawnCount,
                new SimDeadlineWait(simClock));
    }

    private PoissonSpawnBehaviour(Agent agent, SimClock simClock, RandomSource randomSource,
                                  JadeAgentSpawner spawner, MarketHistoryArtifact marketHistory, Random draw,
                                  int startingSpawnCount, SimDeadlineWait gap) {
        super(agent, gap.armUntil(simClock.nowSimMillis() + nextGapSimMillis(draw)));
        this.gap = gap;
        this.simClock = simClock;
        this.randomSource = randomSource;
        this.spawner = spawner;
        this.marketHistory = marketHistory;
        this.draw = draw;
        this.spawnCount = startingSpawnCount;
    }

    /** The walk-in id counter — persisted by task 22's snapshot. */
    public int spawnCount() {
        return spawnCount;
    }

    @Override
    protected void onWake() {
        if (simClock.isPaused()) {
            // S14: a pause holds the pending arrival — it is neither spawned nor thrown away.
            reset(SimDeadlineWait.HOLD_WALL_MS);
            return;
        }
        long more = gap.next();
        if (more > 0) {
            reset(more); // still short of the arrival's sim time (mid-wait, or re-paced)
            return;
        }
        if (canSpawnNow()) {
            spawnWalkIn();
        }
        reset(gap.armUntil(simClock.nowSimMillis() + nextGapSimMillis(draw)));
    }

    /** @return true iff the clock isn't paused, no scenario scripted wave is firing
     * (task 23: Poisson traffic is suppressed until the last scripted event, and because
     * the wave is derived from events-minus-firedEventIds the suppression window survives
     * a mid-scenario save/load), fewer than {@link #MAX_ACTIVE_WALKINS} walk-ins are
     * currently active, and (S14) fewer than {@link #MAX_NEGOTIATING_WALKINS} of them are
     * still negotiating. Public (not private) so {@code PoissonSpawnBehaviourIT}
     * (parent {@code behaviours} test package — a test in this sub-package would shadow
     * {@code BehaviourCatalogueTest}'s scan) can drive it directly without needing a full
     * spawn round-trip to observe the outcome. */
    public boolean canSpawnNow() {
        if (simClock.isPaused()) {
            return false;
        }
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        if (hm.scriptedWaveActive()) {
            return false;
        }
        // S17b: both counts include a walk-in that was spawned a moment ago and has not hailed yet.
        return hm.walkInsInPort() < MAX_ACTIVE_WALKINS
                && hm.negotiatingWalkIns() < MAX_NEGOTIATING_WALKINS;
    }

    /**
     * S17b (playtest 2, PT8): "Call next ship". Brings the next walk-in in NOW instead of when its
     * arrival falls due, and starts a fresh gap from this moment - the ship that was on its way is
     * the one that came. Refused, with the reason for the player, while the game is paused, while
     * a scenario's own ships are still arriving, and at either limit. HarbourMaster thread only.
     *
     * @return {@code null} if a walk-in was called in, otherwise the sentence for the strip
     */
    public String callNext() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        if (simClock.isPaused()) {
            return "The game is paused \u2014 resume it to call a ship in.";
        }
        if (hm.scriptedWaveActive()) {
            return "The scenario's own ships are still arriving \u2014 no extra ship can be called yet.";
        }
        if (hm.negotiatingWalkIns() >= MAX_NEGOTIATING_WALKINS) {
            return "Two ships are already waiting for your answer \u2014 answer one first.";
        }
        if (hm.walkInsInPort() >= MAX_ACTIVE_WALKINS) {
            return "Four walk-in ships are in port already \u2014 wait for one to leave.";
        }
        spawnWalkIn();
        reset(gap.armUntil(simClock.nowSimMillis() + nextGapSimMillis(draw)));
        return null;
    }

    /** Public for the same reason {@link #canSpawnNow()} is (S8): {@code WalkInNamePoolIT}
     * drives single spawns directly rather than waiting out exponential gaps. Production's
     * callers are {@link #onWake()}, behind the {@code canSpawnNow} gate, and since S17b
     * {@link #callNext()}, which checks the same two limits itself to say which one refused. */
    public void spawnWalkIn() {
        List<String> types = List.copyOf(VesselTemplates.types());
        String vesselType = types.get(draw.nextInt(types.size()));
        VesselTemplate template = VesselTemplates.forType(vesselType);
        double draft = template.sampleDraft(draw);
        double length = template.sampleLength(draw);
        int tonnage = template.sampleTonnage(draw);

        String vesselId = "WALKIN-" + (++spawnCount);
        // S8 (GOAL 3, ADR-17): the display name is drawn HERE, once, and persisted verbatim —
        // never re-derived (saveSeedAt evolves the seed, so re-derivation would rename every
        // vessel across a load). Offset per session, ordinal = the persisted spawn counter.
        String vesselName = it.unige.portcommand.core.NamePool.name(
                ((HarbourMasterAgent) myAgent).namePoolOffset(), spawnCount);
        VesselSpec spec = new VesselSpec(vesselId, vesselType, draft, length, tonnage,
                CARGO_CLASS, simClock.nowSimMillis(), vesselName);

        RealNegotiationEngine engine = new RealNegotiationEngine(
                Settings.load().roundLimit(), randomSource.forStream("nego-" + vesselId));
        AgentRoster.spawnWalkIn(spawner, spec, simClock, marketHistory, randomSource, engine,
                ((HarbourMasterAgent) myAgent).directory());
        ((HarbourMasterAgent) myAgent).walkInSpawned(vesselId);
        log.info("Poisson spawn: {} ({}) draft={} length={} tonnage={}",
                vesselId, vesselType, draft, length, tonnage);
    }

    /**
     * Exponential inter-arrival gap for a Poisson process at {@link #DEFAULT_RATE_PER_SIM_HOUR},
     * in whole sim-seconds expressed as sim-millis (at least one sim-second). Public and static
     * so the rate is testable from the draw alone, with no agent and no clock — from the parent
     * {@code behaviours} test package, for the same reason {@link #canSpawnNow()} is public.
     */
    public static long nextGapSimMillis(Random draw) {
        double u = draw.nextDouble();
        double gapSimHours = -Math.log(1 - u) / DEFAULT_RATE_PER_SIM_HOUR;
        long gapSimSeconds = Math.round(gapSimHours * 3600.0);
        return Math.max(1L, gapSimSeconds) * 1_000L;
    }
}
