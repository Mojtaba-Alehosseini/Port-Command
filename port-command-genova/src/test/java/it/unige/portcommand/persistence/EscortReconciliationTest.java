package it.unige.portcommand.persistence;

import java.util.List;

import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The load-side escort reconciliation (S16, ADR-20; found by the adversarial review).
 *
 * <p>The HarbourMaster lists a tug as soon as it awards it; the tug takes the job when the
 * ACCEPT reaches it. A save between the two holds a waiting ship "with" a tug that is saved
 * idle. Since S16 a ship waits for EVERY assigned tug to report on station, so that ship would
 * wait for good. {@link GameStateBuilder#reconcileEscorts} drops such a tug from a WAITING
 * ship's list — the ship is short again and the restore sweep tenders for it — and touches
 * nothing else.
 */
class EscortReconciliationTest {

    private static final Position ANYWHERE = new Position(100.0, 100.0, 0.0);

    private static VesselStateDTO vessel(String id, String stage, String phase, List<String> tugs) {
        return new VesselStateDTO(
                id, "cargo_vessel", VesselStateDTO.CHANNEL_CONTRACTED,
                9.5, 180.0, 40_000, "general_cargo", 0L,
                stage, phase, 0L,
                "berth_1", 5_200.0, 8,
                tugs, false, false,
                700.0, 200.0, 180.0,
                "CONTRACT-1",
                null, null, null, null, null, null, null,
                null);
    }

    private static TugStateDTO tug(String id, String status, String jobFor) {
        return new TugStateDTO(id, status, ANYWHERE, 1.0,
                jobFor == null ? null : new TugStateDTO.TugJobDTO(jobFor, ANYWHERE, "cnp-" + jobFor),
                "EN_ROUTE_TO_VESSEL".equals(status) ? ANYWHERE : null);
    }

    private static GameState stateWith(List<VesselStateDTO> vessels, List<TugStateDTO> tugs) {
        GameState base = SaveLoadManagerRoundTripTest.representativeState();
        GameState.AgentsState a = base.agents();
        return new GameState(base.schemaVersion(), base.savedAt(), base.randomSeed(), base.settings(),
                base.simClock(), base.wallet(), base.reputation(), base.consecutiveDeepDebtDays(),
                base.lastDeepDebtDay(), base.marketHistory(), base.policyTriggers(), base.activeScenario(),
                base.firedEventIds(),
                new GameState.AgentsState(a.harbourMaster(), a.weather(), a.customs(), tugs, a.terminals(),
                        vessels));
    }

    @Test
    void aWaitingShipKeepsOnlyTheTugsThatReallyHoldAJobForIt() {
        // tug_1 took the job; tug_2's ACCEPT was still on the wire when the save was written.
        VesselStateDTO waiting = vessel("C001", "AWAITING_TUG", "AWAITING_CLEARANCE", List.of("tug_1", "tug_2"));
        GameState state = stateWith(List.of(waiting), List.of(
                tug("tug_1", "EN_ROUTE_TO_VESSEL", "C001"), tug("tug_2", "IDLE", null),
                tug("tug_3", "IDLE", null), tug("tug_4", "IDLE", null)));

        List<VesselStateDTO> reconciled = GameStateBuilder.reconcileEscorts(state);

        assertEquals(List.of("tug_1"), reconciled.get(0).assignedTugs(),
                "the tug that never got its award is dropped, so the ship is short and is tendered for");
        assertEquals("AWAITING_TUG", reconciled.get(0).stage(), "nothing else about the ship changes");
        assertEquals("berth_1", reconciled.get(0).assignedBerth());
    }

    @Test
    void aTugOnStationOrWithAJobForAnotherShipIsJudgedByItsJob_notItsStatus() {
        VesselStateDTO a = vessel("C001", "AWAITING_TUG", "AWAITING_CLEARANCE", List.of("tug_1", "tug_2"));
        VesselStateDTO b = vessel("C002", "AWAITING_TUG", "AWAITING_CLEARANCE", List.of("tug_3"));
        GameState state = stateWith(List.of(a, b), List.of(
                tug("tug_1", "ON_STATION", "C001"),
                tug("tug_2", "EN_ROUTE_TO_VESSEL", "C002"), // listed by C001, but its job is C002's
                tug("tug_3", "ESCORTING", "C002"),
                tug("tug_4", "RETURNING", null)));

        List<VesselStateDTO> reconciled = GameStateBuilder.reconcileEscorts(state);

        assertEquals(List.of("tug_1"), reconciled.get(0).assignedTugs());
        assertEquals(List.of("tug_3"), reconciled.get(1).assignedTugs(), "a tug with a job for the ship stays");
    }

    @Test
    void aConsistentSaveAndAShipThatIsNotWaitingAreLeftExactlyAsTheyAre() {
        VesselStateDTO consistent = vessel("C001", "AWAITING_TUG", "AWAITING_CLEARANCE", List.of("tug_1"));
        // Under way and moored: the list is history (the tugs may be home already), not a condition.
        VesselStateDTO moving = vessel("C002", "IN_TRANSIT", "TRANSIT", List.of("tug_2", "tug_3"));
        VesselStateDTO moored = vessel("C003", "DOCKED", "DOCKED", List.of("tug_4"));
        VesselStateDTO held = vessel("C004", "AWAITING_TUG", "AWAITING_CLEARANCE", List.of());
        GameState state = stateWith(List.of(consistent, moving, moored, held), List.of(
                tug("tug_1", "EN_ROUTE_TO_VESSEL", "C001"), tug("tug_2", "ESCORTING", "C002"),
                tug("tug_3", "IDLE", null), tug("tug_4", "IDLE", null)));

        List<VesselStateDTO> reconciled = GameStateBuilder.reconcileEscorts(state);

        assertEquals(4, reconciled.size());
        for (int i = 0; i < 4; i++) {
            assertSame(state.agents().activeVessels().get(i), reconciled.get(i), "vessel " + i + " untouched");
        }
    }
}
