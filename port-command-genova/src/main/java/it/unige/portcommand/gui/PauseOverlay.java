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
 * pass-through regions below. Esc still works because the resume binding sits on the root pane at
 * WHEN_IN_FOCUSED_WINDOW scope.
 *
 * <h2>The chat is NOT swallowed either (S14, 2026-10-11)</h2>
 * A paused game freezes the vessels' patience (ADR-18), and the whole point of that is to let the
 * player read, think and TYPE: a vessel's reply is message-driven and never waits on the game
 * clock. So the chat panel's rectangle passes mouse events through and is left undimmed, exactly
 * like the menu bar, and showing the overlay no longer pulls keyboard focus out of the chat
 * input. Everything else — the map, the comm log, the HUD — stays blocked as before. The overlay's
 * own PAUSED box stays where it was: {@link #captionBox()} — the caption, its hint line and the
 * Resume button, with a small margin — keeps both its dim and its clicks even where it overlaps
 * the chat, because white text on an undimmed transcript is not a pause indicator (adversarial
 * review, S14: at the default dividers the box straddles the map | chat boundary).
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
    /** S14: the chat panel's rectangle (in THIS component's coordinates), or empty. Re-read on
     * every hit-test and paint, like the menu strip — the split panes move it. */
    private final transient Supplier<Rectangle> chatRegion;

    PauseOverlay(Runnable onResume) {
        this(onResume, Rectangle::new);
    }

    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion) {
        this(onResume, passThroughRegion, Rectangle::new);
    }

    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion, Supplier<Rectangle> chatRegion) {
        super(new GridBagLayout());
        this.passThroughRegion = passThroughRegion;
        this.chatRegion = chatRegion;
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
     * Solid everywhere EXCEPT the pass-through region (the menu-bar strip). Swing's lightweight
     * dispatcher consults {@code contains} to pick the mouse-event target, so a false here lets
     * the click reach the menu bar under the glass pane. Same mechanism as
     * {@code TutorialOverlay.contains}.
     */
    @Override
    public boolean contains(int x, int y) {
        if (passThroughRegion.get().contains(x, y)) {
            return false;
        }
        // S14: the chat passes through too — but never under the overlay's own PAUSED box, whose
        // Resume button must stay clickable wherever the layout happens to put it.
        if (chatRegion.get().contains(x, y) && !captionBox().contains(x, y)) {
            return false;
        }
        return super.contains(x, y);
    }

    /** Margin around the PAUSED group that stays dimmed and click-blocking. */
    private static final int CAPTION_MARGIN = 12;

    /**
     * The overlay's own PAUSED group — caption, hint line, Resume button — as one rectangle with
     * a margin, wherever the layout put it; empty before the first layout. The same rectangle
     * decides what stays dimmed and what stays click-blocking inside the chat pass-through, so
     * the two can never disagree.
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
        // chat hole (S14) is left clear for the same reason.
        int top = skip.isEmpty() ? 0 : Math.max(0, skip.y + skip.height);
        Area scrim = new Area(new Rectangle(0, top, getWidth(), Math.max(0, getHeight() - top)));
        Rectangle chat = chatRegion.get();
        if (!chat.isEmpty()) {
            scrim.subtract(new Area(chat));
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
        if (visible && !focusIsInChat()) {
            // Pull focus out of whatever is now blocked (the comm-log filters, the map) — but NOT
            // out of the chat input: the player may type while paused (S14).
            requestFocusInWindow();
        }
    }

    /** Whether the keyboard focus owner sits inside the chat pass-through region. */
    private boolean focusIsInChat() {
        Rectangle chat = chatRegion.get();
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (chat.isEmpty() || owner == null || !owner.isShowing() || !isShowing()
                || SwingUtilities.getWindowAncestor(owner) != SwingUtilities.getWindowAncestor(this)) {
            return false;
        }
        Point origin = SwingUtilities.convertPoint(owner, 0, 0, this);
        return chat.contains(origin);
    }
}
