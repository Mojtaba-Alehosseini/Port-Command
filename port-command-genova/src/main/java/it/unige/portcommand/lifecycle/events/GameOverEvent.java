package it.unige.portcommand.lifecycle.events;

import it.unige.portcommand.util.Event;

/**
 * The run ended (task 24). Published exactly once per run by
 * {@code GameOverGuard} (the single emitter), after it has recorded the score
 * on the {@code Leaderboard} and moved {@code GameLifecycle} to GAME_OVER.
 * Consumers: {@code MainWindow} (opens {@code GameOverDialog}).
 *
 * @param reason          {@code "bankrupt"} (wallet below the bankruptcy floor at
 *                        end-of-day for 3 consecutive days), {@code "day_cap"}
 *                        (day advanced past {@code Settings.maxGameDays}), or
 *                        {@code "quit"} (player choice)
 * @param finalWallet     last known wallet balance (absolute €)
 * @param finalDay        the game day the run ended on
 * @param finalReputation last known reputation, rounded to the 0–100 int scale
 */
public record GameOverEvent(String reason, double finalWallet, int finalDay, int finalReputation)
        implements Event {

    public static final String REASON_BANKRUPT = "bankrupt";
    public static final String REASON_DAY_CAP = "day_cap";
    public static final String REASON_QUIT = "quit";
}
