package it.unige.portcommand.harbourmaster;

import java.util.Map;

import it.unige.portcommand.ontology.Position;

/**
 * Static {@code tug_id -> home-base Position} table — the tug-pier equivalent of
 * {@link BerthPositions}, and the single canonical source of the four tug bases. Extracted from
 * {@code AgentRoster}'s former private {@code TUG_BASES} array (byte-identical coordinates) so both
 * the bootstrap (which injects each base as a tug's initial position) and the GUI map (task 18,
 * which seeds an IDLE dot at each base so idle tugs are visible at boot — the tug pier next to the
 * breakwater, four berths stacked vertically, task 08 §8.6) read ONE table rather than duplicating
 * the coordinates. Placeholder coordinates — task 18's real map layout supersedes these (same
 * placeholder spirit as {@link BerthPositions}).
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
