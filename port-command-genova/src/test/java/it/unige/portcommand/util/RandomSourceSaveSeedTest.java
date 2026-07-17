package it.unige.portcommand.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Task 22 / task-09 deferred note: the save-seed support on {@link RandomSource}. */
class RandomSourceSaveSeedTest {

    @Test
    void currentSeedReportsTheLiveMasterSeed() {
        RandomSource source = new RandomSource(42L);
        assertEquals(42L, source.currentSeed());
        source.setSeed(7L);
        assertEquals(7L, source.currentSeed());
    }

    @Test
    void saveSeedIsAPureReadThatNeverShiftsLiveStreams() {
        RandomSource a = new RandomSource(42L);
        RandomSource b = new RandomSource(42L);
        a.saveSeedAt(123_456L); // must not perturb anything
        a.saveSeedAt(999_999L);
        for (int i = 0; i < 20; i++) {
            assertEquals(b.forStream("weather").nextDouble(), a.forStream("weather").nextDouble(),
                    "taking a save must not shift any derived stream");
        }
    }

    @Test
    void saveSeedEvolvesWithTheInstantButIsDeterministicPerInstant() {
        RandomSource source = new RandomSource(42L);
        assertEquals(source.saveSeedAt(1000L), source.saveSeedAt(1000L),
                "same state + same instant → same seed (the byte-diff round trip needs this)");
        assertNotEquals(source.saveSeedAt(1000L), source.saveSeedAt(2000L),
                "a later save derives a different seed — no from-boot stream replay after load");
    }

    @Test
    void reseedingWithASavedSeedGivesIdenticalStreamsAcrossRepeatedLoads() {
        long saved = new RandomSource(42L).saveSeedAt(5_000L);
        RandomSource loadOne = new RandomSource(0L);
        loadOne.setSeed(saved);
        RandomSource loadTwo = new RandomSource(99L);
        loadTwo.setSeed(saved);
        for (int i = 0; i < 20; i++) {
            assertEquals(loadOne.forStream("vessel-WALKIN-3").nextDouble(),
                    loadTwo.forStream("vessel-WALKIN-3").nextDouble(),
                    "two loads of the same save must stream identically");
        }
    }
}
