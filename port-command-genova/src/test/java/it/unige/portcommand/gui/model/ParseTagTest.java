package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.nlp.ParseProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** S15 (2026-10-12): the grey {@code (DCG)} / {@code (Rasa 0.97)} note after a player's message. */
class ParseTagTest {

    @Test
    void aSentenceTheGrammarParsedReadsDcg() {
        assertEquals("(DCG)", ParseTag.text(ParseProvenance.DCG));
    }

    @Test
    void aRasaRouteShowsItsConfidenceToTwoDecimals() {
        assertEquals("(Rasa 0.97)", ParseTag.text(ParseProvenance.rasa(0.97)));
    }

    @Test
    void theConfidenceIsTruncatedNotRounded() {
        // The measured "so?" false accept: 0.9997 must not read as a certainty Rasa never claimed.
        assertEquals("(Rasa 0.99)", ParseTag.text(ParseProvenance.rasa(0.9997)));
    }

    @Test
    void fullConfidenceReadsOnePointZeroZero() {
        assertEquals("(Rasa 1.00)", ParseTag.text(ParseProvenance.rasa(1.0)));
    }

    @ParameterizedTest(name = "{0} -> (Rasa {1})")
    @CsvSource({
            "0.85,      0.85",  // the wire floor, exactly
            "0.95,      0.95",  // the accept floor, exactly
            "0.949,     0.94",  // just under the accept floor: never lifted onto it
            "0.8499999, 0.84",
            "0.999999,  0.99",
            "0.9,       0.90",  // always two decimals
            "0.5,       0.50",
            "0.0,       0.00",
            "0.29,      0.29",  // 0.29 is 0.28999… in binary: must not truncate to 0.28
            "0.57,      0.57",  // same trap
            "0.00001,   0.00",  // 1.0E-5 as a double: no exponent in the tag
    })
    void truncatesAtTheSecondDecimal(double confidence, String shown) {
        assertEquals("(Rasa " + shown + ")", ParseTag.text(ParseProvenance.rasa(confidence)));
    }

    @Test
    void anUnknownOrMissingSourceGetsNoTag() {
        assertEquals("", ParseTag.text(ParseProvenance.UNKNOWN),
                "a result that does not say where it came from must not be guessed at");
        assertEquals("", ParseTag.text(null));
        assertEquals("", ParseTag.text(new ParseProvenance(null, 0.9)), "a record with no stage is unknown too");
        assertEquals("", ParseTag.text(new ParseProvenance(ParseProvenance.Stage.UNKNOWN, 0.9)),
                "the stage decides, not a stray confidence");
    }

    @Test
    void theDcgTagNeverCarriesANumber() {
        assertEquals("(DCG)", ParseTag.text(new ParseProvenance(ParseProvenance.Stage.DCG, 0.42)),
                "a grammar parses or it does not");
    }

    @Test
    void aRasaRouteWithNoUsableConfidenceStillSaysRasa() {
        assertEquals("(Rasa)", ParseTag.text(ParseProvenance.rasa(Double.NaN)));
        assertEquals("(Rasa)", ParseTag.text(ParseProvenance.rasa(Double.POSITIVE_INFINITY)));
        assertEquals("(Rasa)", ParseTag.text(ParseProvenance.rasa(Double.NEGATIVE_INFINITY)));
    }

    @Test
    void theDecimalSeparatorDoesNotFollowTheDefaultLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            // German writes 0,97 — like the Danish and Italian desktops this game is played on.
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("0,97", String.format("%.2f", 0.97),
                    "non-vacuity: the default locale now uses a decimal comma");

            assertEquals("(Rasa 0.97)", ParseTag.text(ParseProvenance.rasa(0.97)));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }
}
