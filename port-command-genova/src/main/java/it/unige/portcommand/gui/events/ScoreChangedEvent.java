package it.unige.portcommand.gui.events;

import java.util.Objects;

import it.unige.portcommand.harbourmaster.financial.DayScore;
import it.unige.portcommand.util.Event;

/**
 * The current day's score, as it stands (S17b, ADR-22). Published by the HarbourMaster's
 * {@code ScoreKeeper} whenever a figure behind it moves — a deal, a ship that left, a wallet or
 * reputation change, a movement the gate refused, a whole game hour passing — and once when a
 * window reports ready, because the bus has no replay.
 *
 * <p>It is the top bar's whole feed for the day: the score, the profit so far against the target,
 * and — in {@link #scenarioName} — whether a scenario is being played. The target travels here
 * and not in {@code Settings}: a scenario's target is the session's, never the player's global one.
 *
 * @param score        the day's four parts and the figures behind them
 * @param scenarioName the scenario's display name while its own day runs; {@code null} in free
 *                     play and once a scenario's first midnight has passed
 */
public record ScoreChangedEvent(DayScore score, String scenarioName) implements Event {

    public ScoreChangedEvent {
        Objects.requireNonNull(score, "score");
    }
}
