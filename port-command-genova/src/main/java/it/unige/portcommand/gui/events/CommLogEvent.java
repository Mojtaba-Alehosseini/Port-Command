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
 * <p>S15: the event also carries the three FIPA slots the paraphrase used to swallow — ontology,
 * protocol and content — so the comm log's Raw switch shows the REAL message and its filters can
 * tell a weather alert from a berth grant. They are copied by the one publisher
 * ({@code HarbourMasterAgent.publishCommLog}) with side-effect-free getters; nothing downstream
 * decides anything from them.
 *
 * @param simTimeMillis  sim time the message was sent/received
 * @param sender         sender's local name
 * @param receivers      receivers' local names, in envelope order
 * @param performative   the raw {@code ACLMessage} performative constant
 * @param paraphrase     the one-line natural-language rendering ({@code Paraphraser})
 * @param conversationId the FIPA conversation id, or {@code null} if the message carried none
 * @param ontology       the message's ontology slot as sent (S15, the comm log's Raw view), or
 *                       {@code null} when no message is attached
 * @param protocol       the message's protocol slot, or {@code null} if it carried none
 * @param content        the message's content exactly as on the wire (JSON text), or {@code null}
 */
public record CommLogEvent(long simTimeMillis, String sender, List<String> receivers, int performative,
                            String paraphrase, String conversationId,
                            String ontology, String protocol, String content) implements Event {

    public CommLogEvent {
        receivers = receivers == null ? List.of() : List.copyOf(receivers);
    }

    /**
     * The pre-S15 shape: a row with no message attached (tests, synthetic rows). The three raw
     * fields are {@code null}, and the Raw view shows a dash for each.
     */
    public CommLogEvent(long simTimeMillis, String sender, List<String> receivers, int performative,
                        String paraphrase, String conversationId) {
        this(simTimeMillis, sender, receivers, performative, paraphrase, conversationId, null, null, null);
    }
}
