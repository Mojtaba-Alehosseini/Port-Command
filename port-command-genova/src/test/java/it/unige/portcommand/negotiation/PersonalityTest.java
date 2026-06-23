package it.unige.portcommand.negotiation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalityTest {

    @Test
    void openingModifiersOrderedAggressiveHighest() {
        assertTrue(Personality.AGGRESSIVE.openingModifier() > Personality.NEUTRAL.openingModifier());
        assertTrue(Personality.NEUTRAL.openingModifier() > Personality.DESPERATE.openingModifier());
    }

    @Test
    void fromDistributionReadsCumulativeBuckets() {
        // AGG 0.30, NEU 0.55, DES 0.15 -> cumulative 0.30 / 0.85 / 1.00
        Map<String, Double> dist = Map.of("AGGRESSIVE", 0.30, "NEUTRAL", 0.55, "DESPERATE", 0.15);
        assertEquals(Personality.AGGRESSIVE, Personality.fromDistribution(dist, 0.00));
        assertEquals(Personality.AGGRESSIVE, Personality.fromDistribution(dist, 0.29));
        assertEquals(Personality.NEUTRAL, Personality.fromDistribution(dist, 0.30)); // boundary -> next bucket
        assertEquals(Personality.NEUTRAL, Personality.fromDistribution(dist, 0.84));
        // 0.30+0.55 = 0.8500000000000001 in double, so test clear of that ~1e-16 fuzzy edge
        assertEquals(Personality.DESPERATE, Personality.fromDistribution(dist, 0.86));
        assertEquals(Personality.DESPERATE, Personality.fromDistribution(dist, 0.999));
    }

    @Test
    void fromDistributionFallsBackToNeutralWhenUnderspecified() {
        assertEquals(Personality.NEUTRAL, Personality.fromDistribution(Map.of(), 0.5));
    }
}
