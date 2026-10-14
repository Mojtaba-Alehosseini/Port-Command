package it.unige.portcommand.gui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.PolicyClearRequestEvent;
import it.unige.portcommand.gui.events.PolicyParsedEvent;
import it.unige.portcommand.gui.model.PolicyListModel;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.model.DayLengthSteps;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless test of the settings form (planning/21 §21.6). The form is a plain {@code JPanel}, so
 * it constructs under {@code -Djava.awt.headless=true} where its wrapping {@code SettingsDialog}
 * (a {@code Window}) could not — the same view/model split every other GUI screen uses. Delivery
 * is {@code CALLER_THREAD} so a publish from {@code doClick()} lands synchronously, no EDT flush.
 */
class SettingsFormTest {

    private static SettingsForm form(EventBus bus, List<SettingsChangedEvent> sink) {
        bus.subscribe(SettingsChangedEvent.class, sink::add, DeliveryMode.CALLER_THREAD);
        return new SettingsForm(bus, "NORMAL", 3_600L, false, "microsoft/Phi-4-mini-instruct", () -> { });
    }

    // ==================== Autopilot policy field (audit C-01/D-02, 2026-07-28) ====================
    //
    // Before this field existed, PolicyParser.parse(text, eventBus) — the method that publishes
    // PolicyParsedEvent — had ZERO production call sites: the DSL, the event, the subscribing
    // PolicyParseBehaviour, the registry and its save/load restore were all built and tested, and
    // nothing in the shipped game could put a rule into any of them. DEMO_SCRIPT Beat 4 and
    // DEMO_CHECKLIST Row 6 both said "open Settings, type a policy", and there was no field.

    @Test
    @Timeout(5)
    void aParsedPolicyPublishesItAndEchoesWhatWasUnderstood() {
        EventBus bus = new EventBus();
        List<PolicyParsedEvent> parsed = new ArrayList<>();
        bus.subscribe(PolicyParsedEvent.class, parsed::add, DeliveryMode.CALLER_THREAD);
        SettingsForm form = form(bus, new ArrayList<>());

        form.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        form.clickSetPolicyForTest();

        assertEquals(1, parsed.size(), "a parsed policy must reach the Assistant's subscribed behaviour");
        assertEquals(PolicyParser.GRAMMAR_EXAMPLE, parsed.get(0).rule().trigger());

        // The echo must show what was UNDERSTOOD, not read the input back: the description is built
        // by the parser from the parsed action, condition and canonical vessel type.
        String feedback = form.policyFeedbackForTest();
        assertTrue(feedback.contains("Policy set"), feedback);
        assertTrue(feedback.contains("accept the vessel's offer"), "the parsed ACTION: " + feedback);
        assertTrue(feedback.contains("price &gt; 6000"), "the parsed CONDITION: " + feedback);
        assertTrue(feedback.contains("tankers only"), "the parsed canonical SCOPE: " + feedback);
        assertTrue(form.policyFieldForTest().getText().isEmpty(), "an accepted policy clears the field");
    }

    /**
     * S18a (ADR-23, decision N11) - born red. The success line said "active now" whatever the
     * autopilot was doing. A policy is consulted by the AUTOPILOT only: with the autopilot off it
     * does nothing at all, and the line told the player the opposite. It now reports the state
     * that is LIVE (what the form opened on, or what was last applied) and, when the box has been
     * ticked but not applied, says that pressing Apply is what is missing.
     */
    @Test
    @Timeout(5)
    void theSuccessLineSaysWhetherThePolicyDoesAnythingYet() {
        EventBus bus = new EventBus();

        SettingsForm off = new SettingsForm(bus, "NORMAL", 3_600L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        off.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        off.clickSetPolicyForTest();
        assertFalse(off.policyFeedbackForTest().contains("active now"),
                "the autopilot is off - nothing is active: " + off.policyFeedbackForTest());
        assertTrue(off.policyFeedbackForTest().contains("Autopilot is off, so it does nothing yet"),
                off.policyFeedbackForTest());
        assertTrue(off.policyFeedbackForTest().contains("Cancel will not undo it"), off.policyFeedbackForTest());

        // Ticked, not applied: still not live.
        off.autopilotCheckForTest().setSelected(true);
        off.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        off.clickSetPolicyForTest();
        assertFalse(off.policyFeedbackForTest().contains("active now"), off.policyFeedbackForTest());
        assertTrue(off.policyFeedbackForTest().contains("it takes effect when you press Apply"),
                off.policyFeedbackForTest());

        // Applied: live from here on.
        off.clickApplyForTest();
        off.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        off.clickSetPolicyForTest();
        assertTrue(off.policyFeedbackForTest().contains("active now"), off.policyFeedbackForTest());

        SettingsForm on = new SettingsForm(bus, "NORMAL", 3_600L, true, "microsoft/Phi-4-mini-instruct", () -> { });
        on.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        on.clickSetPolicyForTest();
        assertTrue(on.policyFeedbackForTest().contains("active now"), on.policyFeedbackForTest());
        assertTrue(on.policyFeedbackForTest().contains("Cancel will not undo it"), on.policyFeedbackForTest());
    }

    // ---- S18a (ADR-23, decision N11): the policies in force can be seen, and cleared ----
    // Until S18a the registry was write-only from the player's side. The first policy that
    // matches a vessel wins, so an early mistake silently shadowed every later correction, and
    // nothing on screen said what was in force.

    private static SettingsForm formShowing(EventBus bus, PolicyListModel policies) {
        return new SettingsForm(bus, "NORMAL", 3_600L, true, "microsoft/Phi-4-mini-instruct", false,
                policies, () -> { });
    }

    @Test
    @Timeout(5)
    void withNoPoliciesTheListSaysNoneAndThereIsNothingToClear() {
        SettingsForm form = formShowing(new EventBus(), new PolicyListModel());

        assertEquals("none", form.policyListForTest());
        assertFalse(form.clearPoliciesButtonForTest().isEnabled());
        assertEquals("Clear policies", form.clearPoliciesButtonForTest().getText());
    }

    @Test
    @Timeout(5)
    void theFormOpensOnTheListTheWindowHolds() {
        PolicyListModel policies = new PolicyListModel();
        policies.set(List.of("Automatically accept the vessel's offer when price > 6000 (tankers only)."));

        SettingsForm form = formShowing(new EventBus(), policies);

        assertTrue(form.policyListForTest().contains("1. Automatically accept the vessel's offer when "
                + "price &gt; 6000 (tankers only)."), form.policyListForTest());
        assertFalse(form.policyListForTest().contains("first one that matches"),
                "with one policy there is no precedence to explain");
        assertTrue(form.clearPoliciesButtonForTest().isEnabled());
    }

    /** The order is the precedence, so the list is numbered and says which one wins. */
    @Test
    @Timeout(5)
    void severalPoliciesAreNumberedInTheOrderTheyApply() {
        PolicyListModel policies = new PolicyListModel();
        SettingsForm form = formShowing(new EventBus(), policies);

        policies.set(List.of("accept when price > 6000", "accept when price > 5000 <b>"));

        String list = form.policyListForTest();
        assertTrue(list.contains("1. accept when price &gt; 6000<br>2. accept when price &gt; 5000 &lt;b&gt;<br>"), list);
        assertTrue(list.contains("The first one that matches a vessel wins."), list);
    }

    /** The button ASKS; the list empties when the Assistant answers. Showing "none" at the click
     * would be the form claiming something it does not know. */
    @Test
    @Timeout(5)
    void clearAsksTheAssistantAndTheListEmptiesOnlyWhenItAnswers() {
        EventBus bus = new EventBus();
        List<PolicyClearRequestEvent> asked = new ArrayList<>();
        bus.subscribe(PolicyClearRequestEvent.class, asked::add, DeliveryMode.CALLER_THREAD);
        PolicyListModel policies = new PolicyListModel();
        policies.set(List.of("accept when price > 6000"));
        SettingsForm form = formShowing(bus, policies);
        form.confirmClearForTest(count -> true);

        form.clearPoliciesButtonForTest().doClick();

        assertEquals(1, asked.size());
        assertTrue(form.policyListForTest().contains("1. accept when price"),
                "still listed: the Assistant has not answered yet");

        policies.set(List.of());
        assertEquals("none", form.policyListForTest());
        assertFalse(form.clearPoliciesButtonForTest().isEnabled());
    }

    /**
     * Playtest 4, PT19. Moji's log: a policy registered, then cleared 24 seconds later by this
     * button - the only sender of the clear - with no question and no word in the form, so the
     * reopened Settings looked as if the policy had been lost. A clear now asks first, cannot be
     * fired by a key press meant for something else, and says what it did.
     */
    @Test
    @Timeout(5)
    void aClearIsAskedFirstAndSaysWhatItDid() {
        EventBus bus = new EventBus();
        List<PolicyClearRequestEvent> asked = new ArrayList<>();
        bus.subscribe(PolicyClearRequestEvent.class, asked::add, DeliveryMode.CALLER_THREAD);
        PolicyListModel policies = new PolicyListModel();
        policies.set(List.of("accept when price > 6000", "reject when price < 1000"));
        SettingsForm form = formShowing(bus, policies);
        List<Integer> questions = new ArrayList<>();

        form.confirmClearForTest(count -> {
            questions.add(count);
            return false;
        });
        form.clearPoliciesButtonForTest().doClick();
        assertEquals(List.of(2), questions, "the question names how many policies would go");
        assertTrue(asked.isEmpty(), "a clear the player did not confirm sends nothing");
        assertFalse(form.policyFeedbackForTest().contains("cleared"), form.policyFeedbackForTest());

        form.confirmClearForTest(count -> true);
        form.clearPoliciesButtonForTest().doClick();
        assertEquals(1, asked.size());
        assertTrue(form.policyFeedbackForTest().contains("Policies cleared."), form.policyFeedbackForTest());
    }

    @Test
    @Timeout(5)
    void theClearButtonCannotBeFiredByAKeyPressMeantForSomethingElse() {
        assertFalse(formShowing(new EventBus(), new PolicyListModel()).clearPoliciesButtonForTest().isFocusable());
    }

    /** The model belongs to the window and outlives every dialog; a closed form must not go on
     * being told (and being kept alive) by it. {@code removeNotify()} is what Swing calls when the
     * dialog is disposed - the same lifecycle every panel in the window follows. */
    @Test
    @Timeout(5)
    void aClosedFormStopsFollowingTheList() {
        PolicyListModel policies = new PolicyListModel();
        SettingsForm form = formShowing(new EventBus(), policies);
        assertEquals(1, policies.listenerCount());

        form.removeNotify();

        assertEquals(0, policies.listenerCount());
        policies.set(List.of("accept when price > 6000"));
        assertEquals("none", form.policyListForTest(), "a closed form is no longer updated");
    }

    @Test
    @Timeout(5)
    void anUnparseablePolicyShowsTheGrammarAndAnExampleAndRegistersNothing() {
        EventBus bus = new EventBus();
        List<PolicyParsedEvent> parsed = new ArrayList<>();
        bus.subscribe(PolicyParsedEvent.class, parsed::add, DeliveryMode.CALLER_THREAD);
        SettingsForm form = form(bus, new ArrayList<>());

        // Straight out of the Rasa set_policy training corpus — audit D-03 measured that 0 of its
        // 60 rows parse here, so this is what a player who improvises will actually type.
        form.policyFieldForTest().setText("Auto-accept offers over 2000");
        form.clickSetPolicyForTest();

        assertTrue(parsed.isEmpty(), "nothing may be registered from input that did not parse");
        String feedback = form.policyFeedbackForTest();
        assertTrue(feedback.contains("Not understood"), feedback);
        assertTrue(feedback.contains("fixed grammar"), "the player must be told it is a DSL: " + feedback);
        assertTrue(feedback.contains("rounds_used"), "the grammar itself: " + feedback);
        assertTrue(feedback.contains("auto accept if price &gt; 6000 for tankers"),
                "a working example: " + feedback);
        assertFalse(form.policyFieldForTest().getText().isEmpty(),
                "a rejected policy keeps the text so it can be corrected, not retyped");
    }

    /**
     * Playtest 4, PT20 - born red. A policy that is set clears the field; a second press of Set
     * policy (or Enter) on the now empty field replaced "Policy set." with "Type a policy first."
     * - which read as if the text had not been there at all. Right after a success, an empty
     * field leaves the success line where it is.
     */
    @Test
    @Timeout(5)
    void anEmptyFieldRightAfterASuccessKeepsTheSuccessLine() {
        EventBus bus = new EventBus();
        SettingsForm form = form(bus, new ArrayList<>());
        form.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        form.clickSetPolicyForTest();
        assertTrue(form.policyFeedbackForTest().contains("Policy set"), form.policyFeedbackForTest());

        form.clickSetPolicyForTest();

        assertTrue(form.policyFeedbackForTest().contains("Policy set"),
                "the second press must not erase what the first one did: " + form.policyFeedbackForTest());

        form.policyFieldForTest().setText("accept anything above 2500");
        form.clickSetPolicyForTest();
        assertTrue(form.policyFeedbackForTest().contains("Not understood"), form.policyFeedbackForTest());
        form.policyFieldForTest().setText("");
        form.clickSetPolicyForTest();
        assertTrue(form.policyFeedbackForTest().contains("Type a policy first"),
                "an empty field after anything but a success is still asked for: " + form.policyFeedbackForTest());
    }

    @Test
    @Timeout(5)
    void anEmptyPolicyFieldIsNeverASilentNoOp() {
        EventBus bus = new EventBus();
        List<PolicyParsedEvent> parsed = new ArrayList<>();
        bus.subscribe(PolicyParsedEvent.class, parsed::add, DeliveryMode.CALLER_THREAD);
        SettingsForm form = form(bus, new ArrayList<>());

        form.policyFieldForTest().setText("   ");
        form.clickSetPolicyForTest();

        assertTrue(parsed.isEmpty());
        assertTrue(form.policyFeedbackForTest().contains("Type a policy first"),
                form.policyFeedbackForTest());
    }

    @Test
    @Timeout(5)
    void theOtherFourKnobsAreUnaffectedByAPolicySubmission() {
        // A policy is an ACTION, not a knob: it must not ride the SettingsChangedEvent, and Apply
        // must still publish exactly what the widgets say.
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> settings = new ArrayList<>();
        SettingsForm form = form(bus, settings);

        form.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        form.clickSetPolicyForTest();

        assertTrue(settings.isEmpty(), "setting a policy must not publish a SettingsChangedEvent");
        form.clickApplyForTest();
        assertEquals(1, settings.size());
    }

    @Test
    @Timeout(5)
    void applyPublishesAnEventCarryingTheEditedWidgetValues() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "HARD", 3_600L, false, "google/gemma-3-4b-it", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.dayLengthSliderForTest().setValue(DayLengthSteps.nearestIndex(2_700)); // the 45 min step
        form.autopilotCheckForTest().setSelected(true);
        form.clickApplyForTest();

        assertEquals(1, events.size(), "Apply must publish exactly one SettingsChangedEvent");
        SettingsChangedEvent e = events.get(0);
        assertEquals(2_700L, e.realSecondsPerGameDay(), "the slider publishes SECONDS, not its step index");
        assertTrue(e.autopilotEnabled());
        // S18b (P3, P4): no control for either, so they go out exactly as the form opened on them.
        assertEquals("HARD", e.difficulty());
        assertEquals("google/gemma-3-4b-it", e.llmModel());
    }

    /** S18b (adversarial review): the clear's question defaults to keeping the policies - Enter
     * or Space, the keys the Clear button itself no longer takes, press "Keep", not "Forget". */
    @Test
    void theClearQuestionDefaultsToKeepingThePolicies() {
        javax.swing.JOptionPane question = SettingsForm.clearQuestion(2);

        assertEquals(SettingsForm.KEEP_POLICIES, question.getInitialValue(), "the default button keeps them");
        assertEquals(List.of(SettingsForm.FORGET_POLICIES, SettingsForm.KEEP_POLICIES), List.of(question.getOptions()));
        assertEquals("Forget all 2 autopilot policies? The autopilot stops following them at once.",
                question.getMessage());
        assertEquals("Forget the autopilot policy? The autopilot stops following it at once.",
                SettingsForm.clearQuestion(1).getMessage());
    }

    /** S18b (REMAINING_AREAS_DECISIONS P3, P4): the two controls that changed nothing are gone. */
    @Test
    @Timeout(5)
    void thereIsNoDifficultyAndNoModelToChoose() {
        SettingsForm form = new SettingsForm(new EventBus(), "NORMAL", 3_600L, false,
                "microsoft/Phi-4-mini-instruct", () -> { });
        java.util.List<java.awt.Component> all = new java.util.ArrayList<>();
        java.util.Deque<java.awt.Container> todo = new java.util.ArrayDeque<>(java.util.List.of(form));
        while (!todo.isEmpty()) {
            for (java.awt.Component child : todo.pop().getComponents()) {
                all.add(child);
                if (child instanceof java.awt.Container nested) {
                    todo.push(nested);
                }
            }
        }
        assertTrue(all.stream().noneMatch(c -> c instanceof javax.swing.JComboBox), "no combo box on the form");
        assertTrue(all.stream().filter(c -> c instanceof javax.swing.JLabel).map(c -> ((javax.swing.JLabel) c).getText())
                .noneMatch(t -> t != null && (t.contains("Difficulty") || t.contains("LLM model"))), "and no row for either");
    }

    @Test
    @Timeout(5)
    void resetRestoresDefaultWidgetsAndDoesNotPublishUntilApplied() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "HARD", 600L, true, "google/gemma-3-4b-it", () -> { });
        long defaultDay = Settings.defaults().realSecondsPerGameDay();
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.resetToDefaultsForTest();

        assertEquals(DayLengthSteps.nearestIndex(defaultDay), form.dayLengthSliderForTest().getValue());
        assertFalse(form.autopilotCheckForTest().isSelected());
        assertTrue(events.isEmpty(), "Reset must not publish — only Apply/OK do");

        form.clickApplyForTest();
        assertEquals(1, events.size());
        // S18b (P3, P4): Reset touches only what the form shows; the two hidden values are kept.
        assertEquals("HARD", events.get(0).difficulty());
        assertEquals("google/gemma-3-4b-it", events.get(0).llmModel());
        assertEquals(defaultDay, events.get(0).realSecondsPerGameDay());
        assertEquals(3_600L, defaultDay, "S14: the default day is 60 real minutes");
    }

    // ==================== Day length: eight fixed steps (S14) ====================

    @Test
    @Timeout(5)
    void theSliderHasExactlyTheEightStepsAndOpensOnTheLivePace() {
        EventBus bus = new EventBus();
        SettingsForm form = new SettingsForm(bus, "EASY", 1_800L, false, "microsoft/Phi-4-mini-instruct", () -> { });

        assertEquals(0, form.dayLengthSliderForTest().getMinimum());
        assertEquals(DayLengthSteps.count() - 1, form.dayLengthSliderForTest().getMaximum());
        assertTrue(form.dayLengthSliderForTest().getSnapToTicks(), "no position between two steps");
        assertEquals(DayLengthSteps.nearestIndex(1_800), form.dayLengthSliderForTest().getValue(),
                "a scenario's 30-minute day is one of the steps, so the slider shows it exactly");
        assertTrue(form.dayLengthReadoutForTest().startsWith("30 min"), form.dayLengthReadoutForTest());
    }

    @Test
    @Timeout(5)
    void everyStepPublishesItsOwnSeconds() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = form(bus, events);
        long[] expected = {300, 600, 900, 1_800, 2_700, 3_600, 5_400, 7_200};

        for (int i = 0; i < expected.length; i++) {
            form.dayLengthSliderForTest().setValue(i);
            form.clickApplyForTest();
            assertEquals(expected[i], events.get(events.size() - 1).realSecondsPerGameDay(), "step " + i);
        }
    }

    @Test
    @Timeout(5)
    void theReadoutNamesTheStepAndMarksTheDefault() {
        EventBus bus = new EventBus();
        SettingsForm form = form(bus, new ArrayList<>());
        assertEquals("1 h per game day (default)", form.dayLengthReadoutForTest());

        form.dayLengthSliderForTest().setValue(DayLengthSteps.nearestIndex(5_400));

        assertEquals("1.5 h per game day", form.dayLengthReadoutForTest());
    }

    /**
     * Adversarial finding 1 (2026-07-18), restated for the stepped slider: opening Settings on a
     * pace that is NOT one of the steps (a {@code --day-length} test pace) and clicking Apply
     * without touching the slider must re-publish the true pace — not the nearest step the slider
     * happens to show — so merely toggling another knob never silently re-paces the clock.
     */
    @Test
    @Timeout(5)
    void anOffStepPaceIsPreservedWhenTheDayLengthSliderIsUntouched() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "EASY", 75L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);
        assertEquals(0, form.dayLengthSliderForTest().getValue(), "shown at its nearest step, 5 min");
        assertTrue(form.dayLengthReadoutForTest().startsWith("75 s"),
                "but the readout says what is really in force: " + form.dayLengthReadoutForTest());

        form.autopilotCheckForTest().setSelected(true); // change a DIFFERENT knob only
        form.clickApplyForTest();

        assertEquals(75L, events.get(0).realSecondsPerGameDay(),
                "an untouched day-length slider must preserve the live pace, not snap it to a step");
        assertTrue(events.get(0).autopilotEnabled());
    }

    @Test
    @Timeout(5)
    void scenarioPacingIsPreservedWhenTheDayLengthSliderIsUntouched() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "EASY", 1_800L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.autopilotCheckForTest().setSelected(true);
        form.clickApplyForTest();

        assertEquals(1_800L, events.get(0).realSecondsPerGameDay());
    }

    @Test
    @Timeout(5)
    void movingTheSliderOverridesTheLivePace() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "EASY", 75L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.dayLengthSliderForTest().setValue(DayLengthSteps.nearestIndex(900)); // explicit override
        form.clickApplyForTest();

        assertEquals(900L, events.get(0).realSecondsPerGameDay(),
                "moving the slider is an explicit override and must publish the chosen step");
    }

    @Test
    @Timeout(5)
    void resetMeansTheDefaultEvenWhenTheSliderAlreadySitsOnItsStep() {
        // 4000 s shows at the 1 h step — the default's own position. Reset moves nothing on
        // screen, and must still publish 3600, not the 4000 the form opened on.
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "NORMAL", 4_000L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.resetToDefaultsForTest();
        form.clickApplyForTest();

        assertEquals(3_600L, events.get(0).realSecondsPerGameDay());
        assertEquals("1 h per game day (default)", form.dayLengthReadoutForTest());
    }

    // ==================== Day length locked while a scenario's script runs (S16) ====================
    //
    // 2026-10-13, PLAYABILITY §10 / P12. A scenario's scripted events are placed in game time and
    // written for the scenario's own pace; a shorter day squeezes the real time between two scripted
    // hails exactly as a higher speed does. So while scripted events are still to come the form is
    // built with dayLengthLocked = true: the slider is disabled and says why, nothing the form
    // publishes can change the pace, and Reset is not a way round it. Autopilot is NOT locked.

    /** A form opened mid-script on a scenario's 15-minute day. */
    private static SettingsForm lockedForm(EventBus bus, List<SettingsChangedEvent> sink) {
        bus.subscribe(SettingsChangedEvent.class, sink::add, DeliveryMode.CALLER_THREAD);
        return new SettingsForm(bus, "HARD", 900L, true, "google/gemma-3-4b-it", true, () -> { });
    }

    @Test
    @Timeout(5)
    void whileLockedTheDayLengthSliderIsDisabledAndANoteSaysWhy() {
        SettingsForm form = lockedForm(new EventBus(), new ArrayList<>());

        assertFalse(form.dayLengthSliderForTest().isEnabled(), "the player cannot move a locked day length");
        assertEquals("Day length is locked while the scenario's scripted events are running",
                form.dayLengthLockNoteForTest());
        assertEquals(DayLengthSteps.nearestIndex(900), form.dayLengthSliderForTest().getValue(),
                "the disabled slider still shows where the pace is");
        assertEquals("15 min per game day", form.dayLengthReadoutForTest());

        // Only the day length is locked.
        assertTrue(form.autopilotCheckForTest().isEnabled(), "autopilot is not locked by the script");
    }

    @Test
    @Timeout(5)
    void whileLockedApplyPublishesThePaceTheFormOpenedOnAndTheOtherKnobsAsEdited() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = lockedForm(bus, events);

        // No mouse can do this to a disabled slider; whatever else might must not get a new pace out.
        form.dayLengthSliderForTest().setValue(DayLengthSteps.nearestIndex(7_200));
        form.autopilotCheckForTest().setSelected(false);
        form.clickApplyForTest();

        assertEquals(1, events.size());
        assertEquals(900L, events.get(0).realSecondsPerGameDay(), "the day length the form opened on, untouched");
        assertFalse(events.get(0).autopilotEnabled(), "autopilot is applied as edited: it is not locked");
        assertEquals("HARD", events.get(0).difficulty(), "S18b: the value the form opened on");
        assertEquals("15 min per game day", form.dayLengthReadoutForTest(),
                "and the readout keeps saying what is in force");
    }

    @Test
    @Timeout(5)
    void whileLockedResetLeavesTheDayLengthAloneAndResetsTheRest() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = lockedForm(bus, events);
        int sliderBefore = form.dayLengthSliderForTest().getValue();
        assertNotEquals(DayLengthSteps.nearestIndex(Settings.defaults().realSecondsPerGameDay()), sliderBefore,
                "fixture: the scenario's pace is not the default, so a reset of it would show");

        form.resetToDefaultsForTest();

        assertEquals(sliderBefore, form.dayLengthSliderForTest().getValue(), "Reset is not a way round the lock");
        assertEquals("15 min per game day", form.dayLengthReadoutForTest());
        assertFalse(form.autopilotCheckForTest().isSelected(), "the other knob is reset");
        assertTrue(events.isEmpty(), "Reset still publishes nothing");

        form.clickApplyForTest();
        assertEquals(900L, events.get(0).realSecondsPerGameDay(), "not the default 3600 s");
        assertEquals("HARD", events.get(0).difficulty(), "S18b (P4): no control, so Reset does not touch it");
    }

    @Test
    @Timeout(5)
    void anOffStepPaceIsKeptUnderTheLockToo() {
        // A --day-length test pace under a running script: shown at its nearest step, published as itself.
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);
        SettingsForm form = new SettingsForm(bus, "NORMAL", 75L, false, "microsoft/Phi-4-mini-instruct", true,
                () -> { });

        form.resetToDefaultsForTest();
        form.clickApplyForTest();

        assertEquals(75L, events.get(0).realSecondsPerGameDay());
    }

    @Test
    @Timeout(5)
    void whenNotLockedTheSliderIsEnabledAndThereIsNoNote() {
        // The six-argument form — free play, or a scenario whose script has ended — is the form
        // every test above this block drives: nothing about it changed in S16.
        SettingsForm form = form(new EventBus(), new ArrayList<>());

        assertTrue(form.dayLengthSliderForTest().isEnabled());
        assertNull(form.dayLengthLockNoteForTest(), "no lock, no note on the form");

        // And the explicit "not locked" of the seven-argument form is the same thing.
        SettingsForm explicit = new SettingsForm(new EventBus(), "NORMAL", 3_600L, false,
                "microsoft/Phi-4-mini-instruct", false, () -> { });
        assertTrue(explicit.dayLengthSliderForTest().isEnabled());
        assertNull(explicit.dayLengthLockNoteForTest());
    }
}
