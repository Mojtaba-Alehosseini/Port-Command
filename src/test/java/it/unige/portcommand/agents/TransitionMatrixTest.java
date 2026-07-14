package it.unige.portcommand.agents;

import java.util.Random;

import it.unige.portcommand.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Markov sampling is deterministic + reads the cumulative distribution correctly. */
class TransitionMatrixTest {

    @Test
    void nextStateReadsCumulativeDistribution() {
        TransitionMatrix m = TransitionMatrix.defaults(); // sunny: 0.70 / 0.25 / 0.05
        assertEquals("sunny", m.nextState("sunny", 0.0));
        assertEquals("sunny", m.nextState("sunny", 0.69));
        assertEquals("cloudy", m.nextState("sunny", 0.70)); // boundary -> next bucket
        assertEquals("cloudy", m.nextState("sunny", 0.94));
        assertEquals("stormy", m.nextState("sunny", 0.95));
        assertEquals("stormy", m.nextState("sunny", 0.999));
    }

    @Test
    void deterministicForSameRoll() {
        TransitionMatrix m = TransitionMatrix.defaults();
        assertEquals(m.nextState("stormy", 0.5), m.nextState("stormy", 0.5));
    }

    @Test
    void unknownStateReturnsItself() {
        assertEquals("calm", TransitionMatrix.defaults().nextState("calm", 0.5));
    }

    @Test
    void markovChainIsDeterministicForSameSeed() {
        TransitionMatrix m = TransitionMatrix.defaults();
        Random r1 = new RandomSource(42).forStream("weather");
        Random r2 = new RandomSource(42).forStream("weather");
        String s1 = "sunny";
        String s2 = "sunny";
        for (int i = 0; i < 20; i++) {
            s1 = m.nextState(s1, r1.nextDouble());
            s2 = m.nextState(s2, r2.nextDouble());
            assertEquals(s1, s2, "same master seed + 'weather' stream → identical chain");
        }
    }
}

