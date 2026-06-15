package it.unige.portcommand.scaffold;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated JPL native-binding test. If this fails, the SWI-Prolog / JPL / Java
 * native bridge is broken and the whole project is blocked (task 01 exit ramp).
 */
@Tag("integration")
class JplProbeIT {

    @Test
    void jplBindsAndAnswersQueries() {
        assertTrue(assertDoesNotThrow(JplProbe::check),
                "JPL probe should consult smoke.pl and prove member(2,[1,2,3])");
    }
}
