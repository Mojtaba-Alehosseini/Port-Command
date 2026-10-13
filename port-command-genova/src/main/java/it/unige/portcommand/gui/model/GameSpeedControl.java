package it.unige.portcommand.gui.model;

import java.util.function.BooleanSupplier;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;

/**
 * The one place the player's speed choice is applied (S14): the game clock is re-paced, the live
 * {@link Settings} record carries the choice into the next save, and a
 * {@link GameSpeedChangedEvent} tells every control what is now in force. No Swing/AWT import —
 * the HUD toggles, the Game menu and the coming redesign all call {@link #set(int)}.
 *
 * <p>Speed changes game time only. Negotiation patience is conversation time (ADR-18) and never
 * sees it: {@code SimClock} applies the multiplier after forking real time to the conversation
 * clock.
 *
 * <p><b>S16 (2026-10-13): the scenario-script lock.</b> A scenario's scripted events are written
 * for ×1 — a few game minutes apart on purpose (the storm gives the tanker thirty game seconds to
 * hire its tugs before the wind turns) — and at ×4 the player has a quarter of the real time the
 * script assumed to read a hail and answer it. So while scripted events are still to come the
 * speed is held at ×1: {@link #setScriptPending} engages and releases the lock, and {@link #set}
 * refuses while it is engaged. The flag lives here rather than in the window because this class
 * can be tested with no window ({@code GameSpeedControlTest}); the window only forwards the
 * {@code ScriptedWaveEvent} it hears on the bus. The controls read {@link #scriptPending()} to
 * show themselves disabled, and re-read it on every {@link GameSpeedChangedEvent} — which is why
 * a change of the lock is announced with one even when the speed itself did not move.
 */
public final class GameSpeedControl {

    private final SimClock simClock;
    private final EventBus eventBus;
    private final BooleanSupplier locked;
    /** S16: scripted scenario events are still to come. Written on the EDT (the window's bus
     * subscription); volatile so a reader on another thread can never see a stale lock. */
    private volatile boolean scriptPending;

    /**
     * @param locked true while a speed change must be refused for a reason this class cannot see
     *               — a load is replacing the world, and the loader applies the SAVED speed (same
     *               guard as the Settings dialog); or no game is running at all. The script lock
     *               is not part of it: see {@link #setScriptPending}.
     */
    public GameSpeedControl(SimClock simClock, EventBus eventBus, BooleanSupplier locked) {
        this.simClock = simClock;
        this.eventBus = eventBus;
        this.locked = locked;
    }

    /** The speed in force, as one of the offered choices. */
    public int current() {
        return GameSpeedModel.normalise(simClock.speedMultiplier());
    }

    /** S16: whether the scenario's script currently holds the speed at ×1. */
    public boolean scriptPending() {
        return scriptPending;
    }

    /**
     * Applies {@code requested} unless it is not on offer, the scenario's script holds the speed
     * (S16), or the control is locked. Either way the speed actually in force is re-announced, so
     * a control that already shows the refused choice snaps back.
     *
     * @return whether {@code requested} is now in force
     */
    public boolean set(int requested) {
        boolean accepted = GameSpeedModel.options().contains(requested) && !scriptPending
                && !locked.getAsBoolean();
        if (accepted) {
            simClock.setSpeedMultiplier(requested);
            Settings.applyLive(Settings.load().withGameSpeed(requested));
        }
        eventBus.publish(new GameSpeedChangedEvent(current()));
        return accepted;
    }

    /**
     * S16: engages ({@code true}) or releases ({@code false}) the scenario-script lock.
     *
     * <p>Engaging puts ×1 in force FIRST if the game is running faster. {@code GameSession}
     * itself boots a world whose script is pending at ×1 (S16), so this is the backstop, for a
     * world that reaches the lock above ×1 by a route nobody thought of. It deliberately does
     * not ask the {@code locked} supplier: the lock can arrive with a boot that is still
     * LOADING, and a speed left above ×1 behind disabled controls could not be put right by the
     * player.
     *
     * <p>A change — of the lock, or of the speed by the drop to ×1 — is announced with a
     * {@link GameSpeedChangedEvent}, the controls' cue to re-read {@link #scriptPending()}. A
     * call that changes nothing is silent ({@code GameSession} reports "nothing pending" on every
     * boot of a game that has no script).
     */
    public void setScriptPending(boolean pending) {
        boolean changed = pending != scriptPending;
        int base = GameSpeedModel.base();
        if (pending && simClock.speedMultiplier() != base) {
            simClock.setSpeedMultiplier(base);
            Settings.applyLive(Settings.load().withGameSpeed(base));
            changed = true;
        }
        scriptPending = pending;
        if (changed) {
            eventBus.publish(new GameSpeedChangedEvent(current()));
        }
    }
}
