package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Hashtable;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.PolicyClearRequestEvent;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.model.DayLengthSteps;
import it.unige.portcommand.gui.model.PolicyListModel;
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
 * <p><b>S16 (2026-10-13): the day length is locked while a scenario's script is running.</b> The
 * script's events are placed in GAME time and written for the scenario's own pace: a shorter day
 * gives the player less real time between two scripted hails exactly as a higher speed does, so
 * the slider is locked together with the speed control (PLAYABILITY &sect;10, P12). The form is told
 * at construction ({@code dayLengthLocked}); it then shows the slider disabled with one line
 * saying why, publishes the pace it opened on whatever the slider says, and Reset leaves the day
 * length alone. Autopilot is NOT locked — switching it on mid-script is a legitimate way to play
 * a scenario.
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

    static final String CLEAR_POLICIES_LABEL = "Clear policies";

    /** S16: the line under a locked day-length slider. */
    static final String DAY_LENGTH_LOCK_NOTE =
            "Day length is locked while the scenario's scripted events are running";

    private final EventBus eventBus;
    /** Stateless; constructed here rather than injected so no call site has to thread it through. */
    private final transient PolicyParser policyParser = new PolicyParser();
    private final JTextField policyField = new JTextField(28);
    private final JButton policyButton = new JButton("Set policy");
    private final JLabel policyFeedback = new JLabel(" ");
    /** S18a (decision N11): the policies in force, as the Assistant last reported them. */
    private final transient PolicyListModel policies;
    private final JLabel policyList = new JLabel(PolicyListModel.NONE);
    private final JButton clearPoliciesButton = new JButton(CLEAR_POLICIES_LABEL);
    private final transient Runnable policyListListener = this::showPolicyList;
    /** S18a (decision N11): whether the autopilot is ON in the running game - what the form opened
     * on, then whatever was last applied. The checkbox alone does not say: ticking it changes
     * nothing until Apply, and a policy does nothing while the autopilot is off. */
    private boolean autopilotLive;
    private final JComboBox<String> difficultyCombo = new JComboBox<>(DIFFICULTIES);
    /** S14: the slider's value is a STEP INDEX into {@link DayLengthSteps}, never seconds. */
    private final JSlider dayLengthSlider = new JSlider(0, DayLengthSteps.count() - 1, 0);
    private final JLabel dayLengthReadout = new JLabel();
    /** S16: on the form only while the day length is locked. */
    private final JLabel dayLengthLockNote = new JLabel(DAY_LENGTH_LOCK_NOTE);
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
    /** S16: the scenario's script is running — the day length cannot be changed from this form. */
    private final boolean dayLengthLocked;

    /** A form whose day length is not locked (free play, or a scenario whose script has ended). */
    SettingsForm(EventBus eventBus, String difficulty, long initialDayLengthSeconds,
                 boolean autopilotEnabled, String llmModel, Runnable onCloseRequest) {
        this(eventBus, difficulty, initialDayLengthSeconds, autopilotEnabled, llmModel, false, onCloseRequest);
    }

    /**
     * @param initialDayLengthSeconds the LIVE {@code SimClock} base rate (so a scenario's pinned
     *                                pacing is reflected, not the stale {@code defaults.json}
     *                                value), shown at its nearest step
     * @param dayLengthLocked         S16: {@code true} while the scenario's scripted events are
     *                                still to come — the slider is then disabled, with a note
     * @param onCloseRequest          run by OK (after Apply) and Cancel; the wrapping dialog disposes
     */
    SettingsForm(EventBus eventBus, String difficulty, long initialDayLengthSeconds,
                 boolean autopilotEnabled, String llmModel, boolean dayLengthLocked, Runnable onCloseRequest) {
        this(eventBus, difficulty, initialDayLengthSeconds, autopilotEnabled, llmModel, dayLengthLocked,
                new PolicyListModel(), onCloseRequest);
    }

    /**
     * @param policies S18a (decision N11): the window's list of the policies in force. The form
     *                 shows it and follows it until {@link #removeNotify()} (the model outlives
     *                 every dialog)
     */
    SettingsForm(EventBus eventBus, String difficulty, long initialDayLengthSeconds,
                 boolean autopilotEnabled, String llmModel, boolean dayLengthLocked,
                 PolicyListModel policies, Runnable onCloseRequest) {
        super(new GridBagLayout());
        this.eventBus = eventBus;
        this.seededDayLength = initialDayLengthSeconds;
        this.dayLengthLocked = dayLengthLocked;
        this.policies = policies;
        this.autopilotLive = autopilotEnabled;
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
        // S16: disabled LAST, once the seed value and the labels are in — a disabled slider
        // still shows where the pace is, greyed, which is the point of leaving it on the form.
        dayLengthSlider.setEnabled(!dayLengthLocked);

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
        if (dayLengthLocked) {
            dayLengthLockNote.setFont(dayLengthLockNote.getFont().deriveFont(java.awt.Font.PLAIN));
            addRow(row++, "", dayLengthLockNote);
        }
        addRow(row++, "Autopilot:", autopilotCheck);
        addRow(row++, "Autopilot policy:", policyRow);
        addRow(row++, "", policyFeedback);
        // S18a (decision N11): what is in force, and the one way to take it back.
        policyList.setFont(policyList.getFont().deriveFont(java.awt.Font.PLAIN));
        clearPoliciesButton.setToolTipText("Forget every autopilot policy. The first policy that "
                + "matches a vessel wins, so an old one can hide a newer one.");
        clearPoliciesButton.addActionListener(e -> eventBus.publish(new PolicyClearRequestEvent()));
        JPanel policyListRow = new JPanel(new java.awt.BorderLayout(6, 0));
        policyListRow.add(policyList, java.awt.BorderLayout.CENTER);
        JPanel clearHolder = new JPanel(new java.awt.BorderLayout());
        clearHolder.add(clearPoliciesButton, java.awt.BorderLayout.NORTH);
        policyListRow.add(clearHolder, java.awt.BorderLayout.EAST);
        addRow(row++, "Active policies:", policyListRow);
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

        showPolicyList();
        policies.addListener(policyListListener);
    }

    /**
     * S18a (decision N11): the list as the model has it now - numbered, because the order is the
     * precedence (the first policy that matches a vessel wins), and said so once there is more
     * than one. Called when the form opens and on every report from the Assistant while it is on
     * screen; a longer list re-packs the dialog so nothing is cut off.
     */
    private void showPolicyList() {
        List<String> rules = policies.descriptions();
        if (rules.isEmpty()) {
            policyList.setText(PolicyListModel.NONE);
        } else {
            StringBuilder html = new StringBuilder("<html><div style='width:420px'>");
            for (int i = 0; i < rules.size(); i++) {
                html.append(i + 1).append(". ").append(escape(rules.get(i))).append("<br>");
            }
            if (rules.size() > 1) {
                html.append("<i>The first one that matches a vessel wins.</i>");
            }
            policyList.setText(html.append("</div></html>").toString());
        }
        clearPoliciesButton.setEnabled(!rules.isEmpty());
        java.awt.Window dialog = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (dialog != null) {
            dialog.pack();
        }
    }

    /**
     * The project's panel lifecycle (INVARIANTS, "EventBus + GUI shell"): attach in the
     * constructor, let go in {@code removeNotify()} - which Swing calls when the dialog the form
     * sits in is disposed, whether by OK, by Cancel or by the window's own close button. The
     * model belongs to the window and outlives every form; a form that kept listening would be
     * kept alive by it, and would re-pack a dialog that is gone.
     */
    @Override
    public void removeNotify() {
        super.removeNotify();
        policies.removeListener(policyListListener);
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
            policyFeedback.setText("<html><div style='width:420px'><b>Policy set.</b> "
                    + escape(rule.description()) + " <i>(" + whenItActs()
                    + " Cancel will not undo it)</i></div></html>");
            policyField.setText("");
        }, () -> showPolicyHint("Not understood."));
    }

    /**
     * S18a (ADR-23, decision N11): what the success line says about WHEN the policy acts. It used
     * to say "active now" always; a policy is consulted by the autopilot only, so with the
     * autopilot off that was false. The answer follows the state that is live in the game, and
     * a ticked-but-unapplied box gets the sentence that says what is still missing.
     */
    private String whenItActs() {
        if (autopilotLive) {
            return "active now —";
        }
        return autopilotCheck.isSelected()
                ? "it takes effect when you press Apply — Autopilot is ticked but not switched on yet."
                : "Autopilot is off, so it does nothing yet — tick Autopilot above and press Apply.";
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
     *
     * <p>S16: while the day length is locked it is ALWAYS the pace the form opened on. The slider
     * is disabled, so the player cannot move it; this is what makes that true of every other way
     * its value could change as well.
     */
    private long effectiveDayLength() {
        if (dayLengthLocked) {
            return seededDayLength;
        }
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
        autopilotLive = autopilotCheck.isSelected(); // S18a: what was just applied is what is live
    }

    private void resetToDefaults() {
        Settings d = Settings.defaults();
        difficultyCombo.setSelectedItem(d.difficulty());
        if (!dayLengthLocked) {
            // S16: a locked day length is not Reset's to change — the default (1 h) is not the
            // scenario's pace, and Reset must not be the one button that gets past the lock.
            dayLengthSlider.setValue(DayLengthSteps.nearestIndex(d.realSecondsPerGameDay()));
            dayLengthTouched = true; // even when the slider was already there: Reset MEANS the default
            updateDayReadout();
        }
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

    /** Test-only: the note under a locked day-length slider, or {@code null} when the form shows
     * none (the day length is not locked). */
    String dayLengthLockNoteForTest() {
        return dayLengthLockNote.getParent() == this ? dayLengthLockNote.getText() : null;
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

    /** S18a: the "Active policies" line as shown ({@code none}, or the numbered HTML list). */
    String policyListForTest() {
        return policyList.getText();
    }

    JButton clearPoliciesButtonForTest() {
        return clearPoliciesButton;
    }
}
