package it.unige.portcommand.nlp;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WordNetResolver} against the REAL WordNet 3.1 dictionary (planning/16 Step 16.5).
 *
 * <p>Tagged {@code integration}: it loads the ~translated WordNet database from the
 * {@code extjwnl-data-wn31} resource jar, which is far too heavy for the fast lane.
 *
 * <p><b>The fallthrough path is the point.</b> planning/16 is explicit that a WordNet layer whose
 * only tests hit the handcrafted shortcut is effectively untested, and that the fallthrough tests
 * are what justify shipping extjwnl at all. So the tests below deliberately exercise words that
 * are NOT in the table and assert results only the dictionary can produce.
 */
@Tag("integration")
class WordNetResolverTest {

    private static WordNetResolver resolver;

    @BeforeAll
    static void loadDictionary() {
        resolver = new WordNetResolver();
    }

    /** If this fails the whole suite is meaningless — every assertion below would be testing the
     * table-only degradation path instead of WordNet. Fail loudly rather than silently pass. */
    @Test
    void theWordNetDictionaryActuallyLoaded() {
        assertTrue(resolver.isDictionaryAvailable(),
                "extjwnl-data-wn31 must be on the classpath; without it these tests are vacuous");
    }

    // --- the handcrafted table has precedence -------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} (handcrafted)")
    @CsvSource({
            "freighter,     cargo_vessel",
            "merchantman,   cargo_vessel",
            "'cargo ship',  cargo_vessel",
            "slip,          berth",
            "loading,       berth",
            "FREIGHTER,     cargo_vessel",
    })
    void handcraftedMappingsWinAndAreCaseInsensitive(String word, String expected) {
        assertEquals(expected, resolver.resolveToOntology(word, "noun"));
    }

    /**
     * Precedence is load-bearing, not incidental: WordNet's own lemma for "freighter" is
     * "freighter" (its synset is [bottom, freighter, merchantman, merchant ship]), so if the
     * dictionary were consulted first this would return "freighter" and never reach the ontology
     * class. This test fails if the lookup order is ever flipped.
     */
    @Test
    void tableIsConsultedBeforeTheDictionary() {
        assertEquals("cargo_vessel", resolver.resolveToOntology("freighter", "noun"));
        assertNotEquals("freighter", resolver.resolveToOntology("freighter", "noun"));
    }

    // --- THE FALLTHROUGH: words NOT in the handcrafted table -----------------------------------

    /**
     * Morphological resolution over the full noun lexicon — the thing the handcrafted table
     * fundamentally cannot do, and therefore the clearest justification for shipping extjwnl.
     * Every input here is absent from HANDCRAFTED.
     */
    @ParameterizedTest(name = "{0} -> {1} (WordNet morphology)")
    @CsvSource({
            "ships,    ship",
            "vessels,  vessel",
            "berths,   berth",
            "tankers,  tanker",
            "ferries,  ferry",
            "piers,    pier",
    })
    void pluralsFallThroughToWordNetAndResolveToTheirLemma(String plural, String expectedLemma) {
        assertEquals(expectedLemma, resolver.resolveToOntology(plural, "noun"));
    }

    @ParameterizedTest(name = "{0} is a known WordNet noun and survives lookup")
    @ValueSource(strings = {"cargo", "ship", "vessel", "quay", "wharf", "harbor"})
    void generalNounsFallThroughToTheDictionary(String noun) {
        assertEquals(noun, resolver.resolveToOntology(noun, "noun"),
                "a base-form noun known to WordNet resolves to itself, via the dictionary");
    }

    /** planning/16: "words not in WordNet fall through to the literal input string". */
    @ParameterizedTest(name = "{0} is unknown to WordNet -> returned verbatim")
    @ValueSource(strings = {"zzzznotaword", "maersk", "carthago"})
    void wordsUnknownToWordNetAreReturnedVerbatim(String word) {
        assertEquals(word, resolver.resolveToOntology(word, "noun"));
    }

    // --- Lesk: the same word, two contexts, two answers ----------------------------------------

    /**
     * The WSD money shot. WordNet 3.1's FIRST sense of "dock" is the courtroom enclosure, so a
     * naive first-sense resolver answers "courtroom" here. Lesk over a port context picks the
     * harbour sense instead, which is what earns the {@code dock -> berth} mapping.
     */
    @Test
    void leskDisambiguatesDockToBerthInANegotiationContext() {
        String resolved = resolver.resolveToOntology(
                "dock", "noun", List.of("ship", "pier", "water", "harbour", "berth"));

        assertEquals("berth", resolved);
    }

    /**
     * The other half — without which the test above proves nothing, since "dock" maps to "berth"
     * in the table anyway. In a courtroom context the port mapping must NOT be applied: same
     * word, different sense, different answer. This is the test that shows Lesk is doing work.
     */
    @Test
    void leskRefusesTheBerthMappingInACourtroomContext() {
        String resolved = resolver.resolveToOntology(
                "dock", "noun", List.of("court", "law", "defendant", "trial", "jury"));

        assertNotEquals("berth", resolved,
                "dock-the-courtroom must not be mapped into the port ontology as a berth");
        assertEquals("dock", resolved);
    }

    /** No context to disambiguate from -> keep the negotiation-domain default from the table. */
    @Test
    void contextFreeDockKeepsTheTableDefault() {
        assertEquals("berth", resolver.resolveToOntology("dock", "noun"));
    }

    @Test
    void contextThatOverlapsNoSenseKeepsTheTableDefault() {
        assertEquals("berth", resolver.resolveToOntology("dock", "noun", List.of("zzz", "qqq")));
    }

    /** Lesk runs only for context-sensitive words: an ordinary table word ignores the context. */
    @Test
    void aNonContextSensitiveWordIgnoresTheContextEntirely() {
        assertEquals("cargo_vessel",
                resolver.resolveToOntology("freighter", "noun", List.of("court", "law", "trial")));
    }

    // --- S6 (audit P2-2): the widened ambiguous-word set ---------------------------------------

    /**
     * "slip" — 15 WordNet noun senses (a mistake, an undergarment, a piece of paper, potter's
     * clay …); the marine sense is [mooring, moorage, berth, slip]. In a harbour context the
     * table's {@code slip -> berth} mapping holds; in a "slip of the tongue" context it must
     * NOT — the same two-contexts-two-answers contract as dock.
     */
    @Test
    void leskDisambiguatesSlipToBerthInAHarbourContext() {
        assertEquals("berth", resolver.resolveToOntology(
                "slip", "noun", List.of("boat", "mooring", "berth", "harbour", "craft")));
    }

    @Test
    void leskRefusesTheBerthMappingForASlipOfTheTongue() {
        String resolved = resolver.resolveToOntology(
                "slip", "noun", List.of("tongue", "mistake", "awkward", "embarrassing", "blunder"));

        assertNotEquals("berth", resolved,
                "slip-the-mistake must not be mapped into the port ontology as a berth");
        assertEquals("slip", resolved);
    }

    /**
     * "liner" — 4 WordNet noun senses (a baseball line drive, a protective lining, a garment
     * lining, an ocean liner). Only the 4th is a vessel; its synset is [liner, ocean liner],
     * so the domain discriminator is the single lemma "ocean liner" (the ambiguous word itself
     * can never discriminate — it is a member of all four synsets).
     */
    @Test
    void leskDisambiguatesLinerToCruiseShipInAVoyageContext() {
        assertEquals("cruise_ship", resolver.resolveToOntology(
                "liner", "noun", List.of("ship", "passengers", "voyage", "commercial", "schedule")));
    }

    @Test
    void leskRefusesTheCruiseShipMappingForAGarmentLiner() {
        String resolved = resolver.resolveToOntology(
                "liner", "noun", List.of("cloth", "garment", "inside", "surface", "jacket"));

        assertNotEquals("cruise_ship", resolved,
                "liner-the-garment-lining must not become a cruise ship");
    }

    /**
     * The structural invariant behind all of the above (S6 design review): a context-sensitive
     * word must never appear in its own discriminator set — it is a lemma of EVERY one of its
     * synsets, so listing it makes the disambiguation vacuously always-domain. This is exactly
     * the trap "slip" fell into when the berth discriminators still contained "slip".
     */
    @Test
    void noContextSensitiveWordDiscriminatesItsOwnSenses() {
        WordNetResolver.CONTEXT_SENSITIVE.forEach((word, discriminators) -> {
            assertFalse(discriminators.contains(word),
                    "'" + word + "' appears in its own discriminator set — Lesk for it is vacuous");
            assertTrue(WordNetResolver.HANDCRAFTED.containsKey(word),
                    "'" + word + "' is context-sensitive but has no table mapping to defend");
        });
    }

    // --- degradation --------------------------------------------------------------------------

    /**
     * A box without the data jar must still serve the negotiation vocabulary rather than crash.
     * The grammar never depends on a WordNet-only synonym, so parses stay identical either way.
     */
    @Test
    void withoutADictionaryTheTableStillResolves() {
        WordNetResolver degraded = new WordNetResolver(null);

        assertEquals("cargo_vessel", degraded.resolveToOntology("freighter", "noun"));
        assertEquals("berth", degraded.resolveToOntology("slip", "noun"));
        assertEquals("berth", degraded.resolveToOntology("dock", "noun", List.of("ship", "pier")));
        assertEquals("ships", degraded.resolveToOntology("ships", "noun"),
                "no dictionary means no morphology — the input survives unchanged");
        assertEquals(false, degraded.isDictionaryAvailable());
    }

    @Test
    void blankInputIsReturnedUnchanged() {
        assertEquals("  ", resolver.resolveToOntology("  ", "noun"));
    }
}
