package it.unige.portcommand.commlog;

import java.awt.Color;
import java.util.List;
import java.util.stream.Collectors;

import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PerformativeColoursTest {

    private static final List<Integer> CANONICAL_TEN = List.of(
            ACLMessage.REQUEST, ACLMessage.PROPOSE, ACLMessage.ACCEPT_PROPOSAL, ACLMessage.REJECT_PROPOSAL,
            ACLMessage.CFP, ACLMessage.CONFIRM, ACLMessage.INFORM, ACLMessage.REFUSE, ACLMessage.CANCEL,
            ACLMessage.DISCONFIRM);

    @Test
    void everyCanonicalPerformativeHasItsOwnDistinctColour() {
        List<Color> colours = CANONICAL_TEN.stream().map(PerformativeColours::colourFor).collect(Collectors.toList());
        assertEquals(CANONICAL_TEN.size(), colours.stream().distinct().count(),
                "every one of the 10 canonical performatives must map to a DISTINCT colour");
    }

    @Test
    void unknownPerformativeFallsBackToTheDefaultColour() {
        Color queryRefColour = PerformativeColours.colourFor(ACLMessage.QUERY_REF);
        for (int p : CANONICAL_TEN) {
            assertNotEquals(PerformativeColours.colourFor(p), queryRefColour,
                    "an unmapped performative must not accidentally collide with a real one");
        }
        // Calling it twice must be stable (pure function, no hidden state).
        assertEquals(queryRefColour, PerformativeColours.colourFor(ACLMessage.QUERY_REF));
    }
}
