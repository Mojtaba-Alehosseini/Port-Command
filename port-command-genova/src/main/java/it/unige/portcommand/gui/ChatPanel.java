package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.AutopilotOwnershipEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.gui.model.NegotiationTabRegistry;
import it.unige.portcommand.gui.model.OpenDialogue;
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
 *
 * <p><b>S15 (2026-10-12): the middle column, and what the other two ask of it.</b> The chat is
 * now the centre of a three-column screen, and it is the only place that knows which negotiations
 * are open, what each tab's counter reads and which tab the player is looking at. The other
 * columns get exactly that through {@link DialogueDirectory}, which this class implements: the
 * "Waiting for you" list reads the open dialogues and each tab's SHOWN remaining time (so the list
 * and the tab cannot disagree, and the one-second counter timer here stays the only clock on
 * screen) and brings a tab forward on a click; the comm log follows the selected tab's vessel.
 * GUI to GUI, so plain listeners run on the EDT — not bus events, which are the agents' boundary.
 *
 * <p><b>S17a (ADR-21, decisions A2 and A10): who is negotiating in a tab has one source.</b> The
 * panel keeps the Assistant's latest {@link AutopilotOwnershipEvent} — the autopilot switch and
 * the dialogues the player took over — and every tab asks it "is the Assistant negotiating in
 * me?". A tab it owns is locked and says so; a tab the player owns offers Hint. Nothing else
 * feeds that answer: not the Settings toggle (which the Assistant hears and then restates, with
 * the per-tab part only it knows), and not a load — see {@link #ownership}.
 */
public final class ChatPanel extends JPanel implements DialogueDirectory {

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

    /** S15: the narrowest the chat column is ever laid out, in pixels. A tab's own minimum width
     * (its widest row is the three action buttons) must stay under it — {@code ChatPanelTest}
     * pins that — so the screen's explicit column minimum is the one that binds, not whatever a
     * {@code JTabbedPane} computes from its widest tab. */
    public static final int MIN_COLUMN_WIDTH = 380;

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
    private final Subscription<AutopilotOwnershipEvent> ownershipSubscription;
    private final Subscription<it.unige.portcommand.persistence.events.GameLoadedEvent> loadedSubscription;

    /**
     * S17a (ADR-21, decisions A2 and A10): who is negotiating in which tab, as the Assistant last
     * said it — the whole state, replaced whole by each {@link AutopilotOwnershipEvent}. Every
     * tab reads it through a {@code BooleanSupplier} ({@code assistantOwns(dialogueId)}), so a tab
     * that opens later is right from its first render.
     *
     * <p>Until the Assistant has spoken, the panel starts from what the Assistant itself boots
     * from: the session's autopilot setting, with nothing taken over.
     *
     * <p>Before S17a this was a {@code boolean} copy of the Settings toggle, kept in step by
     * hand — updated on {@code SettingsChangedEvent}, and set to {@code false} on every load on
     * the belief that a respawned Assistant boots with the autopilot off. It boots with the
     * SAVED value. After loading an autopilot-ON save the window showed Hint and a free input
     * while the Assistant was negotiating in the same tab (backlog R-07). The copy is gone: a
     * load does not touch this field, and the respawned Assistant states the new world's
     * ownership itself when it boots.
     */
    private AutopilotOwnershipEvent ownership = AutopilotOwnershipEvent.initial(Settings.load().autopilotEnabled());

    /** Set while expired tabs are being removed: removing a tab at or before the selected one
     * re-fires the pane's change event, and the focus hop it triggers must not steal focus from
     * wherever the player is (e.g. the comm-log filters). */
    private boolean removingExpiredTabs;

    /** S15: the {@link DialogueDirectory} listeners. EDT-confined like everything else here; the
     * interface has no way to remove one (its two users live and die with this panel). */
    private final List<Runnable> dialoguesListeners = new ArrayList<>();
    private final List<Consumer<String>> selectedVesselListeners = new ArrayList<>();
    /** S15: the vessel id the selected-vessel listeners were last told — {@code null} for "no
     * tab", which is also where a new panel starts. They hear a value only when it differs. */
    private String announcedVesselId;

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
        // S17a (ADR-21): the Assistant says who is negotiating in which tab; every open tab is
        // re-rendered when it does, so a lock, an unlock after "Take over" and the Hint button all
        // follow at once. (Task 21 had the panel listen to SettingsChangedEvent for this. That
        // subscription is gone — the Assistant hears the toggle and restates the ownership.)
        this.ownershipSubscription = eventBus.subscribe(AutopilotOwnershipEvent.class,
                this::onOwnershipChanged, DeliveryMode.EDT);
        // #9: keep keystrokes in the tab the player is looking at — focusing an open tab's field
        // makes it ready to type; focusing a CLOSED tab's (non-editable) field makes it swallow
        // input rather than letting focus fall through to the comm-log filter combo.
        // S15: the same event is where the selected vessel can change under the player's own
        // click, so it is announced from here too — and skipped under the same guard, because
        // mid-removal the pane's selection passes through states nobody should be told about;
        // removeExpiredTabs announces the settled result itself.
        tabbedPane.addChangeListener(e -> {
            if (!removingExpiredTabs) {
                focusSelectedTabInput();
                announceSelectedVessel();
            }
        });
    }

    private void showWaitingPlaceholder() {
        removeAll();
        add(waitingPlaceholder, BorderLayout.CENTER);
    }

    /** S17a (ADR-21): the Assistant restated who is negotiating where. The event is the whole
     * state, so it simply replaces the last one; every tab then re-renders against it. */
    private void onOwnershipChanged(AutopilotOwnershipEvent event) {
        ownership = event;
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
        // S17a (ADR-21, decision A10 / backlog R-07): the ownership is NOT reset here. This used
        // to set the panel's autopilot copy to "off" (the respawned AssistantAgent was believed
        // to boot with the autopilot off; it boots with the SAVED value), so after loading an
        // autopilot-ON save the next walk-in's tab offered Hint and a free input while the
        // Assistant negotiated in it. The respawned Assistant states the loaded world's ownership
        // itself; until that arrives the last statement stands, which is never worse than a guess.
        revalidate();
        repaint();
        // S15: the old world's tabs are gone — nobody is selected, nobody is waiting.
        announceSelectedVessel();
        fireDialoguesChanged();
    }

    private void onClockTick(SimClockTickEvent event) {
        removeExpiredTabs();
    }

    /** S14: every open tab re-reads its conversation clock. Package-private so a headless test
     * can stand in for the timer. S15: then the dialogues listeners run — this is the tick the
     * "Waiting for you" list moves on, AFTER the tabs have taken their new values, so the list
     * always repeats what the tabs show. */
    void refreshCounters() {
        views.values().forEach(DialogueTabView::refreshCounter);
        fireDialoguesChanged();
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
        // S15: only now, with the guard down and the strip settled, are the listeners told — the
        // tab that took a removed one's place is the selected vessel, and a listener is free to
        // select another dialogue from inside its callback (the guard would have swallowed the
        // change event that does the focusing and the announcing).
        announceSelectedVessel();
        fireDialoguesChanged();
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
            // S15: a later round re-reads the tab's counter (the vessel's reply may have moved
            // it), so what the list repeats may have changed.
            fireDialoguesChanged();
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
        // S15: the tab reports a counter it changed outside the one-second tick (its own
        // re-render after a send, a system line, an Assistant message), so the dialogues
        // listeners — the "Waiting for you" list — repeat the new number at once. The answer is
        // refreshCounters, not a bare announcement: the list SORTS by these numbers, and one
        // tab's fresh reading against the others' up-to-a-second-old ones could put the wrong
        // vessel first for the rest of that second. Every tab re-reads, then the list is told.
        // S17a (ADR-21): the tab asks, at every render and before every send, whether the Assistant
        // is negotiating in it — of the ownership as it stands THEN (the field, not a copy of it).
        DialogueTabView view = new DialogueTabView(dialogueId, registry, eventBus, processor, simClock,
                id -> ownership.assistantOwns(id), this::refreshCounters);
        views.put(dialogueId, view);
        tabbedPane.addTab(tabTitle(event), view);
        revalidate();
        repaint();
        // S15: the first tab of an empty strip selects itself (the pane's change event has
        // already announced it; this is a no-op then). A later tab leaves the selection alone.
        announceSelectedVessel();
        // The new tab read the clock as it was built; the others re-read now, for the same reason.
        refreshCounters();
    }

    private static String tabTitle(NegotiationOpenedEvent event) {
        var snapshot = event.snapshot();
        return snapshot.vesselName() + " (" + snapshot.vesselType() + ")";
    }

    private void onNegotiationClosed(NegotiationClosedEvent event) {
        Optional<String> dialogueId = registry.onNegotiationClosed(event, simClock.nowSimMillis());
        if (dialogueId.isEmpty()) {
            return;
        }
        DialogueTabView view = views.get(dialogueId.get());
        if (view != null) {
            view.refresh();
            int index = tabbedPane.indexOfComponent(view);
            if (index >= 0) {
                tabbedPane.setForegroundAt(index, Color.GRAY);
                tabbedPane.setTitleAt(index, tabbedPane.getTitleAt(index) + " (closed)");
            }
        }
        // S15: a closed negotiation is no longer waiting for the player, although its tab stays
        // on the strip for its dwell (and, if it is the selected one, still names its vessel).
        fireDialoguesChanged();
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

    // ==================== S15 (2026-10-12): DialogueDirectory — EDT only ====================

    /** The negotiations still open, in tab order. A closed tab is left out from the moment it
     * closes, although it stays on the strip for its dwell. */
    @Override
    public List<OpenDialogue> openDialogues() {
        return registry.openDialogues();
    }

    /**
     * Brings that dialogue's tab forward and puts the keyboard focus in its input. Works for any
     * tab still on the strip, a closed one included (its input then swallows keystrokes, #9).
     *
     * <p>The focus is requested here, not left to the pane's change listener: re-selecting the
     * tab that is already selected fires no change event at all, and the listener stands down
     * while expired tabs are being removed.
     *
     * @return {@code false} if no tab has that id — the caller's list is stale and should re-read
     */
    @Override
    public boolean selectDialogue(String dialogueId) {
        DialogueTabView view = dialogueId == null ? null : views.get(dialogueId);
        if (view == null || tabbedPane.indexOfComponent(view) < 0) {
            return false;
        }
        if (tabbedPane.getSelectedComponent() != view) {
            tabbedPane.setSelectedComponent(view);
        }
        announceSelectedVessel(); // already done by the change listener, unless it stood down
        SwingUtilities.invokeLater(view::focusInput);
        return true;
    }

    /** What that tab's counter shows right now, in milliseconds; empty when it is blank (a closed
     * tab, a dialogue the conversation clock does not know) or when there is no such tab. */
    @Override
    public OptionalLong shownRemainingMillis(String dialogueId) {
        DialogueTabView view = dialogueId == null ? null : views.get(dialogueId);
        return view == null ? OptionalLong.empty() : view.shownRemainingMillis();
    }

    /** Runs {@code listener}, on the EDT, each time the answer to {@link #openDialogues()} or
     * {@link #shownRemainingMillis} may have changed: a tab was added, a vessel countered, a
     * negotiation closed, expired tabs left the strip, the world was replaced — and on every
     * one-second counter refresh. */
    @Override
    public void addDialoguesListener(Runnable listener) {
        dialoguesListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Tells {@code listener}, on the EDT, the vessel id of the selected tab each time it changes
     * — {@code null} once no tab is left. Not called at registration: a listener that needs the
     * current value reads {@link #selectedVesselId()}. */
    @Override
    public void addSelectedVesselListener(Consumer<String> listener) {
        selectedVesselListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** S15: the dialogue id of the selected tab, or {@code null} when the strip is empty. EDT only. */
    public String selectedDialogueId() {
        DialogueTabView view = selectedView();
        return view == null ? null : view.dialogueId();
    }

    /** S15: the vessel id of the selected tab, or {@code null} when the strip is empty. A closed
     * tab that is still selected still reports its vessel. EDT only. */
    public String selectedVesselId() {
        DialogueTabView view = selectedView();
        return view == null ? null : view.vesselId();
    }

    private DialogueTabView selectedView() {
        // views is the truth about which tabs exist: a load clears it BEFORE it empties the pane,
        // and in between the pane still holds the old world's pages.
        if (views.isEmpty()) {
            return null;
        }
        return tabbedPane.getSelectedComponent() instanceof DialogueTabView view ? view : null;
    }

    /** Tells the selected-vessel listeners whose tab is selected, if that differs from what they
     * were last told. Selecting another tab of the SAME vessel id, a tab sliding along the strip
     * when an earlier one is removed, or a re-selection all announce nothing. */
    private void announceSelectedVessel() {
        String vesselId = selectedVesselId();
        if (Objects.equals(vesselId, announcedVesselId)) {
            return;
        }
        announcedVesselId = vesselId;
        for (Consumer<String> listener : List.copyOf(selectedVesselListeners)) {
            if (!Objects.equals(announcedVesselId, vesselId)) {
                // A listener changed the selection from inside its callback; that nested
                // announcement already told every listener the newer vessel.
                break;
            }
            listener.accept(vesselId);
        }
    }

    private void fireDialoguesChanged() {
        for (Runnable listener : List.copyOf(dialoguesListeners)) {
            listener.run();
        }
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
        ownershipSubscription.cancel();
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
