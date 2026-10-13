package it.unige.portcommand.behaviours;

import java.util.List;
import java.util.Set;

import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore sweep waits for the restored ships (S16; found by the full gate).
 *
 * <p>After a load the rebuilt HarbourMaster re-drives what the save interrupted. It used to wait
 * for the tugs and customs to be visible and then run — but the loader spawns the ships last, and
 * each registers as the last step of its own setup, so on a fast boot the sweep could run first.
 * It then found "no restored agent" for a ship queued for a berth and dropped its tracking: a
 * ship with a closed deal disappeared from the loaded game. ({@code SaveLoadMidTransitIT}'s
 * queued case lost that race once in the S16 gate.) The sweep now also waits until every tracked
 * vessel's agent is registered; this is the rule it waits on. The timing itself cannot be staged
 * deterministically — the readiness rule can.
 */
class RestoreDispatchReadinessTest {

    private static VesselTracking tracked(String id, Stage stage) {
        VesselSpec spec = new VesselSpec(id, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        return new VesselTracking(id, spec, Channel.WALK_IN, stage, null, "berth_1", List.of(), false, false);
    }

    @Test
    void theSweepIsNotReadyWhileATrackedShipHasNotRegisteredItsAgent() {
        List<VesselTracking> restored = List.of(tracked("C001", Stage.AWAITING_TUG), tracked("WT5", Stage.AWAITING_BERTH));

        assertFalse(RestoreDispatchBehaviour.restoredVesselsRegistered(restored, Set.of("vessel_c001")::contains),
                "the queued ship is still booting: running now would drop it as agent-less");
        assertFalse(RestoreDispatchBehaviour.restoredVesselsRegistered(restored, Set.of("vessel_wt5")::contains),
                "and a ship that is not queued counts too: the sweep sends it orders");
        assertFalse(RestoreDispatchBehaviour.restoredVesselsRegistered(restored, name -> false));
    }

    @Test
    void itIsReadyOnceEveryTrackedShipIsRegisteredUnderItsLocalName() {
        List<VesselTracking> restored = List.of(tracked("C001", Stage.AWAITING_TUG), tracked("WT5", Stage.AWAITING_BERTH));

        assertTrue(RestoreDispatchBehaviour.restoredVesselsRegistered(restored,
                Set.of("vessel_c001", "vessel_wt5", "tug_1")::contains));
    }

    @Test
    void aVesselIdIsLookedUpAsItsAgentIsNamed() {
        // AgentRoster.vesselLocalName: lower case, and anything that is not a letter, a digit or
        // an underscore becomes an underscore - "WALKIN-3" registers as "vessel_walkin_3".
        List<VesselTracking> restored = List.of(tracked("WALKIN-3", Stage.AWAITING_BERTH));

        assertTrue(RestoreDispatchBehaviour.restoredVesselsRegistered(restored, Set.of("vessel_walkin_3")::contains));
        assertFalse(RestoreDispatchBehaviour.restoredVesselsRegistered(restored, Set.of("vessel_WALKIN-3")::contains));
    }

    @Test
    void aWorldWithNoTrackedShipIsReadyAtOnce() {
        assertTrue(RestoreDispatchBehaviour.restoredVesselsRegistered(List.of(), name -> false));
    }
}
