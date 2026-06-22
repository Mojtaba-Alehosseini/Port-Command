package it.unige.portcommand.util;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Determinism + independence of {@link RandomSource#forStream}. */
class RandomSourceStreamTest {

    @Test
    void sameSeedAndNameGiveIdenticalStream() {
        Random a = new RandomSource(42).forStream("weather");
        Random b = new RandomSource(42).forStream("weather");
        for (int i = 0; i < 5; i++) {
            assertEquals(a.nextDouble(), b.nextDouble(), "same master seed + name → identical sub-stream");
        }
    }

    @Test
    void differentNamesGiveIndependentStreams() {
        RandomSource src = new RandomSource(42);
        Random weather = src.forStream("weather");
        Random customs = src.forStream("customs");
        boolean anyDiffer = false;
        for (int i = 0; i < 5; i++) {
            if (weather.nextDouble() != customs.nextDouble()) {
                anyDiffer = true;
                break;
            }
        }
        assertTrue(anyDiffer, "named sub-streams draw independent sequences");
    }

    @Test
    void drawsInOneStreamDoNotShiftAnother() {
        // The contract: adding/removing randomness in one consumer never shifts another's draws.
        Random customsControl = new RandomSource(7).forStream("customs");
        double c0 = customsControl.nextDouble();
        double c1 = customsControl.nextDouble();

        RandomSource src = new RandomSource(7);
        Random weather = src.forStream("weather");
        weather.nextDouble();
        weather.nextDouble();
        weather.nextDouble(); // exhaust some of the weather stream
        Random customsAfter = src.forStream("customs");
        assertEquals(c0, customsAfter.nextDouble(), 0.0, "customs stream unaffected by weather draws");
        assertEquals(c1, customsAfter.nextDouble(), 0.0);
    }
}
