package it.unige.portcommand.nlp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production DCGParser wiring until task 16 lands: always a miss. */
class NoOpDCGParserTest {

    @Test
    void alwaysReturnsEmpty() {
        DCGParser parser = new NoOpDCGParser();

        assertTrue(parser.parse("I will give you 2000 for 5 hours at berth 3", DialogueCtx.NONE).isEmpty());
        assertTrue(parser.parse("", DialogueCtx.NONE).isEmpty());
    }
}
