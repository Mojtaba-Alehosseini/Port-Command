package it.unige.portcommand.artifacts;

/**
 * Aggregate market statistics for a (vessel type, duration) query, returned by
 * {@link MarketHistoryArtifact#queryStats}. The Assistant (task 10) reads
 * {@code mean}/{@code stddev}/{@code sampleCount}; the rest are computed for
 * completeness. {@code lowConfidence} is set when fewer than 10 samples back the
 * estimate (the Assistant then falls back to a static fair price).
 */
public record MarketStats(
        double mean,
        double stddev,
        int sampleCount,
        double min,
        double max,
        double dealRate,
        boolean lowConfidence) {

    /** No in-band data: zeroed stats, flagged low-confidence. */
    public static MarketStats empty() {
        return new MarketStats(0.0, 0.0, 0, 0.0, 0.0, 0.0, true);
    }
}
