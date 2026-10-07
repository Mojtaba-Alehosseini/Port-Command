package it.unige.portcommand.gui;

import java.awt.Dimension;
import java.util.List;
import java.util.Locale;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import it.unige.portcommand.scenario.Scenario;
import it.unige.portcommand.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the New Game dialog's detail label (S12, live playtest F-3: the second line of a selected
 * scenario's detail, the target revenue, was cut off). The dialog is a {@code JDialog}, which
 * cannot be constructed under {@code -Djava.awt.headless=true} (both Gradle test lanes set it), so
 * its sizing lives in two package-private statics and this test measures plain {@code JLabel}s,
 * which construct and report preferred sizes headless. Measured against the SHIPPED scenarios, so
 * a longer description authored later is covered too.
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
}
