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
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
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
 *
 * <p>S14 (playability/timing): the gate has TWO limits now — at most four walk-ins in port
 * (task 19's limit, raised from three in S17b) and at most two of them still NEGOTIATING (new: what one player can hold
 * a conversation with). Each has its own case below, staged so that only the limit under test
 * can be the one that refuses.
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

        // Every walk-in here is PAST negotiation (deal closed, in its flow), so the S14
        // negotiating limit cannot be what refuses — this case is the in-port count alone.
        hm.activeVessels().put("WALKIN-1", walkIn("WALKIN-1", Stage.DOCKED));
        hm.activeVessels().put("WALKIN-2", walkIn("WALKIN-2", Stage.IN_TRANSIT));
        assertTrue(behaviour.canSpawnNow(), "2 active walk-ins must still be under the cap of 4");

        // S17b (PLAYABILITY §10): the in-port limit is 4, one walk-in per berth (was 3).
        hm.activeVessels().put("WALKIN-3", walkIn("WALKIN-3", Stage.AWAITING_TUG));
        assertTrue(behaviour.canSpawnNow(), "3 active walk-ins leave room for a 4th since S17b");

        hm.activeVessels().put("WALKIN-4", walkIn("WALKIN-4", Stage.DOCKED));
        assertFalse(behaviour.canSpawnNow(), "a 4th active walk-in must hit the cap: no 5th");

        // A contracted vessel must never count against the walk-in cap.
        hm.activeVessels().remove("WALKIN-4");
        hm.activeVessels().put("CONTRACTED-1", contracted("CONTRACTED-1", Stage.DOCKED));
        assertTrue(behaviour.canSpawnNow(), "a contracted vessel must not count toward the walk-in cap");
    }

    /**
     * S14: at most two walk-ins NEGOTIATING at once. Before this limit the spawner happily opened
     * a third dialogue while two were unanswered — and with patience now measured in real minutes
     * (ADR-18) those dialogues no longer clear themselves in a few game minutes.
     */
    @Test
    void twoNegotiatingWalkIns_holdTheNextArrival() throws Exception {
        HarbourMasterAgent hm = spawnHm();
        PoissonSpawnBehaviour behaviour = new PoissonSpawnBehaviour(hm, boot.getSimClock(),
                boot.getRandomSource(), boot.getSpawner(), boot.getMarketHistoryArtifact());

        hm.activeVessels().put("WALKIN-1", walkIn("WALKIN-1", Stage.NEGOTIATING));
        assertTrue(behaviour.canSpawnNow(), "one open negotiation leaves room for a second");

        hm.activeVessels().put("WALKIN-2", walkIn("WALKIN-2", Stage.NEGOTIATING));
        assertFalse(behaviour.canSpawnNow(),
                "2 walk-ins negotiating must hold the next arrival, although only 2 of 4 are in port");

        // WALKIN-2 closes its deal and moves on: one negotiating + one past negotiation.
        hm.activeVessels().computeIfPresent("WALKIN-2", (id, t) -> t.withStage(Stage.AWAITING_TUG));
        assertTrue(behaviour.canSpawnNow(),
                "1 negotiating + 1 past negotiation is under both limits: the next walk-in may come");

        // Two more vessels past negotiation fill the port: one negotiating + three past.
        hm.activeVessels().put("WALKIN-3", walkIn("WALKIN-3", Stage.DOCKED));
        assertTrue(behaviour.canSpawnNow(), "1 negotiating + 2 past negotiation is 3 of 4 in port");
        hm.activeVessels().put("WALKIN-4", walkIn("WALKIN-4", Stage.DOCKED));
        assertFalse(behaviour.canSpawnNow(),
                "1 negotiating + 3 past negotiation is 4 walk-ins in port: the in-port cap still holds");
    }

    /** Neither limit ever counts a contracted vessel, whatever its stage. */
    @Test
    void contractedVessels_countTowardNeitherLimit() throws Exception {
        HarbourMasterAgent hm = spawnHm();
        PoissonSpawnBehaviour behaviour = new PoissonSpawnBehaviour(hm, boot.getSimClock(),
                boot.getRandomSource(), boot.getSpawner(), boot.getMarketHistoryArtifact());

        hm.activeVessels().put("WALKIN-1", walkIn("WALKIN-1", Stage.NEGOTIATING));
        hm.activeVessels().put("CONTRACTED-1", contracted("CONTRACTED-1", Stage.AWAITING_TUG));
        hm.activeVessels().put("CONTRACTED-2", contracted("CONTRACTED-2", Stage.IN_TRANSIT));
        hm.activeVessels().put("CONTRACTED-3", contracted("CONTRACTED-3", Stage.DOCKED));
        assertTrue(behaviour.canSpawnNow(),
                "three contracted vessels in port must not fill the walk-in cap");

        // A contracted vessel never negotiates in play; the stage is forced here only to pin the
        // CHANNEL half of the negotiating count — it must filter on walk-ins, not on the stage alone.
        hm.activeVessels().put("CONTRACTED-4", contracted("CONTRACTED-4", Stage.NEGOTIATING));
        assertTrue(behaviour.canSpawnNow(),
                "a contracted entry at NEGOTIATING must not count as a second open negotiation");

        hm.activeVessels().put("WALKIN-2", walkIn("WALKIN-2", Stage.NEGOTIATING));
        assertFalse(behaviour.canSpawnNow(),
                "fixture sanity: a second NEGOTIATING walk-in is what does hold the next arrival");
    }

    private static VesselTracking walkIn(String vesselId, Stage stage) {
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        return VesselTracking.arriving(vesselId, spec, Channel.WALK_IN, new AID(vesselId, AID.ISGUID))
                .withStage(stage);
    }

    private static VesselTracking contracted(String vesselId, Stage stage) {
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        return VesselTracking.arriving(vesselId, spec, Channel.CONTRACTED, new AID(vesselId, AID.ISGUID))
                .withStage(stage);
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
