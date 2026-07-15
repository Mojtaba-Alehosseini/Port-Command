package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Random;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.negotiation.NoOpNegotiationEngine;
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
 * since there is no separate PlayerAgent (design rule 2). Self-reschedules via
 * {@code WakerBehaviour.reset} with an exponentially-distributed sim-second gap
 * (the standard Poisson-process inter-arrival construction), drawn from ONE seeded
 * {@code RandomSource} sub-stream so a run replays deterministically from a save
 * seed.
 *
 * <p>Spawns through {@link AgentRoster#spawnWalkIn}, whose own javadoc already
 * documents the contract this behaviour relies on: "Production passes a
 * NoOpNegotiationEngine; tests pass a Mockito mock." The opening PROPOSE round
 * works today (this task); a counter-offer round starts working the moment task
 * 15's {@code RealNegotiationEngine} replaces that binding — not a gap introduced
 * here.
 *
 * <p>No scenario config exists yet (task 23 owns that); the arrival rate is a
 * fixed default, isolated in one constant so wiring it to config later is a
 * one-line change.
 */
public final class PoissonSpawnBehaviour extends WakerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PoissonSpawnBehaviour.class);
    private static final double DEFAULT_RATE_PER_SIM_HOUR = 0.5; // task 23: source from scenario config
    private static final String CARGO_CLASS = "general_cargo"; // task 23: sample a real cargo mix

    private final SimClock simClock;
    private final RandomSource randomSource;
    private final JadeAgentSpawner spawner;
    private final MarketHistoryArtifact marketHistory;
    private final Random draw;
    private int spawnCount;

    public PoissonSpawnBehaviour(Agent agent, SimClock simClock, RandomSource randomSource,
                                 JadeAgentSpawner spawner, MarketHistoryArtifact marketHistory) {
        this(agent, simClock, randomSource, spawner, marketHistory, randomSource.forStream("poisson-spawn"));
    }

    private PoissonSpawnBehaviour(Agent agent, SimClock simClock, RandomSource randomSource,
                                  JadeAgentSpawner spawner, MarketHistoryArtifact marketHistory, Random draw) {
        super(agent, nextGapWallMs(simClock, draw));
        this.simClock = simClock;
        this.randomSource = randomSource;
        this.spawner = spawner;
        this.marketHistory = marketHistory;
        this.draw = draw;
    }

    @Override
    protected void onWake() {
        spawnWalkIn();
        reset(nextGapWallMs(simClock, draw));
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

        AgentRoster.spawnWalkIn(spawner, spec, simClock, marketHistory, randomSource, new NoOpNegotiationEngine());
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
