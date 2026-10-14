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
import it.unige.portcommand.gui.model.HelpText;
import jade.lang.acl.ACLMessage;

/**
 * Read-only Help screen (task 21, planning/21 §21.4): S18b (REMAINING_AREAS_DECISIONS P7) — the
 * current game, the window, the moves a chat tab understands (with the phrasings S18a added), the
 * Assistant, the keys and saving, all from {@link HelpText}; then the FIPA performative colour
 * legend — the legend swatches are painted with the SAME {@link PerformativeColours} the comm log
 * uses, so the key matches the live colours the player sees rather than a hand-copied palette that
 * could drift.
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

    /** The width the meanings wrap at, in pixels. */
    private static final int MEANING_WIDTH = 330;

    public HelpDialog(Window owner) {
        super(owner, "Help — Port Command Genova", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        for (HelpText.Section section : HelpText.sections()) {
            body.add(sectionHeader(section.title()));
            if (section.intro() != null) {
                JLabel intro = new JLabel(wrapped(section.intro(), MEANING_WIDTH + 160));
                intro.setAlignmentX(Component.LEFT_ALIGNMENT);
                intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
                body.add(intro);
            }
            body.add(rows(section));
            body.add(gap());
        }
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
        setPreferredSize(new Dimension(640, 640));
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

    /** One section's rows: the key at its natural width (fixed-width for what is typed), the
     * meaning wrapping beside it. */
    private static JPanel rows(HelpText.Section section) {
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.anchor = java.awt.GridBagConstraints.FIRST_LINE_START;
        c.insets = new java.awt.Insets(2, 0, 4, 12);
        int y = 0;
        for (HelpText.Row row : section.rows()) {
            JLabel key = new JLabel(row.key());
            if (section.typed()) {
                key.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            } else {
                key.setFont(key.getFont().deriveFont(Font.BOLD));
            }
            c.gridx = 0;
            c.gridy = y;
            c.weightx = 0;
            grid.add(key, c);
            c.gridx = 1;
            c.weightx = 1;
            grid.add(new JLabel(wrapped(row.meaning(), MEANING_WIDTH)), c);
            y++;
        }
        return grid;
    }

    /** Text that wraps at {@code width} pixels; escaped, so nothing in it is read as markup. */
    private static String wrapped(String text, int width) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='width:" + width + "px'>" + escaped + "</body></html>";
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
