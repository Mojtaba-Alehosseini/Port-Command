package it.unige.portcommand.gui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return new SettingsForm(bus, "NORMAL", 300L, false, "microsoft/Phi-4-mini-instruct", () -> { });
    }

    @Test
    @Timeout(5)
    void applyPublishesAnEventCarryingTheEditedWidgetValues() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = form(bus, events);

        form.difficultyComboForTest().setSelectedItem("HARD");
        form.dayLengthSliderForTest().setValue(540);
        form.autopilotCheckForTest().setSelected(true);
        form.llmComboForTest().setSelectedItem("google/gemma-3-4b-it");
        form.clickApplyForTest();

        assertEquals(1, events.size(), "Apply must publish exactly one SettingsChangedEvent");
        SettingsChangedEvent e = events.get(0);
        assertEquals("HARD", e.difficulty());
        assertEquals(540L, e.realSecondsPerGameDay());
        assertTrue(e.autopilotEnabled());
        assertEquals("google/gemma-3-4b-it", e.llmModel());
    }

    @Test
    @Timeout(5)
    void resetRestoresDefaultWidgetsAndDoesNotPublishUntilApplied() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "HARD", 600L, true, "google/gemma-3-4b-it", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.resetToDefaultsForTest();

        assertEquals("NORMAL", form.difficultyComboForTest().getSelectedItem());
        assertEquals(300, form.dayLengthSliderForTest().getValue());
        assertFalse(form.autopilotCheckForTest().isSelected());
        assertEquals("microsoft/Phi-4-mini-instruct", form.llmComboForTest().getSelectedItem());
        assertTrue(events.isEmpty(), "Reset must not publish — only Apply/OK do");

        form.clickApplyForTest();
        assertEquals(1, events.size());
        assertEquals("NORMAL", events.get(0).difficulty());
        assertEquals(300L, events.get(0).realSecondsPerGameDay());
    }

    @Test
    @Timeout(5)
    void dayLengthSliderIsClampedIntoTheSandboxRangeOnOpen() {
        EventBus bus = new EventBus();
        // A scenario's 1800 s/day pin exceeds the 180–600 slider; the form clamps for display and
        // the readout still shows a real value, rather than throwing on setValue out of range.
        SettingsForm form = new SettingsForm(bus, "EASY", 1800L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        assertEquals(SettingsForm.DAY_MAX_SECONDS, form.dayLengthSliderForTest().getValue());
    }

    /**
     * Adversarial finding 1 (2026-07-18): opening Settings mid-scenario (1800 s/day) and clicking
     * Apply WITHOUT touching the day-length slider must re-publish the true 1800 — NOT the clamped
     * 600 the slider shows — so merely toggling another knob never silently re-paces the clock.
     */
    @Test
    @Timeout(5)
    void scenarioPacingIsPreservedWhenTheDayLengthSliderIsUntouched() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "EASY", 1800L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.autopilotCheckForTest().setSelected(true); // change a DIFFERENT knob only
        form.clickApplyForTest();

        assertEquals(1800L, events.get(0).realSecondsPerGameDay(),
                "an untouched day-length slider must preserve the scenario's pinned pacing, not the clamp");
        assertTrue(events.get(0).autopilotEnabled());
    }

    @Test
    @Timeout(5)
    void draggingTheSliderOverridesTheScenarioPace() {
        EventBus bus = new EventBus();
        List<SettingsChangedEvent> events = new ArrayList<>();
        SettingsForm form = new SettingsForm(bus, "EASY", 1800L, false, "microsoft/Phi-4-mini-instruct", () -> { });
        bus.subscribe(SettingsChangedEvent.class, events::add, DeliveryMode.CALLER_THREAD);

        form.dayLengthSliderForTest().setValue(400); // explicit override
        form.clickApplyForTest();

        assertEquals(400L, events.get(0).realSecondsPerGameDay(),
                "moving the slider is an explicit override and must publish the slider value");
    }
}
