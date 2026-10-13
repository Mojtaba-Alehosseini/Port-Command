package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;

import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.PortStatusEvent.BerthRow;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Port table's snapshot rules (S15, 2026-10-12), on the pure builder — no agent, no clock,
 * no bus. Every rule {@link PortStatusSnapshot}'s javadoc states has a case here, and the two
 * that guard against showing something FALSE get the sharpest ones: a walk-in that is still
 * negotiating already carries the berth it is discussing in its tracking, and a vessel waiting
 * for a berth is not in one — neither may appear as an occupant.
 *
 * <p>The wiring (the HarbourMaster really feeds its tracking, its queue and the vessel's phase
 * deadline in here, and publishes the result) is {@code HarbourMasterAgentIT}'s two
 * {@code portStatus_*} cases.
 */
class PortStatusSnapshotTest {

    private static final int DAY = 1;
    /** 10:00 on day 1. */
    private static final long NOW = 36_000_000L;
    private static final Function<VesselTracking, OptionalLong> NOBODY_DOCKED = vessel -> OptionalLong.empty();

    /** A tracked vessel; {@code name} null is a contracted-style vessel without a display name. */
    private static VesselTracking vessel(String id, String name, Channel channel, Stage stage, String berth) {
        VesselSpec spec = new VesselSpec(id, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L, name);
        return new VesselTracking(id, spec, channel, stage, null, berth, List.of(), false, false);
    }

    private static BerthRow row(PortStatusEvent status, String berthId) {
        return status.berths().stream()
                .filter(r -> berthId.equals(r.berthId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + berthId + " in " + status));
    }

    private static List<String> berthIds(PortStatusEvent status) {
        return status.berths().stream().map(BerthRow::berthId).toList();
    }

    @Test
    void anEmptyPortIsFourFreeRowsInBerthIdOrder() {
        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(), List.of(), NOBODY_DOCKED);

        assertEquals(List.of(BerthRow.free("berth_1"), BerthRow.free("berth_2"),
                        BerthRow.free("berth_3"), BerthRow.free("berth_4")), status.berths(),
                "an empty port is still the four berths, in id order, all free");
        assertTrue(status.berths().stream().allMatch(BerthRow::isFree));
        assertEquals(BerthPositions.all().keySet().stream().sorted().toList(), berthIds(status),
                "the rows are exactly the canonical berth table's ids, sorted");
        assertEquals(List.of(), status.queued());
        assertEquals(0, status.queuedCount());
        assertEquals(DAY, status.gameDay());
    }

    @Test
    void negotiatingAndQueuedVesselsHoldNothing_andTheQueuedOneAppearsByName() {
        // The trap this rule exists for: the negotiating walk-in's tracking ALREADY names berth_1
        // (the dialogue's advisory candidate). Showing it would put a vessel in a berth it may
        // never get. An ARRIVING entry with a berth is not produced today, but the stage rule
        // must not depend on that.
        VesselTracking negotiating = vessel("WALKIN-1", "Stella Maris", Channel.WALK_IN, Stage.NEGOTIATING, "berth_1");
        VesselTracking arriving = vessel("C900", null, Channel.CONTRACTED, Stage.ARRIVING, "berth_2");
        VesselTracking queued = vessel("WALKIN-2", "Aurora", Channel.WALK_IN, Stage.AWAITING_BERTH, null);
        // The queue path always nulls the berth; a non-null one must still not count as held.
        VesselTracking queuedWithStaleBerth =
                vessel("WALKIN-3", "Zephyr", Channel.WALK_IN, Stage.AWAITING_BERTH, "berth_3");

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW,
                List.of(negotiating, arriving, queued, queuedWithStaleBerth),
                List.of("WALKIN-2", "WALKIN-3"), NOBODY_DOCKED);

        assertTrue(status.berths().stream().allMatch(BerthRow::isFree),
                "no vessel before its grant may occupy a berth row: " + status);
        assertEquals(List.of("Aurora", "Zephyr"), status.queued(),
                "the waiting vessels are listed by display name, in the order given");
        assertEquals(2, status.queuedCount());
    }

    @Test
    void everyGrantedStageHoldsItsBerth_withChannelStageAndIdFallbackForTheName() {
        VesselTracking awaitingTug = vessel("C501", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_1");
        VesselTracking inTransit = vessel("WALKIN-4", "Stella Maris", Channel.WALK_IN, Stage.IN_TRANSIT, "berth_2");
        VesselTracking docked = vessel("C502", null, Channel.CONTRACTED, Stage.DOCKED, "berth_3");
        VesselTracking departing = vessel("WALKIN-5", null, Channel.WALK_IN, Stage.DEPARTING, "berth_4");

        // Deliberately not in berth order: the rows must come out sorted whatever the map yields.
        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW,
                List.of(departing, docked, awaitingTug, inTransit), List.of(), NOBODY_DOCKED);

        assertEquals(List.of("berth_1", "berth_2", "berth_3", "berth_4"), berthIds(status));
        assertEquals(new BerthRow("berth_1", "C501", "C501", Channel.CONTRACTED, Stage.AWAITING_TUG, null, false),
                row(status, "berth_1"), "a vessel without a display name is shown by its id");
        assertEquals(new BerthRow("berth_2", "WALKIN-4", "Stella Maris", Channel.WALK_IN, Stage.IN_TRANSIT,
                        null, false),
                row(status, "berth_2"), "a walk-in holds its berth like anyone else, under its own name");
        assertEquals(new BerthRow("berth_3", "C502", "C502", Channel.CONTRACTED, Stage.DOCKED, null, false),
                row(status, "berth_3"));
        assertEquals(new BerthRow("berth_4", "WALKIN-5", "WALKIN-5", Channel.WALK_IN, Stage.DEPARTING, null, false),
                row(status, "berth_4"), "a departing vessel still holds the berth until it reports departed");
        assertTrue(status.berths().stream().noneMatch(BerthRow::isFree));
    }

    @Test
    void untilIsKeptOnlyWhileItIsStillInTheFuture() {
        VesselTracking future = vessel("C501", null, Channel.CONTRACTED, Stage.DOCKED, "berth_1");
        VesselTracking exactlyNow = vessel("C502", null, Channel.CONTRACTED, Stage.DOCKED, "berth_2");
        VesselTracking past = vessel("C503", null, Channel.CONTRACTED, Stage.DOCKED, "berth_3");
        VesselTracking notDockedYet = vessel("C504", null, Channel.CONTRACTED, Stage.IN_TRANSIT, "berth_4");
        Map<String, OptionalLong> deadlines = Map.of(
                "C501", OptionalLong.of(NOW + 1),
                "C502", OptionalLong.of(NOW),
                "C503", OptionalLong.of(NOW - 1),
                "C504", OptionalLong.empty());

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW,
                List.of(future, exactlyNow, past, notDockedYet), List.of(),
                tracked -> deadlines.get(tracked.vesselId()));

        assertEquals(Long.valueOf(NOW + 1), row(status, "berth_1").untilSimMillis(),
                "a service end still ahead is the row's 'until'");
        assertNull(row(status, "berth_2").untilSimMillis(), "a deadline that is now has been served out");
        assertNull(row(status, "berth_3").untilSimMillis(),
                "a deadline in the past is never shown (also what a torn phase read would produce)");
        assertNull(row(status, "berth_4").untilSimMillis(), "no deadline known means no 'until'");
        // Dropping the time never drops the occupant.
        assertEquals("C502", row(status, "berth_2").vesselId());
        assertEquals("C503", row(status, "berth_3").vesselId());
    }

    @Test
    void theQueueFollowsArrivalOrder_notIdOrTrackingOrder() {
        VesselTracking a = vessel("WALKIN-1", "Aurora", Channel.WALK_IN, Stage.AWAITING_BERTH, null);
        VesselTracking b = vessel("C700", null, Channel.CONTRACTED, Stage.AWAITING_BERTH, null);
        VesselTracking c = vessel("WALKIN-3", "Zephyr", Channel.WALK_IN, Stage.AWAITING_BERTH, null);

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(a, b, c),
                List.of("WALKIN-3", "GHOST-9", "WALKIN-1", "C700"), NOBODY_DOCKED);

        assertEquals(List.of("Zephyr", "GHOST-9", "Aurora", "C700"), status.queued(),
                "arrival order as given; a name where there is one, the id otherwise — also for an id "
                        + "that is queued but no longer tracked");
        assertEquals(4, status.queuedCount());
    }

    @Test
    void equalInputsGiveEqualEvents_andADayChangeAloneMakesThemDiffer() {
        VesselTracking docked = vessel("C501", null, Channel.CONTRACTED, Stage.DOCKED, "berth_1");
        VesselTracking waiting = vessel("WALKIN-1", "Aurora", Channel.WALK_IN, Stage.AWAITING_BERTH, null);
        Function<VesselTracking, OptionalLong> until = tracked -> OptionalLong.of(NOW + 7_200_000L);

        PortStatusEvent first = PortStatusSnapshot.build(DAY, NOW, List.of(docked, waiting),
                List.of("WALKIN-1"), until);
        // A minute later, the collection in another order, a fresh but equal queue list.
        PortStatusEvent later = PortStatusSnapshot.build(DAY, NOW + 60_000L, List.of(waiting, docked),
                new ArrayList<>(List.of("WALKIN-1")), until);
        PortStatusEvent nextDay = PortStatusSnapshot.build(DAY + 1, NOW, List.of(docked, waiting),
                List.of("WALKIN-1"), until);

        assertEquals(first, later,
                "the event carries no timestamp: an unchanged port a tick later is an EQUAL event, "
                        + "which is what keeps a quiet port off the bus");
        assertEquals(first.hashCode(), later.hashCode());
        assertNotEquals(first, nextDay, "midnight alone must republish: the day is part of the key");
        assertEquals(first.berths(), nextDay.berths());
        assertEquals(first.queued(), nextDay.queued());
    }

    @Test
    void aRealChangeMakesTheEventsDiffer() {
        VesselTracking docked = vessel("C501", null, Channel.CONTRACTED, Stage.DOCKED, "berth_1");
        PortStatusEvent before = PortStatusSnapshot.build(DAY, NOW, List.of(docked), List.of(), NOBODY_DOCKED);

        assertNotEquals(before, PortStatusSnapshot.build(DAY, NOW,
                List.of(docked.withStage(Stage.DEPARTING)), List.of(), NOBODY_DOCKED), "a stage change");
        assertNotEquals(before, PortStatusSnapshot.build(DAY, NOW,
                List.of(docked), List.of("WALKIN-1"), NOBODY_DOCKED), "a vessel joining the queue");
        assertNotEquals(before, PortStatusSnapshot.build(DAY, NOW,
                List.of(docked), List.of(), tracked -> OptionalLong.of(NOW + 1)), "an 'until' becoming known");
        assertNotEquals(before, PortStatusSnapshot.build(DAY, NOW, List.of(), List.of(), NOBODY_DOCKED),
                "the berth freeing");
    }

    @Test
    void aWeatherHoldIsCarriedOnTheRow() {
        VesselTracking held = vessel("C501", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_1")
                .withWeatherHeld(true);
        VesselTracking clear = vessel("C502", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_2");

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(held, clear), List.of(), NOBODY_DOCKED);

        assertTrue(row(status, "berth_1").weatherHeld());
        assertFalse(row(status, "berth_2").weatherHeld());
    }

    // ---- S16 (ADR-20): why a granted ship is not moving, and closed berths --------------------

    @Test
    void theRowCarriesTheReasonAShipIsWaitingAndTheRulesBehindIt() {
        VesselTracking weather = vessel("C501", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_1");
        VesselTracking customs = vessel("C502", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_2");
        VesselTracking tugs = vessel("C503", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_3");
        VesselTracking leaving = vessel("C504", null, Channel.CONTRACTED, Stage.DOCKED, "berth_4");
        Function<VesselTracking, PortStatusSnapshot.WaitInfo> waitOf = tracked -> switch (tracked.vesselId()) {
            case "C501" -> new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.WEATHER, List.of("R15", "R19"));
            case "C502" -> new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.CUSTOMS, List.of("R22", "R24"));
            case "C503" -> new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.TUGS, List.of());
            default -> new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.DEPARTURE_WEATHER, List.of("R16"));
        };

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(weather, customs, tugs, leaving),
                List.of(), NOBODY_DOCKED, waitOf, berthId -> false);

        assertEquals(PortStatusEvent.Wait.WEATHER, row(status, "berth_1").waiting());
        assertEquals(List.of("R15", "R19"), row(status, "berth_1").ruleIds());
        assertTrue(row(status, "berth_1").weatherHeld(), "the pre-S16 flag still reads a weather hold");
        assertEquals(PortStatusEvent.Wait.CUSTOMS, row(status, "berth_2").waiting());
        assertEquals(List.of("R22", "R24"), row(status, "berth_2").ruleIds());
        assertEquals(PortStatusEvent.Wait.TUGS, row(status, "berth_3").waiting());
        assertEquals(List.of(), row(status, "berth_3").ruleIds());
        assertEquals(PortStatusEvent.Wait.DEPARTURE_WEATHER, row(status, "berth_4").waiting());
        assertFalse(row(status, "berth_4").weatherHeld(),
                "a moored ship waiting to leave is not 'held' - it is at its berth");
    }

    @Test
    void aShipThatIsNotWaitingSaysSo_andANullAnswerIsNotAnError() {
        VesselTracking moving = vessel("C501", null, Channel.CONTRACTED, Stage.IN_TRANSIT, "berth_1");

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(moving), List.of(), NOBODY_DOCKED,
                tracked -> null, null);

        assertEquals(PortStatusEvent.Wait.NONE, row(status, "berth_1").waiting());
        assertEquals(List.of(), row(status, "berth_1").ruleIds());
        assertFalse(row(status, "berth_1").closed());
    }

    @Test
    void aClosedBerthIsMarkedWhetherOrNotAShipHoldsIt() {
        VesselTracking waitingForIt = vessel("C501", null, Channel.CONTRACTED, Stage.AWAITING_TUG, "berth_1");

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(waitingForIt), List.of(), NOBODY_DOCKED,
                tracked -> new PortStatusSnapshot.WaitInfo(PortStatusEvent.Wait.BERTH_CLOSED, List.of()),
                berthId -> "berth_1".equals(berthId) || "berth_3".equals(berthId));

        assertTrue(row(status, "berth_1").closed());
        assertEquals("C501", row(status, "berth_1").vesselId(), "a closed berth keeps the ship it was granted to");
        assertEquals(PortStatusEvent.Wait.BERTH_CLOSED, row(status, "berth_1").waiting());
        assertTrue(row(status, "berth_3").closed());
        assertTrue(row(status, "berth_3").isFree(), "closed and unheld: no vessel, but not available either");
        assertEquals(BerthRow.closedFree("berth_3"), row(status, "berth_3"));
        assertFalse(row(status, "berth_2").closed());
        assertNotEquals(BerthRow.free("berth_3"), row(status, "berth_3"),
                "closing a berth changes the snapshot, so the Port table is republished");
    }

    @Test
    void dockedUntilIsAskedOnlyAboutVesselsThatHoldABerth() {
        VesselTracking holder = vessel("C501", null, Channel.CONTRACTED, Stage.DOCKED, "berth_1");
        VesselTracking negotiating = vessel("WALKIN-1", "Aurora", Channel.WALK_IN, Stage.NEGOTIATING, "berth_2");
        VesselTracking queued = vessel("WALKIN-2", "Zephyr", Channel.WALK_IN, Stage.AWAITING_BERTH, null);
        List<String> asked = new ArrayList<>();

        PortStatusSnapshot.build(DAY, NOW, List.of(holder, negotiating, queued), List.of("WALKIN-2"),
                tracked -> {
                    asked.add(tracked.vesselId());
                    return OptionalLong.empty();
                });

        assertEquals(List.of("C501"), asked,
                "the lookup reads another agent's state across threads — it is made once per holder, "
                        + "never for a vessel that is not shown in a berth");
    }

    @Test
    void aBerthThatIsNotOneOfThePortsFourIsNotARow() {
        VesselTracking elsewhere = vessel("C501", null, Channel.CONTRACTED, Stage.DOCKED, "berth_9");

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(elsewhere), List.of(), NOBODY_DOCKED);

        assertEquals(List.of("berth_1", "berth_2", "berth_3", "berth_4"), berthIds(status),
                "the table has a fixed shape");
        assertTrue(status.berths().stream().allMatch(BerthRow::isFree));
    }

    @Test
    void twoClaimantsOfOneBerthResolveTheSameWayWhateverTheIterationOrder() {
        VesselTracking earlier = vessel("C501", null, Channel.CONTRACTED, Stage.IN_TRANSIT, "berth_1");
        VesselTracking furtherAlong = vessel("C999", null, Channel.CONTRACTED, Stage.DOCKED, "berth_1");
        VesselTracking sameStageLowerId = vessel("C100", null, Channel.CONTRACTED, Stage.DOCKED, "berth_2");
        VesselTracking sameStageHigherId = vessel("C200", null, Channel.CONTRACTED, Stage.DOCKED, "berth_2");

        PortStatusEvent oneOrder = PortStatusSnapshot.build(DAY, NOW,
                List.of(earlier, furtherAlong, sameStageLowerId, sameStageHigherId), List.of(), NOBODY_DOCKED);
        PortStatusEvent otherOrder = PortStatusSnapshot.build(DAY, NOW,
                List.of(sameStageHigherId, sameStageLowerId, furtherAlong, earlier), List.of(), NOBODY_DOCKED);

        assertEquals("C999", row(oneOrder, "berth_1").vesselId(), "the vessel further along its stay is shown");
        assertEquals("C100", row(oneOrder, "berth_2").vesselId(), "the lower id breaks a same-stage tie");
        assertEquals(oneOrder, otherOrder,
                "a double claim must not make the status flap from tick to tick with map iteration order");
    }

    @Test
    void aBlankNameFallsBackToTheId() {
        VesselTracking blank = vessel("WALKIN-7", "  ", Channel.WALK_IN, Stage.DOCKED, "berth_1");
        VesselTracking blankQueued = vessel("WALKIN-8", "", Channel.WALK_IN, Stage.AWAITING_BERTH, null);

        PortStatusEvent status = PortStatusSnapshot.build(DAY, NOW, List.of(blank, blankQueued),
                List.of("WALKIN-8"), NOBODY_DOCKED);

        assertEquals("WALKIN-7", row(status, "berth_1").vesselName());
        assertEquals(List.of("WALKIN-8"), status.queued());
    }

    @Test
    void missingInputsReadAsNothingKnown_neverAsAnError() {
        PortStatusEvent nothing = PortStatusSnapshot.build(DAY, NOW, null, null, null);

        assertEquals(4, nothing.berths().size());
        assertTrue(nothing.berths().stream().allMatch(BerthRow::isFree));
        assertEquals(List.of(), nothing.queued());

        // A holder with no lookup at all, and with a lookup that answers null: no 'until', still held.
        VesselTracking docked = vessel("C501", null, Channel.CONTRACTED, Stage.DOCKED, "berth_1");
        PortStatusEvent noLookup = PortStatusSnapshot.build(DAY, NOW, List.of(docked), List.of(), null);
        PortStatusEvent nullAnswer = PortStatusSnapshot.build(DAY, NOW, List.of(docked), List.of(), tracked -> null);

        assertEquals("C501", row(noLookup, "berth_1").vesselId());
        assertNull(row(noLookup, "berth_1").untilSimMillis());
        assertEquals(noLookup, nullAnswer);
    }
}
