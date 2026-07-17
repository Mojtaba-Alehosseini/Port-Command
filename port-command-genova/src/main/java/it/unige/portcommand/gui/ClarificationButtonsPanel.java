package it.unige.portcommand.gui;

import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JPanel;

import it.unige.portcommand.nlp.ButtonOption;

/**
 * The persistent quick-action bar on every open dialogue tab — the same five options
 * {@code NLPPipeline}'s {@code NeedsClarification} names. A dumb, reusable grid of
 * buttons; it does not know what a click MEANS (that mapping is
 * {@code DialogueTabView}'s job, since it needs the tab's dialogue id / EventBus /
 * input field to act on it). {@code DialogueTabView.refresh()} owns visibility:
 * shown while the dialogue is open, hidden once it closes. (Originally hidden until
 * a low-confidence parse; made permanent after task 19's play-test — players
 * expected the actions to always be available.)
 *
 * <p><b>Two-row grid, not a FlowLayout row</b> (task 19 play-test layout bug): a single
 * row of five labelled buttons has a ~900&nbsp;px minimum width, and since a
 * {@code JTabbedPane}'s minimum tracks the widest component of ANY tab (visible or
 * not), one open dialogue was enough to crush {@code MapPanel} to a sliver —
 * {@code GridBagLayout} weights distribute EXTRA space but never override minimums.
 * The 2×3 grid roughly halves the minimum width, keeping the map alive.
 */
public final class ClarificationButtonsPanel extends JPanel {

    public ClarificationButtonsPanel(List<ButtonOption> options, Consumer<ButtonOption> onClick) {
        super(new GridLayout(2, 3, 4, 4));
        for (ButtonOption option : options) {
            JButton button = new JButton(option.label());
            button.addActionListener(e -> onClick.accept(option));
            add(button);
        }
    }
}
