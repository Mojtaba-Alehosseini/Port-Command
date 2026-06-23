package it.unige.portcommand.negotiation;

import java.util.Map;
import java.util.Random;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One vessel type's spawn-time ranges from {@code vessel_templates.json}. The
 * negotiation ranges (price / wait / personality) seed a walk-in's {@link WalkInState};
 * the physical ranges (draft / length / tonnage) feed VesselSpec generation in the
 * Poisson spawner (task 11). Ranges are 2-element {@code [lo, hi]} arrays.
 */
public record VesselTemplate(
        @JsonProperty("personality_distribution") Map<String, Double> personalityDistribution,
        @JsonProperty("min_acceptable_price_range") double[] minAcceptablePriceRange,
        @JsonProperty("target_price_range") double[] targetPriceRange,
        @JsonProperty("max_wait_minutes_range") int[] maxWaitMinutesRange,
        @JsonProperty("draft_range") double[] draftRange,
        @JsonProperty("length_range") double[] lengthRange,
        @JsonProperty("tonnage_range") int[] tonnageRange) {

    public double sampleMinAcceptablePrice(Random r) {
        return uniform(minAcceptablePriceRange, r);
    }

    public double sampleTargetPrice(Random r) {
        return uniform(targetPriceRange, r);
    }

    public int sampleMaxWaitMinutes(Random r) {
        return (int) Math.round(uniform(maxWaitMinutesRange[0], maxWaitMinutesRange[1], r));
    }

    public Personality samplePersonality(Random r) {
        return Personality.fromDistribution(personalityDistribution, r.nextDouble());
    }

    private static double uniform(double[] range, Random r) {
        return uniform(range[0], range[1], r);
    }

    private static double uniform(double lo, double hi, Random r) {
        return lo + r.nextDouble() * (hi - lo);
    }
}
