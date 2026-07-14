package it.unige.portcommand.nlp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static it.unige.portcommand.nlp.ConfidenceGate.Branch.A_HIGH_CONFIDENCE;
import static it.unige.portcommand.nlp.ConfidenceGate.Branch.B_MEDIUM_DCG_ONLY;
import static it.unige.portcommand.nlp.ConfidenceGate.Branch.C_CLARIFICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locked half-open boundaries (planning/14 §14.4): every confidence in [0,1] maps to
 * exactly one branch, no overlap, no gap. Upper boundary of a range belongs to it. */
class ConfidenceGateTest {

    private final ConfidenceGate gate = new ConfidenceGate();

    @ParameterizedTest(name = "route({0}) -> {1}")
    @CsvSource({
            "1.0,  A_HIGH_CONFIDENCE",
            "0.60, A_HIGH_CONFIDENCE",
            "0.61, A_HIGH_CONFIDENCE",
            "0.55, B_MEDIUM_DCG_ONLY",
            "0.40, B_MEDIUM_DCG_ONLY",
            "0.3999, C_CLARIFICATION",
            "0.39, C_CLARIFICATION",
            "0.0,  C_CLARIFICATION",
    })
    void routesConfidenceToTheLockedBranch(double confidence, ConfidenceGate.Branch expected) {
        assertEquals(expected, gate.route(confidence));
    }

    @org.junit.jupiter.api.Test
    void boundariesAreExhaustiveAndNonOverlapping() {
        assertEquals(A_HIGH_CONFIDENCE, gate.route(0.60));
        assertEquals(B_MEDIUM_DCG_ONLY, gate.route(Math.nextDown(0.60)));
        assertEquals(B_MEDIUM_DCG_ONLY, gate.route(0.40));
        assertEquals(C_CLARIFICATION, gate.route(Math.nextDown(0.40)));
    }
}
