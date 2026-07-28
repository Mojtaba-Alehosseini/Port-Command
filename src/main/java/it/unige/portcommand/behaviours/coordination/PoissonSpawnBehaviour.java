package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Random;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poisson-distributed walk-in vessel arrival driver — lives on the HarbourMaster
 * since there is no separate PlayerAgent (CLAUDE.md rule 2). Self-reschedules via
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
 */
public final class PoissonSpawnBehaviour extends WakerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PoissonSpawnBehaviour.class);
    private static final double DEFAULT_RATE_PER_SIM_HOUR = 0.5; // task 23: source from scenario config
    private static final String CARGO_CLASS = "general_cargo"; // task 23: sample a real cargo mix
    private static final int MAX_ACTIVE_WALKINS = 3; // task 19 STEP 1: caps the reported spawn flood

    private final SimClock simClock;
    private final RandomSource randomSource;
    private final JadeAgentSpawner spawner;
    private final MarketHistoryArtifact marketHistory;
    private final Random draw;
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
        super(agent, nextGapWallMs(simClock, draw));
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
        if (canSpawnNow()) {
            spawnWalkIn();
        }
        reset(nextGapWallMs(simClock, draw));
    }

    /** @return true iff the clock isn't paused, no scenario scripted wave is firing
     * (task 23: Poisson traffic is suppressed until the last scripted event, and because
     * the wave is derived from events-minus-firedEventIds the suppression window survives
     * a mid-scenario save/load), and fewer than {@link #MAX_ACTIVE_WALKINS} walk-ins are
     * currently active. Public (not private) so {@code PoissonSpawnBehaviourIT}
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
        long activeWalkIns = hm.activeVessels().values().stream()
                .filter(v -> v.channel() == Channel.WALK_IN)
                .count();
        return activeWalkIns < MAX_ACTIVE_WALKINS;
    }

    private void spawnWalkIn() {
        List<String> types = List.copyOf(VesselTemplates.types());
        String vesselType = types.get(draw.nextInt(types.size()));
        VesselTemplate template = VesselTemplates.forType(vesselType);
        double draft = template.sampleDraft(draw);
        double length = template.sampleLength(draw);
        int tonnage = template.sampleTonnage(draw);

        String vesselId = "WALKIN-" + (++spawnCount);
        VesselSpec spec = new VesselSpec(vesselId, vesselType, draft, length, tonnage,
                CARGO_CLASS, simClock.nowSimMillis());

        RealNegotiationEngine engine = new RealNegotiationEngine(
                Settings.load().roundLimit(), randomSource.forStream("nego-" + vesselId));
        AgentRoster.spawnWalkIn(spawner, spec, simClock, marketHistory, randomSource, engine,
                ((HarbourMasterAgent) myAgent).directory());
        log.info("Poisson spawn: {} ({}) draft={} length={} tonnage={}",
                vesselId, vesselType, draft, length, tonnage);
    }

    /** Exponential inter-arrival gap for a Poisson process at {@link #DEFAULT_RATE_PER_SIM_HOUR}. */
    private static long nextGapWallMs(SimClock simClock, Random draw) {
        double u = draw.nextDouble();
        double gapSimHours = -Math.log(1 - u) / DEFAULT_RATE_PER_SIM_HOUR;
        long gapSimSeconds = Math.round(gapSimHours * 3600.0);
        return simClock.simSecondsToWallMs(Math.max(1L, gapSimSeconds));
    }
}
