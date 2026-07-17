package it.unige.portcommand.behaviours;

import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;

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
 */
public final class SimClockTickPublisherBehaviour extends SimTickerBehaviour {

    private static final long TICK_SIM_MILLIS = 60_000L; // one sim-minute
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final EventBus eventBus;
    private long lastPublishedSimMillis = -1L;

    public SimClockTickPublisherBehaviour(Agent agent, SimClock simClock, EventBus eventBus) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.eventBus = eventBus;
    }

    @Override
    protected void onSimTick() {
        long now = simClock().nowSimMillis();
        if (now == lastPublishedSimMillis) {
            return; // clock frozen (paused, or no advancer in this run) — stay silent
        }
        lastPublishedSimMillis = now;
        eventBus.publish(new SimClockTickEvent(now, SimClock.gameDayOf(now),
                (int) ((now / MILLIS_PER_HOUR) % 24), (int) ((now / MILLIS_PER_MINUTE) % 60)));
    }
}
