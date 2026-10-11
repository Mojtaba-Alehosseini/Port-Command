package it.unige.portcommand.gui.model;

import it.unige.portcommand.gui.model.PatienceCounterModel.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** S14: the walk-in tab's live counter — {@code m:ss} and three colour levels, nothing else. */
class PatienceCounterModelTest {

    @ParameterizedTest
    @CsvSource({
            "240000, 4:00",
            "239999, 4:00", // rounds UP: the display reads 4:00 for the whole first second
            "239000, 3:59",
            "150000, 2:30",
            "61000,  1:01",
            "60000,  1:00",
            "59001,  1:00",
            "59000,  0:59",
            "9000,   0:09",
            "1,      0:01", // one millisecond left is not yet zero
            "0,      0:00",
            "-500,   0:00",
            "300000, 5:00",
    })
    void formatsAsMinutesAndTwoDigitSeconds(long remainingMillis, String expected) {
        assertEquals(expected, PatienceCounterModel.text(remainingMillis));
    }

    @ParameterizedTest
    @CsvSource({
            "240000, NORMAL",
            "30000,  NORMAL",   // exactly 0:30 is not yet under 30 s
            "29001,  NORMAL",   // ...and still DISPLAYS 0:30
            "29000,  WARNING",  // 0:29: orange
            "10000,  WARNING",  // 0:10
            "9001,   WARNING",  // still displays 0:10
            "9000,   CRITICAL", // 0:09: red
            "1,      CRITICAL",
            "0,      CRITICAL",
    })
    void colourLevelFollowsTheDisplayedSeconds(long remainingMillis, Level expected) {
        assertEquals(expected, PatienceCounterModel.level(remainingMillis));
    }

    @Test
    void theThresholdsAreThirtyAndTenSeconds() {
        assertEquals(30, PatienceCounterModel.WARNING_BELOW_SECONDS);
        assertEquals(10, PatienceCounterModel.CRITICAL_BELOW_SECONDS);
    }

    @Test
    void theColourChangesOnTheSameTickTheNumberDoes() {
        // Walking down millisecond by millisecond across both thresholds: the level may only
        // change at an instant where the displayed text changes too.
        String previousText = PatienceCounterModel.text(31_000);
        Level previousLevel = PatienceCounterModel.level(31_000);
        for (long ms = 31_000; ms >= 8_000; ms--) {
            String text = PatienceCounterModel.text(ms);
            Level level = PatienceCounterModel.level(ms);
            if (level != previousLevel) {
                assertNotEquals(previousText, text,
                        "level changed to " + level + " at " + ms + " ms while the display stayed " + text);
            }
            previousText = text;
            previousLevel = level;
        }
        assertEquals(Level.CRITICAL, previousLevel, "the walk must actually have crossed both thresholds");
    }
}
