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
 */
public final class GameSpeedControl {

    private final SimClock simClock;
    private final EventBus eventBus;
    private final BooleanSupplier locked;

    /**
     * @param locked true while a speed change must be refused — a load is replacing the world, and
     *               the loader applies the SAVED speed (same guard as the Settings dialog)
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

    /**
     * Applies {@code requested} unless it is not on offer or the control is locked. Either way the
     * speed actually in force is re-announced, so a control that already shows the refused choice
     * snaps back.
     *
     * @return whether {@code requested} is now in force
     */
    public boolean set(int requested) {
        boolean accepted = GameSpeedModel.options().contains(requested) && !locked.getAsBoolean();
        if (accepted) {
            simClock.setSpeedMultiplier(requested);
            Settings.applyLive(Settings.load().withGameSpeed(requested));
        }
        eventBus.publish(new GameSpeedChangedEvent(current()));
        return accepted;
    }
}
