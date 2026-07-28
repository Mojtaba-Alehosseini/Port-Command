package it.unige.portcommand.negotiation;

import java.util.Map;

/**
 * Walk-in vessel negotiation personality (a hidden belief; never leaves the agent).
 * Drawn at spawn from {@code vessel_templates.json} via {@code RandomSource}.
 *
 * <ul>
 *   <li>{@code openingFractionMin}/{@code openingFractionMax} — the opening PROPOSE
 *       is a lowball {@code sampleOpeningFraction(roll) * targetPrice}, never a markup
 *       over it (task 19 STEP 1 fix: an earlier &gt;1.0 modifier produced openings
 *       above the PROJECT_DEFINITION §7.5-derivable ceiling, e.g. &euro;9,169.75 for a
 *       tanker whose target range tops out at &euro;8,000). The three personality
 *       sub-bands are contiguous across the spec's overall {@code [0.70,0.85]} band:
 *       AGGRESSIVE opens with the deepest lowball and holds out longest (matches its
 *       {@code RealNegotiationEngine.ACCEPT_THRESHOLD} being the lowest of the three);
 *       DESPERATE opens closest to its own target, since it also settles fastest
 *       in-band elsewhere in the engine. Because {@code targetPrice} is itself sampled
 *       within the vessel type's own target range, {@code targetPrice * fraction} is
 *       mathematically guaranteed to land inside
 *       {@code [0.70*typeMinTarget, 0.85*typeMaxTarget]} — the product of two positive
 *       ranges is bounded by the product of their own min/max.</li>
 *   <li>{@code concessionRate} — how fast it concedes per round (0..1). Consumed by
 *       the real {@code NegotiationEngine} in task 15; declared here so the surface
 *       is stable, unused by task 07's mocked-engine flow.</li>
 * </ul>
 */
public enum Personality {

    AGGRESSIVE(0.70, 0.75, 0.10),
    NEUTRAL(0.75, 0.80, 0.20),
    DESPERATE(0.80, 0.85, 0.40);

    private final double openingFractionMin;
    private final double openingFractionMax;
    private final double concessionRate;

    Personality(double openingFractionMin, double openingFractionMax, double concessionRate) {
        this.openingFractionMin = openingFractionMin;
        this.openingFractionMax = openingFractionMax;
        this.concessionRate = concessionRate;
    }

    public double openingFractionMin() {
        return openingFractionMin;
    }

    public double openingFractionMax() {
        return openingFractionMax;
    }

    /** @param roll a {@code [0,1)} draw. @return a fraction inside this personality's opening sub-band. */
    public double sampleOpeningFraction(double roll) {
        return openingFractionMin + roll * (openingFractionMax - openingFractionMin);
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
