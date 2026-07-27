package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.List;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

import it.unige.portcommand.scenario.Scenario;

/**
 * Modal scenario chooser (task 23, planning/23 §23.9): three large buttons in a row —
 * Tutorial / Busy Day / Storm — each showing the scenario's description and target
 * daily revenue on selection; footer {@code [ Start ] [ Cancel ]}.
 *
 * <p>A pure chooser: it returns the selected key and {@code MainWindow} runs
 * {@code GameSession.startScenario} on a worker thread with its existing
 * load-in-progress guard — this dialog never touches the world (single-EDT rule; the
 * boot takes seconds and must not run under a modal's dispatch loop).
 */
public final class NewGameDialog extends JDialog {

    private String selectedKey;
    /** Set ONLY by the Start button. Audit C-09 (2026-07-27): {@link #choose} used to return
     * {@code Optional.ofNullable(selectedKey)} regardless of HOW the dialog closed, and only
     * Cancel nulled the key — so clicking a scenario to read its description and then closing with
     * the title-bar ✕ returned that scenario as a confirmed choice, and {@code MainWindow}'s
     * {@code chooseAndStartScenario} tore down the running world with no second confirmation
     * (its javadoc's "the dialog's explicit Start IS the confirmation" is exactly the assumption
     * the ✕ path broke). Mid-demo that is an accidental world reset. */
    private boolean confirmed;

    private NewGameDialog(Frame owner, List<Scenario> scenarios) {
        super(owner, "New Game", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JLabel detail = new JLabel(" ", SwingConstants.CENTER);
        detail.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JButton start = new JButton("Start");
        start.setEnabled(false);

        JPanel choices = new JPanel(new GridLayout(1, scenarios.size(), 8, 8));
        choices.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        ButtonGroup group = new ButtonGroup();
        for (Scenario scenario : scenarios) {
            JToggleButton button = new JToggleButton(
                    "<html><center><b>" + scenario.displayName() + "</b></center></html>");
            button.setPreferredSize(new Dimension(170, 72));
            button.setToolTipText(scenario.description());
            button.addActionListener(e -> {
                selectedKey = scenario.key();
                detail.setText("<html><center>" + scenario.description()
                        + "<br><i>Target daily revenue: €"
                        + String.format("%,.0f", scenario.targetDailyRevenue())
                        + "</i></center></html>");
                start.setEnabled(true);
            });
            group.add(button);
            choices.add(button);
        }

        start.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            selectedKey = null;
            dispose();
        });
        JPanel footer = new JPanel();
        footer.add(start);
        footer.add(cancel);

        add(choices, BorderLayout.NORTH);
        add(detail, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the chooser modally; empty unless the player pressed <b>Start</b> — Cancel and the
     * title-bar ✕ both return empty (audit C-09). EDT only.
     *
     * @param scenarios the registry's 3 packaged scenarios, in display order
     */
    public static Optional<String> choose(Frame owner, List<Scenario> scenarios) {
        NewGameDialog dialog = new NewGameDialog(owner, scenarios);
        dialog.setVisible(true); // modal — blocks until dispose
        return dialog.confirmed ? Optional.ofNullable(dialog.selectedKey) : Optional.empty();
    }
}
