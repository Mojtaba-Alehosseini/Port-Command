package it.unige.portcommand.lifecycle;

import java.util.Map;
import java.util.Set;

/**
 * The master game state (task 24). One instance-wide mode, owned by
 * {@link GameLifecycle}; everything else observes it through the lifecycle
 * events on the bus, never by polling this enum from another thread.
 *
 * <p>Legal transitions (planning/24 §24.1, incl. the May-audit LOADING→MENU
 * edge for a failed/corrupt load — reachable once task 22 exists):
 *
 * <pre>
 * MENU      → LOADING
 * LOADING   → RUNNING | MENU
 * RUNNING   → PAUSED | GAME_OVER
 * PAUSED    → RUNNING | GAME_OVER
 * GAME_OVER → MENU
 * </pre>
 */
public enum GameMode {
    MENU,
    LOADING,
    RUNNING,
    PAUSED,
    GAME_OVER;

    private static final Map<GameMode, Set<GameMode>> LEGAL = Map.of(
            MENU, Set.of(LOADING),
            LOADING, Set.of(RUNNING, MENU),
            RUNNING, Set.of(PAUSED, GAME_OVER),
            PAUSED, Set.of(RUNNING, GAME_OVER),
            GAME_OVER, Set.of(MENU));

    /** Whether {@code from → to} is a legal mode transition. */
    public static boolean canTransition(GameMode from, GameMode to) {
        return LEGAL.get(from).contains(to);
    }
}
