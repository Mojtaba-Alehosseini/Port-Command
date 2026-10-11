package it.unige.portcommand.negotiation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14 (ADR-18): how long a walk-in waits, in real seconds. The numbers are Moji's playability
 * decision of 2026-10-11 — this table IS the decision, so a change here is a product change.
 */
class PatiencePolicyTest {

    @ParameterizedTest
    @CsvSource({
            "ferry,            120",
            "container_vessel, 150",
            "tanker,           180",
            "cruise_ship,      180",
            "cargo_vessel,     240",
    })
    void baseByVesselType(String vesselType, int expectedSeconds) {
        assertEquals(expectedSeconds, PatiencePolicy.baseSeconds(vesselType));
        assertEquals(expectedSeconds * 1_000L, PatiencePolicy.budgetMillis(vesselType, Personality.NEUTRAL),
                "a neutral vessel waits exactly its type's base");
    }

    @Test
    void personalityShiftsTheBudgetByThirtySeconds() {
        assertEquals(30, PatiencePolicy.modifierSeconds(Personality.DESPERATE));
        assertEquals(-30, PatiencePolicy.modifierSeconds(Personality.AGGRESSIVE));
        assertEquals(0, PatiencePolicy.modifierSeconds(Personality.NEUTRAL));

        assertEquals(150_000L, PatiencePolicy.budgetMillis("ferry", Personality.DESPERATE));
        assertEquals(90_000L, PatiencePolicy.budgetMillis("ferry", Personality.AGGRESSIVE));
        assertEquals(270_000L, PatiencePolicy.budgetMillis("cargo_vessel", Personality.DESPERATE));
        assertEquals(210_000L, PatiencePolicy.budgetMillis("cargo_vessel", Personality.AGGRESSIVE));
    }

    @Test
    void theReplyBonusAndItsCap() {
        assertEquals(30_000L, PatiencePolicy.REPLY_BONUS_MILLIS);
        assertEquals(300_000L, PatiencePolicy.CAP_REMAINING_MILLIS);
    }

    @Test
    void everyOpeningBudgetIsPlayableAndUnderTheCap() {
        // The reason this session exists: a human has to be able to read, think and type.
        for (String type : VesselTemplates.types()) {
            for (Personality personality : Personality.values()) {
                long budget = PatiencePolicy.budgetMillis(type, personality);
                assertTrue(budget >= 90_000L, type + "/" + personality + " opens with only " + budget + " ms");
                assertTrue(budget <= PatiencePolicy.CAP_REMAINING_MILLIS,
                        type + "/" + personality + " opens above the cap: " + budget);
            }
        }
    }

    @Test
    void everyTemplatedVesselTypeHasABase() {
        // The five PROJECT_DEFINITION types, and nothing else: a sixth template without a base
        // must fail here, not as an IllegalArgumentException on a vessel's agent thread.
        assertEquals(5, VesselTemplates.types().size());
        for (String type : VesselTemplates.types()) {
            assertTrue(PatiencePolicy.baseSeconds(type) > 0, type);
        }
        assertThrows(IllegalArgumentException.class, () -> PatiencePolicy.baseSeconds("pleasure"));
    }
}
