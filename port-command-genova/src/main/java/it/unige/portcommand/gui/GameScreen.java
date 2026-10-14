package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.function.DoubleConsumer;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.gui.model.ColumnDividers;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;

/**
 * Everything inside the window's frame (S15, 2026-10-12): the top bar, the three columns and the
 * notification strip, as ONE panel that {@code MainWindow} hosts.
 *
 * <pre>
 *   top bar (HUDPanel: day, time, wallet, reputation, target, speed toggles, weather)
 *   ┌───────────────────┬──────────────────────────┬──────────────────────────┐
 *   │ Waiting for you   │                          │                          │
 *   ├───────────────────┤   chat (one tab per      │   comm log               │
 *   │ Port table        │   walk-in)               │                          │
 *   └───────────────────┴──────────────────────────┴──────────────────────────┘
 *   notification strip
 * </pre>
 *
 * <p><b>Why it is not part of {@code MainWindow}.</b> A {@code JFrame} cannot be constructed under
 * {@code -Djava.awt.headless=true}, which both Gradle test lanes set — so as long as the layout
 * lived in the frame's constructor, nothing about it could be tested or rendered. This is a plain
 * {@code JPanel}: {@code GameScreenCompositionTest} lays it out and measures the columns, and
 * {@code GameScreenRenderTest} paints it to PNG files. For the same reason it holds no
 * {@code Preferences} — the window owns those and hands the stored proportions in and the dragged
 * ones out, so a test never touches the registry.
 *
 * <p><b>S16 (2026-10-13): the weather is on the top bar, not in the left column.</b> It used to be
 * the Port table's last line. The bar is the one part of the screen that is in view whichever
 * column the player is reading, and since S16 the weather decides whether a ship that has a deal
 * may move at all. The bar gives the weather only the room its other cells leave
 * ({@code HUDPanel.BarLayout}), so the layout below — and the 1000 px minimum window width — is
 * unchanged; {@code GameScreenCompositionTest} pins that the speed toggles stay on the bar at
 * that width with the longest weather line there is.
 *
 * <p><b>Three columns, two dividers.</b> The outer split is {@code left | (chat | comm log)}, the
 * inner one {@code chat | comm log}. The left column is narrow by design (20 % by default; the
 * chat and the comm log share the rest evenly): it is two small read-only views, and the room it
 * gives up is what makes the chat and the log readable at 1280 px. Each column has a minimum
 * width, which the split panes honour on a drag and on a window resize.
 *
 * <p><b>A stored proportion is clamped here, not by Swing.</b> Setting a split pane's divider
 * location from code consults nobody's minimum size ({@code BasicSplitPaneUI} only keeps it
 * within the pane), so a proportion saved on a wide monitor, or a hand-edited preference, could
 * open the window with a column a few pixels wide. {@link #applyDividerProportions} goes through
 * {@link ColumnDividers}, which cannot return such a location.
 *
 * <p><b>Wiring between the columns</b> is plain listeners, never bus events (the bus is the
 * GUI&#8596;agents boundary): the waiting list reads the chat column through
 * {@link DialogueDirectory}, and the comm log is told which vessel's tab is selected so its
 * "This vessel" view can follow it.
 *
 * <p>EDT only, like every panel it holds. Each of those cancels its own subscriptions and timers
 * in {@code removeNotify()}, which Swing cascades from this panel.
 */
final class GameScreen extends JPanel {

    /** Component names the {@link TutorialOverlay} finds its highlight targets by (never by
     * pixels — #8/§21.3), and tests find the panels by. */
    static final String NAME_HUD = "tutorial-hud";
    static final String NAME_PORT = "tutorial-port";
    static final String NAME_CHAT = "tutorial-chat";
    static final String NAME_COMMLOG = "tutorial-commlog";

    /** The narrowest (px) the left column — waiting list over Port table — may be made. Both of
     * its panels are built to stay readable at this width (names are cut with an ellipsis). */
    static final int LEFT_MIN_WIDTH = 180;
    /** The narrowest (px) the chat column and the comm-log column may each be made: below this
     * the chat's offer line and button rows, and the log's filter row, no longer fit. */
    static final int COLUMN_MIN_WIDTH = 380;

    /** The left column's share of the whole three-column width when nothing is stored. */
    static final double DEFAULT_LEFT_PROPORTION = 0.20;
    /** The chat's share of what the left column leaves (chat + comm log) when nothing is stored. */
    static final double DEFAULT_CHAT_PROPORTION = 0.50;

    private static final Color RULE = new Color(0xC8, 0xC8, 0xC8);

    private final ChatPanel chatPanel;
    private final CommLogPanel commLogPanel;
    private final JSplitPane outerSplit;
    private final JSplitPane innerSplit;
    /** True while {@link #applyDividerProportions} is moving the dividers itself, so that is never
     * mistaken for the player dragging one. */
    private boolean applyingProportions;

    /**
     * @param eventBus           the shared bus every panel subscribes through
     * @param portStateArtifact  the shared tug state the Port table seeds from and follows
     * @param chatInputProcessor the NLP pipeline seam the chat column calls on Send
     * @param simClock           the shared clock — the chat's counters and closed-tab dwell read it
     * @param speedControl       the one applier behind the top bar's speed toggles — and (S16) the
     *                           holder of the scenario-script lock they show — or {@code null}
     *                           for a top bar without them
     */
    GameScreen(EventBus eventBus, PortStateArtifact portStateArtifact, ChatInputProcessor chatInputProcessor,
               SimClock simClock, GameSpeedControl speedControl) {
        super(new BorderLayout());

        HUDPanel hud = new HUDPanel(eventBus, speedControl);
        hud.setName(NAME_HUD);

        chatPanel = new ChatPanel(eventBus, chatInputProcessor, simClock);
        chatPanel.setName(NAME_CHAT);
        chatPanel.setMinimumSize(new Dimension(COLUMN_MIN_WIDTH, 0));

        commLogPanel = new CommLogPanel(eventBus);
        commLogPanel.setName(NAME_COMMLOG);
        commLogPanel.setMinimumSize(new Dimension(COLUMN_MIN_WIDTH, 0));
        // The log's "This vessel" view follows the tab the player is looking at.
        chatPanel.addSelectedVesselListener(commLogPanel::setFocusVessel);

        // The split panes carry no border of their own, so each column's width is exactly what the
        // divider arithmetic says it is. Resize weights equal the default proportions: a window
        // resize then keeps the default layout's shape instead of feeding one column only.
        innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatPanel, commLogPanel);
        innerSplit.setResizeWeight(DEFAULT_CHAT_PROPORTION);
        innerSplit.setContinuousLayout(true);
        innerSplit.setBorder(null);
        outerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildLeftColumn(eventBus, portStateArtifact), innerSplit);
        outerSplit.setResizeWeight(DEFAULT_LEFT_PROPORTION);
        outerSplit.setContinuousLayout(true);
        outerSplit.setBorder(null);

        add(hud, BorderLayout.NORTH);
        add(outerSplit, BorderLayout.CENTER);
        add(new NotificationStrip(eventBus), BorderLayout.SOUTH);
    }

    /**
     * The left column: "Waiting for you" at its natural height on top — it is as tall as the
     * vessels waiting, and one line when nobody is — and the Port table in everything below.
     * S18b (PT5): "Finished" at the bottom, at its natural height too (never more than five lines;
     * more scroll), so the Port table above it keeps its rows where they were.
     * {@code PortPanel} sets no name of its own; the shell names it here, in the one place the
     * tutorial's targets are defined.
     */
    private JPanel buildLeftColumn(EventBus eventBus, PortStateArtifact portStateArtifact) {
        WaitingListPanel waitingList = new WaitingListPanel(chatPanel);
        // A hairline under the list, on a holder so the list's own border stays as it was built.
        JPanel waitingHolder = new JPanel(new BorderLayout());
        waitingHolder.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, RULE));
        waitingHolder.add(waitingList, BorderLayout.CENTER);

        PortPanel port = new PortPanel(eventBus, portStateArtifact);
        port.setName(NAME_PORT);

        FinishedListPanel finished = new FinishedListPanel(chatPanel);
        JPanel finishedHolder = new JPanel(new BorderLayout());
        finishedHolder.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, RULE));
        finishedHolder.add(finished, BorderLayout.CENTER);

        JPanel column = new JPanel(new BorderLayout());
        column.add(waitingHolder, BorderLayout.NORTH);
        column.add(port, BorderLayout.CENTER);
        column.add(finishedHolder, BorderLayout.SOUTH);
        column.setMinimumSize(new Dimension(LEFT_MIN_WIDTH, 0));
        // Like the panels it holds: a click in this column must never take the keyboard focus
        // out of the chat input.
        for (JPanel part : new JPanel[] {column, waitingHolder, finishedHolder}) {
            part.setFocusable(false);
            part.setRequestFocusEnabled(false);
        }
        return column;
    }

    /**
     * Puts both dividers where two stored proportions say, clamped so that no column ends up
     * below its minimum width: {@code left} is the left column's share of the whole three-column
     * width, {@code chat} the chat's share of what the left column leaves. Call it once this
     * panel has its real size (the window is up), and again after resizing it if the proportions
     * should be re-applied rather than left to the split panes' own resize behaviour. Does nothing
     * while the panel has no width.
     *
     * <p>The layout is run by hand, top down, between the two steps: the inner split's width is
     * only known once the outer divider is in place, and {@code validate()} cannot be relied on
     * for that — on a component tree with no native peer (this panel in a headless test) it
     * returns without laying anything out. The split panes' own layout then keeps the locations
     * set here: before their first paint they re-apply the stored location on every layout, and
     * after it a resize distributes the change by resize weight and respects the minimums.
     *
     * <p>Each split pane is laid out once at its current size BEFORE its divider is placed
     * ({@link #placeDivider}). That is not redundancy: a split pane that has been painted and
     * whose size differs from the one it was last laid out at treats its next layout as a RESIZE
     * — it shares the difference out by resize weight and overwrites whatever location was just
     * set ({@code BasicSplitPaneUI}'s layout only honours a set location while the size is
     * unchanged or before the first paint). The inner split is always in that position here,
     * because placing the outer divider has just changed its width; whether the window has been
     * painted by the time it reports itself opened is not something to depend on.
     */
    void applyDividerProportions(double left, double chat) {
        applyingProportions = true;
        try {
            doLayout(); // BorderLayout: gives the outer split its bounds
            placeDivider(outerSplit, left, LEFT_MIN_WIDTH, innerSplit.getMinimumSize().width);
            // The outer layout inside placeDivider has given the inner split its final width.
            placeDivider(innerSplit, chat, COLUMN_MIN_WIDTH, COLUMN_MIN_WIDTH);
        } finally {
            applyingProportions = false;
        }
    }

    /** Settles {@code split} at its current size, sets its divider, and lays it out again so both
     * sides have their new bounds. Does nothing while the split pane has no width. */
    private static void placeDivider(JSplitPane split, double proportion, int minLeft, int minRight) {
        int span = span(split);
        if (span <= 0) {
            return; // not sized yet: a location of 0 would be remembered and collapse the left side
        }
        split.doLayout(); // see applyDividerProportions: makes the next layout "same size", not "a resize"
        split.setDividerLocation(split.getInsets().left
                + ColumnDividers.location(span, proportion, minLeft, minRight));
        split.doLayout();
    }

    /** The width the two sides of {@code split} share: its inner width minus the divider. */
    private static int span(JSplitPane split) {
        Insets insets = split.getInsets();
        return split.getWidth() - insets.left - insets.right - split.getDividerSize();
    }

    /**
     * Reports each divider the PLAYER moves, as the proportion to store for it (already clamped
     * by {@link ColumnDividers#proportion}). Call once, after {@link #applyDividerProportions}.
     *
     * <p>Only a genuine drag is reported — recognised by the split pane's width being unchanged
     * since its previous divider event. On a window resize a split pane is laid out at a new
     * width and repositions its divider by resize weight; storing those positions would let the
     * remembered proportion drift toward the weights across resize-and-restart cycles
     * (adversarial finding 3 of the #8 fix, kept from {@code MainWindow}). Such an event arrives
     * with a changed width and is skipped, as is anything {@link #applyDividerProportions} does.
     * The same rule covers the inner divider when the outer one is dragged: the inner split's
     * width changes, so its own repositioning is not taken for a drag.
     *
     * <p>The rule errs on the safe side. A width change too small to move the divider produces no
     * event to compare against, so the first event of the NEXT drag still sees a "changed" width
     * and goes unreported; a drag is a stream of events, one per mouse move, so that costs its
     * first pixel and nothing else.
     */
    void trackDividerDrags(DoubleConsumer onLeftDragged, DoubleConsumer onChatDragged) {
        trackDrags(outerSplit, onLeftDragged);
        trackDrags(innerSplit, onChatDragged);
    }

    private void trackDrags(JSplitPane split, DoubleConsumer onDragged) {
        int[] lastWidth = { split.getWidth() };
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            int width = split.getWidth();
            int span = span(split);
            if (!applyingProportions && width == lastWidth[0] && span > 0) {
                onDragged.accept(ColumnDividers.proportion(
                        split.getDividerLocation() - split.getInsets().left, span));
            }
            lastWidth[0] = width;
        });
    }

    /**
     * The whole three-column area — left column, chat, comm log and the two dividers between
     * them — as a rectangle in {@code target}'s coordinate space. The pause overlay leaves exactly
     * this area usable and undimmed while the game is paused.
     */
    Rectangle columnsBoundsIn(Component target) {
        return boundsIn(outerSplit, target);
    }

    /** The chat column alone, as a rectangle in {@code target}'s coordinate space. */
    Rectangle chatBoundsIn(Component target) {
        return boundsIn(chatPanel, target);
    }

    /** Works on a tree with no window too (the conversion only walks parents and adds offsets),
     * provided {@code target} is this panel or shares an ancestor with it. Whether the screen is
     * actually SHOWING is the caller's question, not this method's. */
    private static Rectangle boundsIn(Component source, Component target) {
        if (source.getParent() == null || target == null) {
            return new Rectangle();
        }
        return SwingUtilities.convertRectangle(source.getParent(), source.getBounds(), target);
    }

    /**
     * The narrowest the three columns can be made together: the three minimum widths plus the two
     * dividers. The window's own minimum width must not be below this plus the frame's borders,
     * or the columns' minimums could not all hold ({@code GameScreenCompositionTest} pins the
     * relation to {@code MainWindow.MIN_WIDTH}).
     */
    int minimumColumnsWidth() {
        Insets insets = outerSplit.getInsets();
        return insets.left + LEFT_MIN_WIDTH + outerSplit.getDividerSize()
                + innerSplit.getMinimumSize().width + insets.right;
    }

    /** Test-only: the {@code left | (chat | comm log)} split. */
    JSplitPane outerSplitForTest() {
        return outerSplit;
    }

    /** Test-only: the {@code chat | comm log} split. */
    JSplitPane innerSplitForTest() {
        return innerSplit;
    }
}
