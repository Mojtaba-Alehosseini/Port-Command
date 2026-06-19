package it.unige.portcommand.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reproducibility tests for {@link RandomSource}. */
class RandomSourceTest {

    @Test
    void sameSeedProducesSameSequence() {
        RandomSource a = new RandomSource(42);
        RandomSource b = new RandomSource(42);
        for (int i = 0; i < 10; i++) {
            assertEquals(a.nextInt(1000), b.nextInt(1000));
            assertEquals(a.nextDouble(), b.nextDouble());
            assertEquals(a.nextBoolean(), b.nextBoolean());
        }
    }

    @Test
    void setSeedResetsSequence() {
        RandomSource a = new RandomSource(1);
        int first = a.nextInt(1_000_000);
        a.setSeed(1);
        assertEquals(first, a.nextInt(1_000_000));
    }

    @Test
    void differentSeedsDiffer() {
        RandomSource a = new RandomSource(1);
        RandomSource b = new RandomSource(2);
        boolean anyDiff = false;
        for (int i = 0; i < 5; i++) {
            if (a.nextInt(1_000_000) != b.nextInt(1_000_000)) {
                anyDiff = true;
            }
        }
        assertTrue(anyDiff, "two different seeds should diverge within a few draws");
    }
}
