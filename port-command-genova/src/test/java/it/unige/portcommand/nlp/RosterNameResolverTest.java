package it.unige.portcommand.nlp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The MED fuzzy layer's guard rails (S6, audit P2-1). Each case name states the rule it pins;
 * the end-to-end parse behaviour lives in {@code DcgRegressionIT}'s two typo goldens.
 */
class RosterNameResolverTest {

    private static DialogueCtx roster(DialogueCtx.RosterEntry... entries) {
        return new DialogueCtx(null, DialogueCtx.StandingOffer.NONE, List.of(entries), null);
    }

    private static DialogueCtx.RosterEntry vessel(String id, String name) {
        return new DialogueCtx.RosterEntry(id, "cargo_vessel", name, null, null);
    }

    private static final DialogueCtx MAERSK = roster(vessel("C009", "Maersk Genova"));

    @Test
    void theProjectDefinitionExampleResolves() {
        // §2.2's acceptance example verbatim: "Marsk Genoba" -> "Maersk Genova" (distance 2).
        List<String> out = RosterNameResolver.resolve(
                List.of("marsk", "genoba", ":", "accept"), MAERSK);
        assertEquals(List.of("maersk", "genova", ":", "accept"), out);
    }

    @Test
    void anExactMatchIsUntouchedEvenWithACloseSiblingOnTheRoster() {
        DialogueCtx ctx = roster(vessel("C001", "Carthago"), vessel("C002", "Cartago"));
        List<String> tokens = List.of("cartago", ":", "deal");
        assertSame(tokens, RosterNameResolver.resolve(tokens, ctx),
                "an exact roster name must never be rewritten toward a fuzzy neighbour");
    }

    @Test
    void aTieBetweenTwoDifferentNamesRefuses() {
        // Both one edit from the typo -> refusing mirrors resolve_name/3's ambiguity-fails
        // contract (Moji, S6) instead of silently binding the wrong vessel.
        DialogueCtx ctx = roster(vessel("C001", "Aurora"), vessel("C002", "Aurola"));
        List<String> tokens = List.of("aurita", ":", "deal");
        // aurita->aurora = 2, aurita->aurola = 2 -> tie -> untouched.
        assertSame(tokens, RosterNameResolver.resolve(tokens, ctx));
    }

    @Test
    void tooShortWindowsAndNamesOnlyMatchExactly() {
        DialogueCtx shortName = roster(vessel("C001", "Bo"));
        List<String> tokens = List.of("so", ":", "deal");
        assertSame(tokens, RosterNameResolver.resolve(tokens, shortName),
                "MIN_LEN guards both sides: 'so' must not fuzz onto a 2-char roster name");
    }

    @Test
    void numericWindowsAndNumericNamesRefuse() {
        DialogueCtx numeric = roster(vessel("W001", "walkin 3"));
        List<String> tokens = List.of("walkin", "4", ":", "deal");
        assertSame(tokens, RosterNameResolver.resolve(tokens, numeric),
                "a numeric token in the window (or candidate) must never rewrite — it would "
                        + "re-emit bare and unify with the grammar's number terminals");
    }

    @Test
    void theCommandTargetSiteRewritesAfterToTheAndForThe() {
        DialogueCtx ctx = roster(vessel("T001", "Carthago"));
        assertEquals(List.of("send", "two", "tugs", "to", "the", "carthago"),
                RosterNameResolver.resolve(
                        List.of("send", "two", "tugs", "to", "the", "carthagoo"), ctx));
        assertEquals(List.of("hold", "a", "berth", "for", "the", "carthago"),
                RosterNameResolver.resolve(
                        List.of("hold", "a", "berth", "for", "the", "cartago"), ctx));
    }

    @Test
    void grammarWordsNeverSitInANamePositionAndSurviveUntouched(){
        DialogueCtx ctx = roster(vessel("C001", "Genoa Star"), vessel("T001", "Carthago"));
        // No colon, no "to/for the" marker: nothing is a name window, whatever the distances.
        List<String> tokens = List.of("i", "will", "pay", "2000", "for", "5", "hours");
        assertSame(tokens, RosterNameResolver.resolve(tokens, ctx));
    }

    @Test
    void anEmptyRosterIsAlwaysANoOp() {
        List<String> tokens = List.of("marsk", "genoba", ":", "accept");
        assertSame(tokens, RosterNameResolver.resolve(tokens, DialogueCtx.NONE));
    }

    @Test
    void grammarTerminalsInASiteBWindowRefuseTheRewrite() {
        // "no to the tanker" is GRAMMAR (reject_bare + vessel_type_word), not a name — with a
        // roster vessel two edits from "tanker" the stop-list is the only thing standing
        // between a fleet command and a silent wrong-vessel bind (adversarial finding 6).
        DialogueCtx ctx = roster(vessel("T009", "Tangier"));
        List<String> tokens = List.of("no", "to", "the", "tanker");
        assertSame(tokens, RosterNameResolver.resolve(tokens, ctx));
    }

    @Test
    void idShapedRosterNamesAreNeverFuzzTargets() {
        // Production names are the WALKIN-<n> ids; ids are one edit apart from each other, so
        // "walkin:" fuzzing onto walkin-3 (distance 2) is the silent wrong-vessel class again
        // (adversarial finding 7). Digit-bearing candidates are exact-match-only.
        DialogueCtx ctx = roster(vessel("W003", "walkin-3"), vessel("W012", "walkin-12"));
        List<String> tokens = List.of("walkin", ":", "accept");
        assertSame(tokens, RosterNameResolver.resolve(tokens, ctx));
    }

    @Test
    void nonCanonicalWhitespaceRosterNamesAreSkippedAsCandidates() {
        // A double-space name could never round-trip through atomic_list_concat's single-space
        // join, so rewriting toward it would produce a token list that still fails to resolve.
        DialogueCtx ctx = roster(vessel("C001", "Genoa  Star"));
        List<String> tokens = List.of("genoa", "sttar", ":", "deal");
        assertSame(tokens, RosterNameResolver.resolve(tokens, ctx));
    }

    /**
     * The drift-proof NamePool pin (S8 adversarial finding 3): every pool name is checked
     * against the REAL {@code GRAMMAR_STOP} set — a stop word added later that silently makes
     * a pool name exact-match-only fails HERE, where the real set lives, not in a hand-copied
     * mirror in another package.
     */
    @Test
    void everyNamePoolWordAvoidsTheRealGrammarStopSet() {
        for (String name : it.unige.portcommand.core.NamePool.all()) {
            for (String word : name.toLowerCase(java.util.Locale.ROOT).split(" ")) {
                org.junit.jupiter.api.Assertions.assertFalse(
                        RosterNameResolver.grammarStopWords().contains(word),
                        "pool name '" + name + "' contains grammar terminal '" + word
                                + "' — MED would refuse its window and the name loses fuzzy "
                                + "resolution entirely");
            }
        }
    }
}
