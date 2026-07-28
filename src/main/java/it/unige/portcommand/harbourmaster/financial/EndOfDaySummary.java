package it.unige.portcommand.harbourmaster.financial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One game day's settled figures — the payload of {@code EndOfDayCompletedEvent} and the thing
 * {@code EndOfDaySummaryDialog} renders. Finalised by task 20 (it was a task-17 stub sized from
 * planning/24's usage sketch).
 *
 * <p><strong>This is a DTO, not a calculator.</strong> It is BUILT by
 * {@code DayRolloverCoordinator} (task 24), which owns the EOD math (CLAUDE.md rule 6 /
 * INVARIANTS); task 20 owns the shape and the display. Nothing here computes anything the
 * coordinator did not already settle — the one derived accessor, {@link #net()}, is arithmetic on
 * fields it was handed.
 *
 * <p><strong>Shape decision (2026-07-17).</strong> {@code planning/20}'s file table specifies
 * {@code Map<Integer,Integer> performativeCounts}; the task-17 stub used {@code Map<String,Integer>}.
 * Kept as {@code String}: it matches {@link PerformativeCounter#snapshot()}'s output, it is what
 * the demo transcript's report actually shows ("REQUEST: 2 | INFORM: 7 | …"), and it keeps the raw
 * {@code ACLMessage} int constants out of a display DTO. planning/20's table is the stale one.
 *
 * <p>{@code performativeCounts} is copied through {@code LinkedHashMap} +
 * {@code unmodifiableMap}, never {@code Map.copyOf} — INVARIANTS' determinism rule. Here it is
 * load-bearing for a visible reason rather than a wire-format one: {@code Map.copyOf} randomises
 * iteration order per JVM launch, so the report's performative columns would shuffle between runs.
 *
 * @param gameDay            the day that just ended (1-based)
 * @param income             total income recorded this day
 * @param totalExpense       variable expenses + the fixed €5200, summed
 * @param endingWallet       wallet balance after this day's settlement
 * @param endingReputation   reputation after this day's settlement, 0..100
 * @param performativeCounts FIPA performative name -&gt; count observed this day (all 10, in
 *                           canonical order, zeroes included)
 */
public record EndOfDaySummary(int gameDay, double income, double totalExpense, double endingWallet,
                               int endingReputation, Map<String, Integer> performativeCounts) {

    public EndOfDaySummary {
        if (gameDay < 1) {
            throw new IllegalArgumentException("gameDay must be >= 1 (1-based): " + gameDay);
        }
        performativeCounts = performativeCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(performativeCounts));
    }

    /** The day's net margin — what the §7.5 calibration targets at +€8,000–12,000 on a default day. */
    public double net() {
        return income - totalExpense;
    }

    /** Total FIPA messages the HarbourMaster exchanged this day (the demo's "MAS messages" line). */
    public int totalMessages() {
        return performativeCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
