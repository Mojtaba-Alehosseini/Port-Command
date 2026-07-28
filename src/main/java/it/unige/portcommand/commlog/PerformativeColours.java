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

    private static final Color DEFAULT = new Color(120, 120, 120);

    private static final Map<Integer, Color> COLOURS = Map.ofEntries(
            Map.entry(ACLMessage.REQUEST, new Color(66, 133, 244)),
            Map.entry(ACLMessage.PROPOSE, new Color(251, 188, 5)),
            Map.entry(ACLMessage.ACCEPT_PROPOSAL, new Color(52, 168, 83)),
            Map.entry(ACLMessage.REJECT_PROPOSAL, new Color(234, 67, 53)),
            Map.entry(ACLMessage.CFP, new Color(171, 71, 188)),
            Map.entry(ACLMessage.CONFIRM, new Color(0, 150, 136)),
            Map.entry(ACLMessage.INFORM, new Color(96, 125, 139)),
            Map.entry(ACLMessage.REFUSE, new Color(230, 74, 25)),
            Map.entry(ACLMessage.CANCEL, new Color(158, 158, 158)),
            Map.entry(ACLMessage.DISCONFIRM, new Color(216, 27, 96)));

    private PerformativeColours() {
    }

    public static Color colourFor(int performative) {
        return COLOURS.getOrDefault(performative, DEFAULT);
    }
}
