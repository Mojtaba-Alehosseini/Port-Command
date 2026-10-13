package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.agents.TransitionMatrix;
import it.unige.portcommand.gui.model.WeatherForecast.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S16 (2026-10-13, decision B9): the forecast behind a click on the top bar's weather — state
 * probabilities for the next hours as powers of the weather agent's own transition table. Pure
 * arithmetic on {@link TransitionMatrix#defaults()}, so no agent, no clock and no EDT here.
 *
 * <p>The two-hour figures below were multiplied out by hand from the table
 * (sunny 0.85 / 0.13 / 0.02, cloudy 0.40 / 0.52 / 0.08, stormy 0.15 / 0.55 / 0.30); if the table
 * is ever retuned they must be worked out again, which is the point of pinning them.
 */
class WeatherForecastTest {

    private static final double EXACT = 1e-12;
    private static final double CLOSE = 1e-9;

    private static Row row(String state, int hoursAhead) {
        List<Row> rows = WeatherForecast.forState(state, hoursAhead);
        assertEquals(hoursAhead, rows.size(), "one row per hour ahead");
        return rows.get(hoursAhead - 1);
    }

    @Test
    void threeHoursAheadGivesThreeRowsNumberedFromOne() {
        List<Row> rows = WeatherForecast.forState("cloudy", WeatherForecast.HOURS);

        assertEquals(3, WeatherForecast.HOURS);
        assertEquals(3, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).hoursAhead());
        }
        assertEquals("in 1 hour", rows.get(0).label());
        assertEquals("in 2 hours", rows.get(1).label());
        assertEquals("in 3 hours", rows.get(2).label());
        assertEquals(List.of("sunny", "cloudy", "stormy"), WeatherForecast.columns(),
                "the columns are the chain's own states, in the order of a row's values");
    }

    @Test
    void everyRowSumsToOne() {
        for (String state : TransitionMatrix.STATES) {
            for (Row row : WeatherForecast.forState(state, 12)) {
                assertEquals(1.0, row.sunny() + row.cloudy() + row.stormy(), CLOSE,
                        "from " + state + ", " + row.label());
                assertTrue(row.sunny() >= 0.0 && row.cloudy() >= 0.0 && row.stormy() >= 0.0,
                        "a probability below zero from " + state + ", " + row.label());
            }
        }
    }

    @Test
    void theOneHourRowIsTheTablesOwnRowForTheState() {
        Map<String, Map<String, Double>> table = TransitionMatrix.defaults().rows();
        for (String state : TransitionMatrix.STATES) {
            Row next = row(state, 1);
            assertEquals(table.get(state).get("sunny"), next.sunny(), EXACT, state + " -> sunny");
            assertEquals(table.get(state).get("cloudy"), next.cloudy(), EXACT, state + " -> cloudy");
            assertEquals(table.get(state).get("stormy"), next.stormy(), EXACT, state + " -> stormy");
        }
        // And in plain figures, so a retuned table is noticed here too.
        Row fromStormy = row("stormy", 1);
        assertEquals(0.15, fromStormy.sunny(), EXACT);
        assertEquals(0.55, fromStormy.cloudy(), EXACT);
        assertEquals(0.30, fromStormy.stormy(), EXACT);
    }

    @Test
    void theTwoHourRowIsTheTableMultipliedByItself() {
        // From sunny: 0.85*0.85 + 0.13*0.40 + 0.02*0.15, and so on down the columns.
        Row fromSunny = row("sunny", 2);
        assertEquals(0.7775, fromSunny.sunny(), CLOSE);
        assertEquals(0.1891, fromSunny.cloudy(), CLOSE);
        assertEquals(0.0334, fromSunny.stormy(), CLOSE);

        // From cloudy: 0.40*0.85 + 0.52*0.40 + 0.08*0.15 = 0.34 + 0.208 + 0.012.
        Row fromCloudy = row("cloudy", 2);
        assertEquals(0.5600, fromCloudy.sunny(), CLOSE);
        assertEquals(0.3664, fromCloudy.cloudy(), CLOSE);
        assertEquals(0.0736, fromCloudy.stormy(), CLOSE);

        // From stormy: 0.15*0.85 + 0.55*0.40 + 0.30*0.15 = 0.1275 + 0.22 + 0.045.
        Row fromStormy = row("stormy", 2);
        assertEquals(0.3925, fromStormy.sunny(), CLOSE);
        assertEquals(0.4705, fromStormy.cloudy(), CLOSE);
        assertEquals(0.1370, fromStormy.stormy(), CLOSE);
    }

    @Test
    void theThreeHourRowIsOneMoreStep() {
        // The two-hour row from stormy (0.3925 / 0.4705 / 0.137) times the table once more.
        Row fromStormy = row("stormy", 3);
        assertEquals(0.542375, fromStormy.sunny(), CLOSE);
        assertEquals(0.371035, fromStormy.cloudy(), CLOSE);
        assertEquals(0.086590, fromStormy.stormy(), CLOSE);
    }

    @Test
    void aStormIsUnlikelyToLastAndTheSkyDriftsBackToFair() {
        // What the popup is for: the reading of the numbers a player makes.
        assertTrue(row("stormy", 1).stormy() < 0.5, "a storm more likely ends than lasts another hour");
        assertTrue(row("stormy", 3).stormy() < row("stormy", 1).stormy(), "and less likely still to last three");
        assertTrue(row("sunny", 3).stormy() < 0.05, "from a sunny sky a storm within three hours is rare");
        assertTrue(row("sunny", 3).stormy() > row("sunny", 1).stormy(), "but less rare the further out one looks");
    }

    @Test
    void anUnknownOrAbsentStateHasNoForecast() {
        assertEquals(List.of(), WeatherForecast.forState(null, 3), "no reading yet");
        assertEquals(List.of(), WeatherForecast.forState("", 3));
        assertEquals(List.of(), WeatherForecast.forState("   ", 3));
        assertEquals(List.of(), WeatherForecast.forState("foggy", 3), "not a state of the chain");
        assertEquals(List.of(), WeatherForecast.forState("sunny", 0), "no hours ahead, no rows");
        assertEquals(List.of(), WeatherForecast.forState("sunny", -2));
        assertEquals(List.of(), WeatherForecast.forState(null, "sunny", 3), "no table, no forecast");
    }

    @Test
    void theStateIsReadAsTheWeatherAgentWritesItCaseAndSpacesAside() {
        assertEquals(WeatherForecast.forState("stormy", 3), WeatherForecast.forState(" Stormy ", 3));
        assertEquals(WeatherForecast.forState("sunny", 3), WeatherForecast.forState("SUNNY", 3));
    }

    @Test
    void anotherTableGivesAnotherForecast() {
        // The overload the default delegates to: a table in which a storm never ends.
        TransitionMatrix stuck = new TransitionMatrix(Map.of(
                "sunny", Map.of("sunny", 0.5, "cloudy", 0.5, "stormy", 0.0),
                "cloudy", Map.of("sunny", 0.0, "cloudy", 0.5, "stormy", 0.5),
                "stormy", Map.of("sunny", 0.0, "cloudy", 0.0, "stormy", 1.0)));

        Row inTwo = WeatherForecast.forState(stuck, "sunny", 2).get(1);
        assertEquals(0.25, inTwo.sunny(), EXACT);
        assertEquals(0.50, inTwo.cloudy(), EXACT);
        assertEquals(0.25, inTwo.stormy(), EXACT);
        assertEquals(1.0, WeatherForecast.forState(stuck, "stormy", 3).get(2).stormy(), EXACT);

        // A state the table has no row for stays as it is — what the chain itself does with it.
        TransitionMatrix noStormRow = new TransitionMatrix(Map.of(
                "sunny", Map.of("sunny", 1.0), "cloudy", Map.of("cloudy", 1.0)));
        assertEquals(1.0, WeatherForecast.forState(noStormRow, "stormy", 2).get(1).stormy(), EXACT);
    }

    // --- whole percentages ---------------------------------------------------------------------------

    @Test
    void percentagesAreWholeAndAlwaysAddUpToOneHundred() {
        for (String state : TransitionMatrix.STATES) {
            for (Row row : WeatherForecast.forState(state, 12)) {
                int[] percents = row.percents();
                assertEquals(3, percents.length);
                assertEquals(100, percents[0] + percents[1] + percents[2], "from " + state + ", " + row.label());
                assertTrue(Math.abs(percents[0] - row.sunny() * 100.0) < 1.0, "sunny moved a whole point");
                assertTrue(Math.abs(percents[1] - row.cloudy() * 100.0) < 1.0, "cloudy moved a whole point");
                assertTrue(Math.abs(percents[2] - row.stormy() * 100.0) < 1.0, "stormy moved a whole point");
            }
        }
    }

    @Test
    void thePercentagesThePopupShows() {
        assertArrayEquals(new int[] {85, 13, 2}, row("sunny", 1).percents());
        assertArrayEquals(new int[] {40, 52, 8}, row("cloudy", 1).percents());
        assertArrayEquals(new int[] {15, 55, 30}, row("stormy", 1).percents());

        // 77.75 / 18.91 / 3.34: cut to 77 + 18 + 3 = 98, the two points go to .91 and then .75.
        assertArrayEquals(new int[] {78, 19, 3}, row("sunny", 2).percents());
        // 56.00 / 36.64 / 7.36: cut to 99, the point goes to .64.
        assertArrayEquals(new int[] {56, 37, 7}, row("cloudy", 2).percents());
        // 39.25 / 47.05 / 13.70: cut to 99, the point goes to .70.
        assertArrayEquals(new int[] {39, 47, 14}, row("stormy", 2).percents());
        // 54.2375 / 37.1035 / 8.659: cut to 99, the point goes to .659.
        assertArrayEquals(new int[] {54, 37, 9}, row("stormy", 3).percents());
    }

    @Test
    void roundingEachValueOnItsOwnWouldNotAddUpWhichIsWhyItIsNotDone() {
        // Three hours on from cloudy: 63.36 / 30.3808 / 6.2592. Rounded one by one that is
        // 63 + 30 + 6 = 99 — a forecast row that does not add up.
        Row row = row("cloudy", 3);
        assertEquals(0.6336, row.sunny(), CLOSE);
        assertEquals(0.303808, row.cloudy(), CLOSE);
        assertEquals(0.062592, row.stormy(), CLOSE);
        assertEquals(99, Math.round(row.sunny() * 100) + Math.round(row.cloudy() * 100)
                + Math.round(row.stormy() * 100), "non-vacuity: plain rounding loses a point on this row");

        assertArrayEquals(new int[] {63, 31, 6}, row.percents(), "the lost point goes to the largest fraction, .3808");
    }

    @Test
    void floatingPointDustDoesNotCostAPoint() {
        // 0.57 * 100 is 56.99999999999999 in binary floating point: cut as it stands it would
        // read 56, and the row 30 / 56 / 13 would then hand the missing point to the wrong value.
        assertEquals(56, (int) Math.floor(0.57 * 100), "non-vacuity: the dust is real");
        assertArrayEquals(new int[] {30, 57, 13}, new Row(1, 0.30, 0.57, 0.13).percents());
        assertArrayEquals(new int[] {100, 0, 0}, new Row(1, 1.0, 0.0, 0.0).percents());
        assertArrayEquals(new int[] {34, 33, 33}, new Row(1, 1.0 / 3, 1.0 / 3, 1.0 / 3).percents(),
                "a three-way tie: the point goes to the first state");
    }

    @Test
    void theNoteSaysWhereTheNumbersComeFromAndWhatTheyLeaveOut() {
        assertEquals("From the weather model's transition table. A scenario's scripted weather is not included.",
                WeatherForecast.NOTE);
        assertEquals("No weather reading yet.", WeatherForecast.NO_READING);
    }
}
