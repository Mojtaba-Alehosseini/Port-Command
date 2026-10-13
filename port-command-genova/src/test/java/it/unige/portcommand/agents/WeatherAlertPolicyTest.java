package it.unige.portcommand.agents;

import java.util.Optional;

import it.unige.portcommand.agents.WeatherAlertPolicy.Direction;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Prolog behind the swell/state dimensions (the {@code PrologQueriesTest}
 * fast-lane pattern) — the policy deliberately answers those through the SAME
 * predicates the HarbourMaster's safety gate uses.
 *
 * <p><b>S16 (2026-10-13, decision B4).</b> The WORSENED half is untouched. The CLEARED half
 * was rewritten: a clear used to be "any dimension recovered, the wind band included", and is
 * now "the previous reading was not calm for every vessel type and this one is"
 * ({@code MovementGate.calmForEveryType}: wind 27 kn or less, visibility, swell and sea state
 * safe). Two cases that pinned the old rule are replaced below, each with a note saying why;
 * the cases under "S16" are new and are all red on the pre-S16 policy.
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

    /**
     * Was {@code downwardWindCrossingClears}: 31 → 28 kn was CLEARED because the wind left the
     * 30-kn band. Changed in S16: 28 kn is under a tanker's limit but not by the 3-kn release
     * margin, so a held tanker is NOT let go at 28 — and a "clearing" that releases nobody is
     * the flapping signal B4 removes. The clear comes when the wind is down to 27.
     */
    @Test
    void aDownwardWindCrossingClearsOnlyOnceTheReleaseMarginIsReached() {
        assertTrue(change(snap(31, "fair", 1.5, "cloudy"), snap(28, "fair", 1.5, "cloudy")).isEmpty(),
                "31 → 28 kn: out of the 30-kn band, but a held tanker still waits (resumes at 27)");
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(31, "fair", 1.5, "cloudy"), snap(27, "fair", 1.5, "cloudy")),
                "31 → 27 kn: every type may move again");
    }

    @Test
    void enteringTheStormStateWorsensEvenAtConstantWind() {
        assertEquals(Optional.of(Direction.WORSENED),
                change(snap(28, "fair", 1.5, "cloudy"), snap(28, "poor", 1.5, "stormy")));
    }

    /**
     * Was {@code leavingTheStormStateClearsEvenAtConstantWind}, which expected CLEARED for
     * 31 kn stormy → 31 kn cloudy because the unsafe state had gone. Changed in S16: 31 kn is
     * still over a tanker's 30-kn limit, so for tankers nothing has cleared, and the player was
     * told "Weather clearing" while ships stayed held. It is routine now; the clear is reported
     * when the storm ends AND the wind is calm for every type.
     */
    @Test
    void leavingTheStormStateAtAWindStillUnsafeForTankersIsNotAClear() {
        assertTrue(change(snap(31, "poor", 1.5, "stormy"), snap(31, "fair", 1.5, "cloudy")).isEmpty(),
                "31 kn cloudy is still unsafe for tankers (R15): no clear");
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(31, "poor", 1.5, "stormy"), snap(26, "fair", 1.5, "cloudy")),
                "the storm ends at 26 kn: that is a clear");
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

    // ---- S16 (decision B4): "clearing" only when it is really safe ----

    /**
     * The finding itself (A4): 46 → 44 kn inside a storm leaves the 45-kn band, and before S16
     * that alone sent {@code weather_clear} and the notification "Weather clearing: 44 kn,
     * stormy". It is not a clear for anybody.
     */
    @Test
    void aWindBandDropInsideAStormIsNotAClear() {
        assertTrue(change(snap(46, "poor", 3.5, "stormy"), snap(44, "poor", 3.5, "stormy")).isEmpty());
        assertTrue(change(snap(41, "poor", 4.6, "stormy"), snap(36, "poor", 4.6, "stormy")).isEmpty(),
                "nor is a second band drop with the swell still over the limit");
    }

    /** The storm scenario's scripted pair: 32 kn stormy/poor/4.0 m → 24 kn cloudy/good/1.0 m. */
    @Test
    void aStormGivingWayToCalmWeatherIsAClear() {
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(32, "poor", 4.0, "stormy"), snap(24, "good", 1.0, "cloudy")));
    }

    /**
     * The calm line is 27 kn: 28 kn is safe to START a movement for every type but is not calm
     * (a held tanker resumes at 27 or less), so 28 → 27 is the step on which the last held ship
     * may go — a clear. Between two calm readings nothing clears, whatever the drop.
     */
    @Test
    void aClearNeedsThePreviousReadingToHaveBeenNotCalm() {
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(28, "fair", 1.5, "cloudy"), snap(27, "good", 1.5, "cloudy")),
                "28 kn is not calm, 27 kn is");
        assertTrue(change(snap(27, "fair", 1.5, "cloudy"), snap(20, "good", 0.5, "sunny")).isEmpty(),
                "27 kn was already calm: an improvement, not a clear");
        assertTrue(change(snap(29, "fair", 1.5, "cloudy"), snap(28, "fair", 1.5, "cloudy")).isEmpty(),
                "29 → 28 kn: not calm before, not calm after");
    }

    /** One dimension recovering while another is still unsafe is not a clear either. */
    @Test
    void aPartialRecoveryIsNotAClear() {
        assertTrue(change(snap(20, "poor", 4.5, "cloudy"), snap(20, "good", 4.5, "cloudy")).isEmpty(),
                "visibility recovered, the swell is still over 4.0 m");
        assertTrue(change(snap(20, "poor", 4.5, "cloudy"), snap(20, "poor", 3.0, "cloudy")).isEmpty(),
                "the swell recovered, visibility is still poor");
        assertEquals(Optional.of(Direction.CLEARED),
                change(snap(20, "poor", 4.5, "cloudy"), snap(20, "fair", 4.0, "cloudy")),
                "both recovered (4.0 m is within the limit): a clear");
    }

    /** What the policy calls calm, at its edges: the movement gate's release check for all five types. */
    @Test
    void calmIsTheReleaseCheckForEveryVesselType() {
        assertTrue(WeatherAlertPolicy.calm(snap(27, "fair", 4.0, "cloudy")), "27 kn / fair / 4.0 m / cloudy");
        assertFalse(WeatherAlertPolicy.calm(snap(28, "fair", 1.5, "cloudy")), "28 kn: inside the tanker margin");
        assertFalse(WeatherAlertPolicy.calm(snap(20, "poor", 1.5, "cloudy")), "poor visibility (R16)");
        assertFalse(WeatherAlertPolicy.calm(snap(20, "good", 4.1, "cloudy")), "swell over 4.0 m (R17)");
        assertFalse(WeatherAlertPolicy.calm(snap(20, "good", 1.5, "stormy")), "stormy (R19)");
    }
}
