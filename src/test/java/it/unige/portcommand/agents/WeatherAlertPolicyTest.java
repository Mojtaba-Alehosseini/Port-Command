package it.unige.portcommand.agents;

import java.util.Optional;

import it.unige.portcommand.agents.WeatherAlertPolicy.Direction;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Prolog behind the swell/state dimensions (the {@code PrologQueriesTest}
 * fast-lane pattern) — the policy deliberately answers those through the SAME
 * predicates the HarbourMaster's safety gate uses.
 */
class WeatherAlertPolicyTest {

    @BeforeAll
    static void initProlog() {
        PrologEngine.getInstance().init();
    }

    private static WeatherSnapshot snap(int wind, String visibility, double swell, String state) {
        return new WeatherSnapshot(wind, visibility, swell, state, 0L);
    }

    private static Optional<Direction> change(WeatherSnapshot prev, WeatherSnapshot cur) {
        return WeatherAlertPolicy.transition(prev, cur);
    }

    // ---- wind bands ----

    @Test
    void windBandsFollowTheCanonicalThresholds() {
        assertEquals(0, WeatherAlertPolicy.windBand(29));
        assertEquals(1, WeatherAlertPolicy.windBand(30));
        assertEquals(1, WeatherAlertPolicy.windBand(34));
        assertEquals(2, WeatherAlertPolicy.windBand(35));
        assertEquals(3, WeatherAlertPolicy.windBand(40));
        assertEquals(4, WeatherAlertPolicy.windBand(45));
        assertEquals(4, WeatherAlertPolicy.windBand(55));
    }

    // ---- transitions ----

    @Test
    void theFirstReadingIsABaselineNotAnAlert() {
        assertTrue(change(null, snap(55, "poor", 5.0, "stormy")).isEmpty());
    }

    @Test
    void sameBandWindDriftIsSilent() {
        assertTrue(change(snap(31, "fair", 1.5, "cloudy"), snap(33, "fair", 1.5, "cloudy")).isEmpty(),
                "the every-broadcast-URGENT bug: 31→33 crosses nothing");
    }

    @Test
    void upwardWindCrossingWorsens() {
        assertEquals(Optional.of(Direction.WORSENED),
                change(snap(28, "fair", 1.5, "cloudy"), snap(31, "fair", 1.5, "cloudy")));
    }

    @Test
    void downwardWindCrossingClears() {
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(31, "fair", 1.5, "cloudy"), snap(28, "fair", 1.5, "cloudy")));
    }

    @Test
    void enteringTheStormStateWorsensEvenAtConstantWind() {
        assertEquals(Optional.of(Direction.WORSENED),
                change(snap(28, "fair", 1.5, "cloudy"), snap(28, "poor", 1.5, "stormy")));
    }

    @Test
    void leavingTheStormStateClearsEvenAtConstantWind() {
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(31, "poor", 1.5, "stormy"), snap(31, "fair", 1.5, "cloudy")));
    }

    @Test
    void visibilityCollapsingWorsensAndRecoveringClears() {
        // The adversarial review's M1: operation_safe (R16/R20) gates on visibility, so a
        // fog-only change MUST alert in both directions — without the downward half, a vessel
        // held for fog strands until some unrelated crossing happens by.
        assertEquals(Optional.of(Direction.WORSENED),
                change(snap(20, "good", 0.5, "sunny"), snap(20, "poor", 0.5, "sunny")));
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(20, "poor", 0.5, "sunny"), snap(20, "good", 0.5, "sunny")));
        assertTrue(change(snap(20, "good", 0.5, "sunny"), snap(20, "fair", 0.5, "sunny")).isEmpty(),
                "good→fair stays adequate (R16) — no alert");
    }

    @Test
    void swellCrossingTheLimitWorsensAndRecoveringClears() {
        // rules_weather.pl R17: max_swell(4.0), swell =< max is safe.
        assertEquals(Optional.of(Direction.WORSENED),
                change(snap(20, "good", 3.5, "sunny"), snap(20, "good", 4.5, "sunny")));
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(20, "good", 4.5, "sunny"), snap(20, "good", 3.5, "sunny")));
    }

    @Test
    void aMixedChangeReadsAsWorsenedSafetyFirst() {
        // Storm ended (state recovered) but the wind crossed 30 upward: the worsening
        // dimension wins, so the HarbourMaster re-runs the hold sweep with the new wind.
        assertEquals(Optional.of(Direction.WORSENED),
                change(snap(28, "poor", 1.5, "stormy"), snap(31, "fair", 1.5, "cloudy")));
    }

    @Test
    void identicalReadingsAreSilent() {
        assertTrue(change(snap(18, "good", 0.5, "sunny"), snap(18, "good", 0.5, "sunny")).isEmpty());
    }
}
