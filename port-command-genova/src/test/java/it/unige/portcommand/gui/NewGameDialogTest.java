package it.unige.portcommand.gui;

import java.awt.Dimension;
import java.util.List;
import java.util.Locale;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.gui.model.NewGameChoice;
import it.unige.portcommand.scenario.Scenario;
import it.unige.portcommand.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the New Game dialog's detail label (S12, live playtest F-3: the second line of a selected
 * scenario's detail, the target revenue, was cut off). The dialog is a {@code JDialog}, which
 * cannot be constructed under {@code -Djava.awt.headless=true} (both Gradle test lanes set it), so
 * its sizing lives in two package-private statics and this test measures plain {@code JLabel}s,
 * which construct and report preferred sizes headless. Measured against the SHIPPED scenarios, so
 * a longer description authored later is covered too.
 *
 * <p>S15 (2026-10-12): the dialog gained a fourth button, <b>Free play</b>. Its buttons are built
 * from the pure {@code NewGameDialog.options(...)} list, so what the dialog offers — and what each
 * button means when Start is pressed — is pinned here without the dialog.
 */
class NewGameDialogTest {

    private final ScenarioRegistry registry = new ScenarioRegistry();

    /** A label bordered like the dialog's detail, showing {@code text}. */
    private static JLabel labelLike(JLabel detail, String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setBorder(detail.getBorder());
        return label;
    }

    @Test
    void theDetailLabelReservesRoomForEveryScenariosDetailBeforeAnySelection() {
        List<Scenario> scenarios = registry.all();
        JLabel detail = NewGameDialog.detailLabel(scenarios);
        Dimension reserved = detail.getPreferredSize();
        // Pre-fix, the single pack() sized the centre slot around exactly this blank label.
        int oneLine = labelLike(detail, " ").getPreferredSize().height;

        assertEquals(" ", detail.getText(), "nothing is selected yet when the dialog packs");
        assertTrue(reserved.height > oneLine, "non-vacuity: reserved " + reserved.height
                + " px, the old one-line slot was " + oneLine);
        for (Scenario scenario : scenarios) {
            Dimension need = labelLike(detail, NewGameDialog.detailHtml(scenario)).getPreferredSize();
            assertTrue(need.height > oneLine, "non-vacuity: " + scenario.key() + "'s detail needs "
                    + need.height + " px, more than the old one-line slot's " + oneLine);
            assertTrue(reserved.width >= need.width && reserved.height >= need.height,
                    scenario.key() + " needs " + need + ", reserved " + reserved);
        }
    }

    @Test
    void aSelectionDoesNotChangeThePackedSize() {
        List<Scenario> scenarios = registry.all();
        JLabel detail = NewGameDialog.detailLabel(scenarios);
        Dimension reserved = detail.getPreferredSize();

        for (Scenario scenario : scenarios) {
            detail.setText(NewGameDialog.detailHtml(scenario)); // what the toggle's action does
            assertEquals(reserved, detail.getPreferredSize(),
                    scenario.key() + ": a selection must not change the packed size");
        }
    }

    @Test
    void theTargetRevenueHasItsOwnLineAndIgnoresTheDefaultLocale() {
        Scenario tutorial = registry.findByKey("tutorial").orElseThrow();
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            // German groups thousands with '.', like a Danish or Italian desktop; under a
            // comma-grouping default the literal below would pass even without Locale.ROOT.
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("3.500", String.format("%,.0f", 3500.0),
                    "non-vacuity: the default locale now groups with '.'");

            String html = NewGameDialog.detailHtml(tutorial);
            assertTrue(html.contains(tutorial.description() + "<br><i>Target daily revenue: €3,500</i>"),
                    html);
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    // ==================== S15: Free play, the fourth choice ====================

    @Test
    void theDialogOffersTheThreeScenariosInRegistryOrderAndThenFreePlay() {
        List<Scenario> scenarios = registry.all();
        List<NewGameDialog.Option> options = NewGameDialog.options(scenarios);

        assertEquals(3, scenarios.size(), "fixture: the registry ships three scenarios");
        assertEquals(4, options.size(), "three scenarios and Free play");
        for (int i = 0; i < scenarios.size(); i++) {
            Scenario scenario = scenarios.get(i);
            NewGameDialog.Option option = options.get(i);
            assertEquals(scenario.displayName(), option.label(), "button " + (i + 1));
            assertEquals(scenario.description(), option.tooltip());
            assertEquals(NewGameDialog.detailHtml(scenario), option.detailHtml());
            assertEquals(NewGameChoice.scenario(scenario.key()), option.choice());
            assertFalse(option.choice().isFreePlay(), scenario.key() + " is a scenario, not free play");
        }
        assertEquals(ScenarioRegistry.KEYS,
                options.subList(0, 3).stream().map(option -> option.choice().scenarioKey()).toList(),
                "the scenario buttons keep the registry's order, the tutorial first");
    }

    @Test
    void freePlayIsTheFourthOptionAndChoosingItIsNotAScenario() {
        NewGameDialog.Option freePlay = NewGameDialog.options(registry.all()).get(3);

        assertEquals("Free play", freePlay.label());
        assertTrue(freePlay.choice().isFreePlay(), "choosing it must start the sandbox, not a scenario");
        assertEquals(NewGameChoice.freePlay(), freePlay.choice());
        assertNull(freePlay.choice().scenarioKey(), "free play is not a fourth scenario key");
        assertFalse(ScenarioRegistry.KEYS.contains(freePlay.label()), "and the registry stays at its three keys");
        assertEquals(NewGameDialog.freePlayDetailHtml(), freePlay.detailHtml());
        assertEquals(NewGameDialog.FREE_PLAY_DESCRIPTION, freePlay.tooltip());
    }

    @Test
    void freePlayStillComesLastWhenTheDialogIsGivenNoScenarios() {
        // Not a production state (the registry always has its three) — but the list is a pure
        // function, and "Free play is always there, always last" should not depend on that.
        List<NewGameDialog.Option> options = NewGameDialog.options(List.of());

        assertEquals(1, options.size());
        assertTrue(options.get(0).choice().isFreePlay());
    }

    @Test
    void theFreePlayDetailSaysWhatItIsAndWhatThePortStartsWith() {
        String html = NewGameDialog.freePlayDetailHtml();

        assertEquals("Open-ended sandbox: contracted vessels arrive every day, walk-ins hail at random. "
                + "No script.", NewGameDialog.FREE_PLAY_DESCRIPTION);
        assertEquals("<html><center>" + NewGameDialog.FREE_PLAY_DESCRIPTION
                + "<br><i>Starts with €50,000 · reputation 70</i></center></html>", html);
        // The two figures are the sandbox's real opening state, not numbers typed into the dialog.
        assertEquals(50_000.0, AgentRoster.STARTING_WALLET, 0.0, "the line above quotes this");
        assertEquals(70.0, AgentRoster.STARTING_REPUTATION, 0.0, "and this");
    }

    @Test
    void theFreePlayDetailIgnoresTheDefaultLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("50.000", String.format("%,.0f", 50_000.0),
                    "non-vacuity: the default locale now groups with '.'");

            String html = NewGameDialog.freePlayDetailHtml();
            assertTrue(html.contains("<br><i>Starts with €50,000 · reputation 70</i>"), html);
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    void theDetailLabelReservesRoomForTheFreePlayDetailToo() {
        List<Scenario> scenarios = registry.all();
        JLabel detail = NewGameDialog.detailLabel(scenarios);
        Dimension reserved = detail.getPreferredSize();
        int oneLine = labelLike(detail, " ").getPreferredSize().height;

        Dimension need = labelLike(detail, NewGameDialog.freePlayDetailHtml()).getPreferredSize();
        assertTrue(need.height > oneLine, "non-vacuity: the Free play detail needs " + need.height
                + " px, more than a one-line slot's " + oneLine);
        assertTrue(reserved.width >= need.width && reserved.height >= need.height,
                "Free play needs " + need + ", reserved " + reserved);

        detail.setText(NewGameDialog.freePlayDetailHtml()); // what the fourth toggle's action does
        assertEquals(reserved, detail.getPreferredSize(), "selecting Free play must not change the packed size");
    }

    @Test
    void theReserveIsMeasuredOverFreePlayEvenWithNoScenarioToCompareWith() {
        // With no scenario in the list the reserve can only have come from the Free play detail:
        // this is what proves detailLabel() really measures it, whichever detail is the largest
        // among the shipped ones.
        JLabel detail = NewGameDialog.detailLabel(List.of());

        Dimension need = labelLike(detail, NewGameDialog.freePlayDetailHtml()).getPreferredSize();
        assertEquals(need, detail.getPreferredSize());
    }
}
