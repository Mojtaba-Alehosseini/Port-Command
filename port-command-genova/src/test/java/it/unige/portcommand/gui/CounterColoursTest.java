package it.unige.portcommand.gui;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import it.unige.portcommand.gui.model.PatienceCounterModel.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S15 (2026-10-12): the walk-in counter is drawn in two places now — the tab and the "Waiting for
 * you" list — and the two must never disagree about a colour. {@link CounterColours} is the one
 * definition; {@code DialogueTabView.COUNTER_*} (kept for {@code DialogueCountdownTest}) must be
 * the same three colours, not three look-alikes.
 */
class CounterColoursTest {

    @Test
    void theTabsCounterColoursAreTheSharedOnes() {
        assertEquals(DialogueTabView.COUNTER_NORMAL, CounterColours.NORMAL);
        assertEquals(DialogueTabView.COUNTER_WARNING, CounterColours.WARNING);
        assertEquals(DialogueTabView.COUNTER_CRITICAL, CounterColours.CRITICAL);
    }

    @Test
    void eachLevelMapsToItsColour() {
        assertEquals(CounterColours.NORMAL, CounterColours.of(Level.NORMAL));
        assertEquals(CounterColours.WARNING, CounterColours.of(Level.WARNING));
        assertEquals(CounterColours.CRITICAL, CounterColours.of(Level.CRITICAL));

        assertEquals(DialogueTabView.COUNTER_NORMAL, CounterColours.of(Level.NORMAL));
        assertEquals(DialogueTabView.COUNTER_WARNING, CounterColours.of(Level.WARNING), "orange under 30 s");
        assertEquals(DialogueTabView.COUNTER_CRITICAL, CounterColours.of(Level.CRITICAL), "red under 10 s");
    }

    @Test
    void everyLevelHasAColourOfItsOwn() {
        // The colour is the only thing that tells the three levels apart on screen.
        Set<Color> seen = new HashSet<>();
        for (Level level : Level.values()) {
            Color colour = CounterColours.of(level);
            assertNotNull(colour, level + " has no colour");
            seen.add(colour);
        }
        assertEquals(Level.values().length, seen.size(), "two levels share a colour");
    }

    @Test
    void theColoursAreTheOnesS14Chose() {
        // Dark grey, orange, red — unchanged by moving them out of the tab view.
        assertEquals(new Color(0x33, 0x33, 0x33), CounterColours.NORMAL);
        assertEquals(new Color(0xE0, 0x7B, 0x00), CounterColours.WARNING);
        assertEquals(new Color(0xC6, 0x28, 0x28), CounterColours.CRITICAL);
    }
}
