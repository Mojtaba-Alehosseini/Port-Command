package it.unige.portcommand.commlog;

import java.awt.Color;
import java.util.Map;

import jade.lang.acl.ACLMessage;

/**
 * Maps each canonical FIPA performative (PROJECT_DEFINITION §6.1) to a
 * distinct Swing colour for the comm log (task 18/19 render). An
 * unrecognised performative (e.g. {@code QUERY_REF}, which the player's
 * "ASK" command and the NLP {@code query_status}/{@code ask} paths really do
 * send) falls back to a neutral grey rather than throwing or returning
 * {@code null}.
 */
public final class PerformativeColours {

    /**
     * S18b (UI tree U14): darker and further apart. PROPOSE was yellow on white and CANCEL light
     * grey; REJECT and REFUSE were nearly the same red. Every colour now reads on white at
     * 4.5 : 1 or better (the WCAG contrast for body text, so it holds on a projector too), and
     * {@code PerformativeColoursTest} pins both that and how far apart any two are.
     */
    private static final Color DEFAULT = new Color(100, 100, 100);

    private static final Map<Integer, Color> COLOURS = Map.ofEntries(
            Map.entry(ACLMessage.REQUEST, new Color(26, 86, 196)),          // blue
            Map.entry(ACLMessage.PROPOSE, new Color(130, 110, 0)),          // dark gold
            Map.entry(ACLMessage.ACCEPT_PROPOSAL, new Color(30, 123, 52)),  // green
            Map.entry(ACLMessage.REJECT_PROPOSAL, new Color(183, 28, 28)),  // crimson
            Map.entry(ACLMessage.CFP, new Color(123, 31, 162)),             // purple
            Map.entry(ACLMessage.CONFIRM, new Color(0, 121, 107)),          // teal
            Map.entry(ACLMessage.INFORM, new Color(55, 71, 120)),           // slate blue
            Map.entry(ACLMessage.REFUSE, new Color(191, 87, 0)),            // burnt orange
            Map.entry(ACLMessage.CANCEL, new Color(93, 64, 55)),            // brown
            Map.entry(ACLMessage.DISCONFIRM, new Color(173, 20, 87)));      // magenta

    private PerformativeColours() {
    }

    public static Color colourFor(int performative) {
        return COLOURS.getOrDefault(performative, DEFAULT);
    }
}
