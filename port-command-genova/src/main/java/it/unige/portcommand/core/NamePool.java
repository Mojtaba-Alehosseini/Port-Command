package it.unige.portcommand.core;

import java.util.List;

/**
 * The walk-in vessel name pool (S8, GOAL 3 — the agreed D-05 input; ADR-17's name-pool
 * section). Poisson-spawned walk-ins draw {@code name(offset, ordinal)} where {@code offset}
 * is rolled ONCE per new game from the seeded {@code "name-pool"} stream (persisted in
 * {@code HarbourMasterStateDTO}) and {@code ordinal} is the existing persisted
 * {@code walkInSpawnCount} — so the sequence is deterministic per seed, survives save/load,
 * and is collision-free for {@link #size()} consecutive spawns. A wrap after {@value
 * #POOL_SIZE_DOC} spawns could reuse a name only if the earlier holder were still active,
 * which the 3-active walk-in cap makes practically impossible; the bound is stated, not
 * hidden. Scripted scenario walk-ins carry AUTHORED names from their JSONs instead, kept
 * DISJOINT from this pool (structural test) so an authored name can never collide with a
 * drawn one in-session.
 *
 * <p>Every entry obeys the NL layer's contract ({@code NamePoolTest} pins each rule):
 * no digits (RosterNameResolver's digit guard keeps id-shaped names exact-match-only — a
 * digit here would silently disable MED for that name), single-space canonical (survives
 * {@code atomic_list_concat}'s join), no {@code RosterNameResolver.GRAMMAR_STOP} words
 * (those windows refuse), plain letters (quoteAtom-safe), and ≥ 4 characters joined
 * (the MED MIN_LEN — shorter would be exact-match-only).
 */
public final class NamePool {

    private static final int POOL_SIZE_DOC = 40;

    /** Ligurian/Mediterranean-flavoured two-word names — see the class contract above. */
    private static final List<String> POOL = List.of(
            "Stella Maris", "Aurora Levante", "Mar Ligure", "Vento Nero",
            "Luna Rossa", "Capo Verde", "Alba Marina", "Onda Serena",
            "Golfo Azzurro", "Riva Bianca", "Sirena Blu", "Corsara Verde",
            "Perla Nera", "Brezza Estiva", "Maestrale Grigio", "Libeccio Chiaro",
            "Scirocco Rosso", "Tramonto Oro", "Gabbiano Bruno", "Ancora Felice",
            "Marea Alta", "Faro Vecchio", "Molo Lontano", "Baia Chiara",
            "Isola Bella", "Punta Secca", "Rada Calma", "Sale Bianco",
            "Spuma Lieve", "Vela Doro", "Zefiro Gentile", "Nave Regina",
            "Fortuna Ligure", "Grifone Blu", "Meridiana Rossa", "Nettuno Verde",
            "Orsa Minore", "Pegaso Bianco", "Quarto Vento", "Rosa Ventura");

    private NamePool() {
    }

    /** Pool size — the collision-free window for consecutive ordinals. */
    public static int size() {
        return POOL.size();
    }

    /** The whole pool, for structural tests and disjointness checks. */
    public static List<String> all() {
        return POOL;
    }

    /**
     * The name for the {@code ordinal}-th spawn (1-based — the {@code WALKIN-<n>} counter)
     * under a session's {@code offset}. Pure; any int offset is legal (floorMod).
     */
    public static String name(int offset, int ordinal) {
        return POOL.get(Math.floorMod(offset + ordinal - 1, POOL.size()));
    }
}
