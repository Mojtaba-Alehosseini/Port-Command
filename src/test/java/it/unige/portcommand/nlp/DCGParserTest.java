package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full Java chain over the real grammar: raw English -> {@link DcgTokenizer} -> JPL ->
 * {@code parse_move/2} -> {@link Frame}. Covers all five move types (PROJECT_DEFINITION.md
 * §13.5's five-move-types half) plus the tokeniser criteria end-to-end.
 *
 * <p>Complements — does not duplicate — the PLUnit corpus in {@code test_dcg.pl}. PLUnit asserts
 * the grammar over hand-written TOKEN LISTS; this asserts the chain over raw UTTERANCES, which is
 * the only place tokeniser and grammar are proven to agree. The "5 hours" -> {@code [5, h]}
 * normalisation means the two layers speak measurably different token streams, so proving each in
 * isolation is not enough.
 */
class DCGParserTest {

    private static PrologDcgParser parser;

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
        parser = new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer());
    }

    private static Frame parse(String utterance) {
        Optional<Frame> frame = parser.parse(utterance, DialogueCtx.NONE);
        assertTrue(frame.isPresent(), () -> "expected a parse for: " + utterance);
        return frame.get();
    }

    private static void assertMiss(String utterance) {
        assertEquals(Optional.empty(), parser.parse(utterance, DialogueCtx.NONE),
                () -> "expected NO parse (-> Rasa fallback) for: " + utterance);
    }

    // --- the acceptance criterion --------------------------------------------------------------

    /**
     * planning/16's literal acceptance criterion: DCGParser.parse("I will give you 2000 for 5
     * hours at berth 3") returns Frame(commerce_sell, ...) with money.amount = 2000,
     * money.currency = EUR, duration = 5, berth = berth_3.
     */
    @Test
    void parsesTheAcceptanceCriterionUtterance() {
        Frame frame = parse("I will give you 2000 for 5 hours at berth 3");

        assertEquals("commerce_sell", frame.frameName());
        assertEquals("propose", frame.move());
        assertEquals(Map.of("amount", 2000L, "currency", "EUR"), frame.element("money"));
        assertEquals(5L, frame.element("duration"));
        assertEquals("berth_3", frame.element("berth"));
    }

    // --- all five move types, from raw English -------------------------------------------------

    @ParameterizedTest(name = "[{0}] -> move={1}")
    @CsvSource({
            "'I will give you 2000 for 5 hours at berth 3', propose",
            "'I''ll pay 1800 euros for berth 3',            propose",
            "'We offer 2200 for 6 hours',                   propose",
            "'I''ll only give you 1500',                    counter",
            "'How about 1800?',                             counter",
            "'I can only do 1500 for 4 hours',              counter",
            "'Deal',                                        accept",
            "'Yes, agreed!',                                accept",
            "'Sounds good',                                 accept",
            "'Too low',                                     reject",
            "'No deal',                                     reject",
            "'Not interested',                              reject",
            "'What berths are free?',                       ask",
            "'How many tugs do you have?',                  ask",
            "'What''s your best price?',                    ask",
    })
    void parsesAllFiveMoveTypesFromRawEnglish(String utterance, String expectedMove) {
        assertEquals(expectedMove, parse(utterance).move());
    }

    // --- tokeniser criteria, proven through the grammar ----------------------------------------

    @ParameterizedTest(name = "[{0}] -> 2000 EUR")
    @ValueSource(strings = {
            "I will give you €2000",
            "I will give you 2000€",
            "I will give you €2,000",
            "I will give you 2000 euros",
            "I will give you 2000 eur",
    })
    void everyEurSpellingReachesTheSameFrame(String utterance) {
        assertEquals(Map.of("amount", 2000L, "currency", "EUR"), parse(utterance).element("money"));
    }

    @ParameterizedTest(name = "[{0}] -> 2000 USD")
    @ValueSource(strings = {
            "I will give you $2000",
            "I will give you 2000$",
            "I will give you 2000 dollars",
    })
    void everyUsdSpellingReachesTheSameFrame(String utterance) {
        assertEquals(Map.of("amount", 2000L, "currency", "USD"), parse(utterance).element("money"));
    }

    @ParameterizedTest(name = "[{0}] -> duration 5")
    @ValueSource(strings = {
            "I will give you 2000 for 5 hours",
            "I will give you 2000 for 5h",
            "I will give you 2000 for 5hrs",
            "I will give you 2000 for 5 h",
    })
    void everyHourSpellingReachesTheSameFrame(String utterance) {
        assertEquals(5L, parse(utterance).element("duration"));
    }

    @Test
    void clockTimeSurvivesAsADeadline() {
        assertEquals("19:30", parse("We offer 2200 for 6 hours by 19:30").element("deadline"));
    }

    @Test
    void mixedNormalisationsParseEndToEnd() {
        Frame frame = parse("I'll pay €2,000 for 5hrs at berth 3 by 19:30");

        assertEquals(Map.of("amount", 2000L, "currency", "EUR"), frame.element("money"));
        assertEquals(5L, frame.element("duration"));
        assertEquals("berth_3", frame.element("berth"));
        assertEquals("19:30", frame.element("deadline"));
    }

    @Test
    void caseAndTrailingPunctuationAreIrrelevant() {
        assertEquals(parse("deal").elements(), parse("DEAL!").elements());
    }

    // --- reject reasons ------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] -> reason {1}")
    @CsvSource({
            "'Too low',       price_too_low",
            "'Too cheap',     price_too_low",
            "'Too expensive', price_too_high",
            "'Too high',      price_too_high",
    })
    void inferredRejectReason(String utterance, String expectedReason) {
        assertEquals(expectedReason, parse(utterance).element("reason"));
    }

    @Test
    void bareRejectCarriesNoReason() {
        Frame frame = parse("No deal");
        assertEquals(Map.of(Frame.MOVE, "reject"), frame.elements(),
                "a bare refusal must omit the reason slot, not bind a placeholder");
    }

    // --- precision: misses fall through to Rasa ------------------------------------------------

    @ParameterizedTest(name = "[{0}] does not parse")
    @ValueSource(strings = {
            "",
            "   ",
            "asdf qwer",
            "The weather is nice today",
            "I will give you",
            // Ellipsis/delta need a standing offer, so with the empty context (parse(text) uses
            // DialogueCtx.NONE) they correctly still miss; their positive parses are tested with a
            // real context in the 16-M2 section below.
            "2000",                       // 16-M2 ellipsis (needs a standing offer)
            "make it 2200",               // 16-M2 ellipsis (needs a standing offer)
            "200 more",                   // 16-M2 delta (needs a reference offer)
            "split the difference",       // 16-M2 delta (needs both offers)
            // "nothing below 2000" (negation) and "hold all tankers" (command) are ctx-FREE and now
            // parse even under the empty context — moved to positive cases in the 16-M2 section.
    })
    void outOfGrammarInputMissesRatherThanGuessing(String utterance) {
        assertMiss(utterance);
    }

    @Test
    void nullInputMissesRatherThanThrowing() {
        assertMiss(null);
    }

    /**
     * The mention-guards, end to end. Each names a slot the ontology/grammar cannot honour; the
     * parse must FAIL so the pipeline asks for clarification. Silently dropping the slot would
     * route a proposal that the player never made — and OntologyValidator could not catch it,
     * because the offending slot would be ABSENT rather than malformed.
     */
    @ParameterizedTest(name = "[{0}] fails rather than dropping the slot")
    @ValueSource(strings = {
            "I will give you 2000 at berth 9",
            "I will give you 2000 at berth 0",
            "I will give you 2000 for 99 hours",
            "I will give you 2000 for 0 hours",
            "We offer 2200 by 25:99",
    })
    void aNamedButInvalidSlotMissesRatherThanBeingDropped(String utterance) {
        assertMiss(utterance);
    }

    /**
     * <b>Semantic inversions.</b> Every bare move matches a one-word prefix, so if {@code
     * phrase/3}'s residue were ignored these would parse to the OPPOSITE of what the player said —
     * and the first two would emit a BINDING {@code ACCEPT_PROPOSAL} for an offer just refused.
     * They must miss and fall through to Rasa (which classifies them correctly). Found by the
     * task-16 adversarial review; see ADR-10.
     */
    @ParameterizedTest(name = "[{0}] must NOT parse to the opposite of what it says")
    @ValueSource(strings = {
            "ok but that's too low",                        // would ACCEPT an offer called too low
            "Yes if you drop to 1500",                      // conditional read as unconditional
            "No, I'll give you 1500",                       // the 1500 counter would vanish
            "no thanks, I'll give you 1500 instead",
            "Deal, but only for 3 hours",                   // the 3-hour condition would vanish
            "Deal for 99 hours",
            "yes at berth 9",
            "I accept, but at berth 2",                     // the berth constraint would vanish
            "no deal unless you pay 3000",
            "Fine. I'll give you 2500 for 5 hours at berth 2",
            "We offer 2200 by tomorrow",                    // the stated deadline would vanish
    })
    void anUtteranceWithMeaningfulResidueMissesRatherThanInvertingItsMeaning(String utterance) {
        assertMiss(utterance);
    }

    /** Non-vacuity for the block above: the residue rule must not reject everything with a tail.
     * Pure filler after a move is still ignorable. */
    @ParameterizedTest(name = "[{0}] still parses (filler tail)")
    @CsvSource({
            "'Deal, thanks',   accept",
            "'Yes please',     accept",
            "'No deal, mate',  reject",
            "'Too low please', reject",
            "'I will give you 2000 please', propose",
    })
    void aPureFillerResidueIsStillIgnorable(String utterance, String expectedMove) {
        assertEquals(expectedMove, parse(utterance).move());
    }

    // --- injection -----------------------------------------------------------------------------

    @Test
    void aQuoteInTheUtteranceCannotBreakTheGoalString() {
        // Must not throw: the atom quoting has to survive an apostrophe reaching the goal.
        assertMiss("O'Brien says no'],fail,['");
    }

    // --- 16-M2: the context-carrying grammar, end to end (tokeniser + ctx codec + grammar) -------
    // The fixture: Genoa Star (cargo) is the focus — vessel asked 2000, player bid 1500, over
    // berth_3 for 5h — with one tanker (Carthago) also active. Names carry real case ("Genoa Star")
    // to prove DialogueCtxTerm lower-cases them to match the tokeniser.

    private static final DialogueCtx M2CTX = new DialogueCtx(
            "C001",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"),
                    new DialogueCtx.OfferView(1500.0, 5, "berth_3")),
            List.of(new DialogueCtx.RosterEntry("C001", "cargo_vessel", "Genoa Star", "berth_3", 5),
                    new DialogueCtx.RosterEntry("T001", "tanker", "Carthago", "berth_2", 8)),
            "berth_3");

    private static Frame parseCtx(String utterance) {
        Optional<Frame> frame = parser.parse(utterance, M2CTX);
        assertTrue(frame.isPresent(), () -> "expected a parse for: " + utterance);
        return frame.get();
    }

    private static Map<String, Object> eur(long amount) {
        return Map.of("amount", amount, "currency", "EUR");
    }

    @Test
    void ellipsisCompletesABareFragmentFromTheStandingOffer() {
        Frame f = parseCtx("make it 2200");
        assertEquals("counter", f.move());
        assertEquals(eur(2200), f.element("money"));
        assertEquals(5L, f.element("duration"), "duration copied from the standing offer");
        assertEquals("berth_3", f.element("berth"), "berth copied from the standing offer");
    }

    @Test
    void deltaDoesArithmeticOverTheReferenceOffer() {
        assertEquals(eur(1700), parseCtx("200 more").element("money"));      // 1500 + 200
        assertEquals(eur(1350), parseCtx("10% less").element("money"));      // % normalisation -> 1500*0.9
        assertEquals(eur(1750), parseCtx("split the difference").element("money")); // midpoint(2000,1500)
    }

    @Test
    void negationBuildsAPolarityConstraint() {
        Frame f = parseCtx("nothing below 2000");
        assertEquals("constrain", f.move());
        assertEquals("negative", f.element("polarity"));
        assertEquals("below", f.element("bound"));
        assertEquals(eur(2000), f.element("money"));
    }

    @Test
    void anaphoraResolvesTheDefiniteDescriptionToTheUniqueVessel() {
        Frame f = parseCtx("accept the tanker");
        assertEquals("accept", f.move());
        assertEquals("T001", f.element("addressee"));
    }

    @Test
    void anaphoraRefusesAnAmbiguousTheTankerWithTwoTankersActive() {
        DialogueCtx twoTankers = new DialogueCtx(null, DialogueCtx.StandingOffer.NONE,
                List.of(new DialogueCtx.RosterEntry("T001", "tanker", "Carthago", "berth_2", 8),
                        new DialogueCtx.RosterEntry("T002", "tanker", "Aurora", "berth_1", 6)),
                null);
        assertEquals(Optional.empty(), parser.parse("accept the tanker", twoTankers),
                "two tankers -> the reference is ambiguous -> refuse (never guess)");
    }

    @Test
    void vocativeResolvesANameAcrossTheColonSeparator() {
        // End to end this exercises the ':' tokenisation AND the roster name match ("Genoa Star").
        Frame f = parseCtx("Genoa Star: deal");
        assertEquals("accept", f.move());
        assertEquals("C001", f.element("addressee"));
    }

    @Test
    void vocativeTellFormRoutesToTheAddressedType() {
        Frame f = parseCtx("tell the tanker no");
        assertEquals("reject", f.move());
        assertEquals("T001", f.element("addressee"));
    }

    @Test
    void commandBuildsAQuantifiedImperativeWithACondition() {
        Frame f = parseCtx("hold all tankers until the wind drops");
        assertEquals("command", f.frameName());
        assertEquals("hold", f.element("action"));
        assertEquals("all", f.element("quantifier"));
        assertEquals("tanker", f.element("patient"));
        assertEquals("until_wind_drop", f.element("condition"));
    }

    @Test
    void commandResolvesANamedTargetButStillMissesWithoutAQuantifier() {
        assertEquals("T001", parseCtx("send two tugs to the Carthago").element("target"));
        // no quantifier -> falls through to Rasa, exactly as in 16-M1 (regression seam)
        assertEquals(Optional.empty(), parser.parse("cancel the tug", M2CTX));
    }

    /** Residue attacks per block: a meaningful tail must REFUSE, never silently bind the head. */
    @ParameterizedTest(name = "[{0}] refuses its residue")
    @ValueSource(strings = {
            "make it 2200 but leave by 19:30",   // ellipsis + dropped condition
            "200 more but only if you drop",     // delta + dropped condition
            "accept the tanker but not the ferry", // anaphora + dropped exception
    })
    void aMeaningfulResidueIsRefusedNotSilentlyBound(String utterance) {
        assertEquals(Optional.empty(), parser.parse(utterance, M2CTX),
                () -> "expected NO parse (residue must not be dropped) for: " + utterance);
    }

    /** These parse ONLY with a context — under DialogueCtx.NONE they miss (proving ctx-dependence,
     * which is what keeps parse_move/2 identical to 16-M1). */
    @ParameterizedTest(name = "[{0}] needs a context")
    @ValueSource(strings = {"make it 2200", "200 more", "split the difference", "accept the tanker"})
    void theCtxDependentBlocksMissUnderTheEmptyContext(String utterance) {
        assertTrue(parser.parse(utterance, M2CTX).isPresent(), () -> "should parse with ctx: " + utterance);
        assertEquals(Optional.empty(), parser.parse(utterance, DialogueCtx.NONE),
                () -> "should MISS without ctx: " + utterance);
    }

    /** The other half of the injection surface: a hostile vessel NAME in the context reaches the
     * goal string via the roster term. It must stay an inert quoted atom — the parse resolves the
     * tanker by TYPE (unaffected by the name), and the payload never runs. */
    @Test
    void aHostileVesselNameInTheContextCannotInjectAGoal() {
        DialogueCtx hostile = new DialogueCtx("X", DialogueCtx.StandingOffer.NONE,
                List.of(new DialogueCtx.RosterEntry("X", "tanker", "'), assertz(pwned(yes)), ('", "berth_1", 5)),
                null);

        Frame f = parser.parse("tell the tanker no", hostile).orElseThrow();
        assertEquals("reject", f.move());
        assertEquals("X", f.element("addressee"), "resolved by type, not by the hostile name");
        // engine still sane — a plain parse works and no injected predicate poisoned it.
        assertTrue(parser.parse("deal", DialogueCtx.NONE).isPresent());
    }
}
