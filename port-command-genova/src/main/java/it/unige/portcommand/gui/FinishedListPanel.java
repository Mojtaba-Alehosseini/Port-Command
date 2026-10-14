package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

import it.unige.portcommand.gui.model.FinishedListModel;

/**
 * "Finished" (S18b, playtest 2 PT5), at the bottom of the left column: one line per negotiation that
 * has ended in this game, newest first — the vessel's name, and how it ended ("deal · €1,700",
 * "left", "turned away"). A click on a line shows that negotiation's transcript in the chat column
 * again, read-only: its tab is brought back to the strip if it had left it.
 *
 * <p>Built like "Waiting for you" ({@link WaitingListPanel}) and for the same reasons: plain panels,
 * not a {@code JList} — a line is a button, not a selection; nothing here takes the keyboard focus;
 * line views are reused across refreshes, so a refresh between a press and its release cannot
 * swallow the click. Everything it shows is asked of the chat column through
 * {@link DialogueDirectory}, on the directory's own listener; it has no timer and no bus
 * subscription.
 *
 * <p>A long game finishes many negotiations, so the lines scroll: the list is never taller than
 * {@link #MAX_VISIBLE_ROWS} lines, and the Port table above it keeps the rest of the column.
 */
public final class FinishedListPanel extends JPanel {

    static final String NAME = "finished-list";
    static final String ROW_NAME = "finished-list-row";
    static final String ROW_VESSEL_NAME = "finished-list-row-vessel";
    static final String ROW_RESULT_NAME = "finished-list-row-result";
    static final String EMPTY_NAME = "finished-list-empty";

    static final String TITLE = "Finished";
    static final String EMPTY_TEXT = "None yet.";
    /** The most lines shown at once; more scroll. */
    static final int MAX_VISIBLE_ROWS = 5;

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final double HOVER_SHADE = 0.06;
    private static final double PRESSED_SHADE = 0.14;

    private final DialogueDirectory directory;
    private final JPanel rowsBox = new JPanel(new GridLayout(0, 1));
    private final JScrollPane rowsScroll;
    private final JLabel emptyLabel = new JLabel(EMPTY_TEXT);
    private final List<RowView> rowViews = new ArrayList<>();
    /** What is on screen; {@code null} until the first refresh, so that one always lays the panel out. */
    private List<FinishedListModel.Row> shown;

    public FinishedListPanel(DialogueDirectory directory) {
        super(new BorderLayout(0, 4));
        this.directory = Objects.requireNonNull(directory, "directory");
        setName(NAME);
        setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));

        JLabel title = new JLabel(TITLE);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        emptyLabel.setName(EMPTY_NAME);
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.PLAIN));
        emptyLabel.setForeground(MUTED);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // At most MAX_VISIBLE_ROWS lines tall; the rest scrolls. The bar appears only when needed.
        rowsScroll = new JScrollPane(rowsBox, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                Dimension preferred = super.getPreferredSize();
                if (rowViews.size() > MAX_VISIBLE_ROWS) {
                    int rowHeight = rowViews.get(0).getPreferredSize().height;
                    java.awt.Insets insets = getInsets();
                    preferred.height = rowHeight * MAX_VISIBLE_ROWS + insets.top + insets.bottom;
                }
                return preferred;
            }
        };
        rowsScroll.setBorder(null);
        rowsScroll.setViewportBorder(null);
        rowsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel body = new JPanel(new BorderLayout());
        body.add(emptyLabel, BorderLayout.NORTH);
        body.add(rowsScroll, BorderLayout.CENTER);

        add(title, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        quiet(this, title, emptyLabel, rowsBox, body, rowsScroll, rowsScroll.getViewport(),
                rowsScroll.getVerticalScrollBar(), rowsScroll.getHorizontalScrollBar());

        directory.addDialoguesListener(this::refresh);
        refresh();
    }

    /** Re-reads the directory and shows what it says. EDT only. Cheap when nothing changed. */
    public void refresh() {
        List<FinishedListModel.Row> rows = FinishedListModel.rows(directory.finishedDialogues());
        if (rows.equals(shown)) {
            return;
        }
        shown = rows;
        // Views are kept by position and only added or dropped at the END (see the class javadoc).
        while (rowViews.size() > rows.size()) {
            rowsBox.remove(rowViews.remove(rowViews.size() - 1));
        }
        while (rowViews.size() < rows.size()) {
            RowView view = new RowView();
            rowViews.add(view);
            rowsBox.add(view);
        }
        for (int i = 0; i < rows.size(); i++) {
            rowViews.get(i).display(rows.get(i));
        }
        emptyLabel.setVisible(rows.isEmpty());
        rowsScroll.setVisible(!rows.isEmpty());
        revalidate();
        repaint();
    }

    /** A line was clicked: the chat column shows that negotiation. A stale line re-reads the list. */
    private void open(String dialogueId) {
        if (!directory.selectDialogue(dialogueId)) {
            refresh();
        }
    }

    /** Never a focus owner, never a focus target: a click here leaves the typing focus alone. */
    private static void quiet(JComponent... parts) {
        for (JComponent part : parts) {
            part.setFocusable(false);
            part.setRequestFocusEnabled(false);
        }
    }

    private static Color shade(Color base, double amount) {
        double keep = 1.0 - amount;
        return new Color((int) Math.round(base.getRed() * keep), (int) Math.round(base.getGreen() * keep),
                (int) Math.round(base.getBlue() * keep));
    }

    /** Test-only: the lines as shown, newest first — {@code name | result}. */
    List<String> linesForTest() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < (shown == null ? 0 : shown.size()); i++) {
            RowView view = rowViews.get(i);
            lines.add(view.vesselLabel.getText() + " | " + view.resultLabel.getText());
        }
        return lines;
    }

    /** Test-only: whether the "None yet." line is what the list shows. */
    boolean showsEmptyForTest() {
        return emptyLabel.isVisible();
    }

    /** One line: the vessel's name, and how it ended flush right. */
    private final class RowView extends JPanel {

        private final JLabel vesselLabel = new JLabel();
        private final JLabel resultLabel = new JLabel();
        private FinishedListModel.Row row;
        private String pressedDialogueId;
        private boolean hovered;

        RowView() {
            super(new BorderLayout(8, 0));
            setName(ROW_NAME);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            vesselLabel.setName(ROW_VESSEL_NAME);
            vesselLabel.setFont(vesselLabel.getFont().deriveFont(Font.PLAIN));
            resultLabel.setName(ROW_RESULT_NAME);
            resultLabel.setFont(resultLabel.getFont().deriveFont(Font.PLAIN));
            // The name gives way in a narrow column (cut with an ellipsis); the result keeps its width.
            add(vesselLabel, BorderLayout.CENTER);
            add(resultLabel, BorderLayout.EAST);
            quiet(this, vesselLabel, resultLabel);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && row != null) {
                        pressedDialogueId = row.dialogueId();
                        showState();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) {
                        return;
                    }
                    // The line the player PRESSED on, even if a refresh has moved the list since.
                    String pressed = pressedDialogueId;
                    pressedDialogueId = null;
                    showState();
                    if (pressed != null && RowView.this.contains(e.getPoint())) {
                        open(pressed);
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    showState();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    showState();
                }
            });
            showState();
        }

        void display(FinishedListModel.Row newRow) {
            row = newRow;
            vesselLabel.setText(newRow.vesselName());
            resultLabel.setText(newRow.result());
            resultLabel.setForeground(newRow.deal() ? vesselLabel.getForeground() : MUTED);
        }

        private void showState() {
            Color base = FinishedListPanel.this.getBackground();
            if (base == null) {
                return;
            }
            if (pressedDialogueId != null) {
                setBackground(shade(base, PRESSED_SHADE));
            } else if (hovered) {
                setBackground(shade(base, HOVER_SHADE));
            } else {
                setBackground(base);
            }
        }
    }
}
