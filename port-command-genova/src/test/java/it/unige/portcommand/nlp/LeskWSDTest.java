package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Optional;

import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.dictionary.Dictionary;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LeskWSD} smoke tests against the real WordNet 3.1 glosses (planning/16 Step 16.6).
 * Tagged {@code integration} — loads the dictionary from the resource jar.
 */
@Tag("integration")
class LeskWSDTest {

    private static LeskWSD lesk;

    @BeforeAll
    static void loadDictionary() throws Exception {
        lesk = new LeskWSD(Dictionary.getDefaultResourceInstance());
    }

    private static String glossOf(Optional<Synset> sense) {
        assertTrue(sense.isPresent(), "expected a winning sense");
        return sense.get().getGloss().toLowerCase();
    }

    @Test
    void picksTheHarbourSenseOfDockInAPortContext() {
        String gloss = glossOf(lesk.disambiguate("dock", List.of("ship", "pier", "water", "harbour")));

        assertTrue(gloss.contains("ship") || gloss.contains("pier") || gloss.contains("harbor"),
                "expected a maritime gloss, got: " + gloss);
        assertFalse(gloss.contains("court of law"), "must not pick the courtroom sense: " + gloss);
    }

    @Test
    void picksTheCourtroomSenseOfDockInALegalContext() {
        String gloss = glossOf(lesk.disambiguate("dock", List.of("court", "law", "defendant", "trial")));

        assertTrue(gloss.contains("court"), "expected the courtroom gloss, got: " + gloss);
    }

    /**
     * The two tests above are only meaningful together: the same word must yield DIFFERENT senses
     * for different contexts. If Lesk always returned sense 1 (the naive behaviour) both would
     * pick the courtroom and this assertion would fail.
     */
    @Test
    void theSameWordResolvesToDifferentSensesInDifferentContexts() {
        Optional<Synset> port = lesk.disambiguate("dock", List.of("ship", "pier", "water", "harbour"));
        Optional<Synset> legal = lesk.disambiguate("dock", List.of("court", "law", "defendant", "trial"));

        assertTrue(port.isPresent() && legal.isPresent());
        assertEquals(false, port.get().getGloss().equals(legal.get().getGloss()),
                "context must change the chosen sense, otherwise the WSD is decorative");
    }

    @Test
    void unknownWordYieldsNoSense() {
        assertEquals(Optional.empty(), lesk.disambiguate("zzzznotaword", List.of("ship", "pier")));
    }

    @Test
    void emptyContextYieldsNoSenseRatherThanGuessingSenseOne() {
        assertEquals(Optional.empty(), lesk.disambiguate("dock", List.of()),
                "with no context there is no signal; returning sense 1 would be the naive "
                        + "first-sense heuristic this class exists to avoid");
    }

    @Test
    void contextThatOverlapsNoGlossYieldsNoSense() {
        assertEquals(Optional.empty(), lesk.disambiguate("dock", List.of("zzz", "qqq", "xyzzy")));
    }

    /** Stop words must not create a spurious overlap — every gloss contains "a"/"the"/"of". */
    @Test
    void stopWordsOnlyContextYieldsNoSense() {
        assertEquals(Optional.empty(), lesk.disambiguate("dock", List.of("the", "a", "of", "in", "and")));
    }

    /** The target word appears in its own glosses; counting it would score every sense equally. */
    @Test
    void theTargetWordItselfDoesNotCountAsOverlap() {
        assertEquals(Optional.empty(), lesk.disambiguate("dock", List.of("dock")));
    }
}
