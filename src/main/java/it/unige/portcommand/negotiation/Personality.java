package it.unige.portcommand.negotiation;

import java.util.Map;

/**
 * Walk-in vessel negotiation personality (a hidden belief; never leaves the agent).
 * Drawn at spawn from {@code vessel_templates.json} via {@code RandomSource}.
 *
 * <ul>
 *   <li>{@code openingModifier} — markup over {@code targetPrice} for the opening
 *       PROPOSE (an aggressive vessel asks higher).</li>
 *   <li>{@code concessionRate} — how fast it concedes per round (0..1). Consumed by
 *       the real {@code NegotiationEngine} in task 15; declared here so the surface
 *       is stable, unused by task 07's mocked-engine flow.</li>
 * </ul>
 */
public enum Personality {

    AGGRESSIVE(1.25, 0.10),
    NEUTRAL(1.15, 0.20),
    DESPERATE(1.05, 0.40);

    private final double openingModifier;
    private final double concessionRate;

    Personality(double openingModifier, double concessionRate) {
        this.openingModifier = openingModifier;
        this.concessionRate = concessionRate;
    }

    public double openingModifier() {
        return openingModifier;
    }

    public double concessionRate() {
        return concessionRate;
    }

    /**
     * Pick a personality from a {@code name -> weight} distribution by cumulative
     * sampling against {@code roll} (a 0..1 draw). Deterministic for a given roll.
     * Falls back to {@link #NEUTRAL} if the distribution under-specifies the weights.
     * (Lives here rather than on {@code RandomSource} to keep {@code util}
     * dependency-free — the caller supplies the roll from a seeded sub-stream.)
     */
    public static Personality fromDistribution(Map<String, Double> distribution, double roll) {
        double cumulative = 0.0;
        for (Personality p : values()) {
            Double weight = distribution.get(p.name());
            if (weight == null) {
                continue;
            }
            cumulative += weight;
            if (roll < cumulative) {
                return p;
            }
        }
        return NEUTRAL;
    }
}
