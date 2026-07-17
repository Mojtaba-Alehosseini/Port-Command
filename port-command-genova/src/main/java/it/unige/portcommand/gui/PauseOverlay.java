package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;

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
 */
final class PauseOverlay extends JPanel {

    PauseOverlay(Runnable onResume) {
        super(new GridBagLayout());
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            requestFocusInWindow(); // pull focus out of the chat input while paused
        }
    }
}
