package it.unige.portcommand.gui;

import java.awt.Window;

import javax.swing.JDialog;

import it.unige.portcommand.util.EventBus;

/**
 * The Settings window (task 21, planning/21 §21.5's "Settings → Open settings"): a thin modal
 * {@link JDialog} hosting a {@link SettingsForm}. All logic — the four knobs, Reset/Apply/OK/Cancel
 * and the {@code SettingsChangedEvent} publish — lives in the headless-testable form; this class
 * only mounts it in a window and disposes on OK/Cancel.
 *
 * <p>Modal to the main window but never blocking the JADE thread: the modal dispatch loop runs on
 * the EDT (like every other dialog here); the simulation clock and agents run on their own threads,
 * unaffected. Holds no {@code EventBus} subscription itself, so re-opening it from the menu leaks
 * nothing (the form publishes, it does not subscribe).
 *
 * <p>S16 (2026-10-13): whether the day length is locked — a scenario's script is running — is
 * decided when the dialog OPENS and handed to the form. A dialog that was open when the script
 * ended keeps its slider disabled until it is reopened; the other direction cannot happen, since
 * the lock only ever engages at a game start, and this dialog is modal over the menu that starts
 * one. ({@code MainWindow.applySettings} refuses a changed day length under the lock all the same.)
 */
public final class SettingsDialog extends JDialog {

    private SettingsDialog(Window owner, EventBus eventBus, String difficulty, long dayLengthSeconds,
                           boolean autopilotEnabled, String llmModel, boolean dayLengthLocked) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(new SettingsForm(eventBus, difficulty, dayLengthSeconds, autopilotEnabled,
                llmModel, dayLengthLocked, this::dispose));
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the modal Settings dialog, seeded with the current live values. EDT only.
     *
     * @param dayLengthSeconds the LIVE {@code SimClock} rate (reflects a scenario's pinned pacing)
     * @param dayLengthLocked  S16: the scenario's scripted events are still to come — the
     *                         day-length slider is shown disabled, with a note saying why
     */
    public static void open(Window owner, EventBus eventBus, String difficulty, long dayLengthSeconds,
                            boolean autopilotEnabled, String llmModel, boolean dayLengthLocked) {
        new SettingsDialog(owner, eventBus, difficulty, dayLengthSeconds, autopilotEnabled, llmModel,
                dayLengthLocked).setVisible(true);
    }
}
