package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The player pressed "Call next ship" (S17b, playtest 2 item PT8): the next walk-in should hail
 * now instead of whenever its arrival falls due. The HarbourMaster answers on its own thread —
 * it brings the ship in if there is room (at most two walk-ins negotiating, at most four in port)
 * and no scenario script is still running, and otherwise says in the notification strip why not.
 */
public record CallNextShipEvent() implements Event {
}
