package it.unige.portcommand.persistence;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S9 — the fix-campaign ledger's "live Settings revert on load" limitation, closed. The saved
 * {@code settings} block (which {@code GameStateBuilder} always wrote and nothing ever read) is
 * now BOTH captured from the live values at save and applied as the session's effective settings
 * on load, in {@code bootWorld}'s in-place-restore slot.
 *
 * <p><b>What is asserted vs. what is by-inspection</b> (S9 adversarial finding 10, stated
 * honestly): the file capture, the post-load {@code Settings.load()} values, the stripped-block
 * defaults and ONE construction-time consumer — the respawned {@code AssistantAgent}'s autopilot
 * flag, seeded in its constructor, which can only be true post-load if the apply ran BEFORE the
 * respawn. The full apply-before-every-agent ordering beyond that is the {@code bootWorld}
 * statement order, reviewed not asserted. The knobs driven here are moved via
 * {@code Settings.applyLive} directly — the FORM's write-through path (difficulty / day length /
 * autopilot / llmModel via {@code MainWindow.applySettings}) is a different surface, exercised
 * manually.
 *
 * <p>{@code Settings.load()} is a JVM-wide volatile — every test here restores the classpath
 * baseline in {@link #tearDown()} so no applied value leaks into other suites (the same
 * world-boundary discipline {@code startNewGame} enforces in production).
 */
@Tag("integration")
class SettingsRoundTripIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        Settings.applyLive(Settings.classpathDefaults());
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void roundLimitSpeedAutopilotDifficulty_surviveTheRoundTrip_andApplyBeforeAgentsBoot()
            throws Exception {
        boot = new JadeBootstrap();
        // S17a: an autopilot pacing of its own (no negotiation runs here - the clock is paused and
        // no walk-in hails - so the value costs nothing; it is there to be found again below).
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300, 1_234L),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        boot.getSimClock().pause(); // no Poisson traffic -> deterministic immediate save
        awaitFleetSettled(session);
        assertEquals(1_234L, awaitAssistant(session).autopilotPacing().moveDelayMillis(),
                "S17a: a new game's Assistant is handed the boot configuration's pacing");

        // The player's four live-changeable knobs, all moved off their defaults.
        Settings changed = Settings.load()
                .withRoundLimit(7)
                .withGameSpeed(2)
                .withAutopilotEnabled(true)
                .withDifficulty("HARD");
        Settings.applyLive(changed);

        Path saved = session.saveNow();

        // 1) The FILE carries the live values (the save-side half: GameStateBuilder writes
        //    Settings.load(), which is no longer a boot-frozen constant).
        JsonNode settingsNode = new ObjectMapper().readTree(saved.toFile()).get("settings");
        assertEquals(7, settingsNode.get("roundLimit").asInt(), "the save captures the LIVE roundLimit");
        assertEquals(2, settingsNode.get("gameSpeed").asInt(), "S14: the speed control is saved");
        assertNull(settingsNode.get("timeLimitSeconds"), "S14: the retired nominal window is not written");
        assertTrue(settingsNode.get("autopilotEnabled").asBoolean());
        assertEquals("HARD", settingsNode.get("difficulty").asText());

        // 2) Corrupt the live value to prove the LOAD (not leftover statics) restores it.
        Settings.applyLive(Settings.classpathDefaults());
        assertEquals(Settings.classpathDefaults().roundLimit(), Settings.load().roundLimit(),
                "sanity: defaults back in force pre-load");
        assertEquals(1, Settings.load().gameSpeed());
        boot.getSimClock().setSpeedMultiplier(1);
        assertTrue(ownershipStatements().stream().noneMatch(
                        it.unige.portcommand.gui.events.AutopilotOwnershipEvent::autopilotOn),
                "fixture: nothing has said 'the autopilot is on' before the load (the first Assistant "
                        + "booted with it off, and the live value was changed without the Settings event)");

        session.loadGame(saved);

        // 3) Applied on load, before agents booted (the bootWorld slot): the session's
        //    effective settings ARE the saved four.
        assertEquals(7, Settings.load().roundLimit(), "restored roundLimit is the effective one");
        assertEquals(2, Settings.load().gameSpeed(), "restored speed is the effective setting");
        assertEquals(2, boot.getSimClock().speedMultiplier(), "and it is applied to the game clock");
        assertTrue(Settings.load().autopilotEnabled());
        assertEquals("HARD", Settings.load().difficulty());

        // 4) The construction-time proof: the RESPAWNED assistant seeded its autopilot flag in
        //    its constructor — it can only be ON if the apply ran BEFORE the respawn.
        assertTrue(session.directory()
                        .byType(it.unige.portcommand.agents.AssistantAgent.class).stream()
                        .findFirst().orElseThrow().autopilotOn(),
                "the respawned AssistantAgent's construction-time autopilot seed must reflect "
                        + "the SAVED value — the apply-before-agents-boot ordering, observed");

        // 4b) S17a (ADR-21, PT12): the boot configuration's autopilot pacing reached the
        //     respawned Assistant too - the restore path builds its own argument list.
        assertEquals(1_234L, awaitAssistant(session).autopilotPacing().moveDelayMillis(),
                "a loaded game must pace its autopilot like a new one");

        // 5) S17a (ADR-21, decision A10 / backlog R-07): and it SAYS so. The chat tabs lock and
        //    unlock from the Assistant's own statement and from nothing else; before S17a the
        //    window kept a copy of the switch and set it to "off" on every load, so after loading
        //    this very save it showed Hint and a free input while the Assistant negotiated.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && ownershipStatements().stream().noneMatch(
                it.unige.portcommand.gui.events.AutopilotOwnershipEvent::autopilotOn)) {
            Thread.sleep(50);
        }
        var statements = ownershipStatements();
        assertTrue(!statements.isEmpty() && statements.get(statements.size() - 1).autopilotOn(),
                "the respawned Assistant must state that the autopilot is on in the loaded world: " + statements);
        assertTrue(statements.get(statements.size() - 1).takenOver().isEmpty(),
                "and that no tab of the new world has been taken over");
    }

    /** The session's Assistant, once it has registered (it does so at the END of its setup, so
     * what it was spawned with is in place by then). Bounded. */
    private it.unige.portcommand.agents.AssistantAgent awaitAssistant(GameSession session)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && session.directory().byType(it.unige.portcommand.agents.AssistantAgent.class).isEmpty()) {
            Thread.sleep(50);
        }
        return session.directory().byType(it.unige.portcommand.agents.AssistantAgent.class).stream()
                .findFirst().orElseThrow(() -> new AssertionError("no Assistant registered within 10 s"));
    }

    /** Every ownership statement on the session's bus so far, oldest first. */
    private java.util.List<it.unige.portcommand.gui.events.AutopilotOwnershipEvent> ownershipStatements() {
        return it.unige.portcommand.util.EventBusProbe.published(boot.getEventBus()).stream()
                .filter(it.unige.portcommand.gui.events.AutopilotOwnershipEvent.class::isInstance)
                .map(it.unige.portcommand.gui.events.AutopilotOwnershipEvent.class::cast)
                .toList();
    }

    /** A settings-less save (the pre-S9 optionality decision) loads fine and leaves boot
     * defaults in force — proven by stripping the block from a REAL save at the JSON level. */
    @Test
    void aSaveWithoutTheSettingsBlock_loadsAndBootDefaultsStand() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        boot.getSimClock().pause();
        awaitFleetSettled(session);
        Path saved = session.saveNow();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(saved.toFile());
        assertTrue(root.has("settings"), "fixture sanity: every real save carries the block");
        root.remove("settings");
        Path stripped = tempDir.resolve("stripped.json");
        Files.writeString(stripped, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        Settings.applyLive(Settings.load().withRoundLimit(8)); // a stray live value to displace
        session.loadGame(stripped);

        assertEquals(Settings.classpathDefaults().roundLimit(), Settings.load().roundLimit(),
                "a settings-less save must leave boot defaults in force (applyLive(null) no-op "
                        + "+ the startNewGame-shaped reset is NOT involved — the load path itself "
                        + "must not carry the stray pre-load value forward");
        assertFalse(Settings.load().autopilotEnabled());
    }

    /**
     * Agent spawning is async and the directory registers agents at setup START (the S3
     * register-before-onSetup lesson): a save fired milliseconds after {@code startNewGame} can
     * snapshot a tug whose id is still null. Every established save IT awaits some world event
     * first; this suite saves an EMPTY world, so it waits for the fleet's snapshots to be
     * well-formed instead. Bounded (design rule 5).
     */
    private void awaitFleetSettled(GameSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var tugs = session.directory().byType(it.unige.portcommand.agents.TugAgent.class);
            if (tugs.size() == 4
                    && tugs.stream().allMatch(t -> t.snapshotDto().tugId() != null)
                    && session.directory().byName("harbour_master").isPresent()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("fleet did not settle within 15 s — tugs="
                + session.directory().byType(it.unige.portcommand.agents.TugAgent.class).size());
    }
}
