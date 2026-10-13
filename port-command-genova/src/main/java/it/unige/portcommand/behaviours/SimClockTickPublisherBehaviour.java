package it.unige.portcommand.behaviours;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single {@link SimClockTickEvent} publisher (task-03 Option B, landed by
 * task 24): one instance, hosted by the HarbourMaster, ticking every sim-minute.
 * No other code may publish that event — the HUD clock, comm-log freshness and
 * the negotiation countdown all pace off this one stream.
 *
 * <p>Lives in the UNCOUNTED parent {@code behaviours} package beside its base
 * class — deliberately outside the five catalogue-counted sub-packages, the
 * {@code CnpRetryBehaviour} precedent (INVARIANTS "Agents / behaviours": the 51
 * count is locked; subsystem behaviours belong to their owning tasks).
 *
 * <p>Publish-when-changed: {@code SimTickerBehaviour} fires on a fixed wall
 * cadence even while the game is paused, so this behaviour republishes nothing
 * while {@code nowSimMillis()} is unchanged — a paused game produces a silent
 * bus, not a 5&nbsp;Hz stream of identical ticks. All four event fields derive
 * from ONE {@code nowSimMillis()} read, so a tick can never mix two instants.
 *
 * <p><b>S15 (2026-10-12): the same tick also carries the port's status.</b> When a
 * {@link PortStatusEvent} supplier is given (the HarbourMaster passes its own
 * {@code portStatus()}), every tick asks it for the current snapshot and publishes it under
 * the same publish-when-changed rule: only when it differs from the last one published.
 * A second ticker was not added because this one already runs on the HarbourMaster's own
 * thread, which is the only thread allowed to read the berth wait queue — and because the
 * catalogue count must not move.
 *
 * <p>The two halves are independent ON PURPOSE. The clock half is silent while sim time
 * stands still; the status half is not gated on the clock at all, because the port changes
 * while the clock is paused — the chat stays usable during a pause (S14), so a deal can
 * close and queue a vessel with the clock frozen, and the Port table has to show it. The
 * wall cadence keeps firing through a pause, so that change is published on the next tick.
 * A quiet port is still a silent bus: one event per real change, never one per tick (which
 * would also halve the stretch of history the bus's bounded audit log keeps for the fast-day
 * integration tests that read it).
 *
 * <p>{@link #requestStatus()} asks for one re-announce: the bus has no replay, so a window
 * that subscribes after the world booted, or a freshly loaded game whose first tick beat the
 * GUI's reset, would otherwise show an empty table until something changed.
 *
 * <p>A supplier that throws never escapes {@link #onSimTick()}: JADE kills an agent whose
 * behaviour throws, and this agent is the HarbourMaster. A failed snapshot costs one table
 * refresh; it must not cost the hub.
 */
public final class SimClockTickPublisherBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(SimClockTickPublisherBehaviour.class);

    private static final long TICK_SIM_MILLIS = 60_000L; // one sim-minute
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final EventBus eventBus;
    /** S15: the port-status source, or {@code null} for a clock-only publisher (unit tests). */
    private final Supplier<PortStatusEvent> portStatus;
    /** S15: set from any thread by {@link #requestStatus()}, consumed on the agent thread. */
    private final AtomicBoolean statusRequested = new AtomicBoolean(false);
    private long lastPublishedSimMillis = -1L;
    /** S15: the last status published, the change key. Agent-thread only. {@code null} until the
     * first one goes out, so a new world's first tick always announces. */
    private PortStatusEvent lastStatus;
    /** S15: one warning per run of failures, not one per tick — see {@link #publishStatus()}. */
    private boolean statusFailureLogged;

    /** Clock only — no port status. Kept so the publisher is constructible without a port. */
    public SimClockTickPublisherBehaviour(Agent agent, SimClock simClock, EventBus eventBus) {
        this(agent, simClock, eventBus, null);
    }

    /**
     * S15 (2026-10-12).
     *
     * @param portStatus builds the current port snapshot; called on the hosting agent's thread,
     *                   once per tick. {@code null} publishes the clock only.
     */
    public SimClockTickPublisherBehaviour(Agent agent, SimClock simClock, EventBus eventBus,
                                          Supplier<PortStatusEvent> portStatus) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.eventBus = eventBus;
        this.portStatus = portStatus;
    }

    /**
     * S15: asks for the port status to be published on the next tick even if nothing changed.
     * Safe from any thread — it only raises a flag; the snapshot itself is still built and
     * published on the agent thread. Several requests before one tick collapse into one
     * re-announce. A no-op on a clock-only publisher.
     */
    public void requestStatus() {
        statusRequested.set(true);
    }

    @Override
    protected void onSimTick() {
        publishClock();
        publishStatus();
    }

    private void publishClock() {
        long now = simClock().nowSimMillis();
        if (now == lastPublishedSimMillis) {
            return; // clock frozen (paused, or no advancer in this run) — stay silent
        }
        lastPublishedSimMillis = now;
        eventBus.publish(new SimClockTickEvent(now, SimClock.gameDayOf(now),
                (int) ((now / MILLIS_PER_HOUR) % 24), (int) ((now / MILLIS_PER_MINUTE) % 60)));
    }

    /**
     * S15: the status half — independent of {@link #publishClock()}, see the class javadoc.
     *
     * <p>The request flag is taken BEFORE the snapshot is built: a request that lands while the
     * snapshot is being built is then still pending for the next tick, never swallowed by a
     * snapshot that predates it. A request that could not be served (the supplier threw, or
     * returned nothing) is put back, so the re-announce happens on the first tick that works.
     */
    private void publishStatus() {
        if (portStatus == null) {
            return;
        }
        boolean requested = statusRequested.getAndSet(false);
        try {
            PortStatusEvent status = portStatus.get();
            if (status == null) {
                if (requested) {
                    statusRequested.set(true);
                }
                return;
            }
            statusFailureLogged = false;
            if (requested || !status.equals(lastStatus)) {
                lastStatus = status;
                eventBus.publish(status);
            }
        } catch (RuntimeException e) {
            if (requested) {
                statusRequested.set(true);
            }
            // Never rethrown: JADE would kill the HarbourMaster. And logged once per run of
            // failures, not per tick — a snapshot that keeps failing would otherwise write a
            // stack trace several times a second for the rest of the game.
            if (!statusFailureLogged) {
                statusFailureLogged = true;
                log.warn("port status snapshot failed - the Port table keeps its last state until one succeeds",
                        e);
            }
        }
    }
}
