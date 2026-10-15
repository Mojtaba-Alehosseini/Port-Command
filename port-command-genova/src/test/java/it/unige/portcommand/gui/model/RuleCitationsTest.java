package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S19 (playtest 5, PT43): how often each rule was cited in this game's comm log - the "used this
 * game: N" of Help > Rules (30), and the counts the readable export ends with.
 */
class RuleCitationsTest {

    private static final java.util.function.Predicate<String> KNOWN =
            id -> Set.of("R7", "R8", "R12", "R15", "R19").contains(id);

    private static CommLogEvent row(String paraphrase, String content) {
        return new CommLogEvent(1L, "harbour_master", List.of("vessel_c501"), ACLMessage.ACCEPT_PROPOSAL,
                paraphrase, "berth-vessel_c501", "port_command_v1", null, content);
    }

    @Test
    void aMessageCountsOncePerRuleItCites() {
        RuleCitations citations = new RuleCitations();

        citations.count(row("berth_1 granted (compatible [R7])", "{\"reason\": \"compatible [R7]\"}"), KNOWN);
        citations.count(row("held [R15,R19]", null), KNOWN);
        citations.count(row("granted [R7]", null), KNOWN);

        assertEquals(2, citations.count("R7"), "the paraphrase and the content of one message are one citation");
        assertEquals(1, citations.count("R15"));
        assertEquals(1, citations.count("R19"));
        assertEquals(0, citations.count("R12"));
        assertEquals(Map.of("R7", 2, "R15", 1, "R19", 1), citations.all());
    }

    @Test
    void theContentAloneCountsAndAnUnknownIdDoesNot() {
        RuleCitations citations = new RuleCitations();

        citations.count(row("tug_2 wins the escort", "{\"why\": \"best score [R12]\"}"), KNOWN);
        citations.count(row("see [R99]", null), KNOWN);

        assertEquals(Map.of("R12", 1), citations.all());
    }

    @Test
    void theCountsAreInRuleOrderAndANewGameStartsAtZero() {
        RuleCitations citations = new RuleCitations();
        citations.count(row("[R15]", null), KNOWN);
        citations.count(row("[R8]", null), KNOWN);
        citations.count(row("[R12]", null), KNOWN);

        assertEquals(List.of("R8", "R12", "R15"), List.copyOf(citations.all().keySet()), "R8 before R12, by number");
        citations.reset();
        assertEquals(Map.of(), citations.all());
        assertEquals(0, citations.count("R8"));
    }
}
