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

    /** The live master seed — what {@link #forStream} derives from. Captured by task 22's save. */
    public long currentSeed() {
        return seed;
    }

    /**
     * The seed a save taken at {@code simMillis} stores (task 22; task-09's deferred
     * "save-seed support"). A <b>pure read</b> — no draw, so taking a save never shifts any
     * live stream — yet it evolves with the save instant: a game loaded from it derives
     * fresh {@link #forStream} sequences instead of replaying the from-boot ones (the
     * streams cannot literally continue mid-sequence — {@link Random} exposes no cursor —
     * so "continue deterministically" means: a deterministic function of the save file,
     * identical across repeated loads). Same state + same instant → same seed, which is
     * what keeps the round-trip byte-diff test exact.
     */
    public long saveSeedAt(long simMillis) {
        return seed ^ stableHash("save@" + simMillis);
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

    /**
     * Deterministic 64-bit polynomial hash (unlike 32-bit {@link String#hashCode()}),
     * finished with murmur3's fmix64 avalanche. Without the finalizer (checkpoint-#6 F2,
     * fixed 2026-07-18) adjacent stream names ("vessel-WALKIN-63"/"-64") produced child
     * seeds differing only in low bits, and {@link Random}'s first draw is strongly
     * correlated across adjacent seeds — walk-in personalities (the first draw of each
     * per-vessel stream) locked into dozens-long single-personality runs. The avalanche
     * makes every output bit depend on every input bit, decorrelating sibling streams.
     */
    private static long stableHash(String s) {
        long h = 1125899906842597L; // a large prime
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
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
