package it.unige.portcommand.nlp;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tokeniser's normalisation table and Prolog marshalling (planning/16 Step 16.3 + its
 * acceptance criteria). Pure JVM — no Prolog engine, no network.
 */
class DcgTokenizerTest {

    private final DcgTokenizer tokenizer = new DcgTokenizer();

    private List<String> tokens(String text) {
        return tokenizer.tokenize(text);
    }

    // --- the acceptance-criterion normalisations -------------------------------------------

    @ParameterizedTest(name = "[{0}] normalises to 2000 eur")
    @ValueSource(strings = {"€2000", "€ 2000", "2000€", "2000 €"})
    void currencySymbolsNormaliseToEur(String priceForm) {
        assertEquals(List.of("2000", "eur"), tokens(priceForm),
                "every EUR symbol placement must reach the grammar as the same two tokens");
    }

    @ParameterizedTest(name = "[{0}] normalises to 2000 usd")
    @ValueSource(strings = {"$2000", "$ 2000", "2000$", "2000 $"})
    void currencySymbolsNormaliseToUsd(String priceForm) {
        assertEquals(List.of("2000", "usd"), tokens(priceForm));
    }

    @ParameterizedTest(name = "[{0}] normalises to berth 2")
    @ValueSource(strings = {"berth2", "berth_2", "berth 2"})
    void fusedBerthFormsNormaliseToBerthPlusNumber(String berthForm) {
        // Task 19 play-test: players type what the UI shows ("berth_1") or fuse it ("berth2");
        // the grammar's terminals only ever see [berth, N].
        assertEquals(List.of("berth", "2"), tokens(berthForm));
    }

    @Test
    void berthFusedNormalisationDoesNotSplitOtherWords() {
        // "berthing" must not become "berth ing"; only a DIGIT glued to "berth" splits.
        assertEquals(List.of("berthing", "fee"), tokens("berthing fee"));
    }

    /**
     * Division of labour: the tokeniser only rewrites the SYMBOLS (€/$), because they fuse to the
     * digits and would not survive punctuation stripping. Word forms are passed through untouched
     * and mapped by the grammar's {@code optional_currency//1} — the layer that already owns the
     * closed set of currency lexemes. Pinned so a future "helpful" tokeniser rewrite does not
     * silently duplicate (and then drift from) the grammar's vocabulary.
     */
    @ParameterizedTest(name = "[{0}] is passed through for the grammar to map")
    @ValueSource(strings = {"euros", "euro", "eur", "dollars", "dollar", "usd"})
    void currencyWordFormsAreLeftForTheGrammar(String word) {
        assertEquals(List.of("2000", word), tokens("2000 " + word));
    }

    @ParameterizedTest(name = "[{0}] normalises to 5 h")
    @ValueSource(strings = {"5h", "5 h", "5hrs", "5 hrs", "5hr", "5hours", "5 hours", "5hour"})
    void durationFormsNormaliseToH(String durationForm) {
        assertEquals(List.of("5", "h"), tokens(durationForm),
                "every hour spelling must reach the grammar as the same two tokens");
    }

    @Test
    void clockTimeStaysASingleToken() {
        assertEquals(List.of("by", "14:20"), tokens("by 14:20"),
                "a clock time must survive punctuation stripping as ONE token");
    }

    /**
     * The acceptance-criterion utterance. Note "hours" arrives at the grammar as "h": the
     * tokeniser folds every hour spelling to one token, so the grammar's {@code time_unit//0}
     * alternatives for {@code hours}/{@code hour}/{@code hrs} are reachable only from the PLUnit
     * corpus (which uses the task file's literal token list), never from production input. Both
     * spellings parse identically — this test pins what the real chain actually emits.
     */
    @Test
    void theFullAcceptanceUtteranceTokenisesForTheGrammar() {
        assertEquals(
                List.of("i", "will", "give", "you", "2000", "for", "5", "h", "at", "berth", "3"),
                tokens("I will give you 2000 for 5 hours at berth 3"));
    }

    @Test
    void mixedNormalisationsInOneUtterance() {
        assertEquals(
                List.of("i", "will", "pay", "2000", "eur", "for", "5", "h", "at", "berth", "3", "by", "19:30"),
                tokens("I will pay €2,000 for 5hrs at berth 3 by 19:30"));
    }

    // --- stripping: what survives and what does not ------------------------------------------

    @Test
    void strayPunctuationIsStripped() {
        assertEquals(List.of("deal", "thanks"), tokens("Deal, thanks!"));
    }

    @Test
    void sentenceFinalDotDoesNotStickToTheWord() {
        assertEquals(List.of("no", "deal"), tokens("No deal."),
                "'deal.' would never match the grammar's [deal] terminal");
    }

    @Test
    void decimalPriceSurvivesAsOneNumber() {
        // A dropped decimal point would split 1800.50 into two tokens, and the grammar would
        // bind price 1800 and leave a stray 50 — silent corruption of the player's offer.
        assertEquals(List.of("i", "will", "pay", "1800.50"), tokens("I will pay 1800.50"));
    }

    @Test
    void thousandsSeparatorIsRemovedRatherThanSplitting() {
        assertEquals(List.of("2000"), tokens("2,000"),
                "2,000 must be one number, not the two tokens 2 and 000");
    }

    @Test
    void aCommaAfterANumberIsStillASeparator() {
        assertEquals(List.of("2000", "please"), tokens("2000, please"),
                "the thousands rule must not swallow an ordinary comma");
    }

    @Test
    void aNumberBeforeAnHWordIsNotTreatedAsADuration() {
        assertEquals(List.of("2000", "harbour", "fees"), tokens("2000 harbour fees"),
                "the hour-unit rule must not fire on any word that merely starts with h");
    }

    // --- clitics: kept whole, which is what makes the escaping load-bearing -------------------

    @Test
    void clitalApostropheIsKeptSoTheGrammarSeesOneToken() {
        assertEquals(List.of("i'll", "pay", "1800"), tokens("I'll pay 1800"));
    }

    @Test
    void typographicApostropheFoldsToAscii() {
        // A line pasted from a word processor must tokenise identically to one typed in chat.
        assertEquals(tokens("I'll pay 1800"), tokens("I’ll pay 1800"));
    }

    @Test
    void colonSplitsWhenItIsNotBetweenDigits() {
        // The vocative separator (16-M2) must not fuse onto the vessel name, while a clock must.
        assertEquals(List.of("genoa", "star", ":", "2000"), tokens("Genoa Star: 2000"));
    }

    // --- Prolog marshalling ------------------------------------------------------------------

    /** The exact goal argument {@code PrologDcgParser} hands to {@code parse_move/2}. */
    @Test
    void numbersAreEmittedBareAndWordsAreQuoted() {
        assertEquals("[i,will,give,you,2000,for,5,h,at,berth,3]",
                tokenizer.toPrologList(tokens("I will give you 2000 for 5 hours at berth 3")));
    }

    @Test
    void decimalNumberIsEmittedBare() {
        assertEquals("[1800.50]", tokenizer.toPrologList(List.of("1800.50")));
    }

    @Test
    void clockTokenIsQuotedBecauseItIsNotAnAtom() {
        assertEquals("[by,'14:20']", tokenizer.toPrologList(List.of("by", "14:20")));
    }

    /**
     * The escaping the brief calls out. This is NOT a hypothetical path: the tokeniser keeps
     * apostrophes (see {@link #clitalApostropheIsKeptSoTheGrammarSeesOneToken}), so an
     * O'Brien-class vessel name reaches the goal string for real. Unescaped, the atom would
     * close its own quote and the rest of the name would be parsed as Prolog source.
     *
     * <p>Written with Java string literals rather than {@code @CsvSource} because the expected
     * values are themselves backslash-and-quote soup — a CSV layer on top makes it impossible to
     * see what is actually being asserted.
     */
    @Test
    void apostrophesAreEscapedInsideQuotedAtoms() {
        // Prolog source: ['o\'brien']  — the backslash escapes the quote inside the atom.
        assertEquals("['o\\'brien']", tokenizer.toPrologList(List.of("o'brien")));
        assertEquals("['i\\'ll']", tokenizer.toPrologList(List.of("i'll")));
        assertEquals("['what\\'s']", tokenizer.toPrologList(List.of("what's")));
    }

    @Test
    void backslashesAreEscapedInsideQuotedAtoms() {
        assertEquals("['a\\\\b']", tokenizer.toPrologList(List.of("a\\b")));
    }

    @Test
    void anApostropheCannotTerminateTheAtomEarly() {
        String goal = tokenizer.toPrologList(tokens("O'Brien"));
        // The dangerous shape is a bare, unescaped quote closing the atom mid-token.
        assertFalse(goal.contains("'o'brien'"), "unescaped quote would break the goal: " + goal);
        assertTrue(goal.contains("\\'"), "the apostrophe must be backslash-escaped: " + goal);
    }

    @Test
    void anInjectedTermCannotEscapeTheAtomQuoting() {
        // Adversarial: a "vessel name" that tries to close the list and append a goal.
        List<String> injected = tokens("evil'],fail,[' ship");
        String goal = tokenizer.toPrologList(injected);
        assertFalse(goal.contains("],fail,["), "injection must not survive quoting: " + goal);
    }

    // --- degenerate input --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "..."})
    void emptyOrPunctuationOnlyInputYieldsNoTokens(String text) {
        assertEquals(List.of(), tokens(text));
    }

    @Test
    void nullInputYieldsNoTokens() {
        assertEquals(List.of(), tokens(null));
    }

    @Test
    void emptyTokenListMarshalsToTheEmptyPrologList() {
        assertEquals("[]", tokenizer.toPrologList(List.of()));
    }
}
