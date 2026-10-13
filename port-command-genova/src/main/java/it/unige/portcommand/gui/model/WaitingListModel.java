package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Pure model of the "Waiting for you" list (S15, 2026-10-12): the open negotiations, the one that
 * will walk away soonest on top. No Swing/AWT import; a view only draws the rows.
 *
 * <p><b>It reads the tab's counter, never the clock.</b> The time of a row is whatever that
 * dialogue's own tab last showed ({@code DialogueDirectory.shownRemainingMillis}), run through the
 * very {@link PatienceCounterModel} the tab uses. Two views reading the conversation clock on two
 * timers could disagree across a colour threshold for up to a second; one number handed from the
 * tab to the list cannot.
 *
 * <p>Like the tab's counter, a row is a number and a colour and nothing else: nothing here labels
 * the time or explains why two vessels differ (INVARIANTS; players discover that).
 */
public final class WaitingListModel {

    /**
     * One line of the list.
     *
     * @param dialogueId the tab to bring forward when the line is clicked
     * @param vesselName what the line calls the vessel
     * @param timeText   the bare {@code m:ss} the tab shows, or empty when the tab shows none
     * @param level      how to colour {@code timeText}
     */
    public record Row(String dialogueId, String vesselName, String timeText, PatienceCounterModel.Level level) {
    }

    /** A row together with the number it is sorted by. */
    private record Timed(long remainingMillis, Row row) {
    }

    private WaitingListModel() {
    }

    /**
     * @param open                 the open negotiations, in tab order
     * @param shownRemainingMillis for a dialogue id, the remaining time its tab last showed —
     *                             empty when that counter is blank
     * @return one row per dialogue: those with a shown time first, least time first (a tie keeps
     *         tab order), then those with none, in tab order, with a blank time. Blank, never
     *         {@code 0:00} — that would read as a timeout which did not happen (the tab's own rule).
     */
    public static List<Row> rows(List<OpenDialogue> open, Function<String, OptionalLong> shownRemainingMillis) {
        Objects.requireNonNull(shownRemainingMillis, "shownRemainingMillis");
        if (open == null || open.isEmpty()) {
            return List.of();
        }
        List<Timed> timed = new ArrayList<>();
        List<Row> untimed = new ArrayList<>();
        for (OpenDialogue dialogue : open) {
            if (dialogue == null || dialogue.dialogueId() == null) {
                continue;
            }
            OptionalLong shown = shownRemainingMillis.apply(dialogue.dialogueId());
            if (shown == null || shown.isEmpty()) {
                untimed.add(new Row(dialogue.dialogueId(), displayName(dialogue), "",
                        PatienceCounterModel.Level.NORMAL));
                continue;
            }
            long remaining = shown.getAsLong();
            timed.add(new Timed(remaining, new Row(dialogue.dialogueId(), displayName(dialogue),
                    PatienceCounterModel.text(remaining), PatienceCounterModel.level(remaining))));
        }
        // List.sort is stable, which is what keeps two equal times in tab order.
        timed.sort(Comparator.comparingLong(Timed::remainingMillis));

        List<Row> rows = new ArrayList<>(timed.size() + untimed.size());
        for (Timed entry : timed) {
            rows.add(entry.row());
        }
        rows.addAll(untimed);
        return List.copyOf(rows);
    }

    /** The directory already falls back to the id for a nameless vessel; this only guards a blank. */
    private static String displayName(OpenDialogue dialogue) {
        if (dialogue.vesselName() != null && !dialogue.vesselName().isBlank()) {
            return dialogue.vesselName();
        }
        if (dialogue.vesselId() != null && !dialogue.vesselId().isBlank()) {
            return dialogue.vesselId();
        }
        return dialogue.dialogueId();
    }
}
