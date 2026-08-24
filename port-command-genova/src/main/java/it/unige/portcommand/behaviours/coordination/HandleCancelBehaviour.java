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
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.CANCEL),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    /** ACCEPT_PROPOSALs only — the half of {@code HandleAcceptRejectBehaviour.TEMPLATE} a
     * CANCEL can legitimately overtake (see the race note in {@link #action}). */
    private static final MessageTemplate OVERTAKEN_ACCEPT = MessageTemplate.and(
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY),
            MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL));

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
            // S10 (bug hunt, hunter B finding 2): receive() is template-selective, so this
            // CANCEL can OVERTAKE the very ACCEPT_PROPOSAL it cancels — both sit queued (the
            // HM sent ACCEPT then CANCEL within one storm pass) and the scheduler rotation ran
            // this cyclic first. Dropping the CANCEL as "idle" let the tug then honour the
            // stale ACCEPT and escort a vessel the port believes held — uncancellable (no
            // longer in the vessel's escort set) and the producer of a phantom
            // escort_complete. Honour the WIRE order instead: consume the queued ACCEPT and
            // discard it as cancelled-before-started. Deliberately no REFUSE — the normal
            // ordering (ACCEPT processed, then CANCEL aborts the leg) sends none and keeps
            // the charge (the hold path's stated no-refund design); both orderings now land
            // in the same state.
            ACLMessage overtaken = myAgent.receive(OVERTAKEN_ACCEPT);
            if (overtaken != null) {
                tug.forgetBid(overtaken.getConversationId());
                log.info("{} CANCEL overtook the queued ACCEPT on CNP {} — award discarded "
                        + "before it started", tug.tugId(), overtaken.getConversationId());
                return;
            }
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
        tug.setPickupTarget(null); // task 22: else a save after a CANCEL persists a stale pickup
        ReturnToBaseBehaviour ret = new ReturnToBaseBehaviour(tug);
        tug.setActiveMovement(ret);
        tug.setStatus(TugStatus.RETURNING);
        tug.addBehaviour(ret);
    }
}
