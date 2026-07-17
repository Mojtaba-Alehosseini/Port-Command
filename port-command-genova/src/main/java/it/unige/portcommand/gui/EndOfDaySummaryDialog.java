package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.model.EndOfDaySummaryModel;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The modal end-of-day report (task 20). Subscribes to {@link EndOfDayCompletedEvent} — the
 * settled summary published by task 24's {@code DayRolloverCoordinator} — and NOT to
 * {@code EndOfDayEvent}, which is the bare midnight-DETECT signal task 24 itself consumes
 * (planning/20 §20.5; INVARIANTS: EOD math is task 24's, this task only displays).
 *
 * <p>Deliberately a thin view: every string comes from {@link EndOfDaySummaryModel}, which has no
 * Swing import and is unit-tested headless. This class holds no formatting logic, because it
 * cannot be tested in the {@code test}/{@code integrationTest} lanes at all — they run with
 * {@code -Djava.awt.headless=true} and a {@code JDialog} is a {@link Window}, so constructing one
 * throws {@code HeadlessException} (INVARIANTS, task 17). Verified instead via the model's tests
 * plus the manual {@code gradlew run --args="--gui"} smoke path, where the Debug menu can open it
 * from a synthetic summary while the live clock is still frozen (task 24 owns the real trigger).
 *
 * <p>The report renders as monospaced text, matching the demo transcript's ASCII layout — the
 * column alignment IS the design, and a grid of JLabels would lose it.
 */
public final class EndOfDaySummaryDialog extends JDialog {

    private final Subscription<EndOfDayCompletedEvent> subscription;

    /**
     * Registers the dialog's own listener. Construction does NOT show anything — the dialog opens
     * only when a settled summary arrives.
     *
     * @param owner       the main window; the dialog is modal to it
     * @param eventBus    the shared bus
     * @param leaderboard the board to read the "best score so far" line from
     */
    public EndOfDaySummaryDialog(Window owner, EventBus eventBus, Leaderboard leaderboard) {
        super(owner, "End of Day", ModalityType.APPLICATION_MODAL);
        this.subscription = eventBus.subscribe(
                EndOfDayCompletedEvent.class, e -> show(e.summary(), leaderboard), DeliveryMode.EDT);
    }

    /**
     * Builds and shows the report for {@code summary}. Public so the Debug menu can drive it from
     * a synthetic summary during the demo lane, exactly as the real {@link EndOfDayCompletedEvent}
     * path does — one code path, not a demo-only replica.
     */
    public void show(EndOfDaySummary summary, Leaderboard leaderboard) {
        EndOfDaySummaryModel model = new EndOfDaySummaryModel(
                summary, Settings.load().dailyTargetEur(), leaderboard.top5());
        setTitle(model.title());
        setContentPane(buildContent(model));
        pack();
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    private JPanel buildContent(EndOfDaySummaryModel model) {
        JTextArea report = new JTextArea(String.join("\n", model.lines()));
        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        report.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton continueButton = new JButton("Continue");
        continueButton.addActionListener(e -> setVisible(false));
        buttons.add(continueButton);
        // [Save & Quit] and [View Leaderboard] are planning/20 §20.5's other two buttons. Save is
        // task 22's SaveLoadManager (not built) and the leaderboard screen is task 21's secondary
        // screens — wiring dead buttons that silently do nothing would be worse than omitting them.

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JScrollPane(report), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        return content;
    }

    @Override
    public void dispose() {
        subscription.cancel();
        super.dispose();
    }
}
