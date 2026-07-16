package it.unige.portcommand.harbourmaster.financial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal stub, sized only from {@code planning/24_game_loop_integration.md}'s own usage
 * sketch — created here (task 17) because {@code gui.events.EndOfDayCompletedEvent} needs a
 * typed payload before task 20 (Phase 5) exists to own the real EOD dashboard math; task 20
 * refines/finalizes this shape (dated ownership note, same pattern as {@link IncomeRules} /
 * {@link ExpenseRules}, task 10). {@code performativeCounts} uses
 * {@code Collections.unmodifiableMap(new LinkedHashMap<>(...))}, not {@code Map.copyOf} — this
 * codebase's established rule (INVARIANTS.md) against per-JVM-launch iteration-order drift.
 *
 * @param gameDay            the day that just ended
 * @param income             total income recorded this day
 * @param totalExpense       variable + the fixed €5200 daily cost, summed
 * @param endingWallet       wallet balance after this day's settlement
 * @param endingReputation   reputation after this day's settlement
 * @param performativeCounts FIPA performative name -&gt; count observed this day
 */
public record EndOfDaySummary(int gameDay, double income, double totalExpense, double endingWallet,
                               int endingReputation, Map<String, Integer> performativeCounts) {

    public EndOfDaySummary {
        performativeCounts = performativeCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(performativeCounts));
    }
}
