package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.gui.model.NewGameChoice;
import it.unige.portcommand.scenario.Scenario;

/**
 * Modal game chooser (task 23, planning/23 §23.9): large buttons in a row — Tutorial / Busy Day /
 * Storm, and since S15 (2026-10-12) <b>Free play</b> — each showing its description and a figure
 * line on selection; footer {@code [ Start ] [ Cancel ]}.
 *
 * <p><b>Free play is a choice of this dialog, not a fourth scenario.</b> It is the open-ended
 * sandbox, which until S15 could only be had by launching the program (it was what a plain
 * {@code --gui} start silently booted behind the window). The window now opens first and asks, so
 * the sandbox needs a button; but it has no script and no file, and the scenario registry stays
 * locked at its three keys — hence {@link NewGameChoice}, which is either a scenario key or
 * {@link NewGameChoice#freePlay() free play}.
 *
 * <p>A pure chooser: it returns the choice and {@code MainWindow} runs
 * {@code GameSession.startScenario} / {@code startSandbox} on a worker thread with its existing
 * load-in-progress guard — this dialog never touches the world (single-EDT rule; the
 * boot takes seconds and must not run under a modal's dispatch loop).
 */
public final class NewGameDialog extends JDialog {

    /** The fourth button's label (S15). */
    static final String FREE_PLAY_LABEL = "Free play";
    /** What Free play is, in the words the detail area and the button's tooltip show (S15). */
    static final String FREE_PLAY_DESCRIPTION = "Open-ended sandbox: contracted vessels arrive every day, "
            + "walk-ins hail at random. No script.";

    /**
     * One button of the dialog (S15): what it is labelled, its tooltip, what the detail area shows
     * once it is selected, and what choosing it means. Built by {@link #options}, so the content
     * of the dialog is testable without the dialog — a {@code JDialog} cannot be constructed
     * under {@code -Djava.awt.headless=true}.
     */
    record Option(String label, String tooltip, String detailHtml, NewGameChoice choice) {
    }

    /** What the last-clicked button means; {@code null} until one is clicked, and after Cancel. */
    private transient NewGameChoice selected;
    /** Set ONLY by the Start button. Audit C-09 (2026-07-27): {@link #choose} used to return
     * the selection regardless of HOW the dialog closed, and only Cancel nulled it — so clicking
     * a scenario to read its description and then closing with the title-bar ✕ returned that
     * scenario as a confirmed choice, and {@code MainWindow}'s game starter (then
     * {@code chooseAndStartScenario}, now {@code chooseAndStartGame}) tore down the running world
     * with no second confirmation (its javadoc's "the dialog's explicit Start IS the confirmation"
     * is exactly the assumption the ✕ path broke). Mid-demo that is an accidental world reset. */
    private boolean confirmed;

    private NewGameDialog(Frame owner, List<Scenario> scenarios) {
        super(owner, "New Game", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JLabel detail = detailLabel(scenarios);

        JButton start = new JButton("Start");
        start.setEnabled(false);

        List<Option> options = options(scenarios);
        JPanel choices = new JPanel(new GridLayout(1, options.size(), 8, 8));
        choices.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        ButtonGroup group = new ButtonGroup();
        for (Option option : options) {
            JToggleButton button = new JToggleButton(
                    "<html><center><b>" + option.label() + "</b></center></html>");
            button.setPreferredSize(new Dimension(170, 72));
            button.setToolTipText(option.tooltip());
            button.addActionListener(e -> {
                selected = option.choice();
                detail.setText(option.detailHtml());
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
            selected = null;
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
     * <p>S15 (2026-10-12): returns a {@link NewGameChoice} (was the scenario key), because the
     * fourth button — Free play — has no key.
     *
     * @param scenarios the registry's 3 packaged scenarios, in display order
     */
    public static Optional<NewGameChoice> choose(Frame owner, List<Scenario> scenarios) {
        NewGameDialog dialog = new NewGameDialog(owner, scenarios);
        dialog.setVisible(true); // modal — blocks until dispose
        return dialog.confirmed ? Optional.ofNullable(dialog.selected) : Optional.empty();
    }

    /**
     * The dialog's buttons, left to right (S15): one per scenario in the order given, then Free
     * play LAST — the scripted scenarios, the tutorial first of all, stay where a new player's eye
     * lands. Pure, so {@code NewGameDialogTest} pins the content of the dialog headless.
     */
    static List<Option> options(List<Scenario> scenarios) {
        List<Option> options = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            options.add(new Option(scenario.displayName(), scenario.description(), detailHtml(scenario),
                    NewGameChoice.scenario(scenario.key())));
        }
        options.add(new Option(FREE_PLAY_LABEL, FREE_PLAY_DESCRIPTION, freePlayDetailHtml(),
                NewGameChoice.freePlay()));
        return List.copyOf(options);
    }

    /**
     * What a selection shows: the description, then the target revenue on its own line. The
     * figure pins {@code Locale.ROOT} (INVARIANTS, money formatting): the default locale groups
     * it as "3.500" on a Danish or Italian desktop.
     */
    static String detailHtml(Scenario scenario) {
        return "<html><center>" + scenario.description()
                + "<br><i>Target daily revenue: €"
                + String.format(Locale.ROOT, "%,.0f", scenario.targetDailyRevenue())
                + "</i></center></html>";
    }

    /**
     * What selecting Free play shows (S15): what it is, then what the port starts with — the
     * sandbox has no scenario target to quote, and these two figures are exactly what
     * {@code GameSession.startSandbox} boots with ({@link AgentRoster#STARTING_WALLET},
     * {@link AgentRoster#STARTING_REPUTATION}; read from there, so the line cannot drift from the
     * game). Same shape as {@link #detailHtml} — description, line break, figure line in italics —
     * and the same {@code Locale.ROOT} pin on the money.
     */
    static String freePlayDetailHtml() {
        return "<html><center>" + FREE_PLAY_DESCRIPTION
                + "<br><i>Starts with €"
                + String.format(Locale.ROOT, "%,.0f", AgentRoster.STARTING_WALLET)
                + " · reputation "
                + String.format(Locale.ROOT, "%.0f", AgentRoster.STARTING_REPUTATION)
                + "</i></center></html>";
    }

    /**
     * S12 (live playtest F-3): the detail label, already reserving the size of the largest detail.
     * The dialog {@code pack()}s ONCE, before any selection, and a selection only re-lays-out
     * inside the unchanged window — packed around the blank placeholder, the centre slot held one
     * line and the second (the target-revenue figure DEMO_SCRIPT Beat 0 points at) was cut off;
     * the longer Busy Day / Storm descriptions wrap and would lose it entirely. A fresh HTML
     * label's preferred size is its natural UNWRAPPED layout, so at the max of them no detail
     * wraps or clips. Re-packing on selection instead would resize the dialog on every click, and
     * could not be tested headless, where a {@code JDialog} cannot be constructed.
     *
     * <p>S15: the reserve covers every {@link #options option}'s detail, so Free play's is
     * included — the room is measured over what the buttons can actually show.
     */
    static JLabel detailLabel(List<Scenario> scenarios) {
        JLabel detail = new JLabel(" ", SwingConstants.CENTER);
        detail.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        Dimension reserve = detail.getPreferredSize();
        for (Option option : options(scenarios)) {
            detail.setText(option.detailHtml());
            Dimension need = detail.getPreferredSize();
            reserve = new Dimension(Math.max(reserve.width, need.width),
                    Math.max(reserve.height, need.height));
        }
        detail.setText(" ");
        detail.setPreferredSize(reserve);
        return detail;
    }
}
