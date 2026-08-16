package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.prolog.PrologQueries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OntologyValidator} composed with the REAL WordNet dictionary — the vessel-class half of
 * planning/16 Step 16.7 ("resolves to one of the five vessel_type classes AFTER WordNet/Lesk
 * normalisation") end to end, across BOTH resolver paths.
 *
 * <p>Separate from the fast-lane {@link OntologyValidatorTest}, which deliberately runs the
 * resolver table-only so the unit lane does not pay the WordNet load.
 */
@Tag("integration")
class OntologyValidatorWordNetIT {

    private static OntologyValidator validator;

    @BeforeAll
    static void setUp() {
        PrologEngine.getInstance().init();
        validator = new OntologyValidator(); // the shipped resolver, real dictionary
    }

    private static Frame vesselClass(String value) {
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put(Frame.MOVE, "propose");
        elements.put("vessel_class", value);
        return new Frame("commerce_sell", elements);
    }

    @ParameterizedTest(name = "{0} is accepted (already canonical)")
    @ValueSource(strings = {"tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship"})
    void canonicalClassesShortCircuitBeforeWordNet(String vesselType) {
        assertTrue(validator.validate(vesselClass(vesselType)));
    }

    @ParameterizedTest(name = "{0} -> cargo_vessel via the handcrafted table")
    @ValueSource(strings = {"freighter", "merchantman", "cargo ship"})
    void handcraftedSynonymsAreAccepted(String synonym) {
        assertTrue(validator.validate(vesselClass(synonym)));
    }

    /**
     * The WordNet FALLTHROUGH inside the validator: "tankers" is in neither the ontology nor the
     * handcrafted table — only the dictionary's morphology turns it into "tanker". This is the
     * composed version of the claim WordNetResolverTest makes in isolation.
     */
    @ParameterizedTest(name = "{0} is accepted via WordNet morphology")
    @ValueSource(strings = {"tankers", "ferries"})
    void aPluralIsAcceptedOnlyBecauseWordNetLemmatisesIt(String plural) {
        assertTrue(validator.validate(vesselClass(plural)),
                plural + " must reach the ontology as its lemma");
    }

    /** Non-vacuity: the plurals really are absent from the ontology, so the pass above is
     * attributable to WordNet and nothing else. */
    @ParameterizedTest
    @ValueSource(strings = {"tankers", "ferries"})
    void thePluralsAreNotOntologyClassesOnTheirOwn(String plural) {
        assertFalse(PrologQueries.vesselTypeExists(plural),
                "if '" + plural + "' were already a vessel_type, the morphology step would prove nothing");
    }

    @ParameterizedTest(name = "{0} is still rejected after normalisation")
    @ValueSource(strings = {"pleasure", "submarine", "zzzznotaword", "dock"})
    void aWordThatNormalisesToNothingUsefulIsStillRejected(String word) {
        assertFalse(validator.validate(vesselClass(word)),
                "WordNet must widen what is understood, never what is accepted into the ontology");
    }

    // --- S6 (audit P2-2): the utterance IS the Lesk context at the production seam -------------

    /**
     * The two-contexts-two-answers contract, at the validator's own seam rather than the
     * resolver's: the SAME frame ("liner" as the vessel class) validates under a voyage
     * utterance — Lesk picks the ocean-liner sense, the table maps it to {@code cruise_ship} —
     * and is rejected under a garment utterance, where the winning sense is a lining and the
     * word falls through unmapped. Before S6 this call site passed NO context, so both
     * utterances produced the same (table-blind) answer and the Lesk branch was dead code.
     */
    @Test
    void theSameFrameValidatesOrNotDependingOnTheUtterance() {
        Frame liner = vesselClass("liner");
        assertTrue(validator.validate(liner,
                        "book the liner in for the passengers on the commercial voyage schedule"),
                "a voyage context must resolve 'liner' to the ocean-liner sense -> cruise_ship");
        assertFalse(validator.validate(liner,
                        "the cloth liner inside the garment surface of my jacket"),
                "a garment context must NOT map 'liner' into the ontology");
    }

    /** The Rasa-only shape: no utterance context — the table default holds and nothing throws. */
    @Test
    void theContextFreeFacesStayTableDefaultForRasaOnlyTurns() {
        Frame liner = vesselClass("liner");
        assertTrue(validator.validate(liner),
                "the 1-arg face keeps the pre-S6 behaviour: table default, no Lesk");
        assertTrue(validator.test(liner), "test(frame) must agree with validate(frame)");
        assertTrue(validator.validate(liner, ""),
                "a blank utterance is pinned equivalent to the context-free face");
    }
}
