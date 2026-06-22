package it.unige.portcommand.util;

import java.util.Random;

/**
 * Seeded {@link Random} wrapper for reproducible simulation runs. Injected via
 * agent constructor args (the same {@code Object[]} channel as the init-args
 * records) — deliberately <strong>not</strong> a global static singleton, so each
 * test constructs its own {@code new RandomSource(testSeed)} and a loaded save can
 * restore the production seed via {@link #setSeed(long)}.
 */
public final class RandomSource {

    private final Random random;
    private long seed;

    public RandomSource(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public void setSeed(long seed) {
        this.seed = seed;
        random.setSeed(seed);
    }

    /**
     * A deterministic, independent child RNG for a named sub-stream — seeded from
     * the master seed XORed with a stable 64-bit hash of {@code name}. Adding or
     * removing randomness in one consumer's stream never shifts another's draw
     * sequence (each gets its own {@link Random}), yet a single save-file seed still
     * reproduces the whole game. Used per stochastic agent (task 09: weather, customs).
     */
    public Random forStream(String name) {
        return new Random(seed ^ stableHash(name));
    }

    /** Deterministic 64-bit polynomial hash (unlike 32-bit {@link String#hashCode()}). */
    private static long stableHash(String s) {
        long h = 1125899906842597L; // a large prime
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }
}
