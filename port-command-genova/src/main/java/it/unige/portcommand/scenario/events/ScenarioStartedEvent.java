package it.unige.portcommand.scenario.events;

import it.unige.portcommand.scenario.Scenario;
import it.unige.portcommand.util.Event;

/**
 * Published on the EventBus when a scenario boots ({@code GameSession.startScenario})
 * and on a same-scenario restart (task 24) — a HUD/comm-log banner subscribes
 * (planning/23 file table). Not a {@link it.unige.portcommand.scenario.ScriptedEvent}:
 * this is a bus record about the scenario, not a timeline entry inside one.
 */
public record ScenarioStartedEvent(Scenario scenario) implements Event {
}
