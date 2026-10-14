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

    // --- S18a (ADR-23): number forms that used to become the WRONG number ---------------------

    /**
     * Decision N2 - born red. The game is set in Genova, and a European writes one thousand nine
     * hundred as "1.900". The dot survived between digits (it must, for "1800.50"), the token
     * went into the goal as the Prolog float 1.9, and the vessel was offered a berth for EUR 1.90.
     */
    @ParameterizedTest(name = "[{0}] is one whole number")
    @org.junit.jupiter.params.provider.CsvSource({
            "1.900, 1900",
            "12.500, 12500",
            "1.900.000, 1900000",
            "999.999, 999999",
    })
    void europeanThousandsDotsAreCollapsed(String form, String expected) {
        assertEquals(List.of(expected), tokens(form));
    }

    @Test
    void europeanThousandsWorkWithACurrencySymbolAndInASentence() {
        assertEquals(List.of("1900", "eur"), tokens("€1.900"));
        assertEquals(List.of("1900", "eur"), tokens("1.900€"));
        assertEquals(List.of("1900", "for", "6", "h"), tokens("1.900 for 6h"));
        assertEquals(List.of("make", "it", "2500", "eur"), tokens("make it 2.500€"));
    }

    /**
     * Review finding - born red. A sentence ends with a full stop, and "make it 2.200." was still
     * read as 2.2: the first version refused any dot beside the number. Only a dot that goes on
     * into more DIGITS says "this is not a thousands group".
     */
    @Test
    void europeanThousandsSurviveTheSentencesOwnPunctuation() {
        assertEquals(List.of("make", "it", "2200"), tokens("make it 2.200."));
        assertEquals(List.of("1900", "eur"), tokens("€1.900."));
        assertEquals(List.of("1900"), tokens("1.900..."));
        assertEquals(List.of("hmm", "1900"), tokens("hmm...1.900"));
        assertEquals(List.of("i'll", "give", "you", "1900"), tokens("I'll give you 1.900."));
        assertEquals(List.of("25000"), tokens("25.000."));
        assertEquals(List.of("1900", "6", "h"), tokens("1.900,6h"), "a comma before hours is a comma");
    }

    /** ...and the decimal comma that goes with the thousands dot is a decimal point. */
    @Test
    void aDecimalCommaBehindAThousandsDotIsADecimal() {
        assertEquals(List.of("1900.50"), tokens("1.900,50"));
        assertEquals(List.of("1900.50", "eur"), tokens("€1.900,50"));
        assertEquals(List.of("1900", "6", "h"), tokens("1.900, 6h"));
    }

    /** The other reading must survive: a dot followed by one or two digits is a decimal point. */
    @Test
    void aDecimalPointIsNotAThousandsSeparator() {
        assertEquals(List.of("1800.50"), tokens("1800.50"));
        assertEquals(List.of("1.5"), tokens("1.5"));
        assertEquals(List.of("0.500"), tokens("0.500"), "a leading zero is a decimal, never 500");
        assertEquals(List.of("1.9000"), tokens("1.9000"), "four digits after the dot is not a thousands group");
    }

    /**
     * Decision N1 - born red. "+200" and "-200" lost their sign in the punctuation strip and
     * arrived as a bare 200: a counter of EUR 200. A signed number is a change to the offer on the
     * table, which the grammar already knows as "200 more" / "200 less".
     */
    @Test
    void aSignedNumberIsAChangeNotAPrice() {
        assertEquals(List.of("200", "more"), tokens("+200"));
        assertEquals(List.of("200", "less"), tokens("-200"));
        assertEquals(List.of("200", "more"), tokens("+ 200"));
        assertEquals(List.of("200", "less"), tokens("−200"), "the typographic minus sign too");
        assertEquals(List.of("200", "more"), tokens("+€200"), "the currency of a change is the offer's");
        assertEquals(List.of("200", "more"), tokens("+200€"));
        assertEquals(List.of("10", "percent", "more"), tokens("+10%"));
        assertEquals(List.of("5", "percent", "less"), tokens("-5%"));
        assertEquals(List.of("1900", "more"), tokens("+1.900"), "the thousands dot is read first");
    }

    /** Review finding - born red: the sign was only seen in three of the shapes it is typed in. */
    @Test
    void aSignedNumberIsReadInEveryShapeItIsTypedIn() {
        assertEquals(List.of("200", "more"), tokens("+200 eur"), "a currency WORD is dropped like the symbol");
        assertEquals(List.of("200", "more"), tokens("+200 euros"));
        assertEquals(List.of("200", "less"), tokens("-200 euro"));
        assertEquals(List.of("200", "less"), tokens("- 200"), "a line that opens with the sign");
        assertEquals(List.of("200", "less"), tokens("  −  200"));
        assertEquals(List.of("carthago", ":", "200", "more"), tokens("Carthago:+200"));
        assertEquals(List.of("200", "more"), tokens("(+200)"));
        assertEquals(List.of("200", "more", "europe"), tokens("+200 europe"), "not every word that starts with eur");
    }

    /**
     * A sign in front of HOURS is a separator, not a change: "2000 + 6 hours" is a price and a
     * stay, and it parsed as one before S18a. Only a signed number that could be money is
     * rewritten - and the whole number is looked at, so "+60 hours" is not split into a 6 and a 0.
     */
    @Test
    void aSignInFrontOfHoursIsNotAPriceChange() {
        assertEquals(List.of("2000", "6", "h"), tokens("2000 + 6 hours"));
        assertEquals(List.of("2000", "6", "h"), tokens("2000 +6h"));
        assertEquals(List.of("2000", "6", "h"), tokens("2000 -6h"));
        assertEquals(List.of("2000", "6", "h"), tokens("2000 -6 hrs"));
        assertEquals(List.of("60", "h"), tokens("+60 hours"));
        assertEquals(List.of("6", "h"), tokens("+ 6 hour"));
        assertEquals(List.of("200", "more", "for", "6", "h"), tokens("+200 for 6 hours"),
                "the price change is still read when hours follow the price");
    }

    /** A hyphen that is not a sign stays punctuation: it joins words, ranges and berth ids. */
    @Test
    void aHyphenThatIsNotASignIsStillASeparator() {
        assertEquals(List.of("berth", "5"), tokens("berth-5"));
        assertEquals(List.of("ok", "2000"), tokens("ok - 2000"), "a dash between words is a dash");
        assertEquals(List.of("5", "6", "h"), tokens("5-6 hours"));
        assertEquals(List.of("auto", "accept"), tokens("auto-accept"));
        assertEquals(List.of("2000", "for", "6", "h"), tokens("2000 - for 6h"));
    }

    /** Playtest 2, PT7: every spelling of a berth reaches the grammar as [berth, N] - the valid
     * ones parse, and an invalid one can then be answered by number. A PIN, not a born-red test:
     * these spellings already normalised before S18a (the playtest's "berth-5" failed on the
     * NUMBER, not the hyphen); it is here so the answer-by-number has something to stand on. */
    @ParameterizedTest(name = "[{0}] normalises to berth 5")
    @ValueSource(strings = {"berth 5", "berth5", "berth-5", "berth_5", "Berth #5", "BERTH-5"})
    void everySpellingOfABerthIsBerthPlusNumber(String berthForm) {
        assertEquals(List.of("berth", "5"), tokens(berthForm));
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
