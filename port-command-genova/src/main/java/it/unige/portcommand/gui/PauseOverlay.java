package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.geom.Area;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * The translucent PAUSED glass pane (task 24). {@code MainWindow} installs it as the frame's
 * glass pane and toggles visibility off {@code GamePausedEvent}/{@code GameResumedEvent}.
 *
 * <p>While visible it also swallows mouse input (the empty {@code MouseAdapter} makes this
 * component the event target, so nothing beneath is clickable) — everywhere except the two
 * pass-through regions below. What stays dimmed and blocked is the top bar, the notification
 * strip, and the overlay's own PAUSED box. Esc still works because the resume binding sits on the
 * root pane at WHEN_IN_FOCUSED_WINDOW scope.
 *
 * <h2>The three columns are NOT swallowed either (S14 the chat, S15 all three)</h2>
 * A paused game freezes the vessels' patience (ADR-18), and the whole point of that is to let the
 * player read, think and TYPE: a vessel's reply is message-driven and never waits on the game
 * clock. So S14 (2026-10-11) let the chat panel's rectangle pass mouse events through and left it
 * undimmed, exactly like the menu bar, and showing the overlay no longer pulls keyboard focus out
 * of the chat input. <b>S15 (2026-10-12)</b> widened that rectangle to the whole three-column
 * area — waiting list and Port table, chat, comm log: the two outer columns are read-only views,
 * a pause is exactly when the player wants to scroll the log or pick the next vessel from the
 * waiting list, and a dimmed, unclickable list beside a live chat would only look broken.
 *
 * <p>The overlay's own PAUSED box: {@link #captionBox()} — the caption, its hint line and the
 * Resume button, with a small margin — keeps both its dim and its clicks inside the pass-through
 * area, because white text on an undimmed transcript is not a pause indicator (adversarial
 * review, S14).
 *
 * <h2>S18b: the caption sits on the top bar, and the bar's ▶ can be clicked</h2>
 * Until S18b the box was centred on the window — in the three-column layout, over the chat
 * column the player may be typing in while paused (TREE_ANALYSIS_DECISIONS T8). Given a
 * {@code captionArea} (the top bar left of its pause button, {@code GameScreen.pauseCaptionAreaIn}),
 * it is one row there instead — "PAUSED  press ▶ or Esc to continue  [Resume]" — and the
 * columns are clear. Without one, or when the row would not fit, it is centred as before. And the
 * top bar's pause button (playtest 2, PT4) is a third pass-through region: it is how a scenario
 * that starts paused is started, so it must work through the scrim.
 *
 * <h2>The menu bar is deliberately NOT swallowed (audit C-04, 2026-07-27)</h2>
 * {@code JRootPane.RootLayout} sizes the glass pane to the ENTIRE root pane — which includes the
 * menu-bar strip — and {@code JRootPane.addImpl} forces the glass pane to child index 0, i.e. the
 * top of the Swing z-order. A visible, input-swallowing glass pane therefore makes the whole
 * {@code JMenuBar} unclickable: Game, Debug, Help, and — the contradiction that made this a bug
 * rather than a design choice — Save and Load, which {@code MainWindow.reflectMode()} goes out of
 * its way to keep ENABLED while paused ({@code live = RUNNING || PAUSED}). Pause is the first
 * thing anyone tries, and a dead menu bar reads as a broken build.
 *
 * <p>The fix is the same click-through trick {@link TutorialOverlay} already uses twenty lines
 * away in {@code MainWindow}: report {@link #contains(int, int)} {@code false} over the pass-through
 * region, so hit-testing falls through to the menu bar beneath, and skip that strip when painting
 * the scrim so the menu bar does not merely *look* disabled. The region is supplied as a
 * {@link Supplier} rather than read from the frame so this stays constructible — and assertable —
 * in a headless test, where no {@code JFrame} can be realized.
 */
final class PauseOverlay extends JPanel {

    /** The strip (in THIS component's coordinates) that mouse events and the scrim skip; empty
     * when there is nothing to pass through. Re-read on every hit-test and paint, so a menu bar
     * that changes height (look-and-feel, DPI) can never leave it stale. */
    private final transient Supplier<Rectangle> passThroughRegion;
    /** The three-column area's rectangle (in THIS component's coordinates), or empty — S14's chat
     * rectangle, widened in S15 to all three columns. Re-read on every hit-test and paint, like
     * the menu strip: a window resize changes it. */
    private final transient Supplier<Rectangle> columnsRegion;
    /** S18b (T8): where the caption row goes (the top bar, left of its pause button), or empty. */
    private final transient Supplier<Rectangle> captionArea;
    /** S18b (PT4): the top bar's pause button — clicks through, undimmed — or empty. */
    private final transient Supplier<Rectangle> playButtonRegion;

    /** S18b (T8): the hint's words; ▶ is the top bar's button (PT4). */
    static final String HINT_TEXT = "press ▶ or Esc to continue";
    /** Gap between the caption row's three parts, and between the stacked layout's rows. */
    private static final int GAP = 12;
    private static final int STACK_GAP = 12;
    /** The caption's size stacked in the middle (as before S18b), and in a row on the top bar. */
    private static final float STACKED_POINTS = 36f;
    private static final float ROW_POINTS = 18f;

    private final JLabel label = new JLabel("PAUSED", SwingConstants.CENTER);
    private final JLabel hint = new JLabel(HINT_TEXT, SwingConstants.CENTER);
    private final JButton resume = new JButton("Resume");

    PauseOverlay(Runnable onResume) {
        this(onResume, Rectangle::new);
    }

    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion) {
        this(onResume, passThroughRegion, Rectangle::new);
    }

    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion, Supplier<Rectangle> columnsRegion) {
        this(onResume, passThroughRegion, columnsRegion, Rectangle::new, Rectangle::new);
    }

    /**
     * @param passThroughRegion the menu-bar strip, in this component's coordinates
     * @param columnsRegion     the three-column area, in this component's coordinates
     *                          ({@code GameScreen.columnsBoundsIn(overlay)} in the real window)
     */
    /**
     * @param captionArea      S18b (T8): where the caption row goes, in this component's coordinates
     * @param playButtonRegion S18b (PT4): the top bar's pause button, in this component's coordinates
     */
    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion, Supplier<Rectangle> columnsRegion,
                 Supplier<Rectangle> captionArea, Supplier<Rectangle> playButtonRegion) {
        super(null);
        this.passThroughRegion = passThroughRegion;
        this.columnsRegion = columnsRegion;
        this.captionArea = captionArea;
        this.playButtonRegion = playButtonRegion;
        setOpaque(false);
        setFocusable(true);
        addMouseListener(new MouseAdapter() { });

        label.setFont(label.getFont().deriveFont(Font.BOLD, STACKED_POINTS));
        label.setForeground(Color.WHITE);
        hint.setForeground(Color.LIGHT_GRAY);
        resume.addActionListener(e -> onResume.run());
        add(label);
        add(hint);
        add(resume);
    }

    /**
     * S18b (T8): one row on the top bar when there is an area for it and the row fits there;
     * otherwise stacked in the middle of the overlay, as before S18b.
     */
    @Override
    public void doLayout() {
        Rectangle area = captionArea.get();
        setLabelPoints(ROW_POINTS);
        Dimension l = label.getPreferredSize();
        Dimension h = hint.getPreferredSize();
        Dimension b = resume.getPreferredSize();
        int rowWidth = l.width + GAP + h.width + GAP + b.width;
        int rowHeight = Math.max(l.height, Math.max(h.height, b.height));
        if (!area.isEmpty() && rowWidth <= area.width && rowHeight <= area.height + 8) {
            int x = area.x + (area.width - rowWidth) / 2;
            int middle = area.y + area.height / 2;
            label.setBounds(x, middle - l.height / 2, l.width, l.height);
            x += l.width + GAP;
            hint.setBounds(x, middle - h.height / 2, h.width, h.height);
            x += h.width + GAP;
            resume.setBounds(x, middle - b.height / 2, b.width, b.height);
            return;
        }
        setLabelPoints(STACKED_POINTS);
        l = label.getPreferredSize();
        int total = l.height + STACK_GAP + h.height + STACK_GAP + b.height;
        int y = (getHeight() - total) / 2;
        label.setBounds((getWidth() - l.width) / 2, y, l.width, l.height);
        y += l.height + STACK_GAP;
        hint.setBounds((getWidth() - h.width) / 2, y, h.width, h.height);
        y += h.height + STACK_GAP;
        resume.setBounds((getWidth() - b.width) / 2, y, b.width, b.height);
    }

    private void setLabelPoints(float points) {
        if (label.getFont().getSize2D() != points) {
            label.setFont(label.getFont().deriveFont(points));
        }
    }

    /** S18b (T8): whether the caption is the row on the top bar (as last laid out). */
    boolean captionIsOnTheTopBarForTest() {
        return label.getFont().getSize2D() == ROW_POINTS;
    }

    /**
     * Solid everywhere EXCEPT the two pass-through regions (the menu-bar strip and the three
     * columns). Swing's lightweight dispatcher consults {@code contains} to pick the mouse-event
     * target, so a false here lets the click reach the component under the glass pane. Same
     * mechanism as {@code TutorialOverlay.contains}.
     */
    @Override
    public boolean contains(int x, int y) {
        if (passThroughRegion.get().contains(x, y)) {
            return false;
        }
        // S18b (PT4): the top bar's ▶ is how a paused game is started; it must take the click.
        if (playButtonRegion.get().contains(x, y)) {
            return false;
        }
        // S14/S15: the columns pass through too — but never under the overlay's own PAUSED box,
        // whose Resume button must stay clickable wherever the layout happens to put it.
        if (columnsRegion.get().contains(x, y) && !captionBox().contains(x, y)) {
            return false;
        }
        return super.contains(x, y);
    }

    /** Margin around the PAUSED group that stays dimmed and click-blocking. */
    private static final int CAPTION_MARGIN = 12;

    /**
     * The overlay's own PAUSED group — caption, hint line, Resume button — as one rectangle with
     * a margin, wherever the layout put it; empty before the first layout. The same rectangle
     * decides what stays dimmed and what stays click-blocking inside the columns' pass-through,
     * so the two can never disagree.
     */
    Rectangle captionBox() {
        Rectangle box = null;
        for (Component child : getComponents()) {
            Rectangle bounds = child.getBounds();
            if (child.isVisible() && !bounds.isEmpty()) {
                box = box == null ? bounds : box.union(bounds);
            }
        }
        if (box == null) {
            return new Rectangle();
        }
        box.grow(CAPTION_MARGIN, CAPTION_MARGIN);
        // S18b (T8): on the top bar the margin stays on the bar — it must not dim or block the
        // top of the columns under it.
        Rectangle area = captionArea.get();
        if (!area.isEmpty() && area.contains(label.getBounds())) {
            return box.intersection(area);
        }
        return box;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Rectangle skip = passThroughRegion.get();
        // Dim below the pass-through strip only: a dimmed-but-clickable menu bar would still read
        // as disabled, which is the half of C-04 that a hit-test fix alone does not answer. The
        // columns' hole (S14 the chat, S15 all three) is left clear for the same reason — which
        // leaves the scrim on the top bar, the notification strip and behind the PAUSED group.
        int top = skip.isEmpty() ? 0 : Math.max(0, skip.y + skip.height);
        Area scrim = new Area(new Rectangle(0, top, getWidth(), Math.max(0, getHeight() - top)));
        Rectangle columns = columnsRegion.get();
        if (!columns.isEmpty()) {
            scrim.subtract(new Area(columns));
            // ...but not behind the PAUSED group itself: its white text needs the dim.
            scrim.add(new Area(captionBox().intersection(new Rectangle(0, top, getWidth(),
                    Math.max(0, getHeight() - top)))));
        }
        // S18b (PT4): and the top bar's pause button is left clear — it is clickable.
        Rectangle play = playButtonRegion.get();
        if (!play.isEmpty()) {
            scrim.subtract(new Area(play));
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(new Color(0, 0, 0, 140));
            g2.fill(scrim);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            revalidate(); // S18b (T8): the top bar may have moved since the caption was last placed
        }
        if (visible && !focusIsInColumns()) {
            // Pull focus out of whatever is now blocked — but NOT out of a component inside the
            // pass-through columns, the chat input above all: the player may type while paused
            // (S14), and since S15 nothing in the three columns is blocked at all.
            requestFocusInWindow();
        }
    }

    /** Whether the keyboard focus owner sits inside the columns' pass-through region. */
    private boolean focusIsInColumns() {
        Rectangle columns = columnsRegion.get();
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (columns.isEmpty() || owner == null || !owner.isShowing() || !isShowing()
                || SwingUtilities.getWindowAncestor(owner) != SwingUtilities.getWindowAncestor(this)) {
            return false;
        }
        Point origin = SwingUtilities.convertPoint(owner, 0, 0, this);
        return columns.contains(origin);
    }
}
