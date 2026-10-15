package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

import it.unige.portcommand.gui.model.RuleBook;

/**
 * S19 (playtest 5, PT43): Help ▸ Rules (30) - "let the teacher see all the rules, not only the
 * ones cited in the log". Read-only: every rule of the Prolog kernel in its group, its plain
 * one-liner, the Prolog file it lives in and how often this game's comm log cited it - the content
 * is {@link RuleBook}'s, and {@code RuleBookTest} pins it. Not in Settings (Settings changes
 * things) and not in the comm log.
 *
 * <p>A {@link JDialog}, so it is verified on the manual {@code --gui} path like {@link HelpDialog};
 * it holds no bus subscription - the counts are read when it opens - so re-opening it leaks nothing.
 */
public final class RulesDialog extends JDialog {

    private static final int TEXT_WIDTH = 360;
    private static final Color GREY = new Color(0x66, 0x66, 0x66);

    public RulesDialog(Window owner, List<RuleBook.Group> groups) {
        super(owner, "Rules (" + groups.stream().mapToInt(g -> g.lines().size()).sum() + ") — the Prolog kernel",
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JLabel intro = new JLabel(wrapped("Every rule the HarbourMaster asks Prolog before it decides - "
                + "in its group, with the file it lives in and how often this game's comm log cited it.",
                TEXT_WIDTH + 260));
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        body.add(intro);
        for (RuleBook.Group group : groups) {
            JLabel header = new JLabel(group.title());
            header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize() + 2f));
            header.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(header);
            body.add(rows(group));
            body.add(Box.createVerticalStrut(6));
        }

        JScrollPane scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
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
        setPreferredSize(new Dimension(780, 660));
        pack();
        setLocationRelativeTo(owner);
    }

    /** One group's rules: the id, the one-liner (wrapping), the file, and this game's count. */
    private static JPanel rows(RuleBook.Group group) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        c.insets = new Insets(2, 0, 4, 12);
        int y = 0;
        for (RuleBook.Line line : group.lines()) {
            c.gridy = y++;
            c.gridx = 0;
            c.weightx = 0;
            JLabel id = new JLabel(line.ruleId());
            id.setFont(id.getFont().deriveFont(Font.BOLD));
            grid.add(id, c);
            c.gridx = 1;
            c.weightx = 1;
            grid.add(new JLabel(wrapped(line.text(), TEXT_WIDTH)), c);
            c.gridx = 2;
            c.weightx = 0;
            JLabel file = new JLabel(line.file());
            file.setForeground(GREY);
            grid.add(file, c);
            c.gridx = 3;
            grid.add(new JLabel("used this game: " + line.usedThisGame()), c);
        }
        return grid;
    }

    /** Text that wraps at {@code width} pixels; escaped, so nothing in it is read as markup. */
    private static String wrapped(String text, int width) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='width:" + width + "px'>" + escaped + "</body></html>";
    }
}
