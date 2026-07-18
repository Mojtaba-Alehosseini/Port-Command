package it.unige.portcommand.negotiation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import it.unige.portcommand.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Checkpoint-#6 F2 regression (fixed 2026-07-18): walk-in personality is the FIRST draw of
 * the per-vessel {@code forStream("vessel-" + id)} stream, and before {@code stableHash}
 * gained its murmur3 fmix64 avalanche finalizer, sequential spawn ids produced child seeds
 * differing only in low bits — {@code java.util.Random}'s first output is strongly
 * correlated across adjacent seeds, so the roll crept through [0,1) and parked in one
 * cumulative personality band for dozens of consecutive spawns (observed: 114 GUI spawns
 * with runs of ~50 AGGRESSIVE; a prior session saw 16+ consecutive DESPERATE).
 *
 * <p>This test replays the exact production draw chain ({@code WalkInVesselAgent.onArrival}:
 * template lookup → first {@code nextDouble()} of the per-vessel stream) over 60 sequential
 * walk-in ids and asserts the streams are decorrelated.
 */
class PersonalityStreamDecorrelationTest {

    private static final int SPAWNS = 60;

    /**
     * Longest-run bound, with the math (tanker weights A 0.30 / N 0.55 / D 0.15, so the
     * likeliest single personality is NEUTRAL at p = 0.55):
     * <ul>
     *   <li>Expected longest run of the p = 0.55 personality in n = 60 iid draws is
     *       ln(n(1-p)) / ln(1/p) = ln(27) / ln(1.818) ≈ 5.5.</li>
     *   <li>P(any run ≥ 11) ≤ n · p^11 = 60 × 0.55^11 ≈ 0.083 (union bound), so a healthy
     *       generator stays ≤ 10 with ≥ 91% probability over a random seed — and this test
     *       is DETERMINISTIC (fixed master seed), so "≤ 10" is a pinned property of the
     *       fixed implementation, verified once, not a flaky sample.</li>
     *   <li>The pre-fix pathology produced runs an order of magnitude past this bound
     *       (~50 observed), so the bound cleanly separates broken from healthy.</li>
     * </ul>
     */
    private static final int MAX_RUN_BOUND = 10;

    @Test
    void sequentialWalkInIds_drawAllThreePersonalities_withoutRegimeLock() {
        RandomSource randomSource = new RandomSource(42L);
        VesselTemplate template = VesselTemplates.forType("tanker");

        List<Personality> drawn = new ArrayList<>(SPAWNS);
        for (int i = 1; i <= SPAWNS; i++) {
            // Exact production stream name: "vessel-" + spec.vesselId(), ids are sequential.
            drawn.add(template.samplePersonality(randomSource.forStream("vessel-WALKIN-" + i)));
        }

        assertEquals(EnumSet.allOf(Personality.class), EnumSet.copyOf(drawn),
                "60 sequential spawns must produce all three personalities, got: " + drawn);

        int longestRun = 1;
        int currentRun = 1;
        for (int i = 1; i < drawn.size(); i++) {
            currentRun = drawn.get(i) == drawn.get(i - 1) ? currentRun + 1 : 1;
            longestRun = Math.max(longestRun, currentRun);
        }
        assertTrue(longestRun <= MAX_RUN_BOUND,
                "single-personality run of " + longestRun + " exceeds " + MAX_RUN_BOUND
                        + " — per-vessel streams are correlated again (see MAX_RUN_BOUND math): "
                        + drawn);
    }
}
