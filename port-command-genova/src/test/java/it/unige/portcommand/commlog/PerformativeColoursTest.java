package it.unige.portcommand.commlog;

import java.awt.Color;
import java.util.List;
import java.util.stream.Collectors;

import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** WCAG relative luminance of an sRGB colour. */
    private static double luminance(Color c) {
        double[] rgb = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) {
            rgb[i] = rgb[i] <= 0.03928 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    /** S18b (UI tree U14): "PROPOSE yellow on white, CANCEL light grey" — every colour reads on white. */
    @Test
    void everyColourReadsOnWhite() {
        List<Integer> withTheDefault = new java.util.ArrayList<>(CANONICAL_TEN);
        withTheDefault.add(ACLMessage.QUERY_REF);
        for (int p : withTheDefault) {
            Color c = PerformativeColours.colourFor(p);
            double contrast = 1.05 / (luminance(c) + 0.05);
            assertTrue(contrast >= 4.5, ACLMessage.getPerformative(p) + " " + c + " has contrast "
                    + String.format(java.util.Locale.ROOT, "%.2f", contrast) + " on white, below 4.5");
        }
    }

    /** S18b (U14): "REJECT and REFUSE nearly the same red" — no two are close. */
    @Test
    void noTwoColoursAreNearlyTheSame() {
        for (int a : CANONICAL_TEN) {
            for (int b : CANONICAL_TEN) {
                if (a < b) {
                    Color x = PerformativeColours.colourFor(a);
                    Color y = PerformativeColours.colourFor(b);
                    double distance = Math.sqrt(Math.pow(x.getRed() - y.getRed(), 2)
                            + Math.pow(x.getGreen() - y.getGreen(), 2) + Math.pow(x.getBlue() - y.getBlue(), 2));
                    assertTrue(distance >= 60, ACLMessage.getPerformative(a) + " and " + ACLMessage.getPerformative(b)
                            + " are only " + Math.round(distance) + " apart");
                }
            }
        }
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
