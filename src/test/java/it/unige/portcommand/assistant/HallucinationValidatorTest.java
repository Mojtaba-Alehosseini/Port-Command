package it.unige.portcommand.assistant;

import java.util.List;

import it.unige.portcommand.artifacts.MarketStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pass/fail samples for the three-check hallucination guard (planning/10 §10.4, v1.1 fixes).
 */
class HallucinationValidatorTest {

    private static Recommendation tankerAtBerth3() {
        MarketStats stats = new MarketStats(6000.0, 500.0, 12, 5000.0, 7000.0, 0.8, false);
        return new Recommendation("accept", 6000.0, 1.0, 4900.0, List.of("R7"), stats,
                "tanker", "berth_3", 6, "compatible", null);
    }

    @Test
    void validOutputWithAllNumbersAndKnownEntitiesPasses() {
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on 12 recent deals). "
                + "Acceptance probability 100%, expected value €4900. Berth 3 confirmed.";
        assertTrue(HallucinationValidator.validate(output, tankerAtBerth3()));
    }

    @Test
    void outputOmittingARequiredDecisionFigureFails() {
        // Identical to the valid-output case except the EXPECTED VALUE (4900) is dropped. The
        // three decision figures — price, acceptance probability, expected value — are what
        // Recommendation.requiredFigures() demands, because they are the recommendation.
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on 12 recent deals). "
                + "Acceptance probability 100%. Berth 3 confirmed.";
        assertFalse(HallucinationValidator.validate(output, tankerAtBerth3()));
    }

    /**
     * The 2026-07-27 (task 26) narrowing, pinned from the other side: a paraphrase that keeps
     * every decision figure but words a SUPPORTING one — here the sample count — is accepted.
     *
     * <p>This test is the reason the narrowing is safe to have made. Requiring all eight figures
     * verbatim scored 2/20 against the real sidecar: Phi-4-mini reliably writes "based on recent
     * deals" instead of "based on 12 recent deals", which is true, useful, and not a
     * hallucination. Failing it pinned the Assistant to its template with no visible symptom.
     * Check 3 is unaffected — see {@link #outputThatAltersASupportingFigureStillFails()}.
     */
    @Test
    void outputWordingASupportingFigureInsteadOfQuotingItPasses() {
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on recent deals). "
                + "Acceptance probability 100%, expected value €4900. Berth 3 confirmed.";
        assertTrue(HallucinationValidator.validate(output, tankerAtBerth3()),
                "omitting a supporting figure is not a hallucination; altering one is");
    }

    /**
     * The other half of the same contract: the positive control still spans EVERY figure, so a
     * sample count the model changed (12 → 40) is rejected even though all three decision figures
     * are present. Narrowing check 1 must not have narrowed check 3.
     */
    @Test
    void outputThatAltersASupportingFigureStillFails() {
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on 40 recent deals). "
                + "Acceptance probability 100%, expected value €4900. Berth 3 confirmed.";
        assertFalse(HallucinationValidator.validate(output, tankerAtBerth3()),
                "40 is not a figure this recommendation contains — the positive control must catch it");
    }

    /**
     * The narrowing's cost, pinned so it is a stated behaviour rather than an accident.
     *
     * <p>An adversarial review (task 26) produced this counter-example: the sample count 12 is
     * replaced by 500, which is this same trace's stddev. Check 1 no longer requires the 12, and
     * check 3 admits the 500 because it IS a figure of this recommendation — so the output is
     * accepted even though "based on 500 recent deals" is wrong. Under the old, wider check 1 it
     * was rejected, for the accidental reason that the 12 was missing rather than because the 500
     * was wrong.
     *
     * <p>This is documented in {@link Recommendation#requiredFigures()} rather than fixed: closing
     * it means requiring the sample count again, which is precisely what scored 2/20 against the
     * real model. The failure it admits is a figure swapped for another true figure of the same
     * recommendation; the failure it still catches is a figure invented or altered to a value the
     * recommendation never contained, which is the one that can misrepresent a price.
     */
    @Test
    void outputSwappingASupportingFigureForAnotherOfTheSameTraceIsAcceptedNow() {
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on 500 recent deals). "
                + "Acceptance probability 100%, expected value €4900. Berth 3 confirmed.";
        assertTrue(HallucinationValidator.validate(output, tankerAtBerth3()),
                "known and accepted cost of narrowing check 1 - see Recommendation#requiredFigures");
    }

    /**
     * The exact shape of the two traces that still fail the 18/20 gate, kept as a test so the
     * behaviour is deliberate rather than incidental: restating a 0 % acceptance probability as
     * "100 % likelihood of rejection" is faithful arithmetic, but 100 is a number the
     * recommendation never contained, and the guard cannot tell that apart from an altered price.
     */
    @Test
    void restatingZeroPercentAcceptanceAsHundredPercentRejectionFails() {
        MarketStats stats = new MarketStats(1500.0, 90.0, 11, 1400.0, 1600.0, 0.5, false);
        Recommendation rejectSilent = new Recommendation("reject_silent", 0.0, 0.0, 0.0,
                List.of("R7"), stats, "ferry", "berth_1", 4, "incompatible", null);
        String output = "I advise declining without notice. The prevailing price for a 4-hour "
                + "ferry trip is €1500 (±€90), from 11 recent deals. Your proposed move has a "
                + "100% likelihood of rejection and an expected value of €0. Berth 1 is "
                + "incompatible.";
        assertFalse(HallucinationValidator.validate(output, rejectSilent),
                "100 is absent from allFigures() for a 0%-acceptance recommendation");
    }

    @Test
    void outputWithFabricatedVesselNameFails() {
        // Identical to the valid-output case (every required number present, including the
        // duration "6") except for the trailing fabricated name — isolates the failure to the
        // negative-control (step 2) check, rather than accidentally tripping step 1 first.
        String output = "Recommended accept at €6000. Market average is €6000 for 6 hours "
                + "(±€500, based on 12 recent deals). Acceptance probability 100%, "
                + "expected value €4900. Berth 3 confirmed. Vessel Aurora agrees.";
        assertFalse(HallucinationValidator.validate(output, tankerAtBerth3()),
                "an unknown proper noun (Aurora) not in the gazetteer must be rejected");
    }

    @Test
    void outputWithFabricatedExtraNumberFails() {
        // Every REQUIRED number is present (including the duration "6", so step 1 alone would
        // pass this), but an extra number (99) that traces back to nothing in the recommendation
        // is also present — the positive control (step 3) must catch it.
        String output = "Recommended accept at €6000. Market average is €6000 for 6 hours "
                + "(±€500, based on 12 recent deals). Acceptance probability 100%, "
                + "expected value €4900. Berth 3 confirmed. Also charges a €99 handling fee.";
        assertFalse(HallucinationValidator.validate(output, tankerAtBerth3()),
                "a fabricated extra number (99) not in the trace must be rejected");
    }

    @Test
    void currencyAndThousandsFormattingAllNormaliseToTheSameFigure() {
        MarketStats stats = new MarketStats(2000.0, 100.0, 15, 1900.0, 2100.0, 0.9, false);
        Recommendation rec = new Recommendation("counter", 2000.0, 0.75, 800.0, List.of("R7"), stats,
                "container_vessel", "berth_2", 4, "compatible", null);
        // "€2,000" (comma thousands separator) must match the required "2000" figure.
        String output = "Recommended counter at €2,000. Market average is €2,000 "
                + "(±€100, based on 15 recent deals). Acceptance probability 75%, "
                + "expected value €800. Berth 2 confirmed for 4 hours.";
        assertTrue(HallucinationValidator.validate(output, rec),
                "€2,000 / 2,000 must normalise to match the required figure 2000");
    }

    @Test
    void blankOutputFails() {
        assertFalse(HallucinationValidator.validate("", tankerAtBerth3()));
        assertFalse(HallucinationValidator.validate("   ", tankerAtBerth3()));
        assertFalse(HallucinationValidator.validate(null, tankerAtBerth3()));
    }
}
