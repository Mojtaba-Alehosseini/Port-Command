package it.unige.portcommand.gui.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the divider arithmetic of the three-column window. The numbers are the real
 * ones — a 1280 px window leaves the outer split a span of 1270 (one 10 px divider); the left
 * column's minimum is 180, the chat's and the comm log's 380 each, so the inner split's own
 * minimum is 380 + 10 + 380 = 770.
 */
class ColumnDividersTest {

    private static final int OUTER_SPAN = 1270;
    private static final int LEFT_MIN = 180;
    private static final int COLUMN_MIN = 380;
    private static final int INNER_MIN = COLUMN_MIN + 10 + COLUMN_MIN;

    @Test
    void theDefaultFifthOfADefaultWindowIs254Pixels() {
        assertEquals(254, ColumnDividers.location(OUTER_SPAN, 0.20, LEFT_MIN, INNER_MIN));
    }

    @Test
    void theInnerSplitHalvesItsSpan() {
        int innerSpan = OUTER_SPAN - 254 - 10;
        assertEquals(503, ColumnDividers.location(innerSpan, 0.50, COLUMN_MIN, COLUMN_MIN));
    }

    @Test
    void aLargeProportionIsClampedSoTheRightSideKeepsItsMinimum() {
        // 0.9 of 1270 would be 1143, leaving the two right columns 127 px between them.
        assertEquals(OUTER_SPAN - INNER_MIN, ColumnDividers.location(OUTER_SPAN, 0.90, LEFT_MIN, INNER_MIN));
        assertEquals(OUTER_SPAN - INNER_MIN, ColumnDividers.location(OUTER_SPAN, 0.95, LEFT_MIN, INNER_MIN));
        assertEquals(OUTER_SPAN - INNER_MIN, ColumnDividers.location(OUTER_SPAN, 7.0, LEFT_MIN, INNER_MIN));
    }

    @Test
    void aTinyProportionIsClampedToTheLeftMinimum() {
        assertEquals(LEFT_MIN, ColumnDividers.location(OUTER_SPAN, 0.01, LEFT_MIN, INNER_MIN));
        assertEquals(LEFT_MIN, ColumnDividers.location(OUTER_SPAN, 0.0, LEFT_MIN, INNER_MIN));
        assertEquals(LEFT_MIN, ColumnDividers.location(OUTER_SPAN, -3.0, LEFT_MIN, INNER_MIN));
    }

    @Test
    void aSpanThatExactlyHoldsBothMinimumsHasOneAnswer() {
        int exact = LEFT_MIN + INNER_MIN;
        assertEquals(LEFT_MIN, ColumnDividers.location(exact, 0.20, LEFT_MIN, INNER_MIN));
        assertEquals(LEFT_MIN, ColumnDividers.location(exact, 0.90, LEFT_MIN, INNER_MIN));
    }

    @ParameterizedTest
    @CsvSource({
            "949, 180, 770",
            "600, 180, 770",
            "475, 180, 770",
            "100, 180, 770",
            "1,   180, 770",
            "500, 380, 380",
            "11,  380, 380",
    })
    void aSpanTooSmallForBothMinimumsDegradesWithoutGoingNegative(int span, int minLeft, int minRight) {
        for (double proportion : new double[] {0.0, 0.2, 0.5, 0.9, 1.0}) {
            int location = ColumnDividers.location(span, proportion, minLeft, minRight);

            assertTrue(location >= 0, "left side not negative: " + location);
            assertTrue(span - location >= 0, "right side not negative: " + (span - location));
            assertTrue(location <= minLeft, "the left side gives way too: " + location);
            assertTrue(span - location <= minRight, "the right side gives way too: " + (span - location));
        }
    }

    @Test
    void whenTooSmallBothSidesGiveWayInProportionToTheirMinimums() {
        // 475 is half of 180 + 770, so each side gets half its minimum.
        assertEquals(90, ColumnDividers.location(475, 0.20, 180, 770));
        assertEquals(250, ColumnDividers.location(500, 0.50, 380, 380), "equal minimums split evenly");
        assertEquals(250, ColumnDividers.location(500, 0.90, 380, 380), "the stored proportion no longer matters");
    }

    @Test
    void anEmptyOrNegativeSpanGivesZero() {
        assertEquals(0, ColumnDividers.location(0, 0.5, LEFT_MIN, INNER_MIN));
        assertEquals(0, ColumnDividers.location(-40, 0.5, LEFT_MIN, INNER_MIN));
        assertEquals(0, ColumnDividers.location(0, 0.5, 0, 0));
    }

    @Test
    void negativeMinimumsCountAsNone() {
        assertEquals(500, ColumnDividers.location(1000, 0.5, -50, -50));
        assertEquals(100, ColumnDividers.location(1000, 0.0, -50, -50), "only the 0.1..0.9 band applies");
        assertEquals(900, ColumnDividers.location(1000, 1.0, -50, -50));
    }

    @Test
    void aProportionOutsideTheBandOrNotANumberIsBroughtBack() {
        assertEquals(0.1, ColumnDividers.clampProportion(0.0), 1e-12);
        assertEquals(0.1, ColumnDividers.clampProportion(-2.0), 1e-12);
        assertEquals(0.9, ColumnDividers.clampProportion(1.0), 1e-12);
        assertEquals(0.9, ColumnDividers.clampProportion(Double.POSITIVE_INFINITY), 1e-12);
        assertEquals(0.1, ColumnDividers.clampProportion(Double.NEGATIVE_INFINITY), 1e-12);
        assertEquals(0.35, ColumnDividers.clampProportion(0.35), 1e-12);
        assertEquals(0.5, ColumnDividers.clampProportion(Double.NaN), 1e-12,
                "a corrupt stored preference parses to NaN; it must not reach a divider");
        assertEquals(ColumnDividers.MIN_PROPORTION, ColumnDividers.clampProportion(0.0), 1e-12);
        assertEquals(ColumnDividers.MAX_PROPORTION, ColumnDividers.clampProportion(1.0), 1e-12);
    }

    @Test
    void aNaNProportionStillYieldsALocationInsideBothMinimums() {
        int location = ColumnDividers.location(OUTER_SPAN, Double.NaN, LEFT_MIN, INNER_MIN);

        assertTrue(location >= LEFT_MIN && OUTER_SPAN - location >= INNER_MIN, "got " + location);
    }

    @Test
    void proportionIsTheLeftSharesOfTheSpanClampedToTheBand() {
        assertEquals(0.2, ColumnDividers.proportion(254, OUTER_SPAN), 1e-9);
        assertEquals(0.5, ColumnDividers.proportion(503, 1006), 1e-9);
        assertEquals(0.1, ColumnDividers.proportion(0, OUTER_SPAN), 1e-12);
        assertEquals(0.9, ColumnDividers.proportion(OUTER_SPAN, OUTER_SPAN), 1e-12);
        assertEquals(0.5, ColumnDividers.proportion(300, 0), 1e-12, "an empty span carries no information");
        assertEquals(0.5, ColumnDividers.proportion(300, -10), 1e-12);
    }

    @ParameterizedTest
    @CsvSource({"0.20", "0.25", "0.333", "0.35", "0.39"})
    void aStoredProportionSurvivesTheRoundTripToWithinAPixel(double stored) {
        // Every value here lands between the two minimums at this span, so no clamp interferes.
        int location = ColumnDividers.location(OUTER_SPAN, stored, LEFT_MIN, INNER_MIN);
        double readBack = ColumnDividers.proportion(location, OUTER_SPAN);

        assertEquals(stored, readBack, 1.0 / OUTER_SPAN, "one pixel of rounding at most");
        assertEquals(location, ColumnDividers.location(OUTER_SPAN, readBack, LEFT_MIN, INNER_MIN),
                "and applying what was read back puts the divider where it was");
    }

    @Test
    void aDraggedPositionReappliedAtAnotherWindowWidthKeepsItsShare() {
        // The player drags the left column to 300 of 1270; the next launch is on a 1536 px
        // window (span 1526): the column is again about 23.6 % of it.
        double stored = ColumnDividers.proportion(300, OUTER_SPAN);

        assertEquals(360, ColumnDividers.location(1526, stored, LEFT_MIN, INNER_MIN));
    }
}
