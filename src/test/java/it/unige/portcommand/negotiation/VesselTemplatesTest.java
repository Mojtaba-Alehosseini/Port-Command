package it.unige.portcommand.negotiation;

import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VesselTemplatesTest {

    private static final Set<String> EXPECTED_TYPES =
            Set.of("tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship");

    @Test
    void allFiveVesselTypesPresent() {
        assertEquals(EXPECTED_TYPES, VesselTemplates.types());
    }

    @Test
    void everyTemplateIsInternallyConsistent() {
        for (String type : EXPECTED_TYPES) {
            VesselTemplate t = VesselTemplates.forType(type);
            // distribution covers all three personalities and sums to ~1.0
            assertEquals(3, t.personalityDistribution().size(), type + " distribution size");
            double sum = t.personalityDistribution().values().stream().mapToDouble(Double::doubleValue).sum();
            assertTrue(Math.abs(sum - 1.0) < 1e-9, type + " distribution sums to " + sum);
            // ranges well-formed: lo < hi, and min-acceptable below target
            assertTrue(t.minAcceptablePriceRange()[0] < t.minAcceptablePriceRange()[1], type + " min range");
            assertTrue(t.targetPriceRange()[0] < t.targetPriceRange()[1], type + " target range");
            assertTrue(t.minAcceptablePriceRange()[1] <= t.targetPriceRange()[0],
                    type + " min-acceptable band below target band");
            assertTrue(t.maxWaitMinutesRange()[0] < t.maxWaitMinutesRange()[1], type + " wait range");
            // 19b duration floor: well-formed, and lo >= 1 is LOAD-BEARING — the engine's
            // too_short branch is what keeps a degenerate 0/negative proposal out of every
            // deal, and that only holds if no template can ever draw a floor below 1.
            assertTrue(t.minDurationRange()[0] < t.minDurationRange()[1], type + " duration floor range");
            assertTrue(t.minDurationRange()[0] >= 1, type + " duration floor must be >= 1h");
            assertTrue(t.minDurationRange()[1] <= 18,
                    type + " a floor above the 6..18h preferred-stay envelope would always clamp");
        }
    }

    @Test
    void minDurationSamplingStaysWithinRangeAndIsDeterministic() {
        VesselTemplate cargo = VesselTemplates.forType("cargo_vessel");
        int floor = cargo.sampleMinDurationHours(new Random(11));
        assertTrue(floor >= 6 && floor <= 12, "cargo floor sampled in range: " + floor);
        assertEquals(floor, cargo.sampleMinDurationHours(new Random(11)), "same seed -> same draw");
    }

    @Test
    void samplingStaysWithinRangeAndIsDeterministic() {
        VesselTemplate tanker = VesselTemplates.forType("tanker");
        double min = tanker.sampleMinAcceptablePrice(new Random(7));
        assertTrue(min >= 4000 && min <= 5200, "min sampled in range: " + min); // tanker band [4000,5200] (07b, S7.5)
        // same seed -> same draw
        assertEquals(tanker.sampleTargetPrice(new Random(7)), tanker.sampleTargetPrice(new Random(7)));
        int wait = tanker.sampleMaxWaitMinutes(new Random(3));
        // Task 24 (2026-07-17): patience recalibrated for live time — tanker band is now
        // [240,420] sim-minutes (~50-88 wall-s at the default mapping); the stub-era [15,30]
        // predates a clock that actually advances. See vessel_templates.json's dated header.
        assertTrue(wait >= 240 && wait <= 420, "wait sampled in range: " + wait);
        Personality p = tanker.samplePersonality(new Random(1));
        assertEquals(p, tanker.samplePersonality(new Random(1)));
    }

    @Test
    void unknownTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> VesselTemplates.forType("pleasure"));
    }
}
