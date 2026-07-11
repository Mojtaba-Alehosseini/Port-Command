package it.unige.portcommand.behaviours.coordination;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.core.MessageFactory;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aborts an in-progress escort on {@code CANCEL}: removes the active movement
 * behaviour (so the tug does NOT keep crawling toward a dead target — the "behaviour
 * leak" the task warns about), clears {@code currentJob}, and sends the tug back to
 * base via {@link ReturnToBaseBehaviour}.
 *
 * <p>This is a long-lived {@code CyclicBehaviour}, so it never calls
 * {@code removeBehaviour(this)} — it removes the <em>other</em> (movement) behaviour,
 * which is safe (only {@code removeBehaviour(this)} nulls the caller's {@code myAgent}).
 * A CANCEL for an already-idle tug is ignored.
 */
public final class HandleCancelBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleCancelBehaviour.class);
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.CANCEL),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    private final TugAgent tug;

    public HandleCancelBehaviour(TugAgent tug) {
        super(tug);
        this.tug = tug;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        if (tug.currentJob() == null && tug.activeMovement() == null) {
            log.debug("{} CANCEL while idle — nothing to abort", tug.tugId());
            return;
        }
        log.info("{} CANCEL from {} -> abort, return to base",
                tug.tugId(), msg.getSender().getLocalName());

        Behaviour active = tug.activeMovement();
        if (active != null) {
            tug.removeBehaviour(active); // remove the *movement* behaviour, not this cyclic one
        }
        tug.clearJob();
        ReturnToBaseBehaviour ret = new ReturnToBaseBehaviour(tug);
        tug.setActiveMovement(ret);
        tug.setStatus(TugStatus.RETURNING);
        tug.addBehaviour(ret);
    }
}
