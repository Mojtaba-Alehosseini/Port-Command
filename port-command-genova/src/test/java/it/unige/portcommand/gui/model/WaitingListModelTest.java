package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;

import it.unige.portcommand.gui.model.PatienceCounterModel.Level;
import it.unige.portcommand.gui.model.WaitingListModel.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the "Waiting for you" list's ordering and texts. Pure — the model is handed
 * the open dialogues and, per dialogue, the remaining time its tab last showed; nothing here
 * reads a clock.
 */
class WaitingListModelTest {

    private static OpenDialogue dialogue(String vesselId, String vesselName) {
        return new OpenDialogue("nego-" + vesselId, vesselId, vesselName);
    }

    /** The remaining time each tab shows, by dialogue id; a dialogue not in the map shows none. */
    private static Function<String, OptionalLong> shown(Map<String, Long> remainingByDialogue) {
        return dialogueId -> {
            Long remaining = remainingByDialogue.get(dialogueId);
            return remaining == null ? OptionalLong.empty() : OptionalLong.of(remaining);
        };
    }

    private static List<String> ids(List<Row> rows) {
        List<String> ids = new ArrayList<>();
        for (Row row : rows) {
            ids.add(row.dialogueId());
        }
        return ids;
    }

    @Test
    void theVesselClosestToWalkingAwayComesFirst() {
        List<OpenDialogue> open = List.of(dialogue("A", "Genoa Star"), dialogue("B", "Ligurian Dawn"),
                dialogue("C", "Tirreno"));
        Map<String, Long> remaining = Map.of("nego-A", 144_000L, "nego-B", 24_000L, "nego-C", 61_000L);

        List<Row> rows = WaitingListModel.rows(open, shown(remaining));

        assertEquals(List.of("nego-B", "nego-C", "nego-A"), ids(rows), "least remaining time first");
        assertEquals(new Row("nego-B", "Ligurian Dawn", "0:24", Level.WARNING), rows.get(0));
        assertEquals(new Row("nego-C", "Tirreno", "1:01", Level.NORMAL), rows.get(1));
        assertEquals(new Row("nego-A", "Genoa Star", "2:24", Level.NORMAL), rows.get(2));
    }

    @Test
    void aTieKeepsTabOrder() {
        List<OpenDialogue> open = List.of(dialogue("A", "Alpha"), dialogue("B", "Bravo"), dialogue("C", "Charlie"),
                dialogue("D", "Delta"));
        Map<String, Long> remaining = Map.of("nego-A", 60_000L, "nego-B", 30_000L, "nego-C", 60_000L,
                "nego-D", 30_000L);

        assertEquals(List.of("nego-B", "nego-D", "nego-A", "nego-C"),
                ids(WaitingListModel.rows(open, shown(remaining))),
                "equal times stay in the order the tabs are in");

        List<OpenDialogue> reversed = List.of(dialogue("D", "Delta"), dialogue("C", "Charlie"), dialogue("B", "Bravo"),
                dialogue("A", "Alpha"));
        assertEquals(List.of("nego-D", "nego-B", "nego-C", "nego-A"),
                ids(WaitingListModel.rows(reversed, shown(remaining))),
                "...whatever that order is: the sort is stable, not secretly by id");
    }

    @Test
    void theOrderIsByTheRealNumberNotByTheRoundedText() {
        // Both read 0:25, yet one is nearer the end; it goes first.
        List<OpenDialogue> open = List.of(dialogue("A", "Alpha"), dialogue("B", "Bravo"));
        Map<String, Long> remaining = Map.of("nego-A", 24_900L, "nego-B", 24_100L);

        List<Row> rows = WaitingListModel.rows(open, shown(remaining));

        assertEquals(List.of("nego-B", "nego-A"), ids(rows));
        assertEquals("0:25", rows.get(0).timeText());
        assertEquals("0:25", rows.get(1).timeText());
    }

    @Test
    void aDialogueWhoseTabShowsNoTimeGoesLastAndBlankNeverZero() {
        List<OpenDialogue> open = List.of(dialogue("X", "No Clock"), dialogue("A", "Alpha"), dialogue("Y", "Also None"),
                dialogue("B", "Bravo"));
        Map<String, Long> remaining = Map.of("nego-A", 90_000L, "nego-B", 5_000L);

        List<Row> rows = WaitingListModel.rows(open, shown(remaining));

        assertEquals(List.of("nego-B", "nego-A", "nego-X", "nego-Y"), ids(rows),
                "timed rows first; the untimed ones after them, in tab order");
        assertEquals(new Row("nego-X", "No Clock", "", Level.NORMAL), rows.get(2),
                "blank and uncoloured — \"0:00\" would read as a timeout that did not happen");
        assertEquals(new Row("nego-Y", "Also None", "", Level.NORMAL), rows.get(3));
        for (Row row : rows.subList(2, 4)) {
            assertFalse(row.timeText().contains("0:00"));
        }
    }

    @Test
    void aFunctionThatAnswersNullIsReadAsNoTime() {
        List<Row> rows = WaitingListModel.rows(List.of(dialogue("A", "Alpha")), dialogueId -> null);

        assertEquals(List.of(new Row("nego-A", "Alpha", "", Level.NORMAL)), rows);
    }

    @Test
    void aRealZeroIsShownAsZeroAndSortsFirst() {
        // The tab itself shows 0:00 in red when the time has run out but the vessel has not left
        // yet; the list says the same, it does not blank it.
        List<OpenDialogue> open = List.of(dialogue("A", "Alpha"), dialogue("B", "Bravo"));
        Map<String, Long> remaining = Map.of("nego-A", 12_000L, "nego-B", 0L);

        List<Row> rows = WaitingListModel.rows(open, shown(remaining));

        assertEquals(new Row("nego-B", "Bravo", "0:00", Level.CRITICAL), rows.get(0));
        assertEquals(new Row("nego-A", "Alpha", "0:12", Level.WARNING), rows.get(1));
    }

    @ParameterizedTest
    @CsvSource({
            "240000, 4:00, NORMAL",
            "30000,  0:30, NORMAL",   // exactly 0:30 is not yet under 30 s
            "29001,  0:30, NORMAL",   // ...and still DISPLAYS 0:30
            "29000,  0:29, WARNING",  // orange under 30 s
            "10000,  0:10, WARNING",
            "9001,   0:10, WARNING",  // still displays 0:10
            "9000,   0:09, CRITICAL", // red under 10 s
            "1,      0:01, CRITICAL",
            "0,      0:00, CRITICAL",
    })
    void aRowTurnsOrangeAndRedExactlyWhereTheTabDoes(long remainingMillis, String text, Level level) {
        List<Row> rows = WaitingListModel.rows(List.of(dialogue("A", "Alpha")),
                shown(Map.of("nego-A", remainingMillis)));

        assertEquals(new Row("nego-A", "Alpha", text, level), rows.get(0));
    }

    @Test
    void textAndLevelAreThePatienceCounterModelsOwnForEveryInstant() {
        // Not a second copy of the thresholds: whatever the tab's model says, the row says.
        Map<String, Long> remaining = new HashMap<>();
        List<OpenDialogue> open = List.of(dialogue("A", "Alpha"));
        for (long millis = 0; millis <= 61_000L; millis += 250) {
            remaining.put("nego-A", millis);
            Row row = WaitingListModel.rows(open, shown(remaining)).get(0);
            assertEquals(PatienceCounterModel.text(millis), row.timeText(), "text at " + millis);
            assertEquals(PatienceCounterModel.level(millis), row.level(), "level at " + millis);
        }
    }

    @Test
    void theTimeIsABareNumberWithNoWordAroundIt() {
        Row row = WaitingListModel.rows(List.of(dialogue("A", "Alpha")), shown(Map.of("nego-A", 144_000L))).get(0);

        assertTrue(row.timeText().matches("\\d+:\\d\\d"), "got: " + row.timeText());
    }

    @Test
    void aVesselWithNoNameIsListedByItsIdAndFailingThatByItsDialogue() {
        List<OpenDialogue> open = List.of(
                new OpenDialogue("nego-W1", "W1", null),
                new OpenDialogue("nego-W2", "W2", "  "),
                new OpenDialogue("nego-W3", null, null));

        List<Row> rows = WaitingListModel.rows(open, shown(Map.of()));

        assertEquals("W1", rows.get(0).vesselName());
        assertEquals("W2", rows.get(1).vesselName());
        assertEquals("nego-W3", rows.get(2).vesselName());
    }

    @Test
    void nobodyWaitingIsAnEmptyList() {
        assertEquals(List.of(), WaitingListModel.rows(List.of(), shown(Map.of())));
        assertEquals(List.of(), WaitingListModel.rows(null, shown(Map.of())));
    }

    @Test
    void entriesThatCannotBeOpenedAreSkipped() {
        List<OpenDialogue> open = new ArrayList<>();
        open.add(null);
        open.add(new OpenDialogue(null, "W9", "No Dialogue"));
        open.add(dialogue("A", "Alpha"));

        assertEquals(List.of("nego-A"), ids(WaitingListModel.rows(open, shown(Map.of("nego-A", 1_000L)))),
                "a row with no dialogue id could not be clicked through to a tab");
    }

    @Test
    void theRowsAreAnImmutableSnapshot() {
        List<Row> rows = WaitingListModel.rows(List.of(dialogue("A", "Alpha")), shown(Map.of("nego-A", 1_000L)));

        assertThrows(UnsupportedOperationException.class, () -> rows.add(rows.get(0)));
    }

    @Test
    void theTimeSourceIsRequired() {
        assertThrows(NullPointerException.class, () -> WaitingListModel.rows(List.of(), null));
    }
}
