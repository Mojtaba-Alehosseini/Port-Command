package it.unige.portcommand.behaviours;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.HarbourMasterInitArgs;
import it.unige.portcommand.behaviours.coordination.PoissonSpawnBehaviour;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.ontology.VesselSpec;
import jade.core.AID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 19 STEP 1 flood-fix gate. Drives {@link PoissonSpawnBehaviour#canSpawnNow()}
 * directly (public, exactly for this purpose) rather than through a full spawn
 * round-trip: a real spawn only registers in {@code activeVessels()} once the new
 * agent samples beliefs and sends its opening PROPOSE back to the HarbourMaster,
 * which is an async, non-deterministic-timing hop this test has no need to wait on —
 * the gate decision itself is what's under test, not the round-trip. Lives in the
 * parent {@code behaviours} package, NOT {@code behaviours.coordination} — a test
 * class in a catalogue-counted sub-package shadows {@code BehaviourCatalogueTest}'s
 * {@code getResource} scan (same reason as {@code WithdrawalBehaviourTest}).
 */
@Tag("integration")
class PoissonSpawnBehaviourIT {

    private static final int TEST_PORT = 18099;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void pausedClock_neverSpawns() throws Exception {
        HarbourMasterAgent hm = spawnHm();
        PoissonSpawnBehaviour behaviour = new PoissonSpawnBehaviour(hm, boot.getSimClock(),
                boot.getRandomSource(), boot.getSpawner(), boot.getMarketHistoryArtifact());

        assertTrue(behaviour.canSpawnNow(), "sanity: unpaused + empty activeVessels must allow a spawn");

        boot.getSimClock().pause();
        for (int i = 0; i < 5; i++) {
            assertFalse(behaviour.canSpawnNow(), "must never allow a spawn while the clock is paused");
        }

        boot.getSimClock().resume();
        assertTrue(behaviour.canSpawnNow(), "resuming with an empty roster must allow a spawn again");
    }

    @Test
    void atCap_noNPlusOnethSpawn() throws Exception {
        HarbourMasterAgent hm = spawnHm();
        PoissonSpawnBehaviour behaviour = new PoissonSpawnBehaviour(hm, boot.getSimClock(),
                boot.getRandomSource(), boot.getSpawner(), boot.getMarketHistoryArtifact());

        for (int i = 1; i <= 2; i++) {
            hm.activeVessels().put("WALKIN-" + i, walkIn("WALKIN-" + i));
        }
        assertTrue(behaviour.canSpawnNow(), "2 active walk-ins must still be under the cap of 3");

        hm.activeVessels().put("WALKIN-3", walkIn("WALKIN-3"));
        assertFalse(behaviour.canSpawnNow(), "a 3rd active walk-in must hit the cap: no 4th");

        // A contracted vessel must never count against the walk-in cap.
        hm.activeVessels().remove("WALKIN-3");
        hm.activeVessels().put("CONTRACTED-1", contracted("CONTRACTED-1"));
        assertTrue(behaviour.canSpawnNow(), "a contracted vessel must not count toward the walk-in cap");
    }

    private static VesselTracking walkIn(String vesselId) {
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        return VesselTracking.arriving(vesselId, spec, Channel.WALK_IN, new AID(vesselId, AID.ISGUID));
    }

    private static VesselTracking contracted(String vesselId) {
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        return VesselTracking.arriving(vesselId, spec, Channel.CONTRACTED, new AID(vesselId, AID.ISGUID));
    }

    private HarbourMasterAgent spawnHm() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", BootstrapConfig.DEFAULT_REAL_SECONDS_PER_GAME_DAY));
        CompletableFuture<HarbourMasterAgent> selfRef = new CompletableFuture<>();
        boot.getSpawner().spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(10_000.0, 70.0),
                boot.getSimClock(), boot.getRandomSource(), boot.getEventBus(),
                Map.of(), boot.getSpawner(), boot.getMarketHistoryArtifact(), selfRef});
        return selfRef.get(10, TimeUnit.SECONDS);
    }
}
