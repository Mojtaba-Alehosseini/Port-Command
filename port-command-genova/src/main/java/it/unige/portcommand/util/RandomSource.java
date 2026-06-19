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

    public RandomSource(long seed) {
        this.random = new Random(seed);
    }

    public void setSeed(long seed) {
        random.setSeed(seed);
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
