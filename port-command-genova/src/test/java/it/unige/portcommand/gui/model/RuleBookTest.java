package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.core.RuleCatalogue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19 (playtest 5, PT43): "let the teacher see all the rules, not only the ones cited in the log".
 * Help > Rules (30) lists every rule in its group, with its plain one-liner, the Prolog file it
 * lives in, and how often this game's comm log cited it.
 */
class RuleBookTest {

    @Test
    void allThirtyRulesInTheirFiveGroupsInOrder() {
        List<RuleBook.Group> groups = RuleBook.groups(RuleCatalogue.get(), Map.of());

        assertEquals(List.of("Compatibility (R1–R8)", "Escort (R9–R14)", "Weather (R15–R20)",
                "Customs (R21–R24)", "Priority (R25–R30)"), groups.stream().map(RuleBook.Group::title).toList());
        assertEquals(30, groups.stream().mapToInt(g -> g.lines().size()).sum());
        RuleBook.Line r7 = groups.get(0).lines().get(6);
        assertEquals("R7", r7.ruleId());
        assertEquals("rules_compatibility.pl", r7.file());
        assertEquals(RuleCatalogue.get().find("R7").orElseThrow().text(), r7.text());
        assertEquals("rules_escort.pl", groups.get(1).lines().get(0).file());
    }

    @Test
    void eachRuleSaysHowOftenThisGameCitedIt() {
        List<RuleBook.Group> groups = RuleBook.groups(RuleCatalogue.get(), Map.of("R7", 12, "R15", 3));

        assertEquals(12, groups.get(0).lines().get(6).usedThisGame());
        assertEquals(3, groups.get(2).lines().get(0).usedThisGame());
        assertTrue(groups.stream().flatMap(g -> g.lines().stream())
                .filter(l -> !l.ruleId().equals("R7") && !l.ruleId().equals("R15"))
                .allMatch(l -> l.usedThisGame() == 0), "a rule never cited says 0");
    }
}
