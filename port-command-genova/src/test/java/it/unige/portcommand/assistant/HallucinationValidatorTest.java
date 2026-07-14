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
                "tanker", "berth_3", 6, "compatible");
    }

    @Test
    void validOutputWithAllNumbersAndKnownEntitiesPasses() {
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on 12 recent deals). "
                + "Acceptance probability 100%, expected value €4900. Berth 3 confirmed.";
        assertTrue(HallucinationValidator.validate(output, tankerAtBerth3()));
    }

    @Test
    void outputOmittingOneRequiredNumberFails() {
        // Identical to the valid-output case except the sample-count figure (12) is dropped.
        String output = "Recommended accept at €6000. The Berth is compatible for 6 hours. "
                + "Market average is €6000 (±€500, based on recent deals). "
                + "Acceptance probability 100%, expected value €4900. Berth 3 confirmed.";
        assertFalse(HallucinationValidator.validate(output, tankerAtBerth3()));
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
                "container_vessel", "berth_2", 4, "compatible");
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
