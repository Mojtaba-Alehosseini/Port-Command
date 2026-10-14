package it.unige.portcommand.gui;

import java.nio.file.Path;
import java.util.List;

import javax.swing.SwingUtilities;

import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.PolicyListEvent;
import it.unige.portcommand.gui.model.PolicyListModel;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.GameMode;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.util.DeliveryMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playtest 4, PT19 - "the policy list is empty after reopening Settings" - in a REAL world: the
 * Settings form typed into as the player does, the parser it calls, the live Assistant AGENT (not
 * its behaviour built by hand), the {@code PolicyListEvent} it answers with, the window's list
 * model fed on the EDT exactly as {@code MainWindow} feeds it, and Settings opened a second time
 * on that model. No window: the form is a plain panel and the model is what the window keeps.
 */
@Tag("integration")
class PolicyListChainIT {

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

    private static boolean onTheEdtWithin(long millis, java.util.function.BooleanSupplier condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            boolean[] holds = new boolean[1];
            SwingUtilities.invokeAndWait(() -> holds[0] = condition.getAsBoolean());
            if (holds[0]) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void aPolicySetInSettingsIsListedWhenSettingsIsOpenedAgain() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot, new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startSandbox(7L);
        assertEquals(GameMode.RUNNING, session.lifecycle().mode());

        // What MainWindow does in its constructor, on the boot's own bus.
        PolicyListModel windowModel = new PolicyListModel();
        boot.getEventBus().subscribe(PolicyListEvent.class, e -> windowModel.set(e.descriptions()),
                DeliveryMode.EDT);

        SettingsForm[] first = new SettingsForm[1];
        SwingUtilities.invokeAndWait(() -> {
            first[0] = new SettingsForm(boot.getEventBus(), "NORMAL", 300L, false,
                    "microsoft/Phi-4-mini-instruct", false, windowModel, () -> { });
            first[0].policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
            first[0].clickSetPolicyForTest();
        });
        String expected = new PolicyParser().parseQuietly(PolicyParser.GRAMMAR_EXAMPLE).orElseThrow().description();
        assertTrue(first[0].policyFeedbackForTest().contains("Policy set"), first[0].policyFeedbackForTest());

        assertTrue(onTheEdtWithin(15_000, () -> windowModel.descriptions().equals(List.of(expected))),
                () -> "the window's list never heard of the policy: " + windowModel.descriptions()
                        + " - registry holds " + boot.getPolicyRegistryArtifact().all().size() + " rule(s)");
        SwingUtilities.invokeAndWait(() -> first[0].removeNotify());

        String[] reopened = new String[1];
        SwingUtilities.invokeAndWait(() -> reopened[0] = new SettingsForm(boot.getEventBus(), "NORMAL", 300L, false,
                "microsoft/Phi-4-mini-instruct", false, windowModel, () -> { }).policyListForTest());
        assertTrue(reopened[0].contains("1. "), "Settings opened again shows: " + reopened[0]);
    }
}
