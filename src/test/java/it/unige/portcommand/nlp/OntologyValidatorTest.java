package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OntologyValidator} against the real ontology facts (planning/16 Step 16.7). Runs on the
 * live JPL engine — the whole point is that it agrees with the generated {@code port_ontology.pl},
 * so mocking the fact base would test nothing.
 */
class OntologyValidatorTest {

    /**
     * Table-only WordNet (no dictionary): keeps this in the FAST lane — loading the WordNet
     * database costs seconds. The handcrafted table alone still covers the vessel-class synonyms
     * this class needs, and the dictionary-backed fallthrough has its own integration coverage in
     * {@link WordNetResolverTest} / {@link OntologyValidatorWordNetIT}.
     */
    private final OntologyValidator validator = new OntologyValidator(new WordNetResolver(null));

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private static Frame frame(Object... keyValues) {
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put(Frame.MOVE, "propose");
        for (int i = 0; i < keyValues.length; i += 2) {
            elements.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return new Frame("commerce_sell", elements);
    }

    private static Map<String, Object> price(Object amount) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "EUR");
        return money;
    }

    // --- berths ------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} exists in the ontology")
    @ValueSource(strings = {"berth_1", "berth_2", "berth_3", "berth_4"})
    void acceptsEveryRealBerth(String berthId) {
        assertTrue(validator.validate(frame("berth", berthId)));
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {"berth_5", "berth_9", "berth_0", "quay_1", "not_a_berth", ""})
    void rejectsABerthTheOntologyDoesNotKnow(String berthId) {
        assertFalse(validator.validate(frame("berth", berthId)),
                "a frame naming a non-existent berth must not become an ACL message");
    }

    /** A vessel type is an instance_of/2 subject too — the berth check must not accept one. */
    @Test
    void rejectsAVesselTypeMasqueradingAsABerth() {
        assertFalse(validator.validate(frame("berth", "tanker")));
    }

    @Test
    void absentBerthIsFineBecauseTheHarbourMasterPicksOne() {
        assertTrue(validator.validate(frame()),
                "the vessel's opening PROPOSE carries no berth; HM resolves it via R8");
    }

    // --- vessel classes ----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is one of the five vessel types")
    @ValueSource(strings = {"tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship"})
    void acceptsEveryOntologyVesselType(String vesselType) {
        assertTrue(validator.validate(frame("vessel_class", vesselType)));
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {"pleasure", "submarine", "berth_1", "vessel"})
    void rejectsAClassOutsideTheFiveVesselTypes(String vesselType) {
        assertFalse(validator.validate(frame("vessel_class", vesselType)));
    }

    /**
     * planning/16 Step 16.7: the vessel class is checked AFTER WordNet normalisation. "freighter"
     * is not an ontology class, but it is the player's English for one — the validator must
     * consult the resolver rather than rejecting good English. Proven here with the handcrafted
     * table (no dictionary needed for these words).
     */
    @ParameterizedTest(name = "{0} normalises to an ontology class and is accepted")
    @ValueSource(strings = {"freighter", "merchantman", "cargo ship"})
    void acceptsAVesselClassThatOnlyResolvesViaWordNet(String synonym) {
        assertTrue(validator.validate(frame("vessel_class", synonym)),
                synonym + " must be normalised to cargo_vessel before the ontology is asked");
    }

    /** Non-vacuity for the test above: without normalisation these words are NOT ontology
     * classes, so a validator that skipped the resolver would reject them. */
    @Test
    void theSynonymsAreNotOntologyClassesOnTheirOwn() {
        assertFalse(it.unige.portcommand.prolog.PrologQueries.vesselTypeExists("freighter"),
                "if 'freighter' were already a vessel_type, the WordNet step would prove nothing");
    }

    // --- money -------------------------------------------------------------------------------

    @Test
    void acceptsNonNegativeMoney() {
        assertTrue(validator.validate(frame("money", price(2000L))));
        assertTrue(validator.validate(frame("money", price(0L))), "the criterion is money >= 0");
    }

    @Test
    void rejectsNegativeMoney() {
        assertFalse(validator.validate(frame("money", price(-1L))));
    }

    @Test
    void rejectsAMoneyElementThatIsNotADecodedPrice() {
        assertFalse(validator.validate(frame("money", "2000")));
    }

    @Test
    void rejectsAMoneyAmountThatIsNotANumber() {
        assertFalse(validator.validate(frame("money", price("lots"))));
    }

    // --- duration ----------------------------------------------------------------------------

    @ParameterizedTest(name = "duration {0}h accepted")
    @ValueSource(longs = {1L, 5L, 12L, 24L})
    void acceptsDurationWithinOneToTwentyFourHours(long hours) {
        assertTrue(validator.validate(frame("duration", hours)));
    }

    @ParameterizedTest(name = "duration {0}h rejected")
    @ValueSource(longs = {0L, -3L, 25L, 99L})
    void rejectsDurationOutsideOneToTwentyFourHours(long hours) {
        assertFalse(validator.validate(frame("duration", hours)));
    }

    @Test
    void rejectsAFractionalDuration() {
        assertFalse(validator.validate(frame("duration", 5.5)),
                "a fractional hour count is a grammar bug, not a roundable value");
    }

    @Test
    void rejectsADurationThatIsNotANumber() {
        assertFalse(validator.validate(frame("duration", "five")));
    }

    // --- composition -------------------------------------------------------------------------

    @Test
    void acceptsTheFullAcceptanceCriterionFrame() {
        assertTrue(validator.validate(
                frame("money", price(2000L), "duration", 5L, "berth", "berth_3")));
    }

    @Test
    void oneBadElementRejectsTheWholeFrame() {
        assertFalse(validator.validate(
                frame("money", price(2000L), "duration", 5L, "berth", "berth_9")),
                "validation is conjunctive — a good money slot must not rescue a bad berth");
    }

    @Test
    void nullFrameIsRejectedRatherThanThrowing() {
        assertFalse(validator.validate(null));
    }

    /** It is wired into NLPPipeline as the Predicate<Frame> seam, so test() must agree. */
    @Test
    void testDelegatesToValidate() {
        assertTrue(validator.test(frame("berth", "berth_1")));
        assertFalse(validator.test(frame("berth", "berth_9")));
    }
}
