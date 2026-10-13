package it.unige.portcommand.behaviours;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.PortStatusEvent.BerthRow;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code onSimTick} directly (same-package protected access; the JADE
 * wall-timer that calls it in production is not under test — the established
 * {@code SimTickerBehaviour} split). A {@code null} agent is fine: the
 * behaviour never touches {@code myAgent}.
 *
 * <p>S15 (2026-10-12): the second half of the file covers the port status the same tick now
 * carries — published when it changed or when asked for, independently of the clock, and
 * never at the price of the hosting agent. The cases above it are the pre-S15 ones, untouched,
 * and still build the publisher through the three-argument (clock-only) constructor.
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

    private List<PortStatusEvent> statuses() {
        return EventBusProbe.published(bus).stream()
                .filter(PortStatusEvent.class::isInstance)
                .map(PortStatusEvent.class::cast)
                .toList();
    }

    /** A fresh instance on every call, so "unchanged" below means EQUAL, never the same object. */
    private static PortStatusEvent emptyPort() {
        return new PortStatusEvent(1, List.of(BerthRow.free("berth_1"), BerthRow.free("berth_2"),
                BerthRow.free("berth_3"), BerthRow.free("berth_4")), List.of());
    }

    /** The empty port with one walk-in docked at berth_1 and {@code queued} waiting. */
    private static PortStatusEvent busyPort(String... queued) {
        return new PortStatusEvent(1, List.of(
                new BerthRow("berth_1", "WALKIN-1", "Stella Maris", VesselTracking.Channel.WALK_IN,
                        VesselTracking.Stage.DOCKED, 50_400_000L, false),
                BerthRow.free("berth_2"), BerthRow.free("berth_3"), BerthRow.free("berth_4")),
                List.of(queued));
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

    // ==================== S15 (2026-10-12): the port status on the same tick ====================

    @Test
    void portStatusIsPublishedOnTheFirstTick() {
        SimClockTickPublisherBehaviour withPort = new SimClockTickPublisherBehaviour(null, clock, bus,
                SimClockTickPublisherBehaviourTest::emptyPort);

        withPort.onSimTick();

        assertEquals(List.of(emptyPort()), statuses(), "a new world's first tick announces the port");
        List<Event> published = EventBusProbe.published(bus);
        assertEquals(2, published.size(), "one clock tick and one port status, nothing else");
        assertInstanceOf(SimClockTickEvent.class, published.get(0), "the clock half goes first");
        assertInstanceOf(PortStatusEvent.class, published.get(1));
    }

    @Test
    void anUnchangedPortIsPublishedOnce_howeverOftenTheTickFires() {
        AtomicInteger asked = new AtomicInteger();
        SimClockTickPublisherBehaviour withPort = new SimClockTickPublisherBehaviour(null, clock, bus, () -> {
            asked.incrementAndGet();
            return emptyPort(); // equal to the last one, but never the same object
        });

        for (int tick = 0; tick < 3; tick++) {
            clock.advance(250);
            withPort.onSimTick();
        }

        assertEquals(3, ticks().size(), "fixture: three distinct instants, three clock ticks");
        assertEquals(3, asked.get(), "the port is looked at on every tick");
        assertEquals(1, statuses().size(), "but an EQUAL snapshot is never republished");
    }

    @Test
    void everyChangeIsPublishedOnTheTickThatSeesIt() {
        AtomicReference<PortStatusEvent> port = new AtomicReference<>(emptyPort());
        SimClockTickPublisherBehaviour withPort = new SimClockTickPublisherBehaviour(null, clock, bus, port::get);

        withPort.onSimTick();
        port.set(busyPort());
        withPort.onSimTick();
        withPort.onSimTick(); // nothing new
        port.set(busyPort("Aurora"));
        withPort.onSimTick();
        port.set(emptyPort()); // back to an earlier state: still a change from the LAST one published
        withPort.onSimTick();

        assertEquals(List.of(emptyPort(), busyPort(), busyPort("Aurora"), emptyPort()), statuses(),
                "one event per real change, compared against the last one published");
    }

    /**
     * The reason the status half is not gated on the clock: the chat stays usable while the game
     * is paused (S14), so a deal can close — and a vessel join the queue — with sim time frozen.
     * The old early {@code return} on an unchanged clock would have hidden that until Resume.
     */
    @Test
    void aChangeWithTheClockPausedPublishesAPortStatusAndNoClockTick() {
        AtomicReference<PortStatusEvent> port = new AtomicReference<>(emptyPort());
        SimClockTickPublisherBehaviour withPort = new SimClockTickPublisherBehaviour(null, clock, bus, port::get);
        clock.advance(250);
        withPort.onSimTick();
        assertEquals(1, ticks().size(), "fixture: one clock tick before the pause");
        assertEquals(1, statuses().size(), "fixture: one port status before the pause");

        clock.pause();
        clock.advance(250); // no-op while paused
        port.set(busyPort("Aurora"));
        int before = EventBusProbe.published(bus).size();
        withPort.onSimTick();

        List<Event> afterTheChange = EventBusProbe.published(bus);
        assertEquals(before + 1, afterTheChange.size(), "exactly one event for the one change");
        assertEquals(busyPort("Aurora"), afterTheChange.get(before), "and it is the new port status");
        assertEquals(1, ticks().size(), "a frozen clock publishes no SimClockTickEvent");

        withPort.onSimTick(); // still paused, nothing new
        assertEquals(before + 1, EventBusProbe.published(bus).size(),
                "a paused, unchanged port is a silent bus — the pre-S15 guarantee still holds");
    }

    @Test
    void requestStatusForcesExactlyOneRepublish() {
        SimClockTickPublisherBehaviour withPort = new SimClockTickPublisherBehaviour(null, clock, bus,
                SimClockTickPublisherBehaviourTest::emptyPort);
        withPort.onSimTick();
        withPort.onSimTick();
        assertEquals(1, statuses().size(), "fixture: an unchanged port is silent");

        withPort.requestStatus();
        withPort.requestStatus(); // a second request before the tick asks for nothing more
        withPort.onSimTick();

        assertEquals(2, statuses().size(), "the request is answered on the next tick although nothing changed");
        assertEquals(statuses().get(0), statuses().get(1), "the re-announce repeats the unchanged snapshot");

        withPort.onSimTick();
        withPort.onSimTick();
        assertEquals(2, statuses().size(), "one re-announce, then silence again");
        assertEquals(1, ticks().size(), "a request never touches the clock half (sim time stood still throughout)");
    }

    /**
     * JADE kills an agent whose behaviour throws, and the hosting agent is the HarbourMaster —
     * so a snapshot that blows up may cost a table refresh, never the hub.
     */
    @Test
    void aThrowingSupplierDoesNotEscape_andTheClockTickIsStillPublished() {
        SimClockTickPublisherBehaviour broken = new SimClockTickPublisherBehaviour(null, clock, bus, () -> {
            throw new IllegalStateException("snapshot blew up");
        });
        clock.advance(250);

        assertDoesNotThrow(() -> broken.onSimTick(), "a failing snapshot must never propagate out of the tick");

        assertEquals(1, ticks().size(), "the clock half is published regardless");
        assertEquals(72_000L, ticks().get(0).simMillis());
        assertTrue(statuses().isEmpty(), "and nothing is published for the port");

        clock.advance(250);
        assertDoesNotThrow(() -> broken.onSimTick(), "nor on any later tick");
        assertEquals(2, ticks().size());
        assertTrue(statuses().isEmpty());
    }

    @Test
    void aSupplierThatRecoversPublishesAgain_andARequestMadeDuringTheFailureIsNotLost() {
        AtomicBoolean failing = new AtomicBoolean(false);
        SimClockTickPublisherBehaviour flaky = new SimClockTickPublisherBehaviour(null, clock, bus, () -> {
            if (failing.get()) {
                throw new IllegalStateException("snapshot blew up");
            }
            return emptyPort();
        });
        flaky.onSimTick();
        assertEquals(1, statuses().size(), "fixture: the first status went out");

        failing.set(true);
        flaky.requestStatus();
        flaky.onSimTick();
        assertEquals(1, statuses().size(), "a failed snapshot publishes nothing");

        failing.set(false);
        flaky.onSimTick();
        assertEquals(2, statuses().size(),
                "the request survives the failed tick and is served by the first one that works, "
                        + "although the port itself is unchanged");
        flaky.onSimTick();
        assertEquals(2, statuses().size(), "and it is served exactly once");
    }

    @Test
    void aSupplierWithNothingToReportPublishesNothing() {
        AtomicReference<PortStatusEvent> port = new AtomicReference<>();
        SimClockTickPublisherBehaviour withPort = new SimClockTickPublisherBehaviour(null, clock, bus, port::get);
        withPort.requestStatus();

        assertDoesNotThrow(() -> withPort.onSimTick(), "a null snapshot is 'nothing to say', not an error");
        assertTrue(statuses().isEmpty());
        assertEquals(1, ticks().size());

        port.set(emptyPort());
        withPort.onSimTick();
        withPort.onSimTick();
        assertEquals(1, statuses().size(), "the first real snapshot goes out once");
    }

    @Test
    void theThreeArgumentConstructorStaysClockOnly() {
        publisher.requestStatus(); // harmless without a port
        publisher.onSimTick();

        assertEquals(1, ticks().size());
        assertTrue(statuses().isEmpty(), "no supplier, no port status — the pre-S15 publisher, unchanged");
    }
}
