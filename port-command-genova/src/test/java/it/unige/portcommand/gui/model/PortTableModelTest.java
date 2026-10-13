package it.unige.portcommand.gui.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.PortStatusEvent.BerthRow;
import it.unige.portcommand.gui.events.PortStatusEvent.Wait;
import it.unige.portcommand.gui.model.PortTableModel.BerthLine;
import it.unige.portcommand.gui.model.PortTableModel.InfoLine;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): every string the Port table shows. {@link PortTableModel} is a plain POJO (no
 * Swing/AWT import — pinned by the last test), so all of this runs on the JUnit thread with no EDT
 * involved; {@code PortPanelTest} owns the wiring.
 *
 * <p>S16 (2026-10-13): the berth lines say why a ship is not moving (the "why a ship waits"
 * block), and the weather line is gone from this table — it is the top bar's now
 * ({@code HudWeatherModelTest}).
 */
class PortTableModelTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    /** 14:30 on game day 1. */
    private static final long DAY1_1430 = 14 * HOUR + 30 * MINUTE;
    /** 02:15 on game day 2. */
    private static final long DAY2_0215 = DAY + 2 * HOUR + 15 * MINUTE;

    /** The event's own contract: always the four berths in id order, free unless {@code held} says otherwise. */
    private static PortStatusEvent status(int gameDay, List<String> queued, BerthRow... held) {
        Map<String, BerthRow> rows = new LinkedHashMap<>();
        for (String berthId : List.of("berth_1", "berth_2", "berth_3", "berth_4")) {
            rows.put(berthId, BerthRow.free(berthId));
        }
        for (BerthRow row : held) {
            rows.put(row.berthId(), row);
        }
        return new PortStatusEvent(gameDay, new ArrayList<>(rows.values()), queued);
    }

    /** A holder that is waiting for nothing. */
    private static BerthRow held(String berthId, String vesselId, String vesselName, Stage stage, Long until) {
        return new BerthRow(berthId, vesselId, vesselName, Channel.WALK_IN, stage, until, Wait.NONE, List.of(), false);
    }

    /** S16: "Ligurian Dawn" on berth 2, not yet docked, waiting for {@code wait}. */
    private static BerthRow waiting(Wait wait, String... ruleIds) {
        return waiting(Stage.AWAITING_TUG, null, wait, ruleIds);
    }

    private static BerthRow waiting(Stage stage, Long until, Wait wait, String... ruleIds) {
        return new BerthRow("berth_2", "W004", "Ligurian Dawn", Channel.WALK_IN, stage, until, wait,
                List.of(ruleIds), false);
    }

    private static PortTableModel modelWith(PortStatusEvent event) {
        PortTableModel model = new PortTableModel();
        model.applyStatus(event);
        return model;
    }

    // --- nothing known yet -------------------------------------------------------------------------

    @Test
    void beforeAnyStatusEveryLineReadsUnknownNeverFree() {
        PortTableModel model = new PortTableModel();

        List<BerthLine> berths = model.berthRows();
        assertEquals(4, berths.size(), "always the port's four berths");
        for (int i = 0; i < 4; i++) {
            BerthLine line = berths.get(i);
            assertEquals("berth_" + (i + 1), line.berthId(), "in berth-id order");
            assertEquals("Berth " + (i + 1), line.label());
            assertEquals("—", line.occupant(), "unknown is not \"free\"");
            assertEquals("", line.state());
            assertFalse(line.waiting());
            assertTrue(line.isUnknown());
        }
        assertEquals(new InfoLine("Queue", "—", ""), model.queueRow());
        assertEquals(new InfoLine("Tugs", "—", ""), model.tugRow());
        assertTrue(model.queueRow().isUnknown());
        assertTrue(model.tugRow().isUnknown());
    }

    // --- berths ------------------------------------------------------------------------------------

    @Test
    void aFreeBerthReadsFree() {
        PortTableModel model = modelWith(status(1, List.of()));

        for (BerthLine line : model.berthRows()) {
            assertEquals("free", line.occupant());
            assertEquals("", line.state());
            assertFalse(line.isUnknown());
        }
        assertEquals("Berth 2 · free", model.berthRows().get(1).fullText());
    }

    @Test
    void aDockedVesselShowsItsNameAndUntilWhenItStays() {
        PortTableModel model = modelWith(status(1, List.of(),
                held("berth_1", "C501", "C501", Stage.DOCKED, DAY1_1430),
                held("berth_3", "W003", "Genoa Star", Stage.DOCKED, DAY2_0215)));

        BerthLine first = model.berthRows().get(0);
        assertEquals("Berth 1", first.label());
        assertEquals("C501", first.occupant());
        assertEquals("until 14:30", first.state());
        assertFalse(first.waiting(), "a time is not a reason for waiting: it stays beside the name");
        assertEquals("Berth 1 · C501 · until 14:30", first.fullText());

        BerthLine third = model.berthRows().get(2);
        assertEquals("Genoa Star", third.occupant());
        assertEquals("until D2 02:15", third.state(), "another game day than the status's own is named");

        assertEquals("free", model.berthRows().get(1).occupant(), "the other berths are untouched");
        assertEquals("free", model.berthRows().get(3).occupant());
    }

    @Test
    void untilIsWrittenAgainstTheGameDayOfTheStatus() {
        assertEquals("until 14:30", PortTableModel.untilText(DAY1_1430, 1));
        assertEquals("until D1 14:30", PortTableModel.untilText(DAY1_1430, 2), "an earlier day is named too");
        assertEquals("until D2 02:15", PortTableModel.untilText(DAY2_0215, 1));
        assertEquals("until 02:15", PortTableModel.untilText(DAY2_0215, 2),
                "after midnight the same instant is today's — the day is part of the event for exactly this");
        assertEquals("until D2 00:00", PortTableModel.untilText(DAY, 1), "midnight belongs to the new day");
        assertEquals("until 00:05", PortTableModel.untilText(5 * MINUTE, 1), "hours and minutes are zero-padded");
        assertEquals("until D30 23:59", PortTableModel.untilText(29 * DAY + 23 * HOUR + 59 * MINUTE, 1));
    }

    @Test
    void untilFloorsToTheMinuteLikeTheHudClock() {
        assertEquals("until 14:30", PortTableModel.untilText(DAY1_1430 + MINUTE - 1, 1));
        assertEquals("until 14:31", PortTableModel.untilText(DAY1_1430 + MINUTE, 1));
    }

    @ParameterizedTest
    @CsvSource({
            "ARRIVING,       arriving",
            "NEGOTIATING,    arriving",
            "AWAITING_BERTH, arriving",
            "AWAITING_TUG,   arriving",
            "IN_TRANSIT,     arriving",
            "DOCKED,         docked",
            "DEPARTING,      leaving",
    })
    void aShipThatWaitsForNothingAndHasNoUntilShowsAWordForItsStage(Stage stage, String expected) {
        // S16: AWAITING_TUG used to read "awaiting tugs". A granted ship that is not moving now
        // says what it is waiting for (the block below); with nothing in its way it is arriving.
        BerthRow row = held("berth_2", "W004", "Ligurian Dawn", stage, null);

        assertEquals(expected, PortTableModel.stateText(row, 1));
        BerthLine line = modelWith(status(1, List.of(), row)).berthRows().get(1);
        assertEquals(expected, line.state());
        assertFalse(line.waiting(), "a stage word is short: it stays beside the name");
    }

    @Test
    void aFreeRowAndAMissingStageStillHaveAStateText() {
        assertEquals("", PortTableModel.stateText(BerthRow.free("berth_1"), 1));
        assertEquals("", PortTableModel.stateText(BerthRow.closedFree("berth_1"), 1));
        assertEquals("", PortTableModel.stateText(null, 1));
        assertEquals("arriving", PortTableModel.stateText(held("berth_1", "W1", "W1", null, null), 1));
    }

    @Test
    void aVesselWithNoNameIsShownByItsId() {
        PortTableModel model = modelWith(status(1, List.of(),
                held("berth_1", "C501", null, Stage.DOCKED, null),
                held("berth_2", "C502", "  ", Stage.DOCKED, null)));

        assertEquals("C501", model.berthRows().get(0).occupant());
        assertEquals("C502", model.berthRows().get(1).occupant());
    }

    @Test
    void aStatusThatSaysNothingAboutABerthLeavesItUnknown() {
        // Never produced by the HarbourMaster (its contract is four rows), but a short list must
        // not be read as "the others are free".
        PortTableModel model = modelWith(new PortStatusEvent(1,
                List.of(BerthRow.free("berth_1"), held("berth_2", "C501", "C501", Stage.DOCKED, null)),
                List.of()));

        List<BerthLine> berths = model.berthRows();
        assertEquals(4, berths.size());
        assertEquals("free", berths.get(0).occupant());
        assertEquals("C501", berths.get(1).occupant());
        assertEquals("—", berths.get(2).occupant());
        assertEquals("—", berths.get(3).occupant());
    }

    @Test
    void berthLabelsAreTheIdMadeReadable() {
        assertEquals("Berth 1", PortTableModel.berthLabel("berth_1"));
        assertEquals("Berth 4", PortTableModel.berthLabel("berth_4"));
        assertEquals("—", PortTableModel.berthLabel(null));
        assertEquals("—", PortTableModel.berthLabel(" "));
    }

    // --- why a ship waits (S16) ----------------------------------------------------------------------

    @Test
    void aShipHeldByTheWeatherNamesTheRulesThatHoldIt() {
        BerthRow row = waiting(Wait.WEATHER, "R15", "R19");

        assertEquals("held (weather) [R15,R19]", PortTableModel.stateText(row, 1));
        BerthLine line = modelWith(status(1, List.of(), row)).berthRows().get(1);
        assertEquals("Ligurian Dawn", line.occupant());
        assertEquals("held (weather) [R15,R19]", line.state());
        assertTrue(line.waiting(), "a reason is the long kind of state: the panel gives it its own line");
        assertEquals("Berth 2 · Ligurian Dawn · held (weather) [R15,R19]", line.fullText());

        assertEquals("held (weather) [R15,R16,R19]",
                PortTableModel.stateText(waiting(Wait.WEATHER, "R15", "R16", "R19"), 1));
        assertEquals("held (weather) [R17]", PortTableModel.stateText(waiting(Wait.WEATHER, "R17"), 1));
    }

    @Test
    void aWeatherHoldWithNoRuleIdsHasNoEmptyBracket() {
        assertEquals("held (weather)", PortTableModel.stateText(waiting(Wait.WEATHER), 1));
        // The pre-S16 shape of the event — a bare flag — is such a hold. Before S16 this row read
        // "weather hold".
        BerthRow legacy = new BerthRow("berth_2", "W004", "Ligurian Dawn", Channel.WALK_IN, Stage.AWAITING_TUG,
                null, true);
        assertEquals("held (weather)", PortTableModel.stateText(legacy, 1));
        assertEquals("held (weather)", PortTableModel.stateText(
                new BerthRow("berth_2", "W004", "Ligurian Dawn", Channel.WALK_IN, Stage.IN_TRANSIT, null, true), 1));
    }

    @Test
    void aShipReadyToLeaveButHeldSaysDepartureHeld() {
        assertEquals("departure held (weather) [R19]",
                PortTableModel.stateText(waiting(Stage.DOCKED, null, Wait.DEPARTURE_WEATHER, "R19"), 1));
        assertEquals("departure held (weather)",
                PortTableModel.stateText(waiting(Stage.DOCKED, null, Wait.DEPARTURE_WEATHER), 1));
    }

    @Test
    void waitingForCustomsSaysInspectionOnlyWhenARuleOrderedOne() {
        assertEquals("waiting: customs inspection [R22,R24]",
                PortTableModel.stateText(waiting(Wait.CUSTOMS, "R22", "R24"), 1));
        assertEquals("waiting: customs", PortTableModel.stateText(waiting(Wait.CUSTOMS), 1),
                "the request is out and nothing has been ordered: no inspection is claimed");
    }

    @Test
    void waitingForTugsAndForAClosedBerth() {
        assertEquals("waiting: tugs", PortTableModel.stateText(waiting(Wait.TUGS), 1));
        assertEquals("waiting: berth closed (flood)", PortTableModel.stateText(waiting(Wait.BERTH_CLOSED), 1));

        BerthLine line = modelWith(status(1, List.of(), waiting(Wait.TUGS))).berthRows().get(1);
        assertEquals("Berth 2 · Ligurian Dawn · waiting: tugs", line.fullText());
        assertTrue(line.waiting());
    }

    @Test
    void aFreeBerthThatIsClosedReadsClosedNotFree() {
        PortTableModel model = modelWith(status(1, List.of(), BerthRow.closedFree("berth_2")));

        BerthLine closed = model.berthRows().get(1);
        assertEquals("closed (flood)", closed.occupant());
        assertEquals("", closed.state());
        assertFalse(closed.waiting(), "nobody is waiting there: it is one short cell, not a reason line");
        assertFalse(closed.isUnknown());
        assertEquals("Berth 2 · closed (flood)", closed.fullText());
        assertEquals("Berth 1 · free", model.berthRows().get(0).fullText(), "the open berths still read free");
    }

    @Test
    void aWaitOfTheWayInNeverHidesAnUntilButADepartureHoldDoes() {
        // An "until" is the vessel's own docked-phase deadline: the ship is moored and being
        // served, so a wait of the way in reported with it is the HarbourMaster's tracking being
        // a tick behind. (S15 pinned this for the weather flag; S16 keeps it for all four.)
        for (Wait wayIn : List.of(Wait.WEATHER, Wait.BERTH_CLOSED, Wait.CUSTOMS, Wait.TUGS)) {
            BerthRow row = waiting(Stage.DOCKED, DAY1_1430, wayIn, "R15");
            assertEquals("until 14:30", PortTableModel.stateText(row, 1), wayIn.toString());
            assertFalse(PortTableModel.showsWait(row), wayIn.toString());
            assertFalse(modelWith(status(1, List.of(), row)).berthRows().get(1).waiting(), wayIn.toString());
        }
        // The departure hold is only ever reported for a ship that HAS been served.
        BerthRow leaving = waiting(Stage.DOCKED, DAY1_1430, Wait.DEPARTURE_WEATHER, "R15");
        assertEquals("departure held (weather) [R15]", PortTableModel.stateText(leaving, 1));
        assertTrue(PortTableModel.showsWait(leaving));
    }

    @ParameterizedTest
    @EnumSource(Wait.class)
    void everyKindOfWaitHasItsOwnWords(Wait wait) {
        // A kind added to the enum later must not silently print nothing.
        String text = PortTableModel.waitText(waiting(wait));

        if (wait == Wait.NONE) {
            assertEquals("", text);
            assertFalse(PortTableModel.showsWait(waiting(wait)));
        } else {
            assertFalse(text.isBlank(), wait + " prints nothing");
            assertFalse(text.contains("["), wait + " with no rule ids must not print a bracket: " + text);
            assertTrue(PortTableModel.showsWait(waiting(wait)), wait.toString());
            assertTrue(PortTableModel.waitText(waiting(wait, "R1", "R2")).endsWith(" [R1,R2]"),
                    wait + " drops the rule ids it was given");
        }
    }

    @Test
    void ruleIdsArePrintedInTheBracketFormTheCommLogUses() {
        assertEquals("[R15]", PortTableModel.ruleTag(List.of("R15")));
        assertEquals("[R15,R16,R19]", PortTableModel.ruleTag(List.of("R15", "R16", "R19")));
        assertEquals("", PortTableModel.ruleTag(List.of()));
        assertEquals("", PortTableModel.ruleTag(null));
    }

    // --- queue -------------------------------------------------------------------------------------

    @Test
    void theQueueLineCountsAndNamesWhoIsWaiting() {
        PortTableModel model = new PortTableModel();

        model.applyStatus(status(1, List.of()));
        assertEquals(new InfoLine("Queue", "none", ""), model.queueRow());
        assertEquals("none", model.queueRow().text());
        assertEquals("Queue · none", model.queueRow().fullText());

        model.applyStatus(status(1, List.of("Genoa Star")));
        assertEquals(new InfoLine("Queue", "1 waiting", "Genoa Star"), model.queueRow());

        model.applyStatus(status(1, List.of("Genoa Star", "C502")));
        InfoLine two = model.queueRow();
        assertEquals("2 waiting", two.value());
        assertEquals("Genoa Star, C502", two.detail(), "names in arrival order, as the event lists them");
        assertEquals("2 waiting · Genoa Star, C502", two.text());
        assertEquals("Queue · 2 waiting · Genoa Star, C502", two.fullText());
    }

    @Test
    void queueTextNeverGoesNegative() {
        assertEquals("none", PortTableModel.queueText(0));
        assertEquals("none", PortTableModel.queueText(-1));
        assertEquals("12 waiting", PortTableModel.queueText(12));
    }

    // --- tugs --------------------------------------------------------------------------------------

    @Test
    void idleAndBiddingTugsAreFreeEveryOtherStatusIsBusy() {
        assertEquals("3 free · 1 busy", PortTableModel.tugSummary(
                List.of(TugStatus.IDLE, TugStatus.BIDDING, TugStatus.ESCORTING, TugStatus.IDLE)));
        assertEquals("0 free · 4 busy", PortTableModel.tugSummary(List.of(TugStatus.EN_ROUTE_TO_VESSEL,
                TugStatus.ESCORTING, TugStatus.RETURNING, TugStatus.REFUELING)));
        assertEquals("4 free · 0 busy", PortTableModel.tugSummary(
                List.of(TugStatus.IDLE, TugStatus.IDLE, TugStatus.BIDDING, TugStatus.BIDDING)),
                "the line keeps its shape when nothing is busy");
        assertEquals("—", PortTableModel.tugSummary(List.of()), "no tug has reported: unknown, not \"0 free\"");
        assertEquals("—", PortTableModel.tugSummary(null));
    }

    @Test
    void everyTugStatusIsCountedOnOneSideOrTheOther() {
        // A status added to the enum later must not silently vanish from the line.
        for (TugStatus tugStatus : TugStatus.values()) {
            String summary = PortTableModel.tugSummary(List.of(tugStatus));
            assertTrue(summary.equals("1 free · 0 busy") || summary.equals("0 free · 1 busy"),
                    tugStatus + " -> " + summary);
        }
    }

    @Test
    void theTugLineFollowsSeedsAndDeltas() {
        PortTableModel model = new PortTableModel();
        Map<String, TugStatus> fleet = new LinkedHashMap<>();
        for (int i = 1; i <= 4; i++) {
            fleet.put("tug_" + i, TugStatus.IDLE);
        }

        model.seedTugs(fleet);
        assertEquals("4 free · 0 busy", model.tugRow().value());

        model.applyTug("tug_2", TugStatus.BIDDING);
        assertEquals("4 free · 0 busy", model.tugRow().value(), "bidding is still at its base");
        model.applyTug("tug_2", TugStatus.EN_ROUTE_TO_VESSEL);
        model.applyTug("tug_3", TugStatus.ESCORTING);
        assertEquals("2 free · 2 busy", model.tugRow().value());
        model.applyTug("tug_2", TugStatus.IDLE);
        assertEquals("3 free · 1 busy", model.tugRow().value());
        assertEquals("", model.tugRow().detail());
    }

    @Test
    void aSeedReplacesTheFleetItDoesNotMergeIntoIt() {
        PortTableModel model = new PortTableModel();
        model.applyTug("tug_old", TugStatus.ESCORTING);

        model.seedTugs(Map.of("tug_1", TugStatus.IDLE));
        assertEquals("1 free · 0 busy", model.tugRow().value(), "a tug of the previous world may not linger");

        model.seedTugs(Map.of());
        assertEquals("—", model.tugRow().value());
        model.seedTugs(null);
        assertEquals("—", model.tugRow().value());
    }

    // --- numbers -----------------------------------------------------------------------------------

    @Test
    void theNumbersDoNotFollowTheDefaultLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            // German writes a decimal comma, like a Danish or Italian desktop.
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("18,4", String.format("%.1f", 18.4), "non-vacuity: the default locale is now German");
            assertEquals("until 14:30", PortTableModel.untilText(DAY1_1430, 1));

            // A locale whose default-format digits are not ASCII: without Locale.ROOT the clock
            // would be printed in Thai digits on such a desktop.
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("th-TH-u-nu-thai"));
            assertNotEquals("18", String.format("%.0f", 18.4), "non-vacuity: this default locale has its own digits");
            assertEquals("until D2 02:15", PortTableModel.untilText(DAY2_0215, 1));

            PortTableModel model = modelWith(status(1, List.of("Genoa Star", "C502"),
                    held("berth_1", "C501", "C501", Stage.DOCKED, DAY1_1430)));
            model.seedTugs(Map.of("tug_1", TugStatus.IDLE, "tug_2", TugStatus.ESCORTING));
            assertEquals("until 14:30", model.berthRows().get(0).state());
            assertEquals("2 waiting", model.queueRow().value());
            assertEquals("1 free · 1 busy", model.tugRow().value());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    // --- change notification -----------------------------------------------------------------------

    @Test
    void anEqualStatusFiresNoChange() {
        PortTableModel model = new PortTableModel();
        AtomicInteger changes = new AtomicInteger();
        model.setOnChange(changes::incrementAndGet);

        model.applyStatus(status(1, List.of("Genoa Star"),
                held("berth_1", "C501", "C501", Stage.DOCKED, DAY1_1430)));
        assertEquals(1, changes.get());

        // A separately built but equal event: what a re-announce on request delivers.
        model.applyStatus(status(1, List.of("Genoa Star"),
                held("berth_1", "C501", "C501", Stage.DOCKED, DAY1_1430)));
        assertEquals(1, changes.get(), "record equality is the change test");

        model.applyStatus(status(2, List.of("Genoa Star"),
                held("berth_1", "C501", "C501", Stage.DOCKED, DAY1_1430)));
        assertEquals(2, changes.get(), "a new game day alone is a change: the until text depends on it");
        assertEquals("until D1 14:30", model.berthRows().get(0).state());
    }

    @Test
    void aChangeOfTheReasonOrOfItsRulesIsAChange() {
        // S16: the reason and its rule ids are part of the event's equality, so the table follows
        // a hold that changes its rules (the wind drops, the sea state does not) without a tick
        // being lost to "nothing changed".
        PortTableModel model = new PortTableModel();
        AtomicInteger changes = new AtomicInteger();
        model.setOnChange(changes::incrementAndGet);

        model.applyStatus(status(1, List.of(), waiting(Wait.WEATHER, "R15", "R19")));
        model.applyStatus(status(1, List.of(), waiting(Wait.WEATHER, "R15", "R19")));
        assertEquals(1, changes.get(), "the same hold again is not a change");

        model.applyStatus(status(1, List.of(), waiting(Wait.WEATHER, "R19")));
        assertEquals(2, changes.get());
        assertEquals("held (weather) [R19]", model.berthRows().get(1).state());

        model.applyStatus(status(1, List.of(), waiting(Wait.TUGS)));
        assertEquals(3, changes.get());
        assertEquals("waiting: tugs", model.berthRows().get(1).state());

        model.applyStatus(status(1, List.of(), BerthRow.closedFree("berth_2")));
        assertEquals(4, changes.get());
        model.applyStatus(status(1, List.of(), BerthRow.free("berth_2")));
        assertEquals(5, changes.get(), "a berth reopening is a change too");
        assertEquals("free", model.berthRows().get(1).occupant());
    }

    @Test
    void tugRepeatsFireNoChangeEither() {
        PortTableModel model = new PortTableModel();
        AtomicInteger changes = new AtomicInteger();
        model.setOnChange(changes::incrementAndGet);

        model.applyTug("tug_1", TugStatus.ESCORTING);
        assertEquals(1, changes.get());
        model.applyTug("tug_1", TugStatus.ESCORTING); // a moving tug republishes every transit tick
        model.applyTug(null, TugStatus.IDLE);
        model.applyTug("tug_1", null);
        assertEquals(1, changes.get());
        assertEquals("0 free · 1 busy", model.tugRow().value(), "a null id or status changes nothing");

        model.seedTugs(Map.of("tug_1", TugStatus.ESCORTING));
        assertEquals(1, changes.get(), "a seed equal to what is held is not a change");
        model.seedTugs(Map.of("tug_1", TugStatus.IDLE));
        assertEquals(2, changes.get());
    }

    @Test
    void clearStatusBlanksBerthsAndQueueButKeepsTheTugs() {
        PortStatusEvent event = status(1, List.of("Genoa Star"),
                held("berth_1", "C501", "C501", Stage.DOCKED, DAY1_1430),
                waiting(Wait.WEATHER, "R19"));
        PortTableModel model = modelWith(event);
        model.seedTugs(Map.of("tug_1", TugStatus.IDLE, "tug_2", TugStatus.ESCORTING));
        AtomicInteger changes = new AtomicInteger();
        model.setOnChange(changes::incrementAndGet);

        model.clearStatus();

        assertEquals(1, changes.get());
        for (BerthLine line : model.berthRows()) {
            assertEquals("—", line.occupant(), "back to unknown, not to free");
            assertEquals("", line.state());
            assertFalse(line.waiting(), "the old world's hold is gone with it");
        }
        assertEquals(new InfoLine("Queue", "—", ""), model.queueRow());
        assertEquals("1 free · 1 busy", model.tugRow().value(), "tugs are kept");

        model.clearStatus();
        assertEquals(1, changes.get(), "clearing an already blank table is not a change");

        // The new world's HarbourMaster re-announces; even a status equal to the one shown before
        // the clear must come back, because the clear forgot it.
        model.applyStatus(event);
        assertEquals(2, changes.get());
        assertEquals("C501", model.berthRows().get(0).occupant());
        assertEquals("1 waiting", model.queueRow().value());
    }

    @Test
    void theRowListIsASnapshotNotAView() {
        PortTableModel model = modelWith(status(1, List.of()));
        List<BerthLine> before = model.berthRows();

        model.applyStatus(status(1, List.of(), held("berth_1", "C501", "C501", Stage.DOCKED, null)));

        assertEquals("free", before.get(0).occupant(), "a list handed out earlier does not change under its reader");
        assertEquals("C501", model.berthRows().get(0).occupant());
    }

    // --- the model/view split ----------------------------------------------------------------------

    @Test
    void theLeftColumnAndTopBarModelsImportNoSwingOrAwt() throws IOException {
        // INVARIANTS (task 18): gui/model is plain Java, so it is tested off the EDT. Patterns are
        // matched against the whole source, so not even a fully-qualified use slips through.
        // S16: the two models behind the top bar's weather joined the list.
        for (String name : List.of("PortTableModel", "WaitingListModel", "ColumnDividers", "HudWeatherModel",
                "WeatherForecast")) {
            Path file = Path.of("src/main/java/it/unige/portcommand/gui/model/" + name + ".java");
            String source = Files.readString(file);
            assertFalse(source.contains("java.awt"), file + " must not use AWT");
            assertFalse(source.contains("javax.swing"), file + " must not use Swing");
        }
    }
}
