package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
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

import it.unige.portcommand.gui.model.WaitingListModel;

/**
 * "Waiting for you" (S15, 2026-10-12), at the top of the left column: one line per open
 * negotiation — the vessel's name and the time it will still wait — with the one closest to
 * walking away first. A click on a line brings that vessel's chat tab forward.
 *
 * <p><b>It has no clock, no timer and no bus subscription.</b> Everything it shows is asked of the
 * chat column through {@link DialogueDirectory}: which dialogues are open, and the remaining time
 * each tab's own counter last showed. The chat column's one-second counter refresh calls
 * {@link #refresh()} through the directory's listener, so the list and the tabs move on the same
 * tick and can never show two different numbers for one vessel. Ordering, texts and colour levels
 * are {@link WaitingListModel}'s; the colours are {@link CounterColours}, the tab's own.
 *
 * <p><b>The time is a bare number and a colour.</b> No column header, no caption, no tooltip
 * anywhere in this panel: the counter is never labelled or explained (INVARIANTS).
 *
 * <p><b>Plain panels, not a {@code JList}.</b> A line is a button, not a selection: nothing stays
 * highlighted after a click (a line is shaded only while the pointer is over it or held down on
 * it), and nothing here can take the keyboard focus — the player is usually typing in the chat
 * input when a second vessel hails, and clicking its line must leave the focus for the chat
 * column to place in that tab's input. The line views are REUSED across refreshes rather than
 * rebuilt: the list refreshes every second, and a line replaced between a press and its release
 * would swallow the click.
 *
 * <p>The directory has no way to remove a listener, so this panel does not unregister in
 * {@code removeNotify()}; it lives and dies with the chat column in the same screen, and a refresh
 * of a detached panel only re-reads the directory.
 */
public final class WaitingListPanel extends JPanel {

    /** This panel's component name, set here so the shell and tests can find the list by it. */
    static final String NAME = "waiting-list";
    /** Component names inside one line. */
    static final String ROW_NAME = "waiting-list-row";
    static final String ROW_VESSEL_NAME = "waiting-list-row-vessel";
    static final String ROW_TIME_NAME = "waiting-list-row-time";
    static final String EMPTY_NAME = "waiting-list-empty";

    static final String TITLE = "Waiting for you";
    static final String EMPTY_TEXT = "No one is waiting.";

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    /** How much darker than the panel a line is drawn under the pointer, and while held down. */
    private static final double HOVER_SHADE = 0.06;
    private static final double PRESSED_SHADE = 0.14;

    private final DialogueDirectory directory;
    private final JPanel rowsBox = new JPanel(new GridLayout(0, 1));
    private final JLabel emptyLabel = new JLabel(EMPTY_TEXT);
    private final List<RowView> rowViews = new ArrayList<>();
    /** What is on screen; {@code null} until the first refresh, so that one always lays the panel out. */
    private List<WaitingListModel.Row> shown;

    public WaitingListPanel(DialogueDirectory directory) {
        super(new BorderLayout(0, 4));
        this.directory = Objects.requireNonNull(directory, "directory");
        setName(NAME);
        // 4 px here plus 4 px inside the title and each line: the text starts 8 px in, like the
        // Port table's below, while a line's shading reaches a little past its text on both sides.
        setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));

        JLabel title = new JLabel(TITLE);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        emptyLabel.setName(EMPTY_NAME);
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.PLAIN));
        emptyLabel.setForeground(MUTED);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // Either the empty-state label or the lines is visible, never both; BorderLayout skips the
        // hidden one, so the panel is exactly as tall as what it shows.
        JPanel body = new JPanel(new BorderLayout());
        body.add(emptyLabel, BorderLayout.NORTH);
        body.add(rowsBox, BorderLayout.CENTER);

        add(title, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        quiet(this, title, emptyLabel, rowsBox, body);

        directory.addDialoguesListener(this::refresh);
        refresh();
    }

    /**
     * Re-reads the directory and shows what it says. EDT only. Cheap when nothing changed — which
     * is every second of a game in which nobody is waiting.
     */
    public void refresh() {
        List<WaitingListModel.Row> rows =
                WaitingListModel.rows(directory.openDialogues(), directory::shownRemainingMillis);
        if (rows.equals(shown)) {
            return;
        }
        boolean firstRefresh = shown == null;
        boolean lineCountChanged = rowViews.size() != rows.size();
        shown = rows;
        // Views are kept by position and only added or dropped at the END: see the class javadoc
        // on why a line that is still needed must stay the same component.
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
        rowsBox.setVisible(!rows.isEmpty());
        if (firstRefresh || lineCountChanged) {
            // The panel's height follows its line count, so the column has to be laid out again;
            // a tick that only changed the numbers needs no more than the labels' own repaint.
            revalidate();
        }
        repaint();
    }

    /**
     * A line was clicked. The directory selects the tab and puts the keyboard focus in its input.
     * If the tab is gone (it closed between the last refresh and the click) the line is stale:
     * re-read the directory so it disappears now rather than at the next tick.
     */
    private void open(String dialogueId) {
        if (!directory.selectDialogue(dialogueId)) {
            refresh();
        }
    }

    /** Never a focus owner, never a focus target: see the class javadoc. */
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

    /** One line: the vessel's name, and its time flush right in bold. */
    private final class RowView extends JPanel {

        private final JLabel vesselLabel = new JLabel();
        private final JLabel timeLabel = new JLabel();
        private WaitingListModel.Row row;
        /** The dialogue this line showed when the left button went down on it; {@code null} otherwise. */
        private String pressedDialogueId;
        private boolean hovered;

        RowView() {
            super(new BorderLayout(8, 0));
            setName(ROW_NAME);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            vesselLabel.setName(ROW_VESSEL_NAME);
            vesselLabel.setFont(vesselLabel.getFont().deriveFont(Font.PLAIN));
            timeLabel.setName(ROW_TIME_NAME);
            timeLabel.setFont(timeLabel.getFont().deriveFont(Font.BOLD));
            // The name is the part that gives way in a narrow column (it is cut with an ellipsis);
            // the time keeps its full width at the right edge.
            add(vesselLabel, BorderLayout.CENTER);
            add(timeLabel, BorderLayout.EAST);
            quiet(this, vesselLabel, timeLabel);

            // On the line only: its two labels have no listeners (and no tooltips), so AWT
            // delivers a press anywhere on the line to the line itself.
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
                    // Opens the dialogue the player PRESSED on, even if a refresh has re-sorted
                    // the list under the pointer since: that is the line they aimed at. Acted on
                    // at the release rather than on AWT's "clicked" event, which is not sent when
                    // the pointer moves between the press and the release.
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

        void display(WaitingListModel.Row newRow) {
            row = newRow;
            vesselLabel.setText(newRow.vesselName());
            timeLabel.setText(newRow.timeText());
            timeLabel.setForeground(CounterColours.of(newRow.level()));
        }

        private void showState() {
            Color base = WaitingListPanel.this.getBackground();
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
