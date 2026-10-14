package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PolicyListEvent;
import it.unige.portcommand.gui.events.ScriptedWaveEvent;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.gui.model.GameSpeedModel;
import it.unige.portcommand.gui.model.NewGameChoice;
import it.unige.portcommand.gui.model.PolicyListModel;
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
 * The Swing shell (task 17): the {@code JFrame}, its menu bar, its two overlays and its dialogs.
 *
 * <p><b>S15 (2026-10-12): the frame no longer lays the panels out itself.</b> Everything inside
 * it — the top bar, the three columns (waiting list over Port table | chat | comm log) and the
 * notification strip — is one {@link GameScreen}, a plain panel that can be built, measured and
 * painted in a headless test, which a {@code JFrame} cannot. This class keeps what needs a real
 * window: the menus, the glass pane, the window's size and position, the {@link Preferences} the
 * two column dividers are remembered in, and the worker threads behind New Game, Save and Load.
 * The window also opens BEFORE any game exists when {@code Main} asks for that (a plain
 * {@code --gui} launch): it then offers the New Game dialog once, and until the player picks a
 * game — or loads one — it shows the empty shell (mode MENU).
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

    /**
     * Below this the three-column layout stops being usable; the clamp floors at it. S15
     * (2026-10-12): 800 → 1000. The three columns have minimum widths of 180 / 380 / 380 px
     * ({@link GameScreen}), which with the two dividers is {@link GameScreen#minimumColumnsWidth()}
     * — 960 px under the default look-and-feel — and the frame's own borders come on top. A
     * window narrower than that could not give every column its minimum. Package-private so
     * {@code GameScreenCompositionTest} can pin the relation.
     */
    static final int MIN_WIDTH = 1000;
    static final int MIN_HEIGHT = 600; // S18b (U18): package-private for WindowSizingTest

    /** S14: component name of the Game ▸ Speed submenu. (The names the {@link TutorialOverlay}
     * highlights by moved to {@link GameScreen} in S15, with the panels they name.) */
    static final String NAME_SPEED_MENU = "menu-game-speed";

    /**
     * {@link Preferences} keys for the two column-divider PROPORTIONS (window-size independent,
     * so a restart at any size restores the same relative layout — #8). S15 (2026-10-12): NEW
     * keys. The pre-S15 keys described a different layout (a wide left panel at 50 %), so a value
     * stored under them would open the new layout with the narrow left column taking half the
     * window; they are simply no longer read.
     */
    private static final String PREF_LEFT_DIVIDER = "leftColumnDividerProportion";
    private static final String PREF_CHAT_DIVIDER = "chatCommLogDividerProportion";

    /** S15: shown when the player closes the opening New Game dialog without choosing — the window
     * is then an empty shell, and this says how to get out of it. */
    static final String NO_GAME_RUNNING_TEXT =
            "No game running — Game ▸ New Game… or Load Game… to start.";

    /** The scenario whose own scripted cards ARE the tutorial (see {@link #maybeAutoOpenTutorial}). */
    private static final String TUTORIAL_SCENARIO_KEY = "tutorial";
    /** S18b (P10): set once the player has ended the tutorial (×, Skip or Done) - the cards do
     * not open by themselves on any later launch. Help → Show tutorial still shows them. */
    static final String PREF_TUTORIAL_CLOSED = "tutorial.closed";

    /** S18b (REMAINING_AREAS_DECISIONS P1): the window's ✕ and Game ▸ Exit ask first. */
    static final String QUIT_TEXT = "Quit? Unsaved progress since the last autosave will be lost.";
    static final String SAVE_AND_QUIT = "Save & quit";
    static final String QUIT = "Quit";
    static final String CANCEL = "Cancel";
    /** S18b (PLAYABILITY_DECISIONS §10): a manual save with negotiations open. */
    static final String OPEN_NEGOTIATIONS_TEXT = "Open negotiations will be lost. Save anyway?";
    /** S18b (P11): New Game… over a running game. */
    static final String REPLACE_GAME_TEXT =
            "Start a new game? The current game ends — progress since the last save will be lost.";
    /** S18b (P8): a game the window loaded waits for the player. */
    static final String GAME_LOADED_TEXT = "Game loaded — press ▶ or Esc to continue.";
    /** S18b (P5): an autosave is said; a failed one is said by the autosave itself. */
    static String autosavedText(int endedDay) {
        return "Autosaved (end of day " + endedDay + ")";
    }


    /** S16: shown when a changed day length reaches {@link #applySettings} under the script lock. */
    static final String DAY_LENGTH_LOCKED_TEXT =
            "Day length not changed — it is locked while the scenario's scripted events are running. "
                    + "The other settings were applied.";

    private final EventBus eventBus;
    private final GameLifecycle lifecycle;
    private final Leaderboard leaderboard;
    private final GameSession session;
    private final EndOfDaySummaryDialog endOfDayDialog;
    private final GameOverDialog gameOverDialog;
    private final PauseOverlay pauseOverlay;
    private final TutorialOverlay tutorialOverlay;
    /** S14: the one applier behind both speed controls (HUD toggles, Game ▸ Speed). S16: it also
     * holds the scenario-script lock ({@link GameSpeedControl#scriptPending()}) — the window's
     * "is the script still running" for the speed menu, the Settings dialog and
     * {@link #applySettings}. */
    private final GameSpeedControl speedControl;
    /** S18a (ADR-23, decision N11): the autopilot policies in force, as the Assistant last
     * reported them ({@code PolicyListEvent}). Kept here because the Settings dialog exists only
     * while it is open and has to show the list the moment it does. */
    private final PolicyListModel policyList = new PolicyListModel();
    /** S16: the Game ▸ Speed items by multiplier, kept so the script lock can disable them. */
    private final Map<Integer, JRadioButtonMenuItem> speedMenuItems = new LinkedHashMap<>();
    /** S15: everything inside the frame — top bar, three columns, notification strip. */
    private final GameScreen screen;
    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
    /** S15: whether this window opened with no game running and must offer the New Game dialog. */
    private final boolean offerNewGameOnOpen;
    /** S15: set the first time {@link #maybeAutoOpenTutorial()} runs — the first-launch tutorial
     * cards are considered once per process, whatever the answer was. EDT-only. */
    private boolean tutorialAutoOpenConsidered;
    private JMenuItem newGameItem;
    /** S18b (U13): disabled in the empty shell, where its three items have no game to act on. */
    private JMenu debugMenu;
    private JMenuItem pauseItem;
    private JMenuItem resumeItem;
    private JMenuItem quitItem;
    private JMenuItem saveItem;
    private JMenuItem loadItem;
    private JMenuItem settingsItem;
    /** S17b (PT8): Game ▸ Call Next Ship. */
    private JMenuItem callNextShipItem;
    /** True while a load worker thread owns the world — menus disabled, no re-entry. */
    private boolean loadInProgress;
    /** True from the moment {@link #saveGame()} starts its worker until the worker's
     * {@code invokeLater} lands. EDT-only, like {@code loadInProgress}. See
     * {@link #toggleMenuResume()} for what it protects (audit C-08). */
    private boolean saveInProgress;

    /** Live mirror of the three non-clock editable settings (task 21): seeded from
     * {@code defaults.json} and updated on {@link SettingsChangedEvent} so re-opening the Settings
     * dialog shows the last-applied values. S9 (adversarial finding 3): the three field
     * MIRRORS that used to live here were DELETED — once {@code Settings.load()} became the
     * session's effective record, a mirror seeded at construction went stale after every
     * {@code loadGame}, and the new write-through would have PERSISTED the stale value on the
     * next Apply (a silent revert of the restored settings). The dialog now seeds from
     * {@code Settings.load()} directly; the day length keeps reading the live {@code SimClock}
     * (single source of truth for pacing, reflects scenario pins). */

    /** The latest REAL settled summary — the Debug menu re-shows it once one exists. */
    private EndOfDaySummary latestSummary;
    /** Whether the CURRENT pause was taken for the EOD report (vs. by the player). */
    private boolean pausedForEndOfDay;

    /**
     * @param eventBus          the shared bus every panel subscribes through
     * @param portStateArtifact the shared tug state the Port table seeds from and subscribes
     *                          to — the only panel that needs it
     * @param chatInputProcessor the NLP pipeline seam (task 19) {@code ChatPanel} calls on Send
     * @param lifecycle         the task-24 game-loop orchestrator this shell's menu drives; also
     *                          the read-only source of the shared {@code SimClock} for countdowns
     * @param leaderboard       the ONE shared board ({@code Main} constructs it) — the same
     *                          instance {@code GameOverGuard} records into, so the dialogs render
     *                          scores the moment they land
     * @param onClose           clean-shutdown hook run before the window disposes and the JVM
     *                          exits (wired to {@code JadeBootstrap::shutdown} in {@code Main})
     * @param offerNewGameOnOpen S15: {@code true} when the window opens on the empty shell (no
     *                          game was started on the command line) — it then shows the New Game
     *                          dialog once, as soon as it is up
     */
    public MainWindow(EventBus eventBus, PortStateArtifact portStateArtifact,
                      ChatInputProcessor chatInputProcessor, GameLifecycle lifecycle,
                      Leaderboard leaderboard, GameSession session, Runnable onClose,
                      boolean offerNewGameOnOpen) {
        super("Port Command Genova");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // we drive shutdown ourselves
        this.eventBus = eventBus;
        this.lifecycle = lifecycle;
        this.leaderboard = leaderboard;
        this.session = session;
        this.offerNewGameOnOpen = offerNewGameOnOpen;
        // S14: refused while a load owns the world — the loader applies the SAVED speed, and a
        // click landing mid-load must not overwrite it (same guard as applySettings). S15: refused
        // in MENU too. With the window open on the empty shell there is no game to speed up, and
        // the next game start applies ITS settings' speed anyway — so an accepted ×4 would be
        // silently discarded a moment later. Refusing re-announces the speed in force, which
        // snaps the toggle back instead. S16: the third refusal — a scenario's script is still
        // running — is not in this predicate but in the control itself (setScriptPending, fed by
        // the ScriptedWaveEvent subscription below), where it can be tested without a window.
        this.speedControl = new GameSpeedControl(lifecycle.simClock(), eventBus,
                () -> lifecycle.mode() == GameMode.LOADING || lifecycle.mode() == GameMode.MENU);
        this.screen = new GameScreen(eventBus, portStateArtifact, chatInputProcessor,
                lifecycle.simClock(), speedControl);
        // Audit C-04: the glass pane covers the menu-bar strip too, so it is handed the menu bar's
        // live bounds (in glass-pane coordinates) to pass mouse events through and to skip when
        // painting the scrim. Computed lazily on every hit-test/paint — the menu bar does not exist
        // yet at this point in construction, and its height can change with the look-and-feel.
        // S15: the second region it passes through is the whole three-column area (was the chat
        // alone in S14) — the waiting list and the comm log are read-only views, and a paused
        // game is exactly when the player wants to read them.
        // S18b: the caption sits on the top bar (T8), and the bar's pause button clicks through (PT4).
        this.pauseOverlay = new PauseOverlay(this::toggleMenuResume, this::menuBarBoundsInGlassPane,
                this::columnsBoundsInGlassPane, this::pauseCaptionAreaInGlassPane, this::pauseButtonInGlassPane);
        screen.onPauseToggle(this::toggleMenuResume); // S18b (PT4): the top bar's ⏸/▶
        setGlassPane(pauseOverlay);
        setJMenuBar(buildMenuBar(eventBus, onClose));
        setLayout(new BorderLayout());
        add(screen, BorderLayout.CENTER);
        installEscBinding();

        // Task 21: the tutorial scrim lives in the layered pane (the glass pane is already the
        // PauseOverlay) above the content; it searches the content pane's tree for the named
        // highlight targets and paints nothing until the window is up (GuiReadyEvent).
        this.tutorialOverlay = new TutorialOverlay(eventBus, getContentPane());
        tutorialOverlay.onClosedForGood(() -> prefs.putBoolean(PREF_TUTORIAL_CLOSED, true));
        getLayeredPane().add(tutorialOverlay, JLayeredPane.MODAL_LAYER);
        // Task 21: apply the four-knob Settings screen. Day length is genuinely live (re-paces the
        // shared SimClock); the other three are mirrored for the dialog's next open (difficulty is
        // inert in v1, llmModel is next-launch — see SettingsChangedEvent).
        eventBus.subscribe(SettingsChangedEvent.class, this::applySettings, DeliveryMode.EDT);
        // S18a (ADR-23, decision N11): the Assistant reports the policies in force; the Settings
        // dialog, which exists only while it is open, reads them from this window-long model.
        eventBus.subscribe(PolicyListEvent.class, e -> policyList.set(e.descriptions()), DeliveryMode.EDT);
        // S16 (2026-10-13, PLAYABILITY §10 / T7 / P12): while a scenario's scripted events are
        // still to come, the speed and the day length are locked. The fact lives on the
        // HarbourMaster's scenario engine; the window may only hear of it over the bus — at every
        // world boot, when the last scripted event fires, and again when a window reports ready
        // (a world booted before the window existed). Engaging the lock drops the speed to ×1
        // first if need be, and the control then announces the change: the top bar's toggles
        // and the Game ▸ Speed items disable themselves on that announcement.
        eventBus.subscribe(ScriptedWaveEvent.class,
                e -> speedControl.setScriptPending(e.pending()), DeliveryMode.EDT);
        // S18b (REMAINING_AREAS_DECISIONS P5): the autosave used to be silent. A failure is
        // reported by the autosave itself; a manual save has its own dialog.
        eventBus.subscribe(it.unige.portcommand.persistence.events.GameSavedEvent.class, e -> {
            if (e.autosave()) {
                eventBus.publish(new NotificationEvent(autosavedText(e.gameDay()), NotificationEvent.Severity.INFO,
                        lifecycle.simClock().nowSimMillis()));
            }
        }, DeliveryMode.EDT);

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
            // S9: the old `currentAutopilot = false` line here compensated for a respawned
            // AssistantAgent booting autopilot OFF unconditionally. Both halves are gone: the
            // agent now seeds its flag from Settings.load() (which bootWorld applied from the
            // SAVE before any agent spawned), and the dialog reads Settings.load() directly —
            // agent and dialog agree on the RESTORED value by construction.
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
                // S17b (decision E8): Close on a scenario's end. The scenario is over and the
                // player does not want to carry on in free play: the game stays paused and the
                // New Game dialog opens. Cancelling that leaves the finished day on screen,
                // paused - Resume (or Esc) is then "continue in free play" after all.
                if (endOfDayDialog.takeCloseRequested()) {
                    pausedForEndOfDay = false;
                    chooseAndStartGame(false); // the scenario is over: nothing to confirm (S18b, P11)
                    return;
                }
                if (pausedForEndOfDay) {
                    pausedForEndOfDay = false;
                    lifecycle.resumeIfPaused();
                }
            }
        });
        // S18b (P11): New Game… on the game-over report; the game is over, so nothing to confirm.
        this.gameOverDialog = new GameOverDialog(this, eventBus, leaderboard, () -> shutdownAndExit(onClose),
                () -> chooseAndStartGame(false));
        reflectMode();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestExit(onClose); // S18b (P1): asks first while a game is running
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
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        // S18b (UI tree U18): the minimum height gives way to a desktop shorter than it (1366x768 at
        // 125%), so the notification strip is never pushed behind the taskbar.
        int minHeight = minimumHeightFor(usable);
        setMinimumSize(new Dimension(MIN_WIDTH, minHeight));
        // Clamp to the USABLE desktop, not the raw screen (2026-08-10, audit P1-13 / demo-laptop
        // risk C-19). Two distinct failures, and 125% vs 150% scaling hits a different one each:
        // at 150% the desktop reports 1280x720, so a flat setSize(1280, 800) simply does not FIT;
        // at 125% it reports 1536x864 and 800 does fit, but setLocationRelativeTo(null) centres on
        // the FULL screen rect, so the window straddles the ~48px taskbar and the NotificationStrip
        // along its bottom edge lands behind it. Clamping without re-centring would fix only the
        // first, which is why this computes both. getMaximumWindowBounds()
        // already excludes the taskbar. It throws under -Djava.awt.headless=true (both Gradle test
        // lanes set it), so the call stays HERE and only the arithmetic is extracted, mirroring
        // makePopupsHeavyweight/MenuPopupWeightTest.
        setBounds(clampToUsableBounds(usable, DEFAULT_WIDTH, DEFAULT_HEIGHT, MIN_WIDTH, minHeight));
    }

    /**
     * S18b (UI tree U18): the window's minimum height on a desktop whose usable area is
     * {@code usable} — {@link #MIN_HEIGHT}, or less on a desktop that is shorter than that. A
     * window taller than the desktop pushed the notification strip behind the taskbar (1366x768
     * at 125% scaling leaves about 574 px). The width keeps its minimum: below it the three
     * columns cannot all have theirs. Pure, for {@code WindowSizingTest}.
     */
    static int minimumHeightFor(Rectangle usable) {
        return usable == null || usable.height <= 0 ? MIN_HEIGHT : Math.min(MIN_HEIGHT, usable.height);
    }

    /**
     * The preferred window rectangle for a desktop whose usable area is {@code usable}: the
     * preferred size capped to what actually fits, floored at the minimum, then centred WITHIN
     * {@code usable} (never within the full screen — see the call site).
     *
     * <p>The minimum deliberately wins over the cap on a desktop too small for it: a window
     * smaller than {@link #MIN_WIDTH}x{@link #MIN_HEIGHT} crushes the three-column layout into
     * unusability, so overflowing a very small screen is the better failure — the user can still
     * move the window, and {@code setMinimumSize} would override us anyway.
     *
     * <p>Pure arithmetic on a caller-supplied rectangle so it is unit-testable headless;
     * {@code WindowSizingTest} pins it.
     */
    static Rectangle clampToUsableBounds(Rectangle usable, int preferredWidth, int preferredHeight,
                                         int minWidth, int minHeight) {
        int width = Math.max(minWidth, Math.min(preferredWidth, usable.width));
        int height = Math.max(minHeight, Math.min(preferredHeight, usable.height));
        int x = usable.x + Math.max(0, (usable.width - width) / 2);
        int y = usable.y + Math.max(0, (usable.height - height) / 2);
        return new Rectangle(x, y, width, height);
    }

    /** Post-window-shown setup (#8 dividers need real split sizes; the overlay needs valid content
     * bounds; the New Game offer and the first-launch tutorial need the window up). Runs once from
     * {@code windowOpened}. */
    private void onWindowShown() {
        syncTutorialOverlayBounds();
        getContentPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                syncTutorialOverlayBounds();
            }
        });
        restoreDividers();
        if (offerNewGameOnOpen) {
            // S15: the window opened on the empty shell — offer a game. Deferred one EDT turn so
            // the window finishes opening (and is painted) before a modal dialog sits on top of
            // it; the dialog's own event loop then keeps the shell behind it repainting.
            SwingUtilities.invokeLater(this::offerNewGame);
        } else {
            // A game was started on the command line (--scenario / --load) and is already
            // running: for the first-launch tutorial, "the first game has started" is now.
            showTheTutorialsFirstCard(); // S18b (PT2): a --scenario tutorial launch starts paused
            maybeAutoOpenTutorial();
        }
    }

    /**
     * S15: the one-time New Game offer of a window that opened with no game running. Cancel (or
     * the dialog's ✕) is a legitimate answer — the player may want Load Game… instead — so it
     * leaves the empty shell up and says where to go from there.
     */
    private void offerNewGame() {
        // S18b (UI tree U13): a Cancel leaves the empty shell, and the shell says what to do itself
        // — a hint pinned in the notification strip, "No game running" in the chat column (see
        // reflectMode). The 8-second chip that used to say it is gone.
        chooseAndStartGame(false);
    }

    /**
     * First launch (no manual save yet): open the five tutorial cards — once per process, and only
     * once a game is running. The always-available path stays Help → Show tutorial (so a dev
     * machine that already has saves can still see them).
     *
     * <p>S15 (2026-10-12) — why not at window-open any more. The window now opens BEFORE a game
     * exists, and every game start publishes {@code GameLoadedEvent}, which dismisses the overlay
     * (audit C-13): cards opened with the window would be wiped by the first game the player
     * picks. So they open after that game has started — the completion callback of
     * {@link #chooseAndStartGame} is queued behind the boot's own EDT deliveries, hence after that
     * dismiss. And not at all when that game IS the tutorial scenario: its script drives the same
     * overlay through the same {@code showStep}, so a scripted step would replace the card the
     * player is paging, and its final step arms the auto-dismiss even over manual paging. The
     * scenario counts as the tutorial having been offered.
     */
    private void maybeAutoOpenTutorial() {
        if (tutorialAutoOpenConsidered) {
            return;
        }
        tutorialAutoOpenConsidered = true;
        if (session.saveLoadManager().isFirstLaunch()
                && !prefs.getBoolean(PREF_TUTORIAL_CLOSED, false) // S18b (P10)
                && !TUTORIAL_SCENARIO_KEY.equals(session.activeScenarioKey())) {
            tutorialOverlay.startFromBeginning();
        }
    }

    /**
     * S18b (PT2): the Tutorial starts paused, so its first card has to be on screen without the
     * clock - the script's own step 1 fires on the first tick, which a paused world never takes.
     * The overlay shows each step once, so the script's step 1 is dropped when it does come.
     */
    private void showTheTutorialsFirstCard() {
        if (!TUTORIAL_SCENARIO_KEY.equals(session.activeScenarioKey())) {
            return;
        }
        session.scenarioRegistry().findByKey(TUTORIAL_SCENARIO_KEY)
                .flatMap(MainWindow::firstScriptedTutorialStep)
                .ifPresent(text -> eventBus.publish(
                        new it.unige.portcommand.scenario.events.TutorialStepAdvancedEvent(1, text)));
    }

    /** S18b (PT2): the text of a scenario's scripted tutorial step 1, if it has one. Static and
     * pure so a headless test can read it from the real scenario file. */
    static Optional<String> firstScriptedTutorialStep(it.unige.portcommand.scenario.Scenario scenario) {
        return scenario.events().stream()
                .filter(e -> e instanceof it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent t
                        && t.step() == 1)
                .map(e -> ((it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent) e).text())
                .findFirst();
    }

    /** Keeps the tutorial overlay exactly over the content area as the window resizes. */
    private void syncTutorialOverlayBounds() {
        tutorialOverlay.setBounds(getContentPane().getBounds());
        tutorialOverlay.revalidate();
        tutorialOverlay.repaint();
    }

    /**
     * #8: restore both column dividers from their persisted PROPORTIONS (or the defaults — a
     * 20 % left column, chat and comm log sharing the rest), then start remembering drags. Called
     * once the window is shown, so the split panes have real sizes.
     *
     * <p>S15: the arithmetic lives in {@link GameScreen} and {@code ColumnDividers}. A stored
     * proportion is clamped there against the columns' minimum widths — Swing itself does not do
     * that for a location set from code — and only a genuine drag is handed back to be stored (a
     * window resize, which also moves the dividers, is not). Tracking starts AFTER the restore, so
     * the restore itself is never re-persisted.
     */
    private void restoreDividers() {
        screen.applyDividerProportions(
                prefs.getDouble(PREF_LEFT_DIVIDER, GameScreen.DEFAULT_LEFT_PROPORTION),
                prefs.getDouble(PREF_CHAT_DIVIDER, GameScreen.DEFAULT_CHAT_PROPORTION));
        screen.trackDividerDrags(
                proportion -> prefs.putDouble(PREF_LEFT_DIVIDER, proportion),
                proportion -> prefs.putDouble(PREF_CHAT_DIVIDER, proportion));
    }

    private JMenuBar buildMenuBar(EventBus eventBus, Runnable onClose) {
        JMenuBar bar = new JMenuBar();
        JMenu game = new JMenu("Game");
        // Task 23: scenario chooser (S15: plus Free play). The current world (if any) is
        // replaced — the dialog's explicit Start IS the confirmation, mirroring confirmAndLoad's
        // dialog.
        newGameItem = new JMenuItem("New Game…");
        newGameItem.addActionListener(e -> chooseAndStartGame(true)); // S18b (P11): asks over a live game
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
        exit.addActionListener(e -> requestExit(onClose)); // S18b (P1)
        // S18b (playtest 2, PT11): the comm log's archive file, or its folder before there is one.
        JMenuItem openCommLog = new JMenuItem("Open comm-log file");
        openCommLog.addActionListener(e -> openCommLogFile());
        // S17b (playtest 2, PT8): the same request as the top bar's "Next ship" button.
        callNextShipItem = new JMenuItem("Call Next Ship");
        callNextShipItem.addActionListener(
                e -> eventBus.publish(new it.unige.portcommand.gui.events.CallNextShipEvent()));
        game.add(pauseItem);
        game.add(resumeItem);
        game.add(buildSpeedMenu(eventBus));
        game.add(callNextShipItem);
        game.addSeparator();
        game.add(saveItem);
        game.add(loadItem);
        game.add(openCommLog);
        game.addSeparator();
        game.add(quitItem);
        game.addSeparator();
        game.add(exit);
        bar.add(game);
        bar.add(buildSettingsMenu(eventBus));
        debugMenu = buildDebugMenu(eventBus);
        bar.add(debugMenu);
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
                makePopupsHeavyweight(menu);
            }
        }
    }

    /** A menu's own dropdown AND every submenu's (S14: Game ▸ Speed is the first submenu — its
     * popup is a separate {@code JPopupMenu}, and a lightweight one would open under the PAUSED
     * scrim exactly like the C-04 case above). */
    private static void makePopupsHeavyweight(JMenu menu) {
        menu.getPopupMenu().setLightWeightPopupEnabled(false);
        for (java.awt.Component item : menu.getMenuComponents()) {
            if (item instanceof JMenu submenu) {
                makePopupsHeavyweight(submenu);
            }
        }
    }

    /**
     * Game ▸ Speed (S14): the keyboard half of the speed control — ×1 / ×2 / ×4 on Ctrl+1 / Ctrl+2
     * / Ctrl+4, mirroring the HUD toggles. Both go through {@link GameSpeedControl#set(int)} and
     * both follow {@link GameSpeedChangedEvent}, so they cannot disagree and a load re-selects the
     * saved speed. Left enabled in every mode: while paused the choice simply takes effect on
     * resume, and while a load is in flight — or (S15) no game is running at all — the control
     * itself refuses and the selection snaps back.
     *
     * <p>S16 (2026-10-13): with one exception. While a scenario's scripted events are still to
     * come the speed is locked to ×1, and the three items are then DISABLED and say why in a
     * tooltip ({@link #reflectScriptLock}) — for the whole of a scripted opening a selection that
     * silently snapped back would read as a broken menu. A disabled item's accelerator does not
     * fire, so Ctrl+2 and Ctrl+4 are locked with it. The two older refusals keep the snap-back:
     * a load lasts a moment, and in the empty shell there is nothing to explain.
     */
    private JMenu buildSpeedMenu(EventBus eventBus) {
        JMenu speedMenu = new JMenu("Speed");
        speedMenu.setName(NAME_SPEED_MENU);
        ButtonGroup group = new ButtonGroup();
        for (int speed : GameSpeedModel.options()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(GameSpeedModel.label(speed));
            item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0 + speed,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK));
            item.addActionListener(e -> speedControl.set(speed));
            group.add(item);
            speedMenuItems.put(speed, item);
            speedMenu.add(item);
        }
        speedMenuItems.get(speedControl.current()).setSelected(true);
        reflectScriptLock();
        eventBus.subscribe(GameSpeedChangedEvent.class, e -> {
            JRadioButtonMenuItem item = speedMenuItems.get(GameSpeedModel.normalise(e.speed()));
            if (item != null) {
                item.setSelected(true);
            }
            // S16: the control announces a change of the script lock with this same event.
            reflectScriptLock();
        }, DeliveryMode.EDT);
        return speedMenu;
    }

    /**
     * S16: the Game ▸ Speed items follow the scenario-script lock — disabled, with the reason as
     * their tooltip, while it is engaged. Read from the control every time, never mirrored in a
     * field of the window: the control is the one place that knows, and the top bar's toggles
     * read the same answer.
     */
    private void reflectScriptLock() {
        boolean locked = speedControl.scriptPending();
        for (JRadioButtonMenuItem item : speedMenuItems.values()) {
            item.setEnabled(!locked);
            item.setToolTipText(locked ? GameSpeedModel.SCRIPT_LOCK_TOOLTIP : null);
        }
        reflectCallNextShip();
    }

    /** S17b (PT8): "Call Next Ship" needs a running game whose scenario script is done. */
    private void reflectCallNextShip() {
        if (callNextShipItem == null) {
            return;
        }
        boolean running = lifecycle.mode() == GameMode.RUNNING;
        boolean locked = speedControl.scriptPending();
        callNextShipItem.setEnabled(!loadInProgress && running && !locked);
        callNextShipItem.setToolTipText(locked ? "The scenario's own ships are still arriving" : null);
    }

    /** Settings → Settings…: opens the four-knob dialog seeded with the current live values (day
     * length read straight off the SimClock so a scenario's pinned pacing shows, not a stale
     * default). S16: and told whether the scenario's script is still running, in which case the
     * dialog shows its day-length slider locked. */
    private JMenu buildSettingsMenu(EventBus eventBus) {
        JMenu settings = new JMenu("Settings");
        settingsItem = new JMenuItem("Settings…");
        // S18b (P2): the game pauses while the dialog is open, and goes on as it was when it closes.
        settingsItem.addActionListener(e -> whilePaused(() -> SettingsDialog.open(this, eventBus,
                Settings.load().difficulty(), lifecycle.simClock().realSecondsPerGameDay(),
                Settings.load().autopilotEnabled(), Settings.load().llmModel(), speedControl.scriptPending(),
                policyList)));
        settings.add(settingsItem);
        return settings;
    }

    /**
     * Whether the Settings dialog may be opened in {@code mode} (S15). Not in MENU: since S15 that
     * is the window's opening state — an empty shell, no game. Every knob of the dialog belongs to
     * a game: a new game starts from the defaults (or its scenario's settings) and a load from the
     * saved ones, so whatever was set in the shell would be thrown away by the next start without
     * a word; and an autopilot policy typed there would be confirmed as "active now" with no
     * Assistant agent alive to register it. The player sets things up once a game is running —
     * the dialog works while paused. Pure, for {@code MainWindowSettingsGateTest}.
     */
    static boolean settingsAvailableIn(GameMode mode) {
        return mode != GameMode.MENU;
    }

    /** Help → Help… (read-only reference), Show tutorial (reopens the overlay from step 1 — the
     * always-available path, since first-launch auto-open is suppressed once a save exists), About. */
    private JMenu buildHelpMenu() {
        JMenu help = new JMenu("Help");
        JMenuItem helpItem = new JMenuItem("Help…");
        helpItem.addActionListener(e -> whilePaused(() -> new HelpDialog(this).setVisible(true))); // S18b (P2)
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

    /** S18b (P2): runs a modal dialog with the game paused behind it ({@link DialogPause}). */
    private void whilePaused(Runnable modalDialog) {
        DialogPause pause = new DialogPause(lifecycle);
        try {
            modalDialog.run();
        } finally {
            pause.release();
        }
    }

    /** S18b (P2): the save that the dialog starts releases the dialog's pause when it is done —
     * not when the dialog closes, which is before the snapshot is taken (the save's quiesce IS
     * that pause, audit C-08). */
    private void openSaveDialog() {
        DialogPause pause = new DialogPause(lifecycle);
        boolean[] saving = {false};
        SaveLoadDialog.open(this, session.saveLoadManager(), SaveLoadDialog.Mode.SAVE, () -> {
            saving[0] = true;
            saveGame(false, null, pause);
        }, null);
        if (!saving[0]) {
            pause.release();
        }
    }

    /** S18b (P2): a load that starts replaces the world, which then comes up paused (P8). */
    private void openLoadDialog() {
        DialogPause pause = new DialogPause(lifecycle);
        boolean[] loading = {false};
        SaveLoadDialog.open(this, session.saveLoadManager(), SaveLoadDialog.Mode.LOAD, null,
                path -> loading[0] = confirmAndLoad(path));
        if (loading[0]) {
            pause.forget();
        } else {
            pause.release();
        }
    }

    /** Applies a {@link SettingsChangedEvent} (task 21). The day length re-paces the shared
     * SimClock at once. S9: every carried knob is written through to {@code Settings.load()}'s
     * live record — llmModel included (finding 2: it still takes effect only next launch, but
     * it now SURVIVES the save/load round trip instead of silently reverting to the classpath
     * default) — so the next save captures the change and a later load restores it (the
     * fix-campaign's settings-revert limitation, closed). The dialog re-opens on
     * {@code Settings.load()} directly; no mirrors (finding 3). */
    private void applySettings(SettingsChangedEvent event) {
        // S10 (bug hunt): a dialog OK landing while a LOAD is replacing the world would write
        // the OLD world's knobs over the settings block bootWorld just restored (the dialog was
        // seeded from pre-load values, so applying them is wrong in both orders). Refuse with a
        // visible reason instead of racing the loader thread.
        if (lifecycle.mode() == GameMode.LOADING) {
            eventBus.publish(new it.unige.portcommand.gui.events.NotificationEvent(
                    "Settings not applied — a game is loading. Re-open Settings once it finishes.",
                    it.unige.portcommand.gui.events.NotificationEvent.Severity.WARNING,
                    lifecycle.simClock().nowSimMillis()));
            return;
        }
        // S15: nor with no game at all (see settingsAvailableIn). The menu item is disabled in
        // MENU; this is for a dialog that was already open when the game under it went away (a
        // load that failed back to the empty shell).
        if (!settingsAvailableIn(lifecycle.mode())) {
            eventBus.publish(new it.unige.portcommand.gui.events.NotificationEvent(
                    "Settings not applied — no game is running. Start or load a game first.",
                    it.unige.portcommand.gui.events.NotificationEvent.Severity.WARNING,
                    lifecycle.simClock().nowSimMillis()));
            return;
        }
        // S16 (2026-10-13, PLAYABILITY §10 / P12): while the scenario's script is running the day
        // length is locked with the speed — a shorter day squeezes the script's game-minute gaps
        // exactly as ×4 does. The dialog shows its slider disabled then, so a changed day length
        // normally cannot arrive; if one does anyway (a dialog opened before the lock engaged),
        // the clock keeps its pace, the other three knobs are applied as asked, and the player is
        // told which part was not done.
        long livePace = lifecycle.simClock().realSecondsPerGameDay();
        boolean dayLengthRefused = speedControl.scriptPending() && event.realSecondsPerGameDay() != livePace;
        long dayLength = dayLengthRefused ? livePace : event.realSecondsPerGameDay();
        lifecycle.simClock().setRealSecondsPerGameDay(dayLength);
        it.unige.portcommand.core.Settings.applyLive(it.unige.portcommand.core.Settings.load()
                .withRealSecondsPerGameDay(dayLength)
                .withDifficulty(event.difficulty())
                .withAutopilotEnabled(event.autopilotEnabled())
                .withLlmModel(event.llmModel()));
        if (dayLengthRefused) {
            eventBus.publish(new NotificationEvent(DAY_LENGTH_LOCKED_TEXT, NotificationEvent.Severity.WARNING,
                    lifecycle.simClock().nowSimMillis()));
        }
    }

    /**
     * Game → Save Game: quiesce + snapshot + atomic write on a WORKER thread (adversarial
     * review #5 — the bounded snapshot wait is normally milliseconds, but a wedged
     * HarbourMaster thread must freeze the save, not the whole UI); polite dialog if
     * refused (22.7). The menu item disables for the duration, so no double-save.
     *
     * <p>S18b (PLAYABILITY_DECISIONS §10): a refusal for an OPEN NEGOTIATION is a question now —
     * "Open negotiations will be lost. Save anyway?" — and a yes saves without those vessels,
     * as the autosave always has; they go on in the running game. The other refusals are as
     * before.
     *
     * @param dropOpenNegotiations the player has already said yes to that question
     * @param afterSuccess         S18b (P1): what follows a save that was written (Save &amp; quit
     *                             exits) instead of the "Game saved" message; {@code null} for that message
     * @param pause                S18b (P2): the Save dialog's pause, released when the save is
     *                             done; {@code null} when there is none
     */
    private void saveGame(boolean dropOpenNegotiations, Runnable afterSuccess, DialogPause pause) {
        saveInProgress = true; // audit C-08: Esc must not un-pause the world mid-quiesce
        reflectMode();
        Thread worker = new Thread(() -> {
            String info = null;
            String error = null;
            boolean saved = false;
            boolean openNegotiation = false;
            try {
                info = "Game saved to " + session.saveNow(dropOpenNegotiations) + ".";
                saved = true;
            } catch (it.unige.portcommand.persistence.OpenNegotiationException e) {
                openNegotiation = true;
                info = e.getMessage();
            } catch (it.unige.portcommand.persistence.SaveNotAllowedException e) {
                info = e.getMessage();
            } catch (Exception e) {
                error = "Save failed: " + e.getMessage();
            }
            String infoMessage = info;
            String errorMessage = error;
            boolean written = saved;
            boolean askAboutNegotiations = openNegotiation;
            javax.swing.SwingUtilities.invokeLater(() -> {
                saveInProgress = false;
                reflectMode();
                if (askAboutNegotiations) {
                    if (JOptionPane.showConfirmDialog(this, OPEN_NEGOTIATIONS_TEXT, "Save Game",
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                        saveGame(true, afterSuccess, pause);
                    } else if (pause != null) {
                        pause.release();
                    }
                    return;
                }
                if (written && afterSuccess != null) {
                    afterSuccess.run();
                    return;
                }
                if (errorMessage != null) {
                    JOptionPane.showMessageDialog(this, errorMessage, "Save Game", JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, infoMessage, "Save Game", JOptionPane.INFORMATION_MESSAGE);
                }
                if (pause != null) {
                    pause.release();
                }
            });
        }, "game-saver");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * S18b (REMAINING_AREAS_DECISIONS P1): the window's ✕ and Game ▸ Exit. With a game running
     * they ask first — "Quit? Unsaved progress since the last autosave will be lost." with Save
     * &amp; quit / Quit / Cancel — because an hour of play could go with one click. With no
     * game, or a finished one, there is nothing to lose and the window simply closes.
     */
    private void requestExit(Runnable onClose) {
        if (!exitAsksFirst(lifecycle.mode(), loadInProgress || saveInProgress)) {
            shutdownAndExit(onClose);
            return;
        }
        DialogPause pause = new DialogPause(lifecycle);
        Object[] options = {SAVE_AND_QUIT, QUIT, CANCEL};
        int choice = JOptionPane.showOptionDialog(this, QUIT_TEXT, "Exit", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            saveGame(false, () -> shutdownAndExit(onClose), pause);
        } else if (choice == 1) {
            shutdownAndExit(onClose);
        } else {
            pause.release();
        }
    }

    /**
     * S18b (P1): whether closing the window asks first — with a game running or paused, and
     * nothing in flight. A save or a load in flight closes at once, as before: neither can be
     * waited out from a dialog. Pure, for {@code MainWindowSaveTextTest}.
     */
    static boolean exitAsksFirst(GameMode mode, boolean busy) {
        return !busy && (mode == GameMode.RUNNING || mode == GameMode.PAUSED);
    }

    /**
     * S18b (P11): whether New Game… asks before replacing the game — a running or paused one,
     * from the menu. The game-over report and a finished scenario's Close start one without
     * asking: the game is over already. Pure, for {@code MainWindowSaveTextTest}.
     */
    static boolean newGameAsksFirst(GameMode mode, boolean fromTheMenu) {
        return fromTheMenu && (mode == GameMode.RUNNING || mode == GameMode.PAUSED);
    }

    /**
     * S18b (UI tree U19): the load confirmation. "The current game will be replaced." only when
     * there is one — the empty shell used to say it too. Pure, for {@code MainWindowSaveTextTest}.
     */
    static String loadConfirmation(String fileName, GameMode mode) {
        boolean live = mode == GameMode.RUNNING || mode == GameMode.PAUSED;
        return "Load " + fileName + "?" + (live ? " The current game will be replaced." : "");
    }

    /**
     * Game → Load: confirm (the running world is replaced), then run the teardown/rebuild
     * on a worker thread — it takes seconds and must never block the EDT. The GUI refreshes
     * itself from the loader's events; menus re-enable when the worker finishes.
     */
    /** @return S18b (P2): whether a load was started (the world is then replaced) */
    private boolean confirmAndLoad(java.nio.file.Path source) {
        if (loadInProgress) {
            return false;
        }
        if (!java.nio.file.Files.exists(source)) {
            JOptionPane.showMessageDialog(this, "No save file at " + source + " yet.",
                    "Load Game", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                loadConfirmation(String.valueOf(source.getFileName()), lifecycle.mode()), // S18b (U19)
                "Load Game", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return false;
        }
        loadInProgress = true;
        reflectMode();
        Thread worker = new Thread(() -> {
            Exception failure = null;
            try {
                session.loadGame(source, true); // S18b (P8): the loaded game waits for the player
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
                } else {
                    eventBus.publish(new NotificationEvent(GAME_LOADED_TEXT, NotificationEvent.Severity.INFO,
                            lifecycle.simClock().nowSimMillis()));
                }
            });
        }, "game-loader");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * Game → New Game… (task 23), and (S15) the offer a window makes when it opens with no game
     * running: the modal chooser — the three scenarios and Free play — then the same worker-thread
     * + {@code loadInProgress} pattern as {@link #confirmAndLoad}. Starting a game is a
     * teardown/rebuild that takes seconds and must never block the EDT. The GUI refreshes itself
     * from the boot's events ({@code GameLoadedEvent} etc.).
     *
     * <p>Free play is {@code GameSession.startSandbox()}, NOT {@code startNewGame()}: the latter
     * can only start the first world of a process (it tears nothing down and resets neither the
     * clock nor the seed), while this menu item must work from the empty shell and as a
     * replacement for a running, paused or ended game alike.
     *
     * <p>S18b: the game pauses behind the dialog (P2) — and goes on as it was if the player
     * cancels; and over a running or paused game a start is confirmed first (P11), as a load is.
     *
     * @param confirmReplace S18b (P11): ask before replacing a running or paused game — the menu
     *                       item; not the game-over report's button, nor the end-of-day Close
     *                       after a scenario's end, where the game is over already
     * @return whether a game start was launched — {@code false} when the player closed the
     *         dialog without pressing Start, or a load already owns the world
     */
    private boolean chooseAndStartGame(boolean confirmReplace) {
        if (loadInProgress) {
            return false;
        }
        boolean asks = newGameAsksFirst(lifecycle.mode(), confirmReplace);
        DialogPause pause = new DialogPause(lifecycle);
        Optional<NewGameChoice> choice = NewGameDialog.choose(this, session.scenarioRegistry().all());
        if (choice.isEmpty() || (asks && JOptionPane.showConfirmDialog(this,
                REPLACE_GAME_TEXT, "New Game", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION)) {
            pause.release();
            return false;
        }
        pause.forget(); // the new world decides for itself whether it runs
        NewGameChoice chosen = choice.get();
        loadInProgress = true;
        reflectMode();
        Thread worker = new Thread(() -> {
            Exception failure = null;
            try {
                if (chosen.isFreePlay()) {
                    session.startSandbox();
                } else {
                    session.startScenario(chosen.scenarioKey(), true); // S18b (PT2): starts paused
                }
            } catch (Exception e) {
                failure = e;
            }
            Exception reported = failure;
            SwingUtilities.invokeLater(() -> {
                loadInProgress = false;
                reflectMode();
                if (reported != null) {
                    JOptionPane.showMessageDialog(this, reported.getMessage(),
                            "New Game", JOptionPane.ERROR_MESSAGE);
                } else {
                    // S15: a game is running now — the moment the first-launch tutorial waits for.
                    showTheTutorialsFirstCard();
                    maybeAutoOpenTutorial();
                }
            });
        }, "game-starter");
        worker.setDaemon(true);
        worker.start();
        return true;
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

    /**
     * The three-column area's rectangle in the glass pane's coordinate space, or an empty one
     * while the screen is not showing. The pause overlay lets input through there and leaves it
     * undimmed. S14 opened the chat this way — patience is frozen while the game is paused
     * (ADR-18), and the player may read, think and type; S15 (2026-10-12) widened it to all three
     * columns, because the waiting list and the comm log are read-only views and a pause is when
     * they get read.
     */
    private java.awt.Rectangle columnsBoundsInGlassPane() {
        if (!screen.isShowing()) {
            return new java.awt.Rectangle();
        }
        return screen.columnsBoundsIn(pauseOverlay);
    }

    /**
     * S18b (playtest 2, PT11): opens the newest comm-log archive — or, before anything has been
     * archived, the folder it goes to. A file type the desktop has no program for falls back to
     * the folder; when even that fails, the path is said.
     */
    private void openCommLogFile() {
        java.nio.file.Path dir = it.unige.portcommand.gui.LogArchiver.defaultDir();
        Optional<java.nio.file.Path> newest = it.unige.portcommand.gui.LogArchiver.newestArchive(dir);
        try {
            java.nio.file.Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(newest.orElse(dir).toFile());
            return;
        } catch (Exception noProgramForTheFile) {
            if (newest.isPresent()) {
                try {
                    java.awt.Desktop.getDesktop().open(dir.toFile());
                    return;
                } catch (Exception noFolderEither) {
                    // said below
                }
            }
        }
        JOptionPane.showMessageDialog(this, "The comm-log archive is in " + dir.toAbsolutePath() + ".",
                "Comm log", JOptionPane.INFORMATION_MESSAGE);
    }

    /** S18b (T8): where the PAUSED caption goes — the top bar left of its pause button, or empty. */
    private java.awt.Rectangle pauseCaptionAreaInGlassPane() {
        return screen.isShowing() ? screen.pauseCaptionAreaIn(pauseOverlay) : new java.awt.Rectangle();
    }

    /** S18b (PT4): the top bar's pause button, which clicks through the PAUSED overlay. */
    private java.awt.Rectangle pauseButtonInGlassPane() {
        return screen.isShowing() ? screen.pauseButtonBoundsIn(pauseOverlay) : new java.awt.Rectangle();
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

    /**
     * S17b (adversarial review): the Debug menu re-shows the latest settled day as a REPORT. If
     * that day ended a scenario, the live window had "Continue in free play" and "Close"; showing
     * it again an hour into free play would be the scenario's end a second time, with a Close
     * that opens New Game over a running game. Same figures and score, an ordinary day's window.
     */
    private static EndOfDaySummary asAnOrdinaryDay(EndOfDaySummary summary) {
        if (!summary.endsScenario()) {
            return summary;
        }
        return new EndOfDaySummary(summary.gameDay(), summary.income(), summary.totalExpense(),
                summary.endingWallet(), summary.endingReputation(), summary.performativeCounts(),
                summary.score(), null);
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
        saveItem.setEnabled(!loadInProgress && !saveInProgress && live);
        // Loading is legal from a live game, from GAME_OVER (continue from the autosave slot) and
        // — S15 — from MENU: the window can now be open with no game running (the New Game dialog
        // was cancelled, or a boot failed), and a player who cancelled in order to load must not
        // find Load greyed out. GameSession.loadGame handles the empty shell as it does any
        // started container (MENU → LOADING is a legal edge; a failure lands back in MENU). Only
        // LOADING itself refuses.
        boolean loadable = mode != GameMode.LOADING;
        loadItem.setEnabled(!loadInProgress && loadable);
        settingsItem.setEnabled(settingsAvailableIn(mode)); // S15: not in the empty shell
        reflectCallNextShip();
        // S18b (UI tree U13): the empty shell looks like one — "—" in the top bar, "No game
        // running" in the chat column, the hint pinned in the strip, and a Debug menu that has
        // no game to act on is off. S18b (PT4): the top bar's ⏸/▶ follows the mode.
        boolean noGame = mode == GameMode.MENU;
        debugMenu.setEnabled(!noGame);
        screen.showGameState(noGame, mode == GameMode.PAUSED, !loadInProgress && !saveInProgress && live);
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
                endOfDayDialog.show(latestSummary != null ? asAnOrdinaryDay(latestSummary) : syntheticSummary(),
                        leaderboard));
        debug.add(showEod);
        debug.addSeparator();
        // Checkpoint-#5 request: storms are rare by design (steady-state ≈4%) — these force the
        // WeatherAgent's snapshot so the full hold → clear → re-dispatch cascade can be shown on
        // demand. Everything downstream (alerts, holds, the top bar's weather — S16 — and the
        // Port table's "held (weather)" lines, notifications) runs the REAL pipeline; the next
        // broadcast tick picks the override up.
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
