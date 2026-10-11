package it.unige.portcommand.behaviours;

import java.util.Random;

import it.unige.portcommand.behaviours.coordination.PoissonSpawnBehaviour;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14: the walk-in arrival rate — one every three sim-hours on average, about eight a game day
 * (it was one every two). Pure: the gap is a function of the seeded draw alone, so this needs no
 * agent, no container and no clock. In the parent {@code behaviours} test package for the reason
 * {@code PoissonSpawnBehaviourIT} gives: a test class inside a behaviour sub-package would shadow
 * {@code BehaviourCatalogueTest}'s scan of it.
 */
class PoissonArrivalRateTest {

    private static final double SIM_MILLIS_PER_HOUR = 3_600_000.0;

    @Test
    void theRateIsOneThirdPerSimHourAndTheCapsAreTwoAndThree() {
        assertEquals(1.0 / 3.0, PoissonSpawnBehaviour.DEFAULT_RATE_PER_SIM_HOUR, 1e-12);
        assertEquals(2, PoissonSpawnBehaviour.MAX_NEGOTIATING_WALKINS, "what a human can hold at once");
        assertEquals(3, PoissonSpawnBehaviour.MAX_ACTIVE_WALKINS, "the in-port limit is unchanged");
    }

    @Test
    void theMeanGapIsThreeSimHoursSoAboutEightArriveInAGameDay() {
        Random draw = new Random(20261011L);
        int samples = 200_000;
        double totalSimMillis = 0;
        for (int i = 0; i < samples; i++) {
            totalSimMillis += PoissonSpawnBehaviour.nextGapSimMillis(draw);
        }
        double meanHours = totalSimMillis / samples / SIM_MILLIS_PER_HOUR;

        // Standard error of the mean of an exponential over 200k draws is ~0.2 %; 2 % is a wide
        // margin that still separates 3.0 h cleanly from the old 2.0 h.
        assertEquals(3.0, meanHours, 0.06, "mean inter-arrival gap in sim-hours");
        assertEquals(8.0, 24.0 / meanHours, 0.2, "arrivals per game day before any cap");
    }

    @Test
    void aGapIsNeverShorterThanOneSimSecondAndAlwaysWholeSeconds() {
        Random draw = new Random(7L);
        for (int i = 0; i < 100_000; i++) {
            long gap = PoissonSpawnBehaviour.nextGapSimMillis(draw);
            assertTrue(gap >= 1_000L, "gap " + gap);
            assertEquals(0L, gap % 1_000L, "whole sim-seconds, as the wall conversion expects");
        }
    }

    @Test
    void theSameSeedDrawsTheSameGaps() {
        Random a = new Random(99L);
        Random b = new Random(99L);
        for (int i = 0; i < 1_000; i++) {
            assertEquals(PoissonSpawnBehaviour.nextGapSimMillis(a), PoissonSpawnBehaviour.nextGapSimMillis(b));
        }
    }
}
