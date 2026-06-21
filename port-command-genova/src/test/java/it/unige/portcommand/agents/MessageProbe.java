package it.unige.portcommand.agents;

import java.util.concurrent.CompletableFuture;

import it.unige.portcommand.core.MessageFactory;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Test agent: sends one message (given performative + content, built via
 * {@link MessageFactory} so it carries the envelope) to a target by local name,
 * and — when a reply future is supplied — captures the correlated reply (matched
 * by conversation id, which {@code createReply} preserves).
 *
 * <p>args: {@code [String target, Integer performative, String content|null,
 * CompletableFuture<ACLMessage> replySlot|null]}.
 */
public final class MessageProbe extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        Object[] args = getArguments();
        String target = (String) args[0];
        int performative = (Integer) args[1];
        String content = (String) args[2];
        CompletableFuture<ACLMessage> replySlot = (CompletableFuture<ACLMessage>) args[3];
        String convId = "conv-" + getLocalName();

        ACLMessage msg = MessageFactory.create(performative);
        msg.addReceiver(new AID(target, AID.ISLOCALNAME));
        msg.setConversationId(convId);
        if (content != null) {
            msg.setContent(content);
        }
        send(msg);

        if (replySlot != null) {
            MessageTemplate tmpl = MessageTemplate.MatchConversationId(convId);
            addBehaviour(new CyclicBehaviour(this) {
                @Override
                public void action() {
                    ACLMessage reply = myAgent.receive(tmpl);
                    if (reply == null) {
                        block();
                        return;
                    }
                    replySlot.complete(reply);
                    myAgent.removeBehaviour(this);
                }
            });
        }
    }
}
