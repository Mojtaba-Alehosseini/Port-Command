package it.unige.portcommand.harbourmaster;

import java.util.Map;

import it.unige.portcommand.ontology.Position;

/**
 * Static {@code berth_id -> physical Position} table. No berth-position registry
 * exists anywhere else in the repo ({@code BerthOccupancy}, {@code BerthSpec},
 * {@code PortOntology}, {@code port_ontology.pl} carry no x/y); ADR-07 already
 * commits the HarbourMaster to being authoritative for the {@code berth_position} a
 * tug's ACCEPT carries, so this is that table. Placeholder coordinates: they only
 * have to be consistent with each other and with {@link TugBasePositions}, because all
 * anyone derives from them is a tug's distance to a berth (S15, 2026-10-12: nothing
 * draws them any more).
 */
public final class BerthPositions {

    private static final Map<String, Position> BERTHS = Map.of(
            "berth_1", new Position(400.0, 150.0, 0.0),
            "berth_2", new Position(500.0, 150.0, 0.0),
            "berth_3", new Position(400.0, 350.0, 0.0),
            "berth_4", new Position(500.0, 350.0, 0.0));

    private BerthPositions() {
    }

    /** The physical position of {@code berthId}, or {@code null} if unknown. */
    public static Position position(String berthId) {
        return BERTHS.get(berthId);
    }

    /**
     * The full {@code berth_id -> Position} table — the single canonical source for both the
     * berth id list and coordinates ({@code PortStatusSnapshot} takes the four berth ids of the
     * Port table from its key set rather than hardcoding {@code "berth_1".."berth_4"} a second
     * time).
     */
    public static Map<String, Position> all() {
        return BERTHS;
    }
}
