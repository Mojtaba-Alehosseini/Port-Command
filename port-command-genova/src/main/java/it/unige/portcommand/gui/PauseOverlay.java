package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The translucent PAUSED glass pane (task 24). {@code MainWindow} installs it as the frame's
 * glass pane and toggles visibility off {@code GamePausedEvent}/{@code GameResumedEvent}.
 *
 * <p>While visible it also swallows all mouse input (the empty {@code MouseAdapter} makes this
 * component the event target, so nothing beneath is clickable) and takes keyboard focus, so a
 * paused game can't be typed into — Esc still works because the resume binding sits on the
 * root pane at WHEN_IN_FOCUSED_WINDOW scope.
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

    PauseOverlay(Runnable onResume) {
        this(onResume, Rectangle::new);
    }

    PauseOverlay(Runnable onResume, Supplier<Rectangle> passThroughRegion) {
        super(new GridBagLayout());
        this.passThroughRegion = passThroughRegion;
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
        return !passThroughRegion.get().contains(x, y) && super.contains(x, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Rectangle skip = passThroughRegion.get();
        g.setColor(new Color(0, 0, 0, 140));
        // Dim below the pass-through strip only: a dimmed-but-clickable menu bar would still read
        // as disabled, which is the half of C-04 that a hit-test fix alone does not answer.
        int top = skip.isEmpty() ? 0 : Math.max(0, skip.y + skip.height);
        g.fillRect(0, top, getWidth(), Math.max(0, getHeight() - top));
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            requestFocusInWindow(); // pull focus out of the chat input while paused
        }
    }
}
