package it.unige.portcommand.scenario;

import java.util.Map;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.persistence.AgentDirectory;
import it.unige.portcommand.scenario.events.ScriptedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;

/**
 * Everything a {@link ScriptedEvent#apply(GameContext)} may touch (Command pattern —
 * planning/23 "each event is a Runnable against GameContext"). Events run on the
 * HarbourMaster's agent thread inside {@link ScriptedEventBehaviour} — never on the
 * EDT; GUI effects go through {@link EventBus} only.
 *
 * <p>Deliberately carries the HarbourMaster's LIVE contract map rather than the agent
 * itself: it is the only HM state the engine touches, and the narrower seam lets the
 * queue mechanics be unit-tested against a plain map (no container).
 *
 * @param contracts     the HarbourMaster's live contract map (global + scenario-local +
 *                      the daily stream's day-qualified clones — the engine registers into it)
 * @param simClock      shared sim clock
 * @param spawner       agent spawner for vessel-creation (same path as the Poisson spawner)
 * @param marketHistory shared deal history (vessel spawn arg)
 * @param randomSource  the seeded master RNG (walk-in belief/dimension sub-streams)
 * @param eventBus      the GUI bus (notifications, weather overrides, tutorial steps)
 * @param directory     live-agent registry (nullable in stub harnesses; production always
 *                      threads it so spawned vessels register for the save snapshot)
 */
public record GameContext(
        Map<String, ServiceContract> contracts,
        SimClock simClock,
        JadeAgentSpawner spawner,
        MarketHistoryArtifact marketHistory,
        RandomSource randomSource,
        EventBus eventBus,
        AgentDirectory directory) {
}
