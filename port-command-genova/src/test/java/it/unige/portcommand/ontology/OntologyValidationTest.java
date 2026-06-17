package it.unige.portcommand.ontology;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link OntologyValidation} — populated from the generated
 * {@code port_ontology.pl} on the classpath — recognises exactly the five
 * canonical vessel types and four berths, and rejects unknown atoms.
 */
class OntologyValidationTest {

    @Test
    void recognisesAllFiveVesselTypes() {
        for (String vt : new String[] {"tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship"}) {
            assertTrue(OntologyValidation.isKnownVesselType(vt), "should know vessel type " + vt);
        }
        assertEquals(5, OntologyValidation.getVesselTypes().size(), "exactly five vessel types");
    }

    @Test
    void recognisesAllFourBerths() {
        for (String b : new String[] {"berth_1", "berth_2", "berth_3", "berth_4"}) {
            assertTrue(OntologyValidation.isKnownBerth(b), "should know berth " + b);
        }
        assertEquals(4, OntologyValidation.getBerths().size(), "exactly four berths");
    }

    @Test
    void rejectsUnknownVesselType() {
        assertFalse(OntologyValidation.isKnownVesselType("submarine"));
        assertFalse(OntologyValidation.isKnownVesselType("cargo"));   // root, not a vessel type
        assertFalse(OntologyValidation.isKnownVesselType("berth"));
        assertFalse(OntologyValidation.isKnownVesselType(null));
    }

    @Test
    void rejectsUnknownBerth() {
        assertFalse(OntologyValidation.isKnownBerth("berth_9"));
        assertFalse(OntologyValidation.isKnownBerth(null));
    }

    /**
     * Drift guard: the hardcoded set inside {@link VesselSpec} must equal the
     * set parsed from the generated ontology. If the ontology grows a sixth
     * vessel type and {@code VesselSpec} is not updated (or vice-versa), this fails.
     */
    @Test
    void vesselSpecKnownSetMatchesOntology() {
        assertEquals(new TreeSet<>(VesselSpec.knownVesselTypes()),
                new TreeSet<>(OntologyValidation.getVesselTypes()),
                "VesselSpec.knownVesselTypes() drifted from port_ontology.pl");
    }

    @Test
    void accessorsReturnUnmodifiableSets() {
        Set<String> types = OntologyValidation.getVesselTypes();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> types.add("x"));
    }
}
