package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import it.unige.portcommand.core.RuleCatalogue;
import it.unige.portcommand.gui.model.RuleRefs.Ref;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): which pieces of comm-log text become clickable rule ids. The rule is narrow
 * on purpose — only inside a bracket group, only ids the catalogue knows — and every case here
 * is a string the log really produces or a near miss of one.
 */
class RuleRefsTest {

    /** The production predicate: the shipped catalogue, R1..R30. */
    private static final Predicate<String> KNOWN = RuleCatalogue.get()::contains;

    private static List<String> ids(List<Ref> refs) {
        return refs.stream().map(Ref::ruleId).toList();
    }

    @Test
    void aQueueReleaseCitationGivesFourSpansEachCoveringItsOwnId() {
        String text = "Granted berth berth_2 (after queue scheduled ferry rank 3 [R25,R26,R27,R30])";

        List<Ref> refs = RuleRefs.find(text, KNOWN);

        assertEquals(List.of("R25", "R26", "R27", "R30"), ids(refs), "text order");
        for (Ref ref : refs) {
            assertEquals(ref.ruleId(), text.substring(ref.start(), ref.end()),
                    "a span covers the id itself — no bracket, no comma");
        }
    }

    @Test
    void aSingleIdGroupIsFound() {
        String text = "Granted berth berth_1 (compatible [R7])";

        List<Ref> refs = RuleRefs.find(text, KNOWN);

        assertEquals(1, refs.size());
        assertEquals(new Ref(text.indexOf("R7"), text.indexOf("R7") + 2, "R7"), refs.get(0));
    }

    @Test
    void anIdOutsideABracketGroupIsNeverALink() {
        // A vessel id that happens to end in R7, the same inside brackets, an unclosed or
        // unopened group, a wrong separator, stray spaces, lower case, four digits, and the
        // line's own timestamp bracket.
        List<String> nearMisses = List.of(
                "WALKIN-R7",
                "[WALKIN-R7]",
                "[R7",
                "R7]",
                "R7",
                "[R7;R8]",
                "[R7 ,R8]",
                "[ R7]",
                "[R7 ]",
                "[R7,]",
                "[,R7]",
                "[]",
                "[R]",
                "[r7]",
                "[R1234]",
                "[08:14:03] harbour_master -> vessel_c501: ACCEPT-PROPOSAL \"Granted berth berth_1\"");

        for (String text : nearMisses) {
            assertEquals(List.of(), RuleRefs.find(text, KNOWN), () -> "must not link anything in: " + text);
            assertFalse(RuleRefs.citesAny(text, KNOWN), () -> "must not count as a citation: " + text);
        }
    }

    @Test
    void anIdTheCatalogueDoesNotKnowIsSkippedWithoutSpoilingItsNeighbour() {
        String text = "odd [R7,R31]";

        List<Ref> refs = RuleRefs.find(text, KNOWN);

        assertEquals(List.of("R7"), ids(refs), "there is no rule 31 to show");
        assertEquals("R7", text.substring(refs.get(0).start(), refs.get(0).end()));
    }

    @Test
    void idsThatOnlyLookLikeRulesYieldNothing() {
        assertEquals(List.of(), RuleRefs.find("[R0]", KNOWN), "rules start at R1");
        assertEquals(List.of(), RuleRefs.find("[R07]", KNOWN), "a zero-padded id is not a catalogue id");
        assertEquals(List.of(), RuleRefs.find("[R31]", KNOWN));
        assertEquals(List.of(), RuleRefs.find("[R999]", KNOWN));
    }

    @Test
    void oneSpaceAfterTheCommaIsAcceptedButNotTwo() {
        assertEquals(List.of("R15", "R16"), ids(RuleRefs.find("wind and visibility [R15, R16] — retry", KNOWN)));
        assertEquals(List.of(), RuleRefs.find("[R15,  R16]", KNOWN));
    }

    @Test
    void aGroupInsideJsonContentIsFound() {
        String content = "{\"event\":\"flagged\",\"reason\":\"blacklisted [R21,R22]\"}";

        List<Ref> refs = RuleRefs.find(content, KNOWN);

        assertEquals(List.of("R21", "R22"), ids(refs));
        assertEquals("R21", content.substring(refs.get(0).start(), refs.get(0).end()));
        assertEquals("R22", content.substring(refs.get(1).start(), refs.get(1).end()));
    }

    @Test
    void everyGroupInTheTextIsFound() {
        assertEquals(List.of("R7", "R30", "R8"), ids(RuleRefs.find("queued [R7,R30]; none fits [R8]", KNOWN)));
    }

    @Test
    void citesAnyIsTrueExactlyWhenThereIsAKnownId() {
        assertTrue(RuleRefs.citesAny("Granted berth berth_1 (compatible [R8])", KNOWN));
        assertTrue(RuleRefs.citesAny("[R31,R29]", KNOWN), "one known id among unknown ones is enough");
        assertFalse(RuleRefs.citesAny("Granted berth berth_1", KNOWN));
        assertFalse(RuleRefs.citesAny("[R31]", KNOWN));
    }

    @Test
    void thePredicateDecidesWhatIsKnownNotThisClass() {
        assertEquals(List.of(), RuleRefs.find("[R7]", id -> false));
        assertEquals(List.of("R999"), ids(RuleRefs.find("[R999]", id -> true)));
    }

    @Test
    void atIsStartInclusiveEndExclusiveAndThePunctuationIsNotAHit() {
        //             0123456789
        String text = "x [R7,R8] y";
        List<Ref> refs = RuleRefs.find(text, KNOWN);
        assertEquals(List.of(new Ref(3, 5, "R7"), new Ref(6, 8, "R8")), refs);

        assertEquals(Optional.empty(), RuleRefs.at(refs, 2), "the opening bracket");
        assertEquals("R7", RuleRefs.at(refs, 3).orElseThrow().ruleId(), "start is inclusive");
        assertEquals("R7", RuleRefs.at(refs, 4).orElseThrow().ruleId());
        assertEquals(Optional.empty(), RuleRefs.at(refs, 5), "the comma — and R7's end is exclusive");
        assertEquals("R8", RuleRefs.at(refs, 6).orElseThrow().ruleId());
        assertEquals("R8", RuleRefs.at(refs, 7).orElseThrow().ruleId());
        assertEquals(Optional.empty(), RuleRefs.at(refs, 8), "the closing bracket");
        assertEquals(Optional.empty(), RuleRefs.at(refs, -1));
        assertEquals(Optional.empty(), RuleRefs.at(refs, text.length()));
    }

    @Test
    void nullAndEmptyInputsYieldNothing() {
        assertEquals(List.of(), RuleRefs.find(null, KNOWN));
        assertEquals(List.of(), RuleRefs.find("", KNOWN));
        assertFalse(RuleRefs.citesAny(null, KNOWN));
        assertEquals(Optional.empty(), RuleRefs.at(null, 0));
        assertEquals(Optional.empty(), RuleRefs.at(List.of(), 0));
    }

    @Test
    void aMissingPredicateIsAProgrammingErrorNotAnEmptyResult() {
        assertThrows(NullPointerException.class, () -> RuleRefs.find("[R7]", null));
    }

    @Test
    void theResultCannotBeModified() {
        List<Ref> refs = RuleRefs.find("[R7]", KNOWN);

        assertThrows(UnsupportedOperationException.class, () -> refs.add(new Ref(0, 1, "R1")));
    }
}
