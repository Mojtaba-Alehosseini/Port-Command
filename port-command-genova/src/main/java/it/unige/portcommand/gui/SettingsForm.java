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
import javax.swing.JTextField;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.model.DayLengthSteps;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.util.EventBus;

/**
 * The settings form (task 21, planning/21 §21.1): the four user-editable knobs — difficulty,
 * real-seconds-per-game-day, autopilot, and LLM model — plus Reset / Apply / OK / Cancel.
 * Everything else in {@code defaults.json} stays fixed (not editable in v1).
 *
 * <p><b>Plus one action that is not a knob (2026-07-28, audit C-01/D-02): the autopilot policy
 * field.</b> It does not ride the {@link SettingsChangedEvent} and is not undone by Cancel — it
 * hands its text to {@code PolicyParser}, which publishes {@code PolicyParsedEvent} for the
 * Assistant's already-subscribed {@code PolicyParseBehaviour} to register. It lives here because
 * the demo script and the checklist both said "open Settings and type a policy", and because
 * autopilot — the thing a policy modifies — is the checkbox directly above it. See
 * {@link #submitPolicy()}.
 *
 * <p><b>A {@code JPanel}, not a {@code JDialog}, on purpose.</b> {@link SettingsDialog} wraps it in
 * a window; keeping the form itself a plain panel means it can be constructed and driven headless
 * (the {@code test} lane runs {@code -Djava.awt.headless=true}, under which a {@code JDialog} —
 * being a {@code Window} — throws), so {@code SettingsFormTest} can set the widgets, fire Apply and
 * assert the published {@link SettingsChangedEvent}. Same view/model split as
 * {@code EndOfDaySummaryDialog} → {@code EndOfDaySummaryModel}.
 *
 * <p><b>S14: the day length is eight fixed steps</b> ({@link DayLengthSteps}: 5 min … 2 h), not a
 * 180-600 s range. It sets the &times;1 BASE pace; the speed control (HUD / Game &#9656; Speed)
 * multiplies it and is not on this form.
 *
 * <p><b>Honest per-knob apply</b> (see {@link SettingsChangedEvent}): day-length is live
 * (SimClock), autopilot is live (Assistant + Hint button), {@code llmModel} takes effect on the
 * next launch (sidecar restart — labelled as such), and {@code difficulty} is recorded only — no
 * runtime consumer reads it in v1. The form still exposes all four (they are the canonical §21.1
 * knobs) and the event carries all four; the form does not pretend the inert ones do more.
 */
final class SettingsForm extends JPanel {

    private static final String[] DIFFICULTIES = {"EASY", "NORMAL", "HARD"};
    private static final String[] LLM_MODELS = {"microsoft/Phi-4-mini-instruct", "google/gemma-3-4b-it"};

    private final EventBus eventBus;
    /** Stateless; constructed here rather than injected so no call site has to thread it through. */
    private final transient PolicyParser policyParser = new PolicyParser();
    private final JTextField policyField = new JTextField(28);
    private final JButton policyButton = new JButton("Set policy");
    private final JLabel policyFeedback = new JLabel(" ");
    private final JComboBox<String> difficultyCombo = new JComboBox<>(DIFFICULTIES);
    /** S14: the slider's value is a STEP INDEX into {@link DayLengthSteps}, never seconds. */
    private final JSlider dayLengthSlider = new JSlider(0, DayLengthSteps.count() - 1, 0);
    private final JLabel dayLengthReadout = new JLabel();
    private final JCheckBox autopilotCheck = new JCheckBox("Enable Channel-B autopilot");
    private final JComboBox<String> llmCombo = new JComboBox<>(LLM_MODELS);
    private final JButton applyButton = new JButton("Apply");

    /** The TRUE live day length the form opened on. Every shipped pace is one of the steps, but a
     * {@code --day-length} test pace need not be: the slider then shows the NEAREST step, and as
     * long as the player leaves it alone Apply re-publishes this true value — a pace is never
     * changed by merely opening the dialog. Only moving the slider (or Reset) publishes a step. */
    private final long seededDayLength;
    /** Whether the player has moved the day-length slider (or pressed Reset) since the form opened. */
    private boolean dayLengthTouched;

    /**
     * @param initialDayLengthSeconds the LIVE {@code SimClock} base rate (so a scenario's pinned
     *                                pacing is reflected, not the stale {@code defaults.json}
     *                                value), shown at its nearest step
     * @param onCloseRequest          run by OK (after Apply) and Cancel; the wrapping dialog disposes
     */
    SettingsForm(EventBus eventBus, String difficulty, long initialDayLengthSeconds,
                 boolean autopilotEnabled, String llmModel, Runnable onCloseRequest) {
        super(new GridBagLayout());
        this.eventBus = eventBus;
        this.seededDayLength = initialDayLengthSeconds;
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        difficultyCombo.setSelectedItem(difficulty);
        difficultyCombo.setToolTipText("Challenge level, recorded with your save "
                + "(engine balancing hooks are not wired in v1).");

        dayLengthSlider.setValue(DayLengthSteps.nearestIndex(initialDayLengthSeconds));
        dayLengthSlider.setMajorTickSpacing(1);
        dayLengthSlider.setSnapToTicks(true);
        dayLengthSlider.setPaintTicks(true);
        var labels = new Hashtable<Integer, JLabel>();
        for (int i = 0; i < DayLengthSteps.count(); i++) {
            labels.put(i, new JLabel(DayLengthSteps.label(DayLengthSteps.secondsAt(i))));
        }
        dayLengthSlider.setLabelTable(labels);
        dayLengthSlider.setPaintLabels(true);
        // Registered AFTER the seed value is set: only a later change counts as the player's.
        dayLengthSlider.addChangeListener(e -> {
            dayLengthTouched = true;
            updateDayReadout();
        });
        updateDayReadout();

        autopilotCheck.setSelected(autopilotEnabled);
        autopilotCheck.setToolTipText("When on, the Assistant auto-acts on walk-ins and per-tab "
                + "Hint buttons hide.");

        llmCombo.setSelectedItem(llmModel);
        llmCombo.setToolTipText("Consumed by the Flask LLM sidecar — takes effect on the next launch.");

        policyField.setToolTipText("A fixed grammar, not free text: " + PolicyParser.GRAMMAR_HINT);
        policyField.addActionListener(e -> submitPolicy()); // Enter submits
        policyButton.addActionListener(e -> submitPolicy());
        policyFeedback.setFont(policyFeedback.getFont().deriveFont(java.awt.Font.PLAIN));

        JPanel policyRow = new JPanel(new java.awt.BorderLayout(6, 0));
        policyRow.add(policyField, java.awt.BorderLayout.CENTER);
        policyRow.add(policyButton, java.awt.BorderLayout.EAST);

        int row = 0;
        addRow(row++, "Difficulty:", difficultyCombo);
        addRow(row++, "Game day length (at \u00d71 speed):", dayLengthSlider);
        addRow(row++, "", dayLengthReadout);
        addRow(row++, "Autopilot:", autopilotCheck);
        addRow(row++, "Autopilot policy:", policyRow);
        addRow(row++, "", policyFeedback);
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

    /**
     * The autopilot policy input (2026-07-28 — audit C-01/D-02, option (a)).
     *
     * <p>Before this, {@code PolicyParser.parse(text, eventBus)} — the method that publishes
     * {@code PolicyParsedEvent} — had <b>zero production call sites</b>. The DSL, the event, the
     * subscribing {@code PolicyParseBehaviour}, the registry and its save/load restore were all
     * built and tested, and nothing in the shipped game could put a rule into them: DEMO_SCRIPT
     * Beat 4 and DEMO_CHECKLIST Row 6 both said "open Settings, type a policy" and there was no
     * field. This is that field, wired to the existing seam — no new mechanism.
     *
     * <p><b>Never a silent fail.</b> On success the feedback echoes the rule's
     * {@code description} — built by the parser from the parsed action, condition and vessel-type
     * scope, so it shows what was UNDERSTOOD rather than reading the player's own words back. On
     * failure it shows the grammar and one working example, because {@code PolicyParser}'s
     * language is a fixed DSL and a player who improvises needs to be told the shape (audit D-03:
     * the Rasa training corpus and this DSL are disjoint languages). {@code PolicyParser.parse}
     * also publishes its own {@code NotificationEvent} on both paths; this is the INLINE half, at
     * the point of typing.
     *
     * <p>Applies immediately, not on Apply/OK — a policy is a discrete action with its own button,
     * not one of the four knobs the {@code SettingsChangedEvent} carries. Cancel does not undo it,
     * which the feedback says.
     */
    private void submitPolicy() {
        String text = policyField.getText().trim();
        if (text.isEmpty()) {
            showPolicyHint("Type a policy first.");
            return;
        }
        policyParser.parse(text, eventBus).ifPresentOrElse(rule -> {
            policyFeedback.setForeground(new java.awt.Color(21, 101, 52));
            policyFeedback.setText("<html><b>Policy set.</b> " + escape(rule.description())
                    + " <i>(active now — Cancel will not undo it)</i></html>");
            policyField.setText("");
        }, () -> showPolicyHint("Not understood."));
    }

    private void showPolicyHint(String lead) {
        policyFeedback.setForeground(new java.awt.Color(153, 27, 27));
        policyFeedback.setText("<html><div style='width:420px'><b>" + escape(lead)
                + "</b> Policies follow a fixed grammar:<br><tt>" + escape(PolicyParser.GRAMMAR_HINT)
                + "</tt><br>Example: <tt>" + escape(PolicyParser.GRAMMAR_EXAMPLE)
                + "</tt></div></html>");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        if (!DayLengthSteps.isStep(effective)) {
            // The live pace is not one of the steps (a --day-length test pace); show the TRUE
            // value and say plainly that leaving the slider alone keeps it — no silent snap.
            dayLengthReadout.setText(effective + " s per game day (custom pace — move the slider to change)");
        } else {
            dayLengthReadout.setText(DayLengthSteps.label(effective) + " per game day"
                    + (effective == Settings.defaults().realSecondsPerGameDay() ? " (default)" : ""));
        }
    }

    /**
     * The day length Apply will publish: the slider's step once the player has moved it (or
     * pressed Reset), otherwise the true {@link #seededDayLength} the form opened on — so a pace
     * that is not one of the steps is preserved rather than snapped by merely opening the dialog.
     */
    private long effectiveDayLength() {
        return dayLengthTouched ? DayLengthSteps.secondsAt(dayLengthSlider.getValue()) : seededDayLength;
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
        dayLengthSlider.setValue(DayLengthSteps.nearestIndex(d.realSecondsPerGameDay()));
        dayLengthTouched = true; // even when the slider was already there: Reset MEANS the default
        updateDayReadout();
        autopilotCheck.setSelected(d.autopilotEnabled());
        llmCombo.setSelectedItem(d.llmModel());
    }

    // ---- test seams (headless) ----

    JComboBox<String> difficultyComboForTest() {
        return difficultyCombo;
    }

    JSlider dayLengthSliderForTest() {
        return dayLengthSlider;
    }

    /** Test-only: the readout line under the slider. */
    String dayLengthReadoutForTest() {
        return dayLengthReadout.getText();
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

    JTextField policyFieldForTest() {
        return policyField;
    }

    /** Drives the Set-policy button exactly as a click would. */
    void clickSetPolicyForTest() {
        policyButton.doClick();
    }

    /** The inline feedback line's current HTML (audit C-01/D-02: it must never be silent). */
    String policyFeedbackForTest() {
        return policyFeedback.getText();
    }
}
