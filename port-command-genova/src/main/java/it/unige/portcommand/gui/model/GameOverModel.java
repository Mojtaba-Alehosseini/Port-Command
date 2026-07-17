package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.harbourmaster.financial.ScoreRecord;
import it.unige.portcommand.lifecycle.events.GameOverEvent;

/**
 * Pure rendering model for the game-over dialog (task 24). No Swing/AWT import — the
 * {@code gui/model} split (INVARIANTS, task 18) that keeps the dialog's strings testable in the
 * headless lanes, where a {@code JDialog} cannot even be constructed.
 *
 * <p>{@code Locale.ROOT} on every money format — figures must render identically on Moji's
 * machine and CI (task-20 invariant).
 */
public final class GameOverModel {

    private final GameOverEvent event;
    private final List<ScoreRecord> top5;

    public GameOverModel(GameOverEvent event, List<ScoreRecord> top5) {
        this.event = event;
        this.top5 = List.copyOf(top5);
    }

    public String title() {
        return "Game Over — Day " + event.finalDay();
    }

    /** The monospaced report body, one string per line (the EOD dialog's rendering pattern). */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("GAME OVER");
        lines.add("");
        lines.add("Reason:            " + reasonLine());
        lines.add(String.format(Locale.ROOT, "Final wallet:      €%,.0f", event.finalWallet()));
        lines.add("Final reputation:  " + event.finalReputation());
        lines.add("Final day:         " + event.finalDay());
        lines.add("");
        lines.add("Leaderboard (top " + Leaderboard.MAX_ENTRIES + "):");
        if (top5.isEmpty()) {
            lines.add("  (no recorded runs)");
        } else {
            for (int i = 0; i < top5.size(); i++) {
                ScoreRecord r = top5.get(i);
                String marker = isThisRun(r) ? "  ← this run" : "";
                lines.add(String.format(Locale.ROOT, "  %d. €%,.0f — day %d, reputation %.0f (%s)%s",
                        i + 1, r.finalWallet(), r.finalDay(), r.finalReputation(), r.recordedOn(), marker));
            }
        }
        return lines;
    }

    public String reasonLine() {
        return switch (event.reason()) {
            case GameOverEvent.REASON_BANKRUPT -> "Bankrupt — wallet below the floor for 3 consecutive days";
            case GameOverEvent.REASON_DAY_CAP -> "Day cap reached — the harbour has a new master";
            case GameOverEvent.REASON_QUIT -> "The Harbour Master resigned";
            default -> event.reason();
        };
    }

    /**
     * Best-effort "this run" marker: wallet + day + reputation all matching. Purely cosmetic —
     * two byte-identical runs would both be marked, which is fine.
     */
    private boolean isThisRun(ScoreRecord r) {
        return r.finalWallet() == event.finalWallet() && r.finalDay() == event.finalDay()
                && Math.round(r.finalReputation()) == event.finalReputation();
    }
}
