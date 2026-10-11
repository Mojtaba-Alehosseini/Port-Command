package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.gui.model.NegotiationTabRegistry;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import it.unige.portcommand.util.Subscription;

/**
 * Per-dialogue negotiation chat (task 19): a {@link JTabbedPane}, one
 * {@link DialogueTabView} per active walk-in dialogue, backed by one shared
 * {@link NegotiationTabRegistry}. Every tab's send flow is self-contained (see
 * {@link DialogueTabView}'s javadoc); this class's only job is (a) create a tab on
 * {@link NegotiationOpenedEvent} the registry hasn't seen before, (b) forward every
 * other server-originated event to the registry and refresh whichever tab it names.
 *
 * <p>{@link DealClosedEvent}/{@link WithdrawalEvent} are subscribed only to keep the
 * lifecycle bracket documented at the call site — the same information always
 * arrives paired with a {@link NegotiationClosedEvent} (see that event's own
 * javadoc), which is the one this panel actually acts on; the other two are the
 * HUD's (task 20) more specific signal.
 */
public final class ChatPanel extends JPanel {

    /**
     * Audit C-07: how long a closed negotiation's tab stays readable before it leaves the strip —
     * real seconds of RUNNING game time, measured on the shared SimClock like the tab's own
     * countdown, so a paused game (Esc, the end-of-day summary) keeps closed tabs visible. Closed
     * tabs used to accumulate until a load, scrolling the live vessel's tab out of view (F-9).
     * Nothing durable is lost: the chat transcript was never persisted (a load already discards
     * it), and the ACL exchange behind it stays in the comm log and its archive. Like the
     * countdown, the dwell is converted at the day length in force at each tick, so changing the
     * day length while a closed tab waits rescales its wait (it can leave early or late).
     */
    static final double CLOSED_TAB_DWELL_REAL_SECONDS = 30.0;

    /** S14 (ADR-18): how often every tab's live counter is re-read. Conversation time is REAL
     * time, so it cannot ride the sim-minute tick (2.5 s apart at the default day length). */
    static final int COUNTER_REFRESH_MILLIS = 1_000;

    private final NegotiationTabRegistry registry = new NegotiationTabRegistry();
    private final EventBus eventBus;
    private final ChatInputProcessor processor;
    private final SimClock simClock;
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final Map<String, DialogueTabView> views = new LinkedHashMap<>();
    /** Focusable on purpose (C-07 review): when the last closed tab leaves while it holds focus,
     * focus is parked HERE, where keystrokes do nothing — not left to AWT, whose transfer lands
     * on the comm-log filter combo and lets type-ahead change the filter (the #9 symptom). */
    private final JLabel waitingPlaceholder = new JLabel("Waiting for a walk-in…", SwingConstants.CENTER);
    /** S14: a Swing timer, so it fires on the EDT. It only READS the conversation clock (which is
     * driven by the game's own advancer, frozen by a pause) — it is a repaint cadence, not a
     * time source. Runs only while the panel is displayable. */
    private final Timer counterTimer = new Timer(COUNTER_REFRESH_MILLIS, e -> refreshCounters());

    private final Subscription<NegotiationOpenedEvent> openedSubscription;
    private final Subscription<NegotiationClosedEvent> closedSubscription;
    private final Subscription<AssistantChatEvent> assistantSubscription;
    private final Subscription<PlayerCommandRefusedEvent> refusedSubscription;
    private final Subscription<SimClockTickEvent> clockSubscription;
    private final Subscription<SettingsChangedEvent> settingsSubscription;
    private final Subscription<it.unige.portcommand.persistence.events.GameLoadedEvent> loadedSubscription;

    /** Live Channel-B autopilot state (task 21): seeded from the {@code defaults.json} value and
     * updated on {@link SettingsChangedEvent}; every tab's Hint button follows it. The tabs read
     * it through a {@code BooleanSupplier} so a toggle re-hides/re-shows Hint on the next refresh
     * without threading the flag through every {@link DialogueTabView} field. */
    private boolean autopilotEnabled = Settings.load().autopilotEnabled();

    /** Set while expired tabs are being removed: removing a tab at or before the selected one
     * re-fires the pane's change event, and the focus hop it triggers must not steal focus from
     * wherever the player is (e.g. the comm-log filters). */
    private boolean removingExpiredTabs;

    /**
     * Headless-test convenience: a private frozen clock (nothing advances it, and no vessel has a
     * conversation on it, so every counter stays blank). PRODUCTION MUST use the 3-arg
     * constructor with the boot's shared {@code SimClock} — {@code MainWindow} does.
     */
    ChatPanel(EventBus eventBus, ChatInputProcessor processor) {
        this(eventBus, processor, new SimClock(Settings.load().realSecondsPerGameDay()));
    }

    public ChatPanel(EventBus eventBus, ChatInputProcessor processor, SimClock simClock) {
        super(new BorderLayout());
        this.eventBus = eventBus;
        this.processor = processor;
        this.simClock = simClock;
        waitingPlaceholder.setFocusable(true);
        showWaitingPlaceholder();

        this.openedSubscription = eventBus.subscribe(NegotiationOpenedEvent.class, this::onNegotiationOpened, DeliveryMode.EDT);
        this.closedSubscription = eventBus.subscribe(NegotiationClosedEvent.class, this::onNegotiationClosed, DeliveryMode.EDT);
        this.assistantSubscription = eventBus.subscribe(AssistantChatEvent.class, this::onAssistantChat, DeliveryMode.EDT);
        // S13 (live-playtest F-7): a PROPOSE the Prolog gate refused never reached the vessel, so
        // its terms come back off the offer strip (the refusal TEXT arrives as an AssistantChatEvent).
        this.refusedSubscription = eventBus.subscribe(PlayerCommandRefusedEvent.class,
                this::onPlayerCommandRefused, DeliveryMode.EDT);
        // The per-sim-minute tick sweeps closed tabs off the strip (C-07). S14: it no longer
        // re-renders the offer strips — the "~Ns remaining" it used to count down is gone, and
        // the live counter that replaced it runs on its own one-second Swing timer.
        this.clockSubscription = eventBus.subscribe(SimClockTickEvent.class, this::onClockTick, DeliveryMode.EDT);
        // Task 22: a loaded game never carries an open negotiation (saves drop/refuse them),
        // so the pre-load world's tabs are stale — clean slate on the loaded-marker event. Stored +
        // cancelled like every other subscription (adversarial finding 5 — it was the odd one out).
        this.loadedSubscription = eventBus.subscribe(it.unige.portcommand.persistence.events.GameLoadedEvent.class,
                this::onGameLoaded, DeliveryMode.EDT);
        // Task 21: the Settings screen toggles autopilot live — update the flag and re-render open
        // tabs so the Hint button appears/disappears at once (#9's live-visibility half).
        this.settingsSubscription = eventBus.subscribe(SettingsChangedEvent.class, this::onSettingsChanged, DeliveryMode.EDT);
        // #9: keep keystrokes in the tab the player is looking at — focusing an open tab's field
        // makes it ready to type; focusing a CLOSED tab's (non-editable) field makes it swallow
        // input rather than letting focus fall through to the comm-log filter combo.
        tabbedPane.addChangeListener(e -> {
            if (!removingExpiredTabs) {
                focusSelectedTabInput();
            }
        });
    }

    private void showWaitingPlaceholder() {
        removeAll();
        add(waitingPlaceholder, BorderLayout.CENTER);
    }

    private void onSettingsChanged(SettingsChangedEvent event) {
        autopilotEnabled = event.autopilotEnabled();
        views.values().forEach(DialogueTabView::refresh);
    }

    /** Focuses the input of the currently selected tab (deferred so it runs after the tab-switch
     * layout settles). No-op headless / when nothing is selected. */
    private void focusSelectedTabInput() {
        java.awt.Component selected = tabbedPane.getSelectedComponent();
        if (selected instanceof DialogueTabView view) {
            javax.swing.SwingUtilities.invokeLater(view::focusInput);
        }
    }

    private void onGameLoaded(it.unige.portcommand.persistence.events.GameLoadedEvent event) {
        registry.reset();
        views.clear();
        tabbedPane.removeAll();
        showWaitingPlaceholder();
        // The respawned AssistantAgent boots autopilot OFF; keep this mirror in step so a walk-in
        // after the load shows its Hint button (adversarial finding 4 — else the GUI stayed "ON"
        // and hid Hint while the agent did nothing).
        autopilotEnabled = false;
        revalidate();
        repaint();
    }

    private void onClockTick(SimClockTickEvent event) {
        removeExpiredTabs();
    }

    /** S14: every open tab re-reads its conversation clock. Package-private so a headless test
     * can stand in for the timer. */
    void refreshCounters() {
        views.values().forEach(DialogueTabView::refreshCounter);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        counterTimer.start();
    }

    /** C-07: rides the existing per-sim-minute tick (already EDT-delivered and cancelled in
     * {@link #removeNotify()}), so there is no extra timer and no wall-clock read; ticks stop
     * while paused, which is what keeps a closed tab on screen during a pause. */
    private void removeExpiredTabs() {
        List<String> expired = registry.sweepClosed(simClock.nowSimMillis(),
                simClock.simMillisForRealSeconds(CLOSED_TAB_DWELL_REAL_SECONDS));
        if (expired.isEmpty()) {
            return;
        }
        boolean focusWasInside = false;
        removingExpiredTabs = true;
        try {
            for (String id : expired) {
                DialogueTabView view = views.remove(id);
                if (view != null) {
                    focusWasInside |= SwingUtilities.findFocusOwner(view) != null;
                    tabbedPane.remove(view);
                }
            }
        } finally {
            removingExpiredTabs = false;
        }
        if (views.isEmpty()) {
            showWaitingPlaceholder();
            if (focusWasInside) {
                SwingUtilities.invokeLater(waitingPlaceholder::requestFocusInWindow);
            }
        } else if (focusWasInside) {
            focusSelectedTabInput(); // focus follows into the tab that took the removed one's place
        }
        revalidate();
        repaint();
    }

    private void onNegotiationOpened(NegotiationOpenedEvent event) {
        NegotiationTabRegistry.OpenResult result = registry.onNegotiationOpened(event);
        if (result.isNewTab()) {
            addTab(result.dialogueId(), event);
        } else {
            DialogueTabView view = views.get(result.dialogueId());
            if (view != null) {
                view.refresh();
            }
        }
    }

    private void addTab(String dialogueId, NegotiationOpenedEvent event) {
        if (views.isEmpty()) {
            removeAll();
            // Scroll the tab strip instead of wrapping it: 4+ dialogue tabs were stacking
            // into three header rows (task 19 play-test), eating the transcript's height.
            tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            add(tabbedPane, BorderLayout.CENTER);
        }
        DialogueTabView view = new DialogueTabView(dialogueId, registry, eventBus, processor, simClock,
                () -> autopilotEnabled);
        views.put(dialogueId, view);
        tabbedPane.addTab(tabTitle(event), view);
        revalidate();
        repaint();
    }

    private static String tabTitle(NegotiationOpenedEvent event) {
        var snapshot = event.snapshot();
        return snapshot.vesselName() + " (" + snapshot.vesselType() + ")";
    }

    private void onNegotiationClosed(NegotiationClosedEvent event) {
        Optional<String> dialogueId = registry.onNegotiationClosed(event, simClock.nowSimMillis());
        dialogueId.ifPresent(id -> {
            DialogueTabView view = views.get(id);
            if (view == null) {
                return;
            }
            view.refresh();
            int index = tabbedPane.indexOfComponent(view);
            if (index >= 0) {
                tabbedPane.setForegroundAt(index, Color.GRAY);
                tabbedPane.setTitleAt(index, tabbedPane.getTitleAt(index) + " (closed)");
            }
        });
    }

    private void onAssistantChat(AssistantChatEvent event) {
        Optional<String> dialogueId = registry.onAssistantChat(event);
        dialogueId.ifPresent(id -> {
            DialogueTabView view = views.get(id);
            if (view != null) {
                view.refresh();
            }
        });
    }

    private void onPlayerCommandRefused(PlayerCommandRefusedEvent event) {
        registry.onPlayerCommandRefused(event).ifPresent(id -> {
            DialogueTabView view = views.get(id);
            if (view != null) {
                view.refreshStrip();
            }
        });
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        counterTimer.stop();
        openedSubscription.cancel();
        closedSubscription.cancel();
        assistantSubscription.cancel();
        refusedSubscription.cancel();
        clockSubscription.cancel();
        settingsSubscription.cancel();
        loadedSubscription.cancel();
    }

    /** Test-only: whether the one-second counter timer is running (S14). */
    boolean counterTimerRunningForTest() {
        return counterTimer.isRunning();
    }

    /** Test-only: the shared model backing every tab. */
    NegotiationTabRegistry registry() {
        return registry;
    }

    /** Test-only: the live view for a dialogue id, or {@code null} if no tab opened for it. */
    DialogueTabView viewForTest(String dialogueId) {
        return views.get(dialogueId);
    }

    /** Test-only: how many tabs are currently open. */
    int tabCountForTest() {
        return views.size();
    }

    /** Test-only: how many pages the JTabbedPane itself holds (C-07: must shrink with {@link #views}). */
    int stripTabCountForTest() {
        return tabbedPane.getTabCount();
    }

    /** Test-only: the JTabbedPane title for a dialogue id, or {@code null} if it has no tab. */
    String tabTitleForTest(String dialogueId) {
        DialogueTabView view = views.get(dialogueId);
        if (view == null) {
            return null;
        }
        int index = tabbedPane.indexOfComponent(view);
        return index < 0 ? null : tabbedPane.getTitleAt(index);
    }
}
