package it.unige.portcommand.gui.model;

import it.unige.portcommand.core.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S14: the Settings day-length control's eight fixed steps (5 min … 2 h). */
class DayLengthStepsTest {

    @Test
    void theEightStepsAreExactlyTheOnesDecided() {
        long[] expected = {300, 600, 900, 1_800, 2_700, 3_600, 5_400, 7_200};
        String[] labels = {"5 min", "10 min", "15 min", "30 min", "45 min", "1 h", "1.5 h", "2 h"};

        assertEquals(expected.length, DayLengthSteps.count());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], DayLengthSteps.secondsAt(i), "step " + i);
            assertEquals(labels[i], DayLengthSteps.label(expected[i]), "label of step " + i);
            assertEquals(i, DayLengthSteps.nearestIndex(expected[i]), "a step is its own nearest step");
            assertTrue(DayLengthSteps.isStep(expected[i]));
        }
    }

    @Test
    void everyShippedPaceIsAStep() {
        // The sandbox default and both scenario bases: opening Settings never misrepresents them.
        assertTrue(DayLengthSteps.isStep(Settings.defaults().realSecondsPerGameDay()));
        assertTrue(DayLengthSteps.isStep(900));
        assertTrue(DayLengthSteps.isStep(1_800));
    }

    @Test
    void anOffStepPaceReadsAsItsNearestStepButIsNotAStep() {
        assertFalse(DayLengthSteps.isStep(75), "a --day-length test pace");
        assertEquals(0, DayLengthSteps.nearestIndex(75), "below the range: the shortest step");
        assertEquals(7, DayLengthSteps.nearestIndex(50_000), "above the range: the longest step");
        assertEquals(5, DayLengthSteps.nearestIndex(4_000), "4000 s is nearer 1 h than 1.5 h");
        assertEquals(0, DayLengthSteps.nearestIndex(450), "a tie goes to the shorter day");
        assertEquals("75 s", DayLengthSteps.label(75));
    }
}
