package it.unige.portcommand.behaviours;

import java.util.List;

import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code onSimTick} directly (same-package protected access; the JADE
 * wall-timer that calls it in production is not under test — the established
 * {@code SimTickerBehaviour} split). A {@code null} agent is fine: the
 * behaviour never touches {@code myAgent}.
 */
class SimClockTickPublisherBehaviourTest {

    private SimClock clock;
    private EventBus bus;
    private SimClockTickPublisherBehaviour publisher;

    @BeforeEach
    void setUp() {
        clock = new SimClock(300);
        bus = new EventBus();
        publisher = new SimClockTickPublisherBehaviour(null, clock, bus);
    }

    private List<SimClockTickEvent> ticks() {
        return EventBusProbe.published(bus).stream()
                .filter(SimClockTickEvent.class::isInstance)
                .map(SimClockTickEvent.class::cast)
                .toList();
    }

    @Test
    void publishesAConsistentSnapshotOfTheCurrentSimInstant() {
        clock.advance(250); // 250 real-ms @300 = 72,000 sim-ms = 00:01:12
        publisher.onSimTick();

        List<SimClockTickEvent> ticks = ticks();
        assertEquals(1, ticks.size());
        SimClockTickEvent tick = ticks.get(0);
        assertEquals(72_000L, tick.simMillis());
        assertEquals(1, tick.gameDay());
        assertEquals(0, tick.simHour());
        assertEquals(1, tick.simMinute());
    }

    @Test
    void staysSilentWhileTheClockIsFrozen() {
        clock.advance(250);
        publisher.onSimTick();
        publisher.onSimTick(); // wall timer fires again, sim time unchanged (pause / no advancer)
        publisher.onSimTick();

        assertEquals(1, ticks().size(), "identical instants are never republished");
    }

    @Test
    void resumesPublishingOnceTimeMovesAgain() {
        clock.advance(250);
        publisher.onSimTick();
        clock.pause();
        clock.advance(250); // no-op while paused
        publisher.onSimTick();
        clock.resume();
        clock.advance(250);
        publisher.onSimTick();

        assertEquals(2, ticks().size());
        assertEquals(144_000L, ticks().get(1).simMillis());
    }

    @Test
    void firesOnTheVeryFirstTickOfARun() {
        publisher.onSimTick(); // t=0 — the HUD's first "Day 1 00:00" render

        assertEquals(1, ticks().size());
        assertEquals(0L, ticks().get(0).simMillis());
        assertTrue(ticks().get(0).gameDay() == 1);
    }
}
