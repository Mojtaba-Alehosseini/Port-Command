package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

import it.unige.portcommand.commlog.PerformativeColours;
import jade.lang.acl.ACLMessage;

/**
 * Read-only Help screen (task 21, planning/21 §21.4): keyboard shortcuts, the natural-language
 * command surface (§3.9 examples), and the FIPA performative colour legend — the legend swatches
 * are painted with the SAME {@link PerformativeColours} the comm log uses, so the key matches the
 * live colours the player sees rather than a hand-copied palette that could drift.
 *
 * <p>A {@link JDialog} (a {@link Window}) so it throws {@code HeadlessException} in the test lanes
 * and is verified via the manual {@code --gui} smoke path, exactly like {@link GameOverDialog} /
 * {@link EndOfDaySummaryDialog}. It holds no {@code EventBus} subscription (purely static content),
 * so re-opening it from the Help menu leaks nothing; {@code DISPOSE_ON_CLOSE} frees each instance.
 */
public final class HelpDialog extends JDialog {

    /** The canonical 10 FIPA performatives in the project's documented order (§6.1). */
    private static final int[] CANONICAL_TEN = {
            ACLMessage.REQUEST, ACLMessage.PROPOSE, ACLMessage.ACCEPT_PROPOSAL, ACLMessage.REJECT_PROPOSAL,
            ACLMessage.CFP, ACLMessage.CONFIRM, ACLMessage.INFORM, ACLMessage.REFUSE, ACLMessage.CANCEL,
            ACLMessage.DISCONFIRM,
    };

    private static final String[][] SHORTCUTS = {
            {"Esc", "Pause / resume the game"},
            {"Enter", "Send the typed message in the focused chat tab"},
            {"Hint button", "Ask the Assistant for a recommended counter-offer"},
    };

    private static final String[][] COMMANDS = {
            {"how about 6500 for 10 hours", "Counter-offer a fee and a berth duration"},
            {"6500", "Counter with just a fee (keeps the duration under discussion)"},
            {"deal  ·  accept  ·  yes", "Accept the vessel's current offer"},
            {"no  ·  reject  ·  turn them away", "Reject the offer"},
            {"withdraw  ·  cancel", "Walk away from this negotiation"},
            {"status  ·  how are we doing", "Ask what's on the table right now"},
            {"Genoa Star: 2000 for 5h", "Vocative — address one vessel by name across tabs"},
            {"tell the tanker: deal", "Vocative — route a move to the tanker's dialogue"},
    };

    public HelpDialog(Window owner) {
        super(owner, "Help — Port Command Genova", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        body.add(sectionHeader("Keyboard shortcuts"));
        body.add(twoColumn(SHORTCUTS));
        body.add(gap());
        body.add(sectionHeader("Talking to vessels"));
        body.add(new JLabel("Type in a walk-in's chat tab, or use the quick-action buttons:"));
        body.add(twoColumn(COMMANDS));
        body.add(gap());
        body.add(sectionHeader("Comm-log colour legend"));
        body.add(legend());

        JScrollPane scroll = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.add(close);

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        setPreferredSize(new Dimension(520, 560));
        pack();
        setLocationRelativeTo(owner);
    }

    private static JLabel sectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize() + 2f));
        label.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel twoColumn(String[][] rows) {
        JPanel grid = new JPanel(new GridLayout(rows.length, 2, 12, 4));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String[] row : rows) {
            JLabel key = new JLabel(row[0]);
            key.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            grid.add(key);
            grid.add(new JLabel(row[1]));
        }
        return grid;
    }

    private static JPanel legend() {
        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 4));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int performative : CANONICAL_TEN) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            JPanel swatch = new JPanel();
            swatch.setBackground(PerformativeColours.colourFor(performative));
            swatch.setOpaque(true);
            swatch.setPreferredSize(new Dimension(14, 14));
            swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
            row.add(swatch);
            row.add(new JLabel(ACLMessage.getPerformative(performative)));
            grid.add(row);
        }
        return grid;
    }

    private static Component gap() {
        return Box.createVerticalStrut(10);
    }
}
