package it.unige.portcommand.gui;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import it.unige.portcommand.gui.model.WeatherForecast;

/**
 * The small forecast a click on the top bar's weather opens (S16, 2026-10-13): a three-row table
 * — the chance of sunny / cloudy / stormy in 1, 2 and 3 game hours — and the line that says where
 * the numbers come from. Every number and word is {@link WeatherForecast}'s; this class only
 * builds the labels and owns the popup.
 *
 * <p><b>It never takes the keyboard focus.</b> The player is usually mid-sentence in the chat
 * input when they glance at the weather, and a negotiation's clock is running. Two things make
 * that hold: the popup is a {@link JPopupMenu} marked not focusable — Swing's popup-menu UI moves
 * the focus to the root pane for an ordinary menu (to drive it with the arrow keys) and returns
 * it afterwards, and does neither for a non-focusable one, which is how a combo box's list works
 * — and nothing inside it is focusable either, so a click on the table changes nothing. Typing
 * simply goes on in the chat input while the forecast is open.
 *
 * <p><b>It closes on the next click anywhere</b> — Swing's own behaviour for a popup menu, kept.
 * That includes a click on the weather itself: the press closes the popup before the weather's
 * own listener hears of it, so without care the same press would open it again and the line
 * could never be clicked shut. {@link #toggle} therefore ignores the press that just closed the
 * popup, recognised by its timestamp. Esc does not close it (there is no focus to hear the key
 * with; Esc stays the pause key).
 *
 * <p>The forecast is a snapshot of the moment it was opened. {@code HUDPanel} hides it when the
 * sea state changes or the world is replaced, rather than leave an answer to an old question
 * standing.
 *
 * <p>EDT only. The {@code JPopupMenu} is built on the first click, so a top bar that is only ever
 * laid out and painted (the headless tests) never creates one.
 */
final class WeatherForecastPopup {

    /** Component name of the popup's content panel. */
    static final String NAME = "weather-forecast";
    static final String TITLE = "Forecast";

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    /** A timestamp no mouse press carries. */
    private static final long NO_PRESS = Long.MIN_VALUE;

    private JPopupMenu popup;
    /** The timestamp of the mouse press that closed the popup, or {@link #NO_PRESS}. */
    private long closedByPressAt = NO_PRESS;

    /**
     * A press on the weather: opens the forecast for {@code state} under {@code anchor} — unless
     * this is the very press that has just closed it, in which case it stays closed. Does nothing
     * while {@code anchor} is not on screen (a popup needs a place on it).
     *
     * @param state     the sea state of the current reading, or {@code null} when there is none
     * @param pressWhen the press event's timestamp ({@code MouseEvent.getWhen()})
     */
    void toggle(JComponent anchor, String state, long pressWhen) {
        if (pressWhen == closedByPressAt) {
            closedByPressAt = NO_PRESS;
            return;
        }
        if (!anchor.isShowing()) {
            return;
        }
        JPopupMenu menu = popup();
        menu.removeAll();
        menu.add(content(state), BorderLayout.CENTER);
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** Closes the popup if it is open. */
    void hide() {
        if (popup != null && popup.isVisible()) {
            popup.setVisible(false);
        }
    }

    private JPopupMenu popup() {
        if (popup == null) {
            popup = new JPopupMenu();
            // The line that keeps the keyboard in the chat input — see the class comment.
            popup.setFocusable(false);
            popup.setLayout(new BorderLayout());
            popup.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    closedByPressAt = NO_PRESS;
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    // Swing closes a popup from inside the dispatch of the mouse press that
                    // landed outside it. That press may be on the weather itself, and the event
                    // the weather's listener then receives is a re-targeted copy with the same
                    // timestamp — which is all that identifies it.
                    AWTEvent current = EventQueue.getCurrentEvent();
                    closedByPressAt = current instanceof MouseEvent press ? press.getWhen() : NO_PRESS;
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    // popupMenuWillBecomeInvisible follows and does the work.
                }
            });
        }
        return popup;
    }

    /**
     * What the popup shows for a reading in {@code state}: the title, the table (one row per hour
     * ahead, one column per state, whole percentages that add up to 100) and the note; or the one
     * line {@link WeatherForecast#NO_READING} when there is no reading to forecast from. Nothing
     * in it is focusable. Needs no window, so the tests read it directly.
     */
    static JPanel content(String state) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setName(NAME);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        List<WeatherForecast.Row> rows = WeatherForecast.forState(state, WeatherForecast.HOURS);
        if (rows.isEmpty()) {
            panel.add(plain(WeatherForecast.NO_READING, SwingConstants.LEADING), BorderLayout.CENTER);
        } else {
            JLabel title = new JLabel(TITLE);
            title.setFont(title.getFont().deriveFont(Font.BOLD));

            List<String> columns = WeatherForecast.columns();
            JPanel table = new JPanel(new GridLayout(0, 1 + columns.size(), 12, 2));
            table.add(plain("", SwingConstants.LEADING));
            for (String column : columns) {
                table.add(muted(column, SwingConstants.TRAILING));
            }
            for (WeatherForecast.Row row : rows) {
                table.add(muted(row.label(), SwingConstants.LEADING));
                for (int percent : row.percents()) {
                    table.add(plain(percent + "%", SwingConstants.TRAILING));
                }
            }

            // The note is one sentence per line: as a single line it would make the popup twice
            // as wide as its table.
            JPanel note = new JPanel(new GridLayout(0, 1, 0, 0));
            for (String sentence : WeatherForecast.NOTE.split("(?<=\\.) ")) {
                note.add(muted(sentence, SwingConstants.LEADING));
            }

            panel.add(title, BorderLayout.NORTH);
            panel.add(table, BorderLayout.CENTER);
            panel.add(note, BorderLayout.SOUTH);
        }
        quiet(panel);
        return panel;
    }

    private static JLabel plain(String text, int alignment) {
        JLabel label = new JLabel(text, alignment);
        label.setFont(label.getFont().deriveFont(Font.PLAIN));
        return label;
    }

    private static JLabel muted(String text, int alignment) {
        JLabel label = plain(text, alignment);
        label.setForeground(MUTED);
        return label;
    }

    /** Never a focus owner, never a focus target — the panel and everything in it. */
    private static void quiet(Component component) {
        component.setFocusable(false);
        if (component instanceof JComponent swing) {
            swing.setRequestFocusEnabled(false);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                quiet(child);
            }
        }
    }
}
