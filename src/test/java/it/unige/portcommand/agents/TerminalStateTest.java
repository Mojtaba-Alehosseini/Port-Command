package it.unige.portcommand.agents;

import java.util.List;
import java.util.Optional;

import it.unige.portcommand.agents.TerminalState.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-JVM coverage of the terminal's berth/crane bookkeeping and transitions. */
class TerminalStateTest {

    @Test
    void requestBerthConfirmsWithCraneAndComputesFreeTime() {
        TerminalState state = new TerminalState(List.of("berth_1", "berth_3"), 6);
        Result r = state.requestBerth("berth_1", "v1", 1000L, 2);
        assertTrue(r.confirmed());
        assertTrue(r.craneId() >= 1 && r.craneId() <= 6, "crane in range");
        assertEquals(1000L + 2 * 3_600_000L, r.expectedFreeAtSim());
        assertEquals(BerthOccupancy.Status.PROVISIONAL, state.berth("berth_1").orElseThrow().status());
    }

    @Test
    void secondRequestForSameBerthIsBusy() {
        TerminalState state = new TerminalState(List.of("berth_1"), 6);
        state.requestBerth("berth_1", "v1", 0L, 1);
        Result r = state.requestBerth("berth_1", "v2", 0L, 1);
        assertFalse(r.confirmed());
        assertEquals("berth_busy", r.reason());
    }

    @Test
    void exhaustingCranesRefusesNoCrane() {
        TerminalState state = new TerminalState(List.of("berth_1", "berth_3"), 1); // 2 berths, 1 crane
        assertTrue(state.requestBerth("berth_1", "v1", 0L, 1).confirmed());
        Result r = state.requestBerth("berth_3", "v2", 0L, 1);
        assertFalse(r.confirmed());
        assertEquals("no_crane", r.reason());
        assertEquals(0, state.cranesFree());
    }

    @Test
    void dockReleaseAndCancelTransitions() {
        TerminalState state = new TerminalState(List.of("berth_1"), 6);
        state.requestBerth("berth_1", "v1", 0L, 1);
        assertEquals(BerthOccupancy.Status.DOCKED, state.confirmDocking("berth_1").orElseThrow().status());
        assertEquals(1, state.cranesInUse());
        assertEquals(BerthOccupancy.Status.FREE, state.releaseBerth("berth_1").orElseThrow().status());
        assertEquals(0, state.cranesInUse());

        state.requestBerth("berth_1", "v9", 0L, 1);
        assertTrue(state.cancelProvisional("v9").isPresent());
        assertEquals(BerthOccupancy.Status.FREE, state.berth("berth_1").orElseThrow().status());
    }

    @Test
    void confirmDockingOnlyFromProvisional() {
        TerminalState state = new TerminalState(List.of("berth_1"), 6);
        assertEquals(Optional.empty(), state.confirmDocking("berth_1")); // FREE → no transition
    }

    @Test
    void managesReflectsOwnership() {
        TerminalState state = new TerminalState(List.of("berth_2"), 4);
        assertTrue(state.manages("berth_2"));
        assertFalse(state.manages("berth_1"));
    }
}
