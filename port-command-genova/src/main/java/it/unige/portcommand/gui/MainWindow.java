package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
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

    /** Minimum widths (px) that stop the map (#8: the 1280×800 "map sliver" report) or either side
     * panel from being crushed to nothing by a splitter drag. */
    private static final int MAP_MIN_WIDTH = 380;
    private static final int SIDE_MIN_WIDTH = 240;

    /** Component names the {@link TutorialOverlay} highlights by (never by pixels — #8/§21.3). */
    static final String NAME_HUD = "tutorial-hud";
    static final String NAME_MAP = "tutorial-map";
    static final String NAME_CHAT = "tutorial-chat";
    static final String NAME_COMMLOG = "tutorial-commlog";

    /** {@link Preferences} keys for the two splitter divider PROPORTIONS (window-size independent,
     * so a restart at any size restores the same relative layout — #8). */
    private static final String PREF_OUTER_DIVIDER = "mapChatDividerProportion";
    private static final String PREF_INNER_DIVIDER = "chatLogDividerProportion";

    private final EventBus eventBus;
    private final GameLifecycle lifecycle;
    private final Leaderboard leaderboard;
    private final GameSession session;
    private final EndOfDaySummaryDialog endOfDayDialog;
    private final GameOverDialog gameOverDialog;
    private final PauseOverlay pauseOverlay;
    private final TutorialOverlay tutorialOverlay;
    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
    private JSplitPane outerSplit;
    private JSplitPane innerSplit;
    private JMenuItem newGameItem;
    private JMenuItem pauseItem;
    private JMenuItem resumeItem;
    private JMenuItem quitItem;
    private JMenuItem saveItem;
    private JMenuItem loadItem;
    /** True while a load worker thread owns the world — menus disabled, no re-entry. */
    private boolean loadInProgress;
    /** True from the moment {@link #saveGame()} starts its worker until the worker's
     * {@code invokeLater} lands. EDT-only, like {@code loadInProgress}. See
     * {@link #toggleMenuResume()} for what it protects (audit C-08). */
    private boolean saveInProgress;

    /** Live mirror of the three non-clock editable settings (task 21): seeded from
     * {@code defaults.json} and updated on {@link SettingsChangedEvent} so re-opening the Settings
     * dialog shows the last-applied values. The day length is NOT mirrored here — it is read live
     * off the {@code SimClock}, which is the single source of truth (and reflects scenario pins). */
    private String currentDifficulty = Settings.load().difficulty();
    private boolean currentAutopilot = Settings.load().autopilotEnabled();
    private String currentLlmModel = Settings.load().llmModel();

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
        this.eventBus = eventBus;
        this.lifecycle = lifecycle;
        this.leaderboard = leaderboard;
        this.session = session;
        // Audit C-04: the glass pane covers the menu-bar strip too, so it is handed the menu bar's
        // live bounds (in glass-pane coordinates) to pass mouse events through and to skip when
        // painting the scrim. Computed lazily on every hit-test/paint — the menu bar does not exist
        // yet at this point in construction, and its height can change with the look-and-feel.
        this.pauseOverlay = new PauseOverlay(this::toggleMenuResume, this::menuBarBoundsInGlassPane);
        setGlassPane(pauseOverlay);
        setJMenuBar(buildMenuBar(eventBus, onClose));
        layOutPanels(eventBus, portStateArtifact, chatInputProcessor);
        installEscBinding();

        // Task 21: the tutorial scrim lives in the layered pane (the glass pane is already the
        // PauseOverlay) above the content; it searches the content pane's tree for the named
        // highlight targets and paints nothing until the window is up (GuiReadyEvent).
        this.tutorialOverlay = new TutorialOverlay(eventBus, getContentPane());
        getLayeredPane().add(tutorialOverlay, JLayeredPane.MODAL_LAYER);
        // Task 21: apply the four-knob Settings screen. Day length is genuinely live (re-paces the
        // shared SimClock); the other three are mirrored for the dialog's next open (difficulty is
        // inert in v1, llmModel is next-launch — see SettingsChangedEvent).
        eventBus.subscribe(SettingsChangedEvent.class, this::applySettings, DeliveryMode.EDT);

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
            // A respawned AssistantAgent boots autopilot OFF (its constructor's default), so the
            // GUI mirror must follow or the Settings dialog would seed a stale ON while the agent is
            // OFF (adversarial finding 4). Difficulty/llmModel mirrors are cosmetic here (inert /
            // next-launch) and left as-is — the documented task-22 "settings not re-applied on load".
            currentAutopilot = false;
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
                onWindowShown();
            }
        });
        setMinimumSize(new Dimension(800, 600));
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);
    }

    private void layOutPanels(EventBus eventBus, PortStateArtifact portStateArtifact,
                              ChatInputProcessor chatInputProcessor) {
        setLayout(new BorderLayout());

        HUDPanel hud = new HUDPanel(eventBus);
        hud.setName(NAME_HUD);

        MapPanel map = new MapPanel(eventBus, portStateArtifact);
        map.setName(NAME_MAP);
        map.setMinimumSize(new Dimension(MAP_MIN_WIDTH, 0));

        ChatPanel chat = new ChatPanel(eventBus, chatInputProcessor, lifecycle.simClock());
        chat.setName(NAME_CHAT);
        chat.setMinimumSize(new Dimension(SIDE_MIN_WIDTH, 0));

        CommLogPanel commLog = new CommLogPanel(eventBus);
        commLog.setName(NAME_COMMLOG);
        commLog.setMinimumSize(new Dimension(SIDE_MIN_WIDTH, 0));

        // #8: real splitters (was a fixed GridBag 0.5/0.25/0.25 that let a narrow window squeeze
        // the map to a sliver). Minimum sizes forbid crushing the map or either side panel, and the
        // divider positions persist (restoreDividers, on window-shown). Inner: chat | comm log;
        // outer: map | (chat|comm log).
        innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chat, commLog);
        innerSplit.setResizeWeight(0.5);
        innerSplit.setContinuousLayout(true);
        outerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, map, innerSplit);
        outerSplit.setResizeWeight(0.5);
        outerSplit.setContinuousLayout(true);

        add(hud, BorderLayout.NORTH);
        add(outerSplit, BorderLayout.CENTER);
        add(new NotificationStrip(eventBus), BorderLayout.SOUTH);
    }

    /** Post-window-shown setup (#8 dividers need real split sizes; the overlay needs valid content
     * bounds; first-launch needs the window up). Runs once from {@code windowOpened}. */
    private void onWindowShown() {
        syncTutorialOverlayBounds();
        getContentPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                syncTutorialOverlayBounds();
            }
        });
        restoreDividers();
        // First launch (no manual save yet): auto-open the tutorial. The always-available path is
        // Help → Show tutorial (so a dev machine that already has saves can still see it).
        if (session.saveLoadManager().isFirstLaunch()) {
            tutorialOverlay.startFromBeginning();
        }
    }

    /** Keeps the tutorial overlay exactly over the content area as the window resizes. */
    private void syncTutorialOverlayBounds() {
        tutorialOverlay.setBounds(getContentPane().getBounds());
        tutorialOverlay.revalidate();
        tutorialOverlay.repaint();
    }

    /** #8: restore each splitter's divider from its persisted PROPORTION (or a 50/50 default),
     * then track drags. Called once the splits have real sizes (window shown), so
     * {@code setDividerLocation(double)} lands correctly. */
    private void restoreDividers() {
        applyDivider(outerSplit, PREF_OUTER_DIVIDER);
        applyDivider(innerSplit, PREF_INNER_DIVIDER);
    }

    private void applyDivider(JSplitPane split, String key) {
        split.setDividerLocation(clampProportion(prefs.getDouble(key, 0.5)));
        // Attached AFTER the restore so the restore itself is not re-persisted. Persist ONLY a
        // genuine divider DRAG — detected by the split's width being unchanged since the previous
        // event. On a window RESIZE the split re-lays-out to a new width and repositions the divider
        // (resizeWeight 0.5), which would otherwise drift the stored proportion toward 50/50 across
        // resize+restart cycles (adversarial finding 3); those events carry a changed width and are
        // skipped, so only the ratio the player deliberately dragged is remembered.
        int[] lastWidth = { split.getWidth() };
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            int width = split.getWidth();
            int span = width - split.getDividerSize();
            if (width == lastWidth[0] && span > 0) {
                prefs.putDouble(key, clampProportion(split.getDividerLocation() / (double) span));
            }
            lastWidth[0] = width;
        });
    }

    private static double clampProportion(double proportion) {
        return Math.max(0.1, Math.min(0.9, proportion));
    }

    private JMenuBar buildMenuBar(EventBus eventBus, Runnable onClose) {
        JMenuBar bar = new JMenuBar();
        JMenu game = new JMenu("Game");
        // Task 23: scenario chooser. The current world (if any) is replaced — the dialog's
        // explicit Start IS the confirmation, mirroring confirmAndLoad's dialog.
        newGameItem = new JMenuItem("New Game…");
        newGameItem.addActionListener(e -> chooseAndStartScenario());
        game.add(newGameItem);
        game.addSeparator();
        pauseItem = new JMenuItem("Pause");
        pauseItem.addActionListener(e -> lifecycle.pause());
        resumeItem = new JMenuItem("Resume");
        resumeItem.addActionListener(e -> lifecycle.resume());
        // Task 21: the two fixed slots (savegame.json / autosave.json) now go through
        // SaveLoadDialog — a two-slot chooser with day/wallet previews and an overwrite confirm —
        // rather than the task-22 fixed-path menu items. The dialog is a chooser only; it delegates
        // the actual work back to saveGame()/confirmAndLoad(), which keep the worker-thread quiesce.
        saveItem = new JMenuItem("Save Game…");
        saveItem.addActionListener(e -> openSaveDialog());
        loadItem = new JMenuItem("Load Game…");
        loadItem.addActionListener(e -> openLoadDialog());
        quitItem = new JMenuItem("Quit Game…");
        quitItem.addActionListener(e -> confirmQuit());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> shutdownAndExit(onClose));
        game.add(pauseItem);
        game.add(resumeItem);
        game.addSeparator();
        game.add(saveItem);
        game.add(loadItem);
        game.addSeparator();
        game.add(quitItem);
        game.addSeparator();
        game.add(exit);
        bar.add(game);
        bar.add(buildSettingsMenu(eventBus));
        bar.add(buildDebugMenu(eventBus));
        bar.add(buildHelpMenu());
        makePopupsHeavyweight(bar);
        return bar;
    }

    /**
     * Forces every menu's dropdown to be a HEAVYWEIGHT popup — the second half of the C-04 fix
     * (found by the adversarial review of the first half, 2026-07-27).
     *
     * <p>Passing the menu-bar strip through {@code PauseOverlay.contains} makes the menus OPEN while
     * paused, and stops there. Swing's default popup is <b>lightweight</b>: {@code LightWeightPopup}
     * adds the {@code JPopupMenu} to the frame's own {@code JLayeredPane} — and {@code JRootPane.addImpl}
     * forces the glass pane to child index 0, above the entire layered pane. So the dropdown would
     * render UNDER the PAUSED scrim and its items would land in the region where {@code contains()}
     * still reports true, i.e. **Save and Load would still be unclickable while paused** — the exact
     * two items {@code reflectMode()} deliberately keeps enabled in PAUSED, and the whole reason C-04
     * is a defect rather than a design choice. A heavyweight popup is its own window and is not in the
     * root pane's z-order at all, so the glass pane cannot cover it.
     *
     * <p>Set per menu rather than through the global {@code JPopupMenu.setDefaultLightWeightPopupEnabled}
     * static, which would change popup behaviour for every combo box and tooltip in the app.
     * {@code MenuPopupWeightTest} pins it.
     */
    static void makePopupsHeavyweight(JMenuBar bar) {
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu != null) {
                menu.getPopupMenu().setLightWeightPopupEnabled(false);
            }
        }
    }

    /** Settings → Settings…: opens the four-knob dialog seeded with the current live values (day
     * length read straight off the SimClock so a scenario's pinned pacing shows, not a stale
     * default). */
    private JMenu buildSettingsMenu(EventBus eventBus) {
        JMenu settings = new JMenu("Settings");
        JMenuItem open = new JMenuItem("Settings…");
        open.addActionListener(e -> SettingsDialog.open(this, eventBus, currentDifficulty,
                lifecycle.simClock().realSecondsPerGameDay(), currentAutopilot, currentLlmModel));
        settings.add(open);
        return settings;
    }

    /** Help → Help… (read-only reference), Show tutorial (reopens the overlay from step 1 — the
     * always-available path, since first-launch auto-open is suppressed once a save exists), About. */
    private JMenu buildHelpMenu() {
        JMenu help = new JMenu("Help");
        JMenuItem helpItem = new JMenuItem("Help…");
        helpItem.addActionListener(e -> new HelpDialog(this).setVisible(true));
        JMenuItem tutorial = new JMenuItem("Show tutorial");
        tutorial.addActionListener(e -> tutorialOverlay.startFromBeginning());
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Port Command Genova\nA JADE + SWI-Prolog + Rasa + local-LLM port-management game.\n"
                        + "MSc creative project — Università di Genova.",
                "About Port Command Genova", JOptionPane.INFORMATION_MESSAGE));
        help.add(helpItem);
        help.add(tutorial);
        help.addSeparator();
        help.add(about);
        return help;
    }

    private void openSaveDialog() {
        SaveLoadDialog.open(this, session.saveLoadManager(), SaveLoadDialog.Mode.SAVE, this::saveGame, null);
    }

    private void openLoadDialog() {
        SaveLoadDialog.open(this, session.saveLoadManager(), SaveLoadDialog.Mode.LOAD, null, this::confirmAndLoad);
    }

    /** Applies a {@link SettingsChangedEvent} (task 21). Only the day length has a live effect —
     * it re-paces the shared SimClock at once. The other three are mirrored so the dialog re-opens
     * on the last-applied values; difficulty has no runtime consumer in v1 and llmModel is picked
     * up by the sidecar only on the next launch (see {@link SettingsChangedEvent}). */
    private void applySettings(SettingsChangedEvent event) {
        lifecycle.simClock().setRealSecondsPerGameDay(event.realSecondsPerGameDay());
        currentDifficulty = event.difficulty();
        currentAutopilot = event.autopilotEnabled();
        currentLlmModel = event.llmModel();
    }

    /**
     * Game → Save Game: quiesce + snapshot + atomic write on a WORKER thread (adversarial
     * review #5 — the bounded snapshot wait is normally milliseconds, but a wedged
     * HarbourMaster thread must freeze the save, not the whole UI); polite dialog if
     * refused (22.7). The menu item disables for the duration, so no double-save.
     */
    private void saveGame() {
        saveItem.setEnabled(false);
        saveInProgress = true; // audit C-08: Esc must not un-pause the world mid-quiesce
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
                saveInProgress = false;
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
     * Game → New Game… (task 23): modal scenario chooser, then the same worker-thread +
     * {@code loadInProgress} pattern as {@link #confirmAndLoad} — {@code startScenario}
     * is a teardown/rebuild that takes seconds and must never block the EDT. The GUI
     * refreshes itself from the boot's events ({@code GameLoadedEvent} etc.).
     */
    private void chooseAndStartScenario() {
        if (loadInProgress) {
            return;
        }
        java.util.Optional<String> key = NewGameDialog.choose(this,
                session.scenarioRegistry().all());
        if (key.isEmpty()) {
            return;
        }
        loadInProgress = true;
        reflectMode();
        Thread worker = new Thread(() -> {
            Exception failure = null;
            try {
                session.startScenario(key.get());
            } catch (Exception e) {
                failure = e;
            }
            Exception reported = failure;
            javax.swing.SwingUtilities.invokeLater(() -> {
                loadInProgress = false;
                reflectMode();
                if (reported != null) {
                    JOptionPane.showMessageDialog(this, reported.getMessage(),
                            "New Game", JOptionPane.ERROR_MESSAGE);
                }
            });
        }, "scenario-starter");
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

    /** The menu bar's rectangle expressed in the glass pane's coordinate space, or an empty
     * rectangle when there is no menu bar / it is not showing (audit C-04). */
    private java.awt.Rectangle menuBarBoundsInGlassPane() {
        javax.swing.JMenuBar bar = getJMenuBar();
        if (bar == null || !bar.isVisible() || bar.getParent() == null) {
            return new java.awt.Rectangle();
        }
        return javax.swing.SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), pauseOverlay);
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

    /**
     * The single funnel for Esc, Game → Pause and the overlay's Resume button.
     *
     * <p><b>Audit C-08 (2026-07-27):</b> gated while a manual save is in flight. The pause
     * {@code GameSession.saveNow} takes around the snapshot IS the quiesce —
     * {@code buildOnHarbourMasterThread}'s own javadoc says "no HM behaviour can be mid-step while
     * ours runs; THE CLOCK IS PAUSED so nothing sim-driven moves elsewhere". Esc bypassed that
     * entirely: the player sees an unexplained PAUSED scrim appear (the save's own pause), presses
     * Esc, the clock restarts, and the one-shot snapshot behaviour then walks a world that is
     * moving — tug legs, cargo timers and walk-in patience all advancing underneath it. Normally
     * milliseconds; the code budgets up to {@code MANUAL_SAVE_TIMEOUT_SECONDS = 5} for it.
     * Secondary: the save's {@code finally { resumeIfPaused(); }} would otherwise silently cancel
     * a pause the player took deliberately during the write.
     */
    private void toggleMenuResume() {
        if (saveInProgress || loadInProgress) {
            return;
        }
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
        newGameItem.setEnabled(!loadInProgress); // legal from any settled mode (replaces the world)
        pauseItem.setEnabled(!loadInProgress && mode == GameMode.RUNNING);
        resumeItem.setEnabled(!loadInProgress && mode == GameMode.PAUSED);
        quitItem.setEnabled(!loadInProgress && (mode == GameMode.RUNNING || mode == GameMode.PAUSED));
        boolean live = mode == GameMode.RUNNING || mode == GameMode.PAUSED;
        saveItem.setEnabled(!loadInProgress && live);
        // Loading is legal from a live game AND from GAME_OVER (continue from the autosave slot).
        boolean loadable = live || mode == GameMode.GAME_OVER;
        loadItem.setEnabled(!loadInProgress && loadable);
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
