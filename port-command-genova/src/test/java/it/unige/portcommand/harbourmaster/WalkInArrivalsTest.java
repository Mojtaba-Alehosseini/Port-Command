package it.unige.portcommand.harbourmaster;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import it.unige.portcommand.harbourmaster.WalkInArrivals.Candidate;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playtest 4, PT21 - the berth-2 bottleneck - as a rule of its own: the next walk-in is drawn
 * among the ships that fit a berth that is free now, and "Next ship" says who is waiting for what.
 */
class WalkInArrivalsTest {

    private static final Candidate TANKER = new Candidate("tanker", 12.0, 200.0, 40000);
    private static final Candidate CONTAINER = new Candidate("container_vessel", 13.0, 280.0, 60000);
    private static final Candidate CARGO = new Candidate("cargo_vessel", 9.0, 150.0, 30000);
    private static final Candidate FERRY = new Candidate("ferry", 6.0, 180.0, 20000);
    private static final Candidate CRUISE = new Candidate("cruise_ship", 8.0, 300.0, 90000);
    private static final List<Candidate> ALL = List.of(TANKER, CONTAINER, CARGO, FERRY, CRUISE);

    /** Berth 2 is taken: the two types that fit only berth 2 must not be drawn - over many
     * draws, not one lucky one. */
    @Test
    void aShipThatFitsOnlyATakenBerthIsNeverDrawn() {
        Set<String> berth2Only = Set.of("container_vessel", "cruise_ship");
        Random draw = new Random(42);
        for (int i = 0; i < 500; i++) {
            Candidate next = WalkInArrivals.choose(ALL, c -> !berth2Only.contains(c.vesselType()), draw);
            assertTrue(!berth2Only.contains(next.vesselType()), "drawn " + next.vesselType() + " at draw " + i);
        }
    }

    /** Every type that fits is drawn - the rule removes the impossible, it does not pick one. */
    @Test
    void everyFittingTypeStillComes() {
        Random draw = new Random(7);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(WalkInArrivals.choose(ALL, c -> true, draw).vesselType());
        }
        assertEquals(Set.of("tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship"), seen);
    }

    /** With no berth free for anyone, the draw is over all types, as before PT21. */
    @Test
    void whenNothingFitsAnyTypeMayCome() {
        Random draw = new Random(3);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(WalkInArrivals.choose(ALL, c -> false, draw).vesselType());
        }
        assertEquals(5, seen.size());
    }

    @Test
    void thereMustBeSomethingToDrawFrom() {
        assertThrows(IllegalArgumentException.class, () -> WalkInArrivals.choose(List.of(), c -> true, new Random()));
    }

    // --- the "Next ship" refusal ----------------------------------------------------------------

    @Test
    void theRefusalNamesTheQueue() {
        assertEquals("Four walk-ins in port (2 waiting for berth 2) — wait for one to leave.",
                WalkInArrivals.fullPortRefusal(List.of(List.of("berth_2"), List.of("berth_2"))));
        assertEquals("Four walk-ins in port (1 waiting for berth 2, 1 waiting for berth 1 or 3) — wait for one "
                        + "to leave.",
                WalkInArrivals.fullPortRefusal(List.of(List.of("berth_2"), List.of("berth_3", "berth_1"))));
    }

    @Test
    void withNobodyWaitingTheRefusalSaysOnlyThatThePortIsFull() {
        assertEquals("Four walk-ins in port — wait for one to leave.", WalkInArrivals.fullPortRefusal(List.of()));
        assertEquals("", WalkInArrivals.waitingNote(List.of(List.of())), "a ship that fits nowhere is not a queue");
    }

    /** S18b (adversarial review): a contracted ship queued for the berth its contract pins is not
     * one of the four walk-ins - the bracket counts the walk-ins only, in queue order. */
    @Test
    void theBracketCountsTheWalkInsInTheQueueOnly() {
        VesselSpec walkIn = new VesselSpec("WALKIN-1", "container_vessel", 13.0, 280.0, 60000, "general_cargo", 0L);
        VesselSpec contract = new VesselSpec("CONTRACT-1", "tanker", 12.0, 200.0, 40000, "general_cargo", 0L);
        VesselSpec later = new VesselSpec("WALKIN-2", "ferry", 6.0, 180.0, 20000, "general_cargo", 0L);
        Map<String, VesselTracking> inPort = Map.of(
                "WALKIN-1", waiting(walkIn, VesselTracking.Channel.WALK_IN),
                "CONTRACT-1", waiting(contract, VesselTracking.Channel.CONTRACTED),
                "WALKIN-2", waiting(later, VesselTracking.Channel.WALK_IN));

        assertEquals(List.of(walkIn, later), WalkInArrivals.waitingWalkIns(
                List.of("CONTRACT-1", "WALKIN-1", "gone", "WALKIN-2"), inPort));
    }

    private static VesselTracking waiting(VesselSpec spec, VesselTracking.Channel channel) {
        return VesselTracking.arriving(spec.vesselId(), spec, channel, null)
                .withStage(VesselTracking.Stage.AWAITING_BERTH);
    }

    @Test
    void severalBerthsReadAsOneList() {
        assertEquals("berth 1, 2 or 3", WalkInArrivals.berthsPhrase(List.of("berth_3", "berth_1", "berth_2")));
        assertEquals("berth 4", WalkInArrivals.berthsPhrase(List.of("berth_4")));
    }
}
