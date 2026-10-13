package it.unige.portcommand.harbourmaster;

import java.util.Map;

import it.unige.portcommand.ontology.Position;

/**
 * Static {@code tug_id -> home-base Position} table — the tug-pier equivalent of
 * {@link BerthPositions}, and the single canonical source of the four tug bases. Extracted from
 * {@code AgentRoster}'s former private {@code TUG_BASES} array (byte-identical coordinates) so that
 * every boot path — a fresh fleet and a fleet respawned from a save — injects each tug's base from
 * ONE table rather than duplicating the coordinates (the tug pier next to the breakwater, four
 * bases stacked vertically, task 08 §8.6). Placeholder coordinates, in the same spirit as
 * {@link BerthPositions}: since S15 (2026-10-12) nothing draws them, they only set how far a tug
 * has to travel.
 */
public final class TugBasePositions {

    private static final Map<String, Position> BASES = Map.of(
            "tug_1", new Position(50.0, 100.0, 0.0),
            "tug_2", new Position(50.0, 150.0, 0.0),
            "tug_3", new Position(50.0, 200.0, 0.0),
            "tug_4", new Position(50.0, 250.0, 0.0));

    private TugBasePositions() {
    }

    /** The home base of {@code tugId}, or {@code null} if unknown. */
    public static Position position(String tugId) {
        return BASES.get(tugId);
    }

    /** The full {@code tug_id -> base Position} table. */
    public static Map<String, Position> all() {
        return BASES;
    }
}
