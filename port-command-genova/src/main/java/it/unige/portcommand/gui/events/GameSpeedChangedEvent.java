package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The game-speed multiplier now in force (S14): published AFTER it has been applied to the
 * {@code SimClock} and written to the live {@code Settings} — by {@code GameSpeedControl} when the
 * player picks a speed, and by {@code GameSession} when a new game or a load puts one in force.
 *
 * <p>A notification, not a request: nothing applies a speed by listening to this. The HUD toggles
 * and the Game &#9656; Speed menu subscribe so that whichever control was NOT used shows the same
 * choice, and so both follow a load.
 *
 * @param speed the multiplier in force — one of {@code Settings.GAME_SPEEDS}
 */
public record GameSpeedChangedEvent(int speed) implements Event {
}
