package it.unige.portcommand.behaviours;

import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugWeatherOverrideBehaviourTest {

    @Test
    void aPublishedOverrideReplacesTheSharedSnapshotVerbatim() {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        clock.advance(1_000);
        AtomicReference<WeatherSnapshot> current =
                new AtomicReference<>(new WeatherSnapshot(18, "good", 0.5, "sunny", 0L));
        DebugWeatherOverrideBehaviour behaviour =
                new DebugWeatherOverrideBehaviour(new Agent(), current, clock, bus);
        behaviour.action(); // subscribe-once

        bus.publish(new DebugWeatherEvent(42, "poor", 5.0, "stormy"));

        WeatherSnapshot forced = current.get();
        assertEquals(42, forced.wind());
        assertEquals("poor", forced.visibility());
        assertEquals(5.0, forced.swell(), 0.001);
        assertEquals("stormy", forced.state());
        assertEquals(clock.nowSimMillis(), forced.simTime(), "stamped at the current sim instant");
    }

    @Test
    void theHandlerSurvivesTheOneShotMyAgentNulling() {
        // The task-19 dispatch-NPE class: JADE nulls myAgent after a OneShot's action(); the
        // handler must touch only captured finals.
        EventBus bus = new EventBus();
        AtomicReference<WeatherSnapshot> current = new AtomicReference<>();
        DebugWeatherOverrideBehaviour behaviour =
                new DebugWeatherOverrideBehaviour(new Agent(), current, new SimClock(300), bus);
        behaviour.action();
        behaviour.setAgent(null); // what Agent.removeBehaviour really does post-action

        bus.publish(new DebugWeatherEvent(15, "good", 0.5, "sunny"));

        assertEquals(15, current.get().wind(), "the bus handler must not depend on myAgent");
    }
}
