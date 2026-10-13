package it.unige.portcommand.gui.events;

import java.util.List;
import java.util.Objects;

import it.unige.portcommand.util.Event;

/**
 * What the movement gate did about one ship (S17b, ADR-22, decision E1) — the data behind the
 * score's safety part. Published by {@code MovementControl}, on the HarbourMaster's thread, at the
 * two places a ship is told to move and the two places it is told it may not.
 *
 * <ul>
 *   <li>{@link Kind#BLOCKED} — the gate refused a movement: an arrival held outside, or a served
 *       ship kept at its berth. Once per ship per hold, not once per weather message. A deal the
 *       berth-fit rule refuses (R7) is not a movement and is not counted, and neither is anything
 *       said in a negotiation.</li>
 *   <li>{@link Kind#UNSAFE_MOVE} — a move order went out although the gate, asked again at that
 *       very moment, says the move is unsafe. The gate is there so that this never happens; the
 *       event exists so that it would be seen, and scored, if it ever did.</li>
 * </ul>
 *
 * @param kind          what happened
 * @param vesselId      the ship
 * @param ruleIds       the rules that refused the movement, e.g. {@code [R15, R19]}
 * @param simTimeMillis when
 */
public record MovementSafetyEvent(Kind kind, String vesselId, List<String> ruleIds, long simTimeMillis)
        implements Event {

    public enum Kind {
        BLOCKED,
        UNSAFE_MOVE
    }

    public MovementSafetyEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(vesselId, "vesselId");
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }
}
