package it.unige.portcommand.behaviours;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.coordination.PoissonSpawnBehaviour;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOAL 3 (S8, the agreed D-05 input): walk-ins carry deterministic HUMAN display names — drawn
 * once at spawn from the seeded pool, unique per session, persisted verbatim (ADR-17's
 * name-pool section), with the id staying the stable key everywhere. Born red: pre-S8 the
 * snapshot's {@code vesselName} silently coalesced to the id (the 13-arg compat constructor),
 * so every assertion on a HUMAN name fails on the old tree.
 *
 * <p>Spawns are driven directly through {@code PoissonSpawnBehaviour.spawnWalkIn} (public for
 * this IT, the {@code canSpawnNow} precedent) — waiting out real exponential gaps would make
 * the test slow and tail-flaky. The clock stays PAUSED: announce/negotiate is message-driven,
 * and pausing suppresses background Poisson traffic entirely.
 */
@Tag("integration")
class WalkInNamePoolIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    java.nio.file.Path tempDir;

    private JadeBootstrap boot;
    private GameSession session;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void walkIns_getHumanNames_deterministicFromTheSeed_uniqueInSequence() throws Exception {
        List<String> firstRun = bootAndSpawnThree();
        int offset = hmOffset();
        boot.shutdown();

        // Same seed (same BootstrapConfig defaults) => the SAME name sequence — the invariant.
        List<String> secondRun = bootAndSpawnThree();

        assertEquals(firstRun, secondRun, "same seed must produce the same name sequence");
        assertEquals(3, firstRun.stream().distinct().count(), "names are unique in sequence");
        for (int i = 0; i < firstRun.size(); i++) {
            String name = firstRun.get(i);
            String id = "WALKIN-" + (i + 1);
            assertNotEquals(id, name, "a walk-in's display name must be a HUMAN name, not its id");
            assertTrue(name.chars().noneMatch(Character::isDigit),
                    "names must be digit-free (RosterNameResolver's guard keeps ids exact-only, "
                            + "names MED-eligible): " + name);
            assertEquals(it.unige.portcommand.core.NamePool.name(offset, i + 1), name,
                    "the name is the pool draw at (sessionOffset, spawnOrdinal)");
        }
    }

    /**
     * The MED layer goes LIVE with real names (the S6 scope note's whole point): a typo'd
     * roster name — built from the ACTUAL spawned vessel's snapshot, not a fixture — resolves
     * through RosterNameResolver + the grammar's vocative block to the right addressee.
     */
    @Test
    void liveRosterName_withOneTypo_resolvesThroughTheVocative() throws Exception {
        List<String> names = bootAndSpawnThree();
        it.unige.portcommand.gui.events.NegotiationOpenedEvent opened = awaitEvent(
                it.unige.portcommand.gui.events.NegotiationOpenedEvent.class,
                e -> "nego-WALKIN-1".equals(e.dialogueId()), 5_000);
        assertNotNull(opened);

        it.unige.portcommand.prolog.PrologEngine.getInstance().init();
        it.unige.portcommand.nlp.DCGParser parser = new it.unige.portcommand.nlp.PrologDcgParser(
                it.unige.portcommand.prolog.PrologEngine.getInstance(),
                new it.unige.portcommand.nlp.DcgTokenizer());
        it.unige.portcommand.nlp.DialogueCtx ctx = it.unige.portcommand.nlp.DialogueCtx.from(
                List.of(opened.snapshot()), "WALKIN-1");

        String name = names.get(0);
        String typo = name.substring(0, name.length() - 1)
                + (name.endsWith("x") ? "z" : "x"); // one edit, inside the k=2 budget
        var frame = parser.parse(typo + ": no", ctx);
        assertTrue(frame.isPresent(), "the typo'd LIVE name must resolve (MED rewrite + vocative): "
                + typo + " -> " + name);
        assertEquals("WALKIN-1", String.valueOf(frame.get().element("addressee")),
                "the addressee is the vessel whose name was typo'd");
    }

    /**
     * The persistence half: a named walk-in's deal closes, the save carries the NAME and the
     * OFFSET verbatim, and a post-load spawn CONTINUES the sequence (offset + counter both
     * restored) — names never re-roll across a load (ADR-17's name-pool section).
     */
    @Test
    void names_surviveSaveLoad_andPostLoadSpawnsContinueTheSequence() throws Exception {
        List<String> names = bootAndSpawnThree();
        int offset = hmOffset();

        // Close WALKIN-1's negotiation so the save is legal; the other two walk-ins withdraw
        // on their own once the clock advances far enough, but ACCEPTing all three is faster.
        for (int i = 1; i <= 3; i++) {
            boot.getEventBus().publish(new it.unige.portcommand.gui.events.PlayerCommandEvent(
                    it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind.ACCEPT,
                    "WALKIN-" + i, java.util.Map.of()));
        }
        for (int i = 1; i <= 3; i++) {
            final String id = "WALKIN-" + i;
            assertNotNull(awaitEvent(it.unige.portcommand.gui.events.DealClosedEvent.class,
                    e -> id.equals(e.deal().vesselId()), 15_000), id + " deal must close");
        }
        java.nio.file.Path saved = saveNowRetrying(15_000);

        // The DTOs carry the names verbatim.
        var state = new SaveLoadManager(tempDir).load(saved);
        for (int i = 1; i <= 3; i++) {
            final String id = "WALKIN-" + i;
            var dto = state.agents().activeVessels().stream()
                    .filter(v -> id.equals(v.id())).findFirst().orElseThrow();
            assertEquals(names.get(i - 1), dto.vesselName(), "the save carries " + id + "'s name");
        }

        session.loadGame(saved);
        boot.getSimClock().pause();
        HarbourMasterAgent hm2 = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertEquals(offset, hm2.namePoolOffset(), "the rolled offset restores verbatim");
        // A restored vessel's spec carries its name (tracking side).
        assertEquals(names.get(0), hm2.activeVessels().get("WALKIN-1").spec().vesselName());

        // A post-load spawn continues the sequence at ordinal 4 under the SAME offset. The
        // starting count is passed explicitly (3): this test's spawns went through a DETACHED
        // spawner instance, so the HM's own persisted counter stayed 0 — a harness artifact
        // (production spawns only through the HM's instance, whose counter the snapshot reads;
        // counter round-tripping is exercised by the SaveLoad ITs' real Poisson traffic).
        // What THIS assert owns is the offset restoring verbatim + pure pool indexing.
        PoissonSpawnBehaviour spawner2 = new PoissonSpawnBehaviour(hm2, boot.getSimClock(),
                boot.getRandomSource(), boot.getSpawner(), boot.getMarketHistoryArtifact(), 3);
        spawner2.spawnWalkIn();
        it.unige.portcommand.gui.events.NegotiationOpenedEvent fourth = awaitEvent(
                it.unige.portcommand.gui.events.NegotiationOpenedEvent.class,
                e -> "nego-WALKIN-4".equals(e.dialogueId()), 15_000);
        assertNotNull(fourth, "the post-load spawn must open WALKIN-4's dialogue");
        assertEquals(it.unige.portcommand.core.NamePool.name(offset, 4),
                fourth.snapshot().vesselName(),
                "post-load spawns continue the sequence — same offset, next ordinal");
    }

    private int hmOffset() throws Exception {
        return ((HarbourMasterAgent) session.directory().byName("harbour_master").orElseThrow())
                .namePoolOffset();
    }

    private java.nio.file.Path saveNowRetrying(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                return session.saveNow();
            } catch (it.unige.portcommand.persistence.SaveNotAllowedException transientRefusal) {
                if (System.currentTimeMillis() >= deadline) {
                    throw transientRefusal;
                }
                Thread.sleep(100);
            }
        }
    }

    private List<String> bootAndSpawnThree() throws Exception {
        boot = new JadeBootstrap();
        session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        boot.getSimClock().pause();
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();

        PoissonSpawnBehaviour spawner = new PoissonSpawnBehaviour(hm, boot.getSimClock(),
                boot.getRandomSource(), boot.getSpawner(), boot.getMarketHistoryArtifact());
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            spawner.spawnWalkIn();
            String dialogueId = "nego-WALKIN-" + i;
            NegotiationOpenedEvent opened = awaitEvent(NegotiationOpenedEvent.class,
                    e -> dialogueId.equals(e.dialogueId()), 15_000);
            assertNotNull(opened, "the spawned walk-in must open its dialogue: " + dialogueId);
            names.add(opened.snapshot().vesselName());
        }
        return names;
    }

    private <E extends Event> E awaitEvent(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Event event : EventBusProbe.published(boot.getEventBus())) {
                if (type.isInstance(event) && matcher.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
            Thread.sleep(50);
        }
        return null;
    }
}
