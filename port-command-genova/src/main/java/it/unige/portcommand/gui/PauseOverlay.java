package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
 * <p>The overlay's own PAUSED box stays where it was, centred on the window — which in the
 * three-column layout puts it over the chat column: {@link #captionBox()} — the caption, its hint
 * line and the Resume button, with a small margin — keeps both its dim and its clicks inside the
 * pass-through area, because white text on an undimmed transcript is not a pause indicator
 * (adversarial review, S14).
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

    PauseOverlay(Runnable onResume) {
        this(onResume, Rectangle::new);
    }

    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion) {
        this(onResume, passThroughRegion, Rectangle::new);
    }

    /**
     * @param passThroughRegion the menu-bar strip, in this component's coordinates
     * @param columnsRegion     the three-column area, in this component's coordinates
     *                          ({@code GameScreen.columnsBoundsIn(overlay)} in the real window)
     */
    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion, Supplier<Rectangle> columnsRegion) {
        super(new GridBagLayout());
        this.passThroughRegion = passThroughRegion;
        this.columnsRegion = columnsRegion;
        setOpaque(false);
        setFocusable(true);
        addMouseListener(new MouseAdapter() { });

        JLabel label = new JLabel("PAUSED", SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 36f));
        label.setForeground(Color.WHITE);
        JLabel hint = new JLabel("press Esc or Resume to continue", SwingConstants.CENTER);
        hint.setForeground(Color.LIGHT_GRAY);
        JButton resume = new JButton("Resume");
        resume.addActionListener(e -> onResume.run());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.insets = new Insets(6, 0, 6, 0);
        c.gridy = 0;
        add(label, c);
        c.gridy = 1;
        add(hint, c);
        c.gridy = 2;
        add(resume, c);
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
