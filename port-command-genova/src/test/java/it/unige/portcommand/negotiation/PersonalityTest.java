package it.unige.portcommand.negotiation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalityTest {

    @Test
    void openingFractionSubBandsAreContiguousAndOrderedAggressiveLowest() {
        // AGGRESSIVE lowballs deepest (holds out longest elsewhere in the engine),
        // DESPERATE opens closest to target (settles fastest elsewhere in the engine).
        assertTrue(Personality.AGGRESSIVE.openingFractionMax() <= Personality.NEUTRAL.openingFractionMin());
        assertTrue(Personality.NEUTRAL.openingFractionMax() <= Personality.DESPERATE.openingFractionMin());
    }

    @Test
    void openingFractionSubBandsSpanTheFullSevenZeroToEightFiveRange() {
        assertEquals(0.70, Personality.AGGRESSIVE.openingFractionMin(), 0.0001);
        assertEquals(0.85, Personality.DESPERATE.openingFractionMax(), 0.0001);
        for (Personality p : Personality.values()) {
            assertTrue(p.openingFractionMin() >= 0.70 && p.openingFractionMax() <= 0.85,
                    p + " must stay inside [0.70,0.85]: [" + p.openingFractionMin() + "," + p.openingFractionMax() + "]");
        }
    }

    @Test
    void sampleOpeningFractionInterpolatesWithinItsOwnBand() {
        assertEquals(Personality.NEUTRAL.openingFractionMin(), Personality.NEUTRAL.sampleOpeningFraction(0.0), 0.0001);
        assertEquals(Personality.NEUTRAL.openingFractionMax(), Personality.NEUTRAL.sampleOpeningFraction(1.0), 0.0001);
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
