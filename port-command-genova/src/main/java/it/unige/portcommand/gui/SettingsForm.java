package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Hashtable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.util.EventBus;

/**
 * The settings form (task 21, planning/21 §21.1): the four user-editable knobs — difficulty,
 * real-seconds-per-game-day, autopilot, and LLM model — plus Reset / Apply / OK / Cancel.
 * Everything else in {@code defaults.json} stays fixed (not editable in v1).
 *
 * <p><b>A {@code JPanel}, not a {@code JDialog}, on purpose.</b> {@link SettingsDialog} wraps it in
 * a window; keeping the form itself a plain panel means it can be constructed and driven headless
 * (the {@code test} lane runs {@code -Djava.awt.headless=true}, under which a {@code JDialog} —
 * being a {@code Window} — throws), so {@code SettingsFormTest} can set the widgets, fire Apply and
 * assert the published {@link SettingsChangedEvent}. Same view/model split as
 * {@code EndOfDaySummaryDialog} → {@code EndOfDaySummaryModel}.
 *
 * <p><b>Honest per-knob apply</b> (see {@link SettingsChangedEvent}): day-length is live
 * (SimClock), autopilot is live (Assistant + Hint button), {@code llmModel} takes effect on the
 * next launch (sidecar restart — labelled as such), and {@code difficulty} is recorded only — no
 * runtime consumer reads it in v1. The form still exposes all four (they are the canonical §21.1
 * knobs) and the event carries all four; the form does not pretend the inert ones do more.
 */
final class SettingsForm extends JPanel {

    /** Slider bounds for {@code realSecondsPerGameDay} (planning/21 §21.1: 180–600, default 300). */
    static final int DAY_MIN_SECONDS = 180;
    static final int DAY_MAX_SECONDS = 600;

    private static final String[] DIFFICULTIES = {"EASY", "NORMAL", "HARD"};
    private static final String[] LLM_MODELS = {"microsoft/Phi-4-mini-instruct", "google/gemma-3-4b-it"};

    private final EventBus eventBus;
    private final JComboBox<String> difficultyCombo = new JComboBox<>(DIFFICULTIES);
    private final JSlider dayLengthSlider = new JSlider(DAY_MIN_SECONDS, DAY_MAX_SECONDS, DAY_MIN_SECONDS);
    private final JLabel dayLengthReadout = new JLabel();
    private final JCheckBox autopilotCheck = new JCheckBox("Enable Channel-B autopilot");
    private final JComboBox<String> llmCombo = new JComboBox<>(LLM_MODELS);
    private final JButton applyButton = new JButton("Apply");

    /** The TRUE live day length the form opened on (may exceed the slider's 180–600 range when a
     * scenario pinned e.g. 1800), and the clamped position the slider actually shows for it. If the
     * player never moves the slider off {@link #initialSliderValue}, Apply re-publishes
     * {@link #seededDayLength} — so a scenario's pacing is preserved, NOT stomped down to the clamp.
     * Only an explicit drag publishes the slider value (the "player intent wins" override). */
    private final long seededDayLength;
    private final int initialSliderValue;

    /**
     * @param initialDayLengthSeconds the LIVE {@code SimClock} rate (so a scenario's pinned pacing
     *                                is reflected, not the stale {@code defaults.json} value),
     *                                clamped into the slider's sandbox range
     * @param onCloseRequest          run by OK (after Apply) and Cancel; the wrapping dialog disposes
     */
    SettingsForm(EventBus eventBus, String difficulty, long initialDayLengthSeconds,
                 boolean autopilotEnabled, String llmModel, Runnable onCloseRequest) {
        super(new GridBagLayout());
        this.eventBus = eventBus;
        this.seededDayLength = initialDayLengthSeconds;
        this.initialSliderValue = clampDay(initialDayLengthSeconds);
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        difficultyCombo.setSelectedItem(difficulty);
        difficultyCombo.setToolTipText("Challenge level, recorded with your save "
                + "(engine balancing hooks are not wired in v1).");

        dayLengthSlider.setValue(initialSliderValue);
        dayLengthSlider.setMajorTickSpacing(120);
        dayLengthSlider.setPaintTicks(true);
        var labels = new Hashtable<Integer, JLabel>();
        labels.put(DAY_MIN_SECONDS, new JLabel(DAY_MIN_SECONDS + "s"));
        labels.put(300, new JLabel("300s"));
        labels.put(DAY_MAX_SECONDS, new JLabel(DAY_MAX_SECONDS + "s"));
        dayLengthSlider.setLabelTable(labels);
        dayLengthSlider.setPaintLabels(true);
        dayLengthSlider.addChangeListener(e -> updateDayReadout());
        updateDayReadout();

        autopilotCheck.setSelected(autopilotEnabled);
        autopilotCheck.setToolTipText("When on, the Assistant auto-acts on walk-ins and per-tab "
                + "Hint buttons hide.");

        llmCombo.setSelectedItem(llmModel);
        llmCombo.setToolTipText("Consumed by the Flask LLM sidecar — takes effect on the next launch.");

        int row = 0;
        addRow(row++, "Difficulty:", difficultyCombo);
        addRow(row++, "Real seconds per game day:", dayLengthSlider);
        addRow(row++, "", dayLengthReadout);
        addRow(row++, "Autopilot:", autopilotCheck);
        addRow(row++, "LLM model:", llmCombo);
        addRow(row++, "", new JLabel("(LLM model takes effect on next launch)"));

        JButton reset = new JButton("Reset to defaults");
        reset.addActionListener(e -> resetToDefaults());
        applyButton.addActionListener(e -> apply());
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            apply();
            onCloseRequest.run();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> onCloseRequest.run());

        JPanel buttons = new JPanel();
        buttons.add(reset);
        buttons.add(applyButton);
        buttons.add(ok);
        buttons.add(cancel);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(12, 4, 0, 4);
        add(buttons, c);
    }

    private void addRow(int row, String label, Component control) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 4, 4, 8);
        add(new JLabel(label), lc);

        GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 1;
        cc.gridy = row;
        cc.anchor = GridBagConstraints.WEST;
        cc.fill = GridBagConstraints.HORIZONTAL;
        cc.weightx = 1.0;
        cc.insets = new Insets(4, 4, 4, 4);
        add(control, cc);
    }

    private void updateDayReadout() {
        long effective = effectiveDayLength();
        if (effective != dayLengthSlider.getValue()) {
            // The seed exceeded the slider (a scenario's pinned pacing); show the TRUE value and
            // say plainly that leaving the slider alone keeps it — no silent clamp-down.
            dayLengthReadout.setText(effective + " s/day (scenario pace — drag to override)");
        } else {
            dayLengthReadout.setText(dayLengthSlider.getValue() + " s/day"
                    + (dayLengthSlider.getValue() == 300 ? " (default)" : ""));
        }
    }

    /**
     * The day length Apply will publish: the slider value once the player has moved it, otherwise
     * the true {@link #seededDayLength} the form opened on (so a scenario's 900/1800 pacing is
     * preserved rather than stomped down to the clamped slider position). A deliberate drag to
     * exactly the clamped seed position reads as "unchanged" — a benign corner (that IS the shown
     * value).
     */
    private long effectiveDayLength() {
        return dayLengthSlider.getValue() == initialSliderValue ? seededDayLength : dayLengthSlider.getValue();
    }

    /** Publishes the current widget values. Apply and OK both call this; Reset does not (a reset is
     * not applied until the player confirms with Apply/OK). */
    private void apply() {
        eventBus.publish(new SettingsChangedEvent(
                (String) difficultyCombo.getSelectedItem(),
                effectiveDayLength(),
                autopilotCheck.isSelected(),
                (String) llmCombo.getSelectedItem()));
    }

    private void resetToDefaults() {
        Settings d = Settings.defaults();
        difficultyCombo.setSelectedItem(d.difficulty());
        dayLengthSlider.setValue(clampDay(d.realSecondsPerGameDay()));
        autopilotCheck.setSelected(d.autopilotEnabled());
        llmCombo.setSelectedItem(d.llmModel());
    }

    private static int clampDay(long seconds) {
        return (int) Math.max(DAY_MIN_SECONDS, Math.min(DAY_MAX_SECONDS, seconds));
    }

    // ---- test seams (headless) ----

    JComboBox<String> difficultyComboForTest() {
        return difficultyCombo;
    }

    JSlider dayLengthSliderForTest() {
        return dayLengthSlider;
    }

    JCheckBox autopilotCheckForTest() {
        return autopilotCheck;
    }

    JComboBox<String> llmComboForTest() {
        return llmCombo;
    }

    /** Drives the Apply button exactly as a click would (publishes the {@link SettingsChangedEvent}). */
    void clickApplyForTest() {
        applyButton.doClick();
    }

    /** Resets the widgets to {@code Settings.defaults()} exactly as the Reset button does — WITHOUT
     * publishing (a reset only takes effect once the player confirms with Apply/OK). */
    void resetToDefaultsForTest() {
        resetToDefaults();
    }
}
