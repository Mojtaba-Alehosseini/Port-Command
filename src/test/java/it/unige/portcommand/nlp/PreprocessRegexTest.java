package it.unige.portcommand.nlp;

import java.time.LocalTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven coverage of {@link PreprocessRegex}'s six extractors. Every row is drawn from
 * or modelled on real corpus phrasing (task 12's {@code nlu_authoring.csv}) — currency
 * variants, fused tokens, berth synonyms, vessel-name candidates.
 */
class PreprocessRegexTest {

    private final PreprocessRegex preprocess = new PreprocessRegex();

    // --- price: currency variants + fused tokens ---------------------------------------------

    @ParameterizedTest(name = "price in \"{0}\" -> {1}")
    @MethodSource("priceCases")
    void extractsPriceAcrossCurrencyVariants(String text, double expected) {
        assertEquals(expected, preprocess.extract(text).price().orElseThrow(), 1e-9);
    }

    static Stream<Arguments> priceCases() {
        return Stream.of(
                Arguments.of("I'll give you 2000 for 5 hours at berth 3", 2000.0),
                Arguments.of("€2000 for 5 hours", 2000.0),
                Arguments.of("2000€ for 5 hours", 2000.0),
                Arguments.of("$2000 for 5 hours", 2000.0),
                Arguments.of("How about we start at 2k for 4 hours", 2000.0),
                Arguments.of("I propose 1850 euros for 6 hours at pier 4", 1850.0),
                Arguments.of("Offering 2050 euros to dock for 5 hours", 2050.0),
                Arguments.of("My offer is 1600 for 6h", 1600.0));
    }

    @Test
    void noPriceCandidateIsEmptyNotException() {
        assertTrue(preprocess.extract("hello there").price().isEmpty());
    }

    // --- duration: fused tokens + spaced variants ----------------------------------------------

    @ParameterizedTest(name = "duration in \"{0}\" -> {1}")
    @MethodSource("durationCases")
    void extractsDurationAcrossFusedAndSpacedTokens(String text, int expected) {
        assertEquals(expected, preprocess.extract(text).duration().orElseThrow());
    }

    static Stream<Arguments> durationCases() {
        return Stream.of(
                Arguments.of("I'll give you 2000 for 5 hours at berth 3", 5),
                Arguments.of("My offer is 1600 for 6h", 6),
                Arguments.of("I'm offering 2600 for 5 hrs", 5),
                Arguments.of("I'll do 3500 for 8h", 8),
                Arguments.of("Pay you 1900 for 8 hours at berth 3", 8));
    }

    // --- berth: dock/quay/pier/slip synonyms + word-numbers -------------------------------------

    @ParameterizedTest(name = "berth in \"{0}\" -> {1}")
    @MethodSource("berthCases")
    void extractsBerthAcrossSynonyms(String text, String expected) {
        assertEquals(expected, preprocess.extract(text).berthId().orElseThrow());
    }

    static Stream<Arguments> berthCases() {
        return Stream.of(
                Arguments.of("at berth 3", "berth_3"),
                Arguments.of("at dock 1", "berth_1"),
                Arguments.of("at quay 2", "berth_2"),
                Arguments.of("at pier 4", "berth_4"),
                Arguments.of("at slip 3", "berth_3"),
                Arguments.of("at berth one", "berth_1"),
                Arguments.of("For 4h at berth two I'll pay 2500", "berth_2"),
                Arguments.of("at berth #3", "berth_3"));
    }

    // --- vessel name candidates -----------------------------------------------------------------

    @ParameterizedTest(name = "vessel name in \"{0}\" -> {1}")
    @MethodSource("vesselNameCases")
    void extractsVesselNameCandidates(String text, String expected) {
        assertEquals(expected, preprocess.extract(text).vesselName().orElseThrow());
    }

    static Stream<Arguments> vesselNameCases() {
        return Stream.of(
                Arguments.of("Genova Star: 2000 for 5h", "Genova Star"),
                Arguments.of("I will pay 1800 euros for berth 3 for the Maersk Genova", "Maersk Genova"),
                Arguments.of("tell the Atlantic Carrier no", "Atlantic Carrier"));
    }

    @Test
    void noVesselNameCandidateIsEmpty() {
        assertTrue(preprocess.extract("I'll give you 2000 for 5 hours at berth 3").vesselName().isEmpty());
    }

    // --- time / deadline ---------------------------------------------------------------------

    @Test
    void extractsColonTimeAsPlainTime() {
        assertEquals(LocalTime.of(14, 20), preprocess.extract("arrive at 14:20").time().orElseThrow());
    }

    @Test
    void extractsDeadlineFromByClause() {
        LocalTime deadline = preprocess.extract("deal, but only if you're out by 19:30").deadline().orElseThrow();
        assertEquals(LocalTime.of(19, 30), deadline);
    }

    @Test
    void extractsBareAmPmDeadline() {
        LocalTime deadline = preprocess.extract("only if you're out by 7pm").deadline().orElseThrow();
        assertEquals(LocalTime.of(19, 0), deadline);
    }

    @Test
    void deadlineClauseIsNotDoubleCountedAsPlainTime() {
        PreprocessRegex.Extracted extracted = preprocess.extract("out by 19:30");
        assertEquals(LocalTime.of(19, 30), extracted.deadline().orElseThrow());
        assertTrue(extracted.time().isEmpty());
    }

    // --- graceful misses -------------------------------------------------------------------------

    @Test
    void blankTextReturnsAllFieldsEmpty() {
        assertAllEmpty(preprocess.extract("   "));
    }

    @Test
    void nullTextReturnsAllFieldsEmpty() {
        assertAllEmpty(preprocess.extract(null));
    }

    private static void assertAllEmpty(PreprocessRegex.Extracted extracted) {
        assertTrue(extracted.price().isEmpty());
        assertTrue(extracted.duration().isEmpty());
        assertTrue(extracted.time().isEmpty());
        assertTrue(extracted.deadline().isEmpty());
        assertTrue(extracted.berthId().isEmpty());
        assertTrue(extracted.vesselName().isEmpty());
    }
}
