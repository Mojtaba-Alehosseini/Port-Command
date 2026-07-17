package it.unige.portcommand.gui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.lifecycle.GameLifecycle;
import it.unige.portcommand.lifecycle.GameMode;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import it.unige.portcommand.lifecycle.events.GamePausedEvent;
import it.unige.portcommand.lifecycle.events.GameResumedEvent;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;

/**
 * The Swing shell (task 17): a {@code JFrame} with five panel slots laid out
 * per planning/17 §Step 17.3 — HUD across the top, Map/Chat/CommLog sharing
 * the middle row (~50/25/25 width split), a notification strip along the
 * bottom. {@code GridBagLayout} is pinned (planning/17: "MigLayout ... keep
 * GridBagLayout").
 *
 * <p>Task 24 additions: the Game menu drives {@link GameLifecycle}
 * (Pause/Resume/Quit — Quit is confirmed, then routed through
 * {@code GameOverGuard} via {@code lifecycle.quit()}), Esc toggles pause, a
 * translucent {@link PauseOverlay} glass pane mirrors the PAUSED state, and
 * {@link GameOverDialog} opens on the run's {@code GameOverEvent}.
 *
 * <p><b>Single EDT rule.</b> This class and every panel it holds must only be
 * constructed and mutated on the EDT — {@code Main} constructs and shows it
 * via {@code SwingUtilities.invokeLater} (behind the {@code --gui} flag;
 * {@code gradlew run} stays headless without it).
 *
 * <p><b>Agents never reach this class.</b> The only channel from agents to the
 * GUI is {@link EventBus} (INVARIANTS.md: "GUI ↔ agents only through
 * EventBus"); the {@link GameLifecycle}/{@code SimClock} references flow the
 * OTHER way only (GUI → lifecycle control, GUI → read-only clock queries),
 * and nothing under {@code agents/}, {@code behaviours/} or
 * {@code harbourmaster/} may import {@code java.awt}/{@code javax.swing}
 * (grep-gated, planning/17).
 */
public final class MainWindow extends JFrame {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 800;

    private final GameLifecycle lifecycle;
    private final Leaderboard leaderboard;
    private final GameSession session;
    private final EndOfDaySummaryDialog endOfDayDialog;
    private final GameOverDialog gameOverDialog;
    private final PauseOverlay pauseOverlay;
    private JMenuItem pauseItem;
    private JMenuItem resumeItem;
    private JMenuItem quitItem;
    private JMenuItem saveItem;
    private JMenuItem loadItem;
    private JMenuItem loadAutosaveItem;
    /** True while a load worker thread owns the world — menus disabled, no re-entry. */
    private boolean loadInProgress;

    /** The latest REAL settled summary — the Debug menu re-shows it once one exists. */
    private EndOfDaySummary latestSummary;
    /** Whether the CURRENT pause was taken for the EOD report (vs. by the player). */
    private boolean pausedForEndOfDay;

    /**
     * @param eventBus          the shared bus every panel subscribes through
     * @param portStateArtifact the shared berth/tug state {@code MapPanel} (task 18) reads and
     *                          subscribes to — the only panel that needs it
     * @param chatInputProcessor the NLP pipeline seam (task 19) {@code ChatPanel} calls on Send
     * @param lifecycle         the task-24 game-loop orchestrator this shell's menu drives; also
     *                          the read-only source of the shared {@code SimClock} for countdowns
     * @param leaderboard       the ONE shared board ({@code Main} constructs it) — the same
     *                          instance {@code GameOverGuard} records into, so the dialogs render
     *                          scores the moment they land
     * @param onClose           clean-shutdown hook run before the window disposes and the JVM
     *                          exits (wired to {@code JadeBootstrap::shutdown} in {@code Main})
     */
    public MainWindow(EventBus eventBus, PortStateArtifact portStateArtifact,
                      ChatInputProcessor chatInputProcessor, GameLifecycle lifecycle,
                      Leaderboard leaderboard, GameSession session, Runnable onClose) {
        super("Port Command Genova");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // we drive shutdown ourselves
        this.lifecycle = lifecycle;
        this.leaderboard = leaderboard;
        this.session = session;
        this.pauseOverlay = new PauseOverlay(this::toggleMenuResume);
        setGlassPane(pauseOverlay);
        setJMenuBar(buildMenuBar(eventBus, onClose));
        layOutPanels(eventBus, portStateArtifact, chatInputProcessor);
        installEscBinding();

        // Lifecycle mirror: the overlay + menu enablement follow the bus, never poll. The
        // EndOfDayCompletedEvent handler is subscribed BEFORE the dialog is constructed so the
        // world freezes before the modal report opens (adversarial review m2 — otherwise the
        // clock kept running behind the dialog and walk-ins timed out while the player read it);
        // the dialog's componentHidden unfreezes it, but only if THIS handler paused (a manual
        // pause, or a game-over racing the same settlement, is left alone).
        eventBus.subscribe(GamePausedEvent.class, e -> reflectMode(), DeliveryMode.EDT);
        eventBus.subscribe(GameResumedEvent.class, e -> reflectMode(), DeliveryMode.EDT);
        eventBus.subscribe(GameOverEvent.class, e -> reflectMode(), DeliveryMode.EDT);
        // Task 22: after a load the previous world's settled summary no longer describes
        // this timeline — the Debug re-show falls back to the synthetic day until the
        // restored run settles its own first midnight.
        eventBus.subscribe(GameLoadedEvent.class, e -> {
            latestSummary = null;
            reflectMode();
        }, DeliveryMode.EDT);
        eventBus.subscribe(EndOfDayCompletedEvent.class, e -> {
            latestSummary = e.summary();
            pausedForEndOfDay = lifecycle.pauseIfRunning();
        }, DeliveryMode.EDT);
        this.endOfDayDialog = new EndOfDaySummaryDialog(this, eventBus, leaderboard);
        this.endOfDayDialog.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                if (pausedForEndOfDay) {
                    pausedForEndOfDay = false;
                    lifecycle.resumeIfPaused();
                }
            }
        });
        this.gameOverDialog = new GameOverDialog(this, eventBus, leaderboard, () -> shutdownAndExit(onClose));
        reflectMode();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownAndExit(onClose);
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // Fires exactly once, the first time this window is made visible — the
                // natural "first paint has happened" signal (Window API contract), rather
                // than guessing at invokeLater ordering.
                eventBus.publish(new GuiReadyEvent());
            }
        });
        setMinimumSize(new Dimension(800, 600));
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);
    }

    private void layOutPanels(EventBus eventBus, PortStateArtifact portStateArtifact,
                              ChatInputProcessor chatInputProcessor) {
        setLayout(new GridBagLayout());
        add(new HUDPanel(eventBus), slot(0, 0, 3, 1.0, 0.0));
        add(new MapPanel(eventBus, portStateArtifact), slot(0, 1, 1, 0.5, 1.0));
        add(new ChatPanel(eventBus, chatInputProcessor, lifecycle.simClock()), slot(1, 1, 1, 0.25, 1.0));
        add(new CommLogPanel(eventBus), slot(2, 1, 1, 0.25, 1.0));
        add(new NotificationStrip(eventBus), slot(0, 2, 3, 1.0, 0.0));
    }

    private static GridBagConstraints slot(int gridx, int gridy, int gridwidth, double weightx, double weighty) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = gridx;
        c.gridy = gridy;
        c.gridwidth = gridwidth;
        c.weightx = weightx;
        c.weighty = weighty;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(1, 1, 1, 1);
        return c;
    }

    private JMenuBar buildMenuBar(EventBus eventBus, Runnable onClose) {
        JMenuBar bar = new JMenuBar();
        JMenu game = new JMenu("Game");
        pauseItem = new JMenuItem("Pause");
        pauseItem.addActionListener(e -> lifecycle.pause());
        resumeItem = new JMenuItem("Resume");
        resumeItem.addActionListener(e -> lifecycle.resume());
        // Task 22: the two fixed slots (savegame.json / autosave.json). A file picker is
        // task 21's SaveLoadDialog; fixed-path items are the planned v1 surface here.
        saveItem = new JMenuItem("Save Game");
        saveItem.addActionListener(e -> saveGame());
        loadItem = new JMenuItem("Load Game");
        loadItem.addActionListener(e -> confirmAndLoad(session.saveLoadManager().savegamePath()));
        loadAutosaveItem = new JMenuItem("Load Autosave");
        loadAutosaveItem.addActionListener(e -> confirmAndLoad(session.saveLoadManager().autosavePath()));
        quitItem = new JMenuItem("Quit Game…");
        quitItem.addActionListener(e -> confirmQuit());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> shutdownAndExit(onClose));
        game.add(pauseItem);
        game.add(resumeItem);
        game.addSeparator();
        game.add(saveItem);
        game.add(loadItem);
        game.add(loadAutosaveItem);
        game.addSeparator();
        game.add(quitItem);
        game.addSeparator();
        game.add(exit);
        bar.add(game);
        bar.add(buildDebugMenu(eventBus));
        return bar;
    }

    /**
     * Game → Save Game: quiesce + snapshot + atomic write on a WORKER thread (adversarial
     * review #5 — the bounded snapshot wait is normally milliseconds, but a wedged
     * HarbourMaster thread must freeze the save, not the whole UI); polite dialog if
     * refused (22.7). The menu item disables for the duration, so no double-save.
     */
    private void saveGame() {
        saveItem.setEnabled(false);
        Thread worker = new Thread(() -> {
            String info = null;
            String error = null;
            try {
                info = "Game saved to " + session.saveNow() + ".";
            } catch (it.unige.portcommand.persistence.SaveNotAllowedException e) {
                info = e.getMessage();
            } catch (Exception e) {
                error = "Save failed: " + e.getMessage();
            }
            String infoMessage = info;
            String errorMessage = error;
            javax.swing.SwingUtilities.invokeLater(() -> {
                reflectMode();
                if (errorMessage != null) {
                    JOptionPane.showMessageDialog(this, errorMessage, "Save Game", JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, infoMessage, "Save Game", JOptionPane.INFORMATION_MESSAGE);
                }
            });
        }, "game-saver");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Game → Load: confirm (the running world is replaced), then run the teardown/rebuild
     * on a worker thread — it takes seconds and must never block the EDT. The GUI refreshes
     * itself from the loader's events; menus re-enable when the worker finishes.
     */
    private void confirmAndLoad(java.nio.file.Path source) {
        if (loadInProgress) {
            return;
        }
        if (!java.nio.file.Files.exists(source)) {
            JOptionPane.showMessageDialog(this, "No save file at " + source + " yet.",
                    "Load Game", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Load " + source.getFileName() + "? The current game will be replaced.",
                "Load Game", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        loadInProgress = true;
        reflectMode();
        Thread worker = new Thread(() -> {
            Exception failure = null;
            try {
                session.loadGame(source);
            } catch (Exception e) {
                failure = e;
            }
            Exception reported = failure;
            javax.swing.SwingUtilities.invokeLater(() -> {
                loadInProgress = false;
                reflectMode();
                if (reported != null) {
                    JOptionPane.showMessageDialog(this, reported.getMessage(),
                            "Load Game", JOptionPane.ERROR_MESSAGE);
                }
            });
        }, "game-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Game → Quit: confirm, then end the run through the single game-over authority
     * ({@code lifecycle.quit()} → {@code QuitEvent} → {@code GameOverGuard} →
     * {@code GameOverEvent} → {@link GameOverDialog}). planning/24's "ask about the active
     * negotiation" is folded into one always-on confirm — with up to 3 concurrent walk-in
     * dialogues a separate per-negotiation prompt would just stack dialogs.
     */
    private void confirmQuit() {
        int choice = JOptionPane.showConfirmDialog(this,
                "End this run? The final score goes to the leaderboard.",
                "Quit Game", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            lifecycle.quit();
        }
    }

    /** Esc toggles pause/resume — bound WHEN_IN_FOCUSED_WINDOW so it works from any panel. */
    private void installEscBinding() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "toggle-pause");
        getRootPane().getActionMap().put("toggle-pause", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggleMenuResume();
            }
        });
    }

    private void toggleMenuResume() {
        if (lifecycle.mode() == GameMode.RUNNING) {
            lifecycle.pause();
        } else if (lifecycle.mode() == GameMode.PAUSED) {
            lifecycle.resume();
        }
    }

    /** One place maps {@link GameMode} → overlay visibility + menu enablement. */
    private void reflectMode() {
        GameMode mode = lifecycle.mode();
        pauseOverlay.setVisible(mode == GameMode.PAUSED);
        pauseItem.setEnabled(!loadInProgress && mode == GameMode.RUNNING);
        resumeItem.setEnabled(!loadInProgress && mode == GameMode.PAUSED);
        quitItem.setEnabled(!loadInProgress && (mode == GameMode.RUNNING || mode == GameMode.PAUSED));
        boolean live = mode == GameMode.RUNNING || mode == GameMode.PAUSED;
        saveItem.setEnabled(!loadInProgress && live);
        // Loading is legal from a live game AND from GAME_OVER (continue from the autosave).
        boolean loadable = live || mode == GameMode.GAME_OVER;
        loadItem.setEnabled(!loadInProgress && loadable);
        loadAutosaveItem.setEnabled(!loadInProgress && loadable);
    }

    /**
     * The Debug menu (task 20). Since task 24 the item shows the LATEST REAL settled summary once
     * a midnight has passed; before the first rollover it falls back to the task-20 synthetic day
     * (still useful for layout checks minutes into a fresh run). One code path either way — the
     * same {@code show(...)} the live {@code EndOfDayCompletedEvent} subscription drives.
     */
    private JMenu buildDebugMenu(EventBus eventBus) {
        JMenu debug = new JMenu("Debug");
        JMenuItem showEod = new JMenuItem("Show End-of-Day Summary (latest)");
        showEod.addActionListener(e ->
                endOfDayDialog.show(latestSummary != null ? latestSummary : syntheticSummary(), leaderboard));
        debug.add(showEod);
        debug.addSeparator();
        // Checkpoint-#5 request: storms are rare by design (steady-state ≈4%) — these force the
        // WeatherAgent's snapshot so the full hold → clear → re-dispatch cascade can be shown on
        // demand. Everything downstream (alerts, holds, chip, notifications) runs the REAL
        // pipeline; the next broadcast tick (~3 s at default speed) picks the override up.
        JMenuItem forceStorm = new JMenuItem("Force storm (42 kn, stormy)");
        forceStorm.addActionListener(e ->
                eventBus.publish(new DebugWeatherEvent(42, "poor", 5.0, "stormy")));
        debug.add(forceStorm);
        JMenuItem clearWeather = new JMenuItem("Clear weather (15 kn, sunny)");
        clearWeather.addActionListener(e ->
                eventBus.publish(new DebugWeatherEvent(15, "good", 0.5, "sunny")));
        debug.add(clearWeather);
        return debug;
    }

    /**
     * A representative day, shaped like the demo transcript's Day 1 so the layout is realistic.
     * Names come from {@link PerformativeCounter#canonicalNames()} rather than typed literals, so
     * this synthetic day renders with exactly the spelling a real day produces (JADE's FIPA names
     * hyphenate: {@code ACCEPT-PROPOSAL}).
     */
    private static EndOfDaySummary syntheticSummary() {
        List<Integer> counts = List.of(2, 7, 13, 7, 4, 2, 4, 0, 3, 0); // demo transcript Day 1, 42 total
        List<String> names = PerformativeCounter.canonicalNames();
        Map<String, Integer> performatives = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            performatives.put(names.get(i), counts.get(i));
        }
        return new EndOfDaySummary(1, 11_200.0, 8_150.0, 53_050.0, 51, performatives);
    }

    private void shutdownAndExit(Runnable onClose) {
        onClose.run();
        dispose();
        System.exit(0);
    }
}
