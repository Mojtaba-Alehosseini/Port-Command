package it.unige.portcommand.gui.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.harbourmaster.financial.ScoreRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EOD report, driven headless from a synthetic {@link EndOfDaySummary} — which is the only way
 * it CAN be tested: {@code EndOfDaySummaryDialog} is a {@code JDialog} and the test lane runs with
 * {@code -Djava.awt.headless=true} (INVARIANTS, task 17). Every string the dialog shows is
 * produced here.
 */
class EndOfDaySummaryModelTest {

    private static final double TARGET = 5_000.0;

    /**
     * The demo transcript's Day 1 tally (42 messages). Keys come from
     * {@link PerformativeCounter#canonicalNames()}, not typed literals, so this fixture uses the
     * same spelling a live day produces — JADE's FIPA names hyphenate ({@code ACCEPT-PROPOSAL}).
     */
    private static Map<String, Integer> performatives() {
        List<Integer> tally = List.of(2, 7, 13, 7, 4, 2, 4, 0, 3, 0);
        List<String> names = PerformativeCounter.canonicalNames();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            counts.put(names.get(i), tally.get(i));
        }
        return counts;
    }

    private static EndOfDaySummary summary(int day, double income, double expense, int reputation) {
        return new EndOfDaySummary(day, income, expense, 50_000.0, reputation, performatives());
    }

    private static EndOfDaySummaryModel model(EndOfDaySummary summary, List<ScoreRecord> board) {
        return new EndOfDaySummaryModel(summary, TARGET, board);
    }

    private static String joined(EndOfDaySummaryModel model) {
        return String.join("\n", model.lines());
    }

    @Test
    void titleNamesTheDayThatEnded() {
        assertEquals("DAY 3 SUMMARY", model(summary(3, 0, 0, 50), List.of()).title());
    }

    @Test
    void rendersTheDaysFiguresWithLocaleIndependentThousandsSeparators() {
        String report = joined(model(summary(1, 11_200.0, 8_150.0, 51), List.of()));

        assertTrue(report.contains("€11,200"), report);
        assertTrue(report.contains("€8,150"), report);
        assertTrue(report.contains("€3,050"), "net margin = 11200 - 8150\n" + report);
        assertTrue(report.contains("€50,000"), "ending wallet\n" + report);
    }

    @Test
    void netMarginIsIncomeMinusTotalExpense() {
        assertEquals(3_050.0, summary(1, 11_200.0, 8_150.0, 51).net(), 1e-9);
    }

    @Test
    void aMissedTargetIsMarkedMissed() {
        EndOfDaySummaryModel model = model(summary(1, 6_000.0, 5_500.0, 50), List.of());

        assertFalse(model.targetMet(), "net 500 < target 5000");
        assertTrue(joined(model).contains("✗ MISSED"), joined(model));
    }

    @Test
    void aMetTargetIsMarkedMet() {
        EndOfDaySummaryModel model = model(summary(1, 18_450.0, 8_525.0, 50), List.of());

        assertTrue(model.targetMet(), "net 9925 >= target 5000");
        assertTrue(joined(model).contains("✓ MET"), joined(model));
    }

    /** Exactly on the target counts as met, not missed. */
    @Test
    void hittingTheTargetExactlyCountsAsMet() {
        assertTrue(model(summary(1, 5_000.0, 0.0, 50), List.of()).targetMet());
    }

    // ---- the performative breakdown (the academic centrepiece) ----

    @Test
    void rendersAllTenPerformativesIncludingTheZeroes() {
        EndOfDaySummaryModel model = model(summary(1, 0, 0, 50), List.of());

        assertEquals(10, model.performativeLines().size());
        String report = joined(model);
        for (String name : performatives().keySet()) {
            assertTrue(report.contains(name), "missing " + name + " in\n" + report);
        }
        assertTrue(model.performativeLines().stream().anyMatch(l -> l.contains("CANCEL") && l.contains("0")),
                "a zero is reported, not hidden");
    }

    @Test
    void breakdownKeepsTheCounterCanonicalOrder() {
        List<String> lines = model(summary(1, 0, 0, 50), List.of()).performativeLines();

        assertTrue(lines.get(0).contains("REQUEST"), lines.get(0));
        assertTrue(lines.get(9).contains("DISCONFIRM"), lines.get(9));
    }

    @Test
    void totalMessagesSumsTheBreakdown() {
        assertEquals(42, summary(1, 0, 0, 50).totalMessages(), "the demo transcript's Day 1 total");
        assertTrue(joined(model(summary(1, 0, 0, 50), List.of()))
                .contains("MAS messages exchanged today: 42"));
    }

    // ---- unlock hint ----

    @Test
    void belowEightyTheHintNamesThePremiumUnlockAndTheCurrentStanding() {
        String hint = model(summary(1, 0, 0, 51), List.of()).unlockHint();

        assertTrue(hint.contains("80"), hint);
        assertTrue(hint.contains("premium"), hint);
        assertTrue(hint.contains("51"), hint);
    }

    @Test
    void atEightyTheHintSaysPremiumIsActive() {
        assertTrue(model(summary(1, 0, 0, 80), List.of()).unlockHint().contains("ACTIVE"));
    }

    // ---- leaderboard line ----

    @Test
    void anEmptyLeaderboardSaysSoRatherThanShowingAFakeBest() {
        assertEquals("Leaderboard: no completed runs yet.",
                model(summary(1, 0, 0, 50), List.of()).leaderboardLine());
    }

    @Test
    void leaderboardLineShowsTheBestRun() {
        List<ScoreRecord> board = List.of(new ScoreRecord(11_200.0, 1, 51.0, "2026-07-17"));

        String line = model(summary(1, 0, 0, 50), board).leaderboardLine();

        assertTrue(line.contains("Day 1"), line);
        assertTrue(line.contains("€11,200"), line);
    }

    @Test
    void aNullLeaderboardIsTreatedAsEmpty() {
        assertEquals("Leaderboard: no completed runs yet.",
                new EndOfDaySummaryModel(summary(1, 0, 0, 50), TARGET, null).leaderboardLine());
    }

    // ---- DTO guards ----

    @Test
    void summaryRejectsANonPositiveDay() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new EndOfDaySummary(0, 0, 0, 0, 50, Map.of()));
    }

    @Test
    void summaryPerformativeCountsAreDefensivelyCopiedAndUnmodifiable() {
        Map<String, Integer> mutable = performatives();
        EndOfDaySummary summary = new EndOfDaySummary(1, 0, 0, 0, 50, mutable);

        mutable.put("REQUEST", 999);

        assertEquals(2, summary.performativeCounts().get("REQUEST"), "copied at construction");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> summary.performativeCounts().put("REQUEST", 1));
    }
}
