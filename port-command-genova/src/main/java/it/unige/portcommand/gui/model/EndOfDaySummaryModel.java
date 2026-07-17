package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.harbourmaster.financial.ScoreRecord;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;

/**
 * Pure rendering model for {@code EndOfDaySummaryDialog} — the §3.12/demo-transcript end-of-day
 * report as ordered text lines. No Swing/AWT import, no EDT assertion: the panel-model split every
 * GUI task follows since task 18, and here it is what makes the dialog testable at all
 * ({@code JDialog} is a {@code java.awt.Window} and throws {@code HeadlessException} in the
 * {@code test}/{@code integrationTest} lanes — INVARIANTS, task 17).
 *
 * <p>Renders only what {@link EndOfDaySummary} actually carries. The demo mockup also shows a
 * per-vessel served list and a safety-incident count; the canonical DTO has neither (and no
 * incident event exists in the game), so those lines are deliberately absent rather than faked.
 *
 * <p>Every number is formatted with {@link Locale#ROOT} explicitly. The default locale would put
 * a {@code .} thousands separator on an Italian machine and a {@code ,} on an English one — the
 * report would render differently on Moji's box than in CI, and the tests would only pass where
 * they were written.
 */
public final class EndOfDaySummaryModel {

    private final EndOfDaySummary summary;
    private final double dailyTargetEur;
    private final List<ScoreRecord> leaderboard;

    public EndOfDaySummaryModel(EndOfDaySummary summary, double dailyTargetEur, List<ScoreRecord> leaderboard) {
        this.summary = summary;
        this.dailyTargetEur = dailyTargetEur;
        this.leaderboard = leaderboard == null ? List.of() : List.copyOf(leaderboard);
    }

    public String title() {
        return "DAY " + summary.gameDay() + " SUMMARY";
    }

    /** {@code true} when the day's net margin met or beat the target. */
    public boolean targetMet() {
        return summary.net() >= dailyTargetEur;
    }

    /** The whole report, in display order — what the dialog renders and the tests assert. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add(title());
        out.add("");
        out.add(pad("Total income:") + euro(summary.income()));
        out.add(pad("Total expenses:") + euro(summary.totalExpense()));
        out.add(pad("Net margin:") + euro(summary.net()));
        out.add(pad("Daily target:") + euro(dailyTargetEur) + (targetMet() ? "  ✓ MET" : "  ✗ MISSED"));
        out.add("");
        out.add(pad("Wallet:") + euro(summary.endingWallet()));
        out.add(pad("Reputation:") + summary.endingReputation());
        out.add("");
        out.add("MAS messages exchanged today: " + summary.totalMessages());
        out.add("  FIPA performatives breakdown:");
        out.addAll(performativeLines());
        out.add("");
        out.add("→ " + unlockHint());
        out.add("→ " + leaderboardLine());
        return List.copyOf(out);
    }

    /**
     * One line per canonical performative, in {@code PerformativeCounter}'s canonical order,
     * zeroes included — a {@code CANCEL: 0} is the point, not a gap (the two performatives a calm
     * day misses are a scenario fact; see {@code PerformativeCounter}).
     */
    public List<String> performativeLines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : summary.performativeCounts().entrySet()) {
            out.add("    " + pad(entry.getKey() + ":", 20) + entry.getValue());
        }
        return List.copyOf(out);
    }

    /**
     * The "what's next" nudge. Tied to the one progression mechanic that actually ships — the
     * premium surcharge unlocking at reputation {@value IncomeRules#PREMIUM_REPUTATION_THRESHOLD}
     * ({@code IncomeRules.premiumSurcharge}). The demo mockup's "Day 2 unlocks: tanker contracts"
     * describes a day-gated unlock table that does not exist in the game; inventing one here would
     * promise the player something no code delivers.
     */
    public String unlockHint() {
        int threshold = (int) IncomeRules.PREMIUM_REPUTATION_THRESHOLD;
        if (summary.endingReputation() >= threshold) {
            return "Premium pricing is ACTIVE: +15% on every fee while reputation holds at "
                    + threshold + "+.";
        }
        return "Reputation " + threshold + " unlocks premium pricing (+15% on every fee) — you are at "
                + summary.endingReputation() + ".";
    }

    /** The best run on the board, or an honest note when nothing has finished yet. */
    public String leaderboardLine() {
        if (leaderboard.isEmpty()) {
            return "Leaderboard: no completed runs yet.";
        }
        ScoreRecord best = leaderboard.get(0);
        return "Best score so far (leaderboard): Day " + best.finalDay() + " — " + euro(best.finalWallet());
    }

    private static String euro(double amount) {
        return String.format(Locale.ROOT, "€%,.0f", amount);
    }

    private static String pad(String label) {
        return pad(label, 24);
    }

    private static String pad(String label, int width) {
        return String.format(Locale.ROOT, "%-" + width + "s", label);
    }
}
