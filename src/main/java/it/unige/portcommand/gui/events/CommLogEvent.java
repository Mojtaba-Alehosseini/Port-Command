package it.unige.portcommand.gui.events;

import java.util.List;

import it.unige.portcommand.util.Event;
import jade.lang.acl.ACLMessage;

/**
 * One parsed ACL message, for the CommLogPanel (task 18/19). Published by the
 * HarbourMaster's instrumented send/receive path ({@code HarbourMasterAgent.sendLogged}
 * / {@code .receiveLogged}, task 17) via {@code commlog.Paraphraser}. {@code performative}
 * is the raw {@link ACLMessage} int constant (e.g. {@link ACLMessage#REQUEST}) so a
 * renderer can look up both its display name ({@code ACLMessage.getPerformative(int)})
 * and its colour ({@code commlog.PerformativeColours}) without a second translation.
 *
 * @param simTimeMillis  sim time the message was sent/received
 * @param sender         sender's local name
 * @param receivers      receivers' local names, in envelope order
 * @param performative   the raw {@code ACLMessage} performative constant
 * @param paraphrase     the one-line natural-language rendering ({@code Paraphraser})
 * @param conversationId the FIPA conversation id, or {@code null} if the message carried none
 */
public record CommLogEvent(long simTimeMillis, String sender, List<String> receivers, int performative,
                            String paraphrase, String conversationId) implements Event {

    public CommLogEvent {
        receivers = receivers == null ? List.of() : List.copyOf(receivers);
    }
}
