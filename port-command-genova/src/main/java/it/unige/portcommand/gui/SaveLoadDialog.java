package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.persistence.SaveLoadManager.SaveSlotInfo;

/**
 * The two-slot Save / Load chooser (task 21, planning/21 §21.2, reconciled 2026-07-18). v1 has
 * exactly two fixed slots — {@code save/autosave.json} (end-of-day, pinned at the top) and
 * {@code save/savegame.json} (the manual slot) — no free-form or {@code slot_N} names. Each row
 * previews the slot's day, wallet and save time, read fresh from the file on open via
 * {@link SaveLoadManager#peek(Path)} (the directory is scanned every time — never cached, so a
 * new autosave since the last open shows up).
 *
 * <p><b>A chooser, not a worker.</b> It never touches the JADE world itself: {@code Save} (after
 * an overwrite confirm on an existing manual slot) invokes the caller's save action — the same
 * {@code MainWindow.saveGame()} quiesce-on-a-worker-thread path the menu already used — and
 * {@code Load} invokes the caller's load action with the chosen slot's path (which runs
 * {@code MainWindow.confirmAndLoad}'s replace-world confirm + teardown/rebuild off the EDT). So
 * the modal never blocks the JADE thread, and the heavy lifting stays in the one tested place.
 * Holds no {@code EventBus} subscription, so re-opening it leaks nothing.
 */
public final class SaveLoadDialog extends JDialog {

    /** SAVE writes the manual slot; LOAD restores whichever slot the player picks. */
    public enum Mode { SAVE, LOAD }

    private SaveLoadDialog(Window owner, SaveLoadManager manager, Mode mode,
                           Runnable saveAction, Consumer<Path> loadAction) {
        super(owner, mode == Mode.SAVE ? "Save Game" : "Load Game", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        Path autosave = manager.autosavePath();
        Path savegame = manager.savegamePath();
        Optional<SaveSlotInfo> autoInfo = manager.peek(autosave);
        Optional<SaveSlotInfo> manualInfo = manager.peek(savegame);

        JPanel slots = new JPanel();
        slots.setLayout(new BoxLayout(slots, BoxLayout.Y_AXIS));
        slots.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        JButton primary = new JButton(mode == Mode.SAVE ? "Save" : "Load");

        if (mode == Mode.LOAD) {
            // Both slots are selectable in LOAD; autosave pinned first. Empty slots are shown but
            // not selectable. Preselect the first non-empty slot and gate Load on a real pick.
            ButtonGroup group = new ButtonGroup();
            JRadioButton autoButton = loadRow(slots, group, "Autosave (end of day)", autoInfo);
            JRadioButton manualButton = loadRow(slots, group, "Manual save", manualInfo);
            primary.setEnabled(autoInfo.isPresent() || manualInfo.isPresent());
            if (autoInfo.isPresent()) {
                autoButton.setSelected(true);
            } else if (manualInfo.isPresent()) {
                manualButton.setSelected(true);
            }
            primary.addActionListener(e -> {
                Path chosen = autoButton.isSelected() ? autosave : savegame;
                dispose();
                loadAction.accept(chosen);
            });
        } else {
            // SAVE targets the manual slot only (autosave is the end-of-day system's). The
            // autosave row is shown read-only for context.
            slots.add(infoRow("Autosave (end of day)", autoInfo));
            slots.add(Box.createVerticalStrut(6));
            slots.add(infoRow("Manual save", manualInfo));
            primary.addActionListener(e -> {
                if (manualInfo.isEmpty() || confirmOverwrite()) {
                    dispose();
                    saveAction.run();
                }
            });
        }

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.add(primary);
        footer.add(cancel);

        setLayout(new BorderLayout());
        add(slots, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(primary);
        setMinimumSize(new Dimension(360, 200));
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shows the chooser modally. EDT only. */
    public static void open(Window owner, SaveLoadManager manager, Mode mode,
                            Runnable saveAction, Consumer<Path> loadAction) {
        new SaveLoadDialog(owner, manager, mode, saveAction, loadAction).setVisible(true);
    }

    private static JRadioButton loadRow(JPanel parent, ButtonGroup group, String title,
                                        Optional<SaveSlotInfo> info) {
        JRadioButton button = new JRadioButton("<html><b>" + title + "</b><br>" + preview(info) + "</html>");
        button.setEnabled(info.isPresent());
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        group.add(button);
        parent.add(button);
        return button;
    }

    private static JPanel infoRow(String title, Optional<SaveSlotInfo> info) {
        JPanel row = new JPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(title);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        row.add(name, BorderLayout.NORTH);
        row.add(new JLabel(preview(info)), BorderLayout.CENTER);
        return row;
    }

    /** Slot preview line: day / wallet / save time, or an em-dash for an empty or unreadable slot. */
    private static String preview(Optional<SaveSlotInfo> info) {
        if (info.isEmpty()) {
            return "— empty —";
        }
        SaveSlotInfo s = info.get();
        String when = s.savedAt() == null ? "" : "  ·  " + prettyInstant(s.savedAt());
        return String.format("Day %d  ·  €%,.0f%s", s.day(), s.wallet(), when);
    }

    /** ISO-8601 → a compact {@code YYYY-MM-DD HH:MM} for the preview; falls back to the raw text
     * if it is shorter/oddly-shaped than expected (never throws in the render path). */
    private static String prettyInstant(String iso) {
        String t = iso.replace('T', ' ');
        return t.length() >= 16 ? t.substring(0, 16) : t;
    }

    private boolean confirmOverwrite() {
        return JOptionPane.showConfirmDialog(this,
                "A manual save already exists. Overwrite it?", "Overwrite Save",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
