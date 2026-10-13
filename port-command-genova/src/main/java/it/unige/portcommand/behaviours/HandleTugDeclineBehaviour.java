package it.unige.portcommand.behaviours;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.cnp.RefuseAssignmentBehaviour;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.gui.events.TugJobDeclinedEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S9 (audit P1-8) — the HarbourMaster's half of the post-award tug decline, closing the CNP's
 * one documented exit ramp ("a winning tug REFUSEs" — CnpCoordinator's javadoc, now historical).
 * Lives in the UNCOUNTED parent {@code behaviours} package (the RestoreDispatch /
 * SimClockTickPublisher precedent — the ADR-01 catalogue stays 52).
 *
 * <p>On a protocol-tagged REFUSE ({@link RefuseAssignmentBehaviour#POST_AWARD_DECLINE_PROTOCOL}
 * — a permanent receiver, because the per-cfpId {@code BidCollectorBehaviour} that owns
 * CFP-window REFUSEs deregisters at award, long before a decline can arrive):
 * <ol>
 *   <li><b>Money:</b> publish {@link TugJobDeclinedEvent}; {@code LedgerCoordinator} posts the
 *       compensating {@code tug_job_refund} income for the recorded charge — append-only,
 *       exactly-once, never for a never-charged pair.</li>
 *   <li><b>Escort:</b> drop the tug from tracking; if that empties the escort the stage falls
 *       back to {@code AWAITING_TUG} (the zero-winner hold shape). A surviving co-winner keeps
 *       {@code IN_TRANSIT} — the vessel's own transit is time-based either way.</li>
 *   <li><b>Replacement:</b> immediately re-tender the SHORTFALL (1 tug) on a fresh CNP toward
 *       the retained berth — a fresh cfpId means a fresh ledger key, so the replacement's
 *       charge-at-award is exactly-once beside the refunded decliner. If nobody bids, the
 *       EXISTING zero-bid machinery (AwardContract's hold + the bounded CnpRetryBehaviour)
 *       takes over — no new retry plumbing. Prolog is NOT re-queried for the count: the
 *       shortfall is one seat of an award R13 already selected (design rule 4 satisfied at
 *       the original grant); the fresh CFP itself is the re-decision.</li>
 * </ol>
 *
 * All on the HarbourMaster thread — no race with the award (same thread, and the decline is a
 * message processed only after the award behaviour's action completed). {@code receiveLogged}
 * puts the REFUSE in the comm log; the fresh CFP follows it — the deliberate demo beat.
 */
public final class HandleTugDeclineBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleTugDeclineBehaviour.class);

    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(RefuseAssignmentBehaviour.POST_AWARD_DECLINE_PROTOCOL),
            MessageTemplate.MatchPerformative(ACLMessage.REFUSE));

    public HandleTugDeclineBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        ACLMessage msg = hm.receiveLogged(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        String tugId = msg.getSender() == null ? null : msg.getSender().getLocalName();
        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        String vesselId = text(content, "vessel_id");
        String reason = text(content, "reason");
        String conversationId = msg.getConversationId();
        if (tugId == null || vesselId == null || vesselId.isEmpty() || conversationId == null) {
            log.warn("malformed post-award decline (tug={} vessel={} conv={}) — ignoring",
                    tugId, vesselId, conversationId);
            return;
        }

        // 1. Money: the coordinator holds the recorded cost; refusal-for-never-charged is ITS
        //    guard, not ours (it owns the money memory).
        hm.eventBus().publish(new TugJobDeclinedEvent(vesselId, tugId, conversationId,
                reason == null ? "unspecified" : reason, hm.simClock().nowSimMillis()));

        VesselTracking tracking = hm.activeVessels().get(vesselId);
        if (tracking == null) {
            // Departed/withdrawn meanwhile: the refund above still reconciles the ledger; there
            // is no escort left to repair and nothing to re-tender for.
            log.info("{} declined {}'s award ({}) but the vessel is no longer tracked — refund only",
                    tugId, vesselId, reason);
            return;
        }

        // 2. Escort: drop the decliner; empty escort falls back to the hold shape.
        //    computeIfPresent, not get-then-put (S10 bug hunt, hunter B finding 13): every
        //    other tracking mutation refuses to resurrect an entry removed in between; the
        //    blind put here was the one exception — inert while all writers share the HM
        //    thread, but it would silently re-create the ghost-entry class handleDeparted
        //    cleans up if that ever changes.
        boolean removed = tracking.assignedTugs().contains(tugId);
        VesselTracking updated = hm.activeVessels().computeIfPresent(vesselId, (id, current) -> {
            List<String> remaining = new ArrayList<>(current.assignedTugs());
            remaining.remove(tugId);
            VesselTracking u = current.withTugs(List.copyOf(remaining));
            if (remaining.isEmpty() && current.stage() == VesselTracking.Stage.IN_TRANSIT) {
                u = u.withStage(VesselTracking.Stage.AWAITING_TUG);
            }
            return u;
        });
        if (updated == null) {
            log.info("{} declined {}'s award but the vessel left during the repair — refund only",
                    tugId, vesselId);
            return;
        }
        if (!removed) {
            log.warn("{} declined {}'s award but was not in its escort set {} — re-tendering anyway",
                    tugId, vesselId, tracking.assignedTugs());
        }

        // 3. Replacement: one seat, fresh CNP, toward the retained berth. Zero bids -> the
        //    existing hold + bounded-retry machinery owns the tail. Each decline spawns at most
        //    ONE fresh CNP and a decliner cannot win the replacement (the CFP gate refuses on
        //    busy/low-fuel — the same conditions that made it decline), so the chain is bounded
        //    by the fleet's actual availability, not by an attempt counter.
        //
        //    Guard (S9 adversarial finding 6): a weather-HELD vessel's re-dispatch belongs to
        //    the clear sweep alone — a decline landing after a hold cleared the escort must not
        //    fire a CFP into the storm (nor may a blocked vessel, nor one already docked or
        //    departing: those have nothing to escort). The refund and escort-drop above stand
        //    regardless; only the re-tender defers to its owner.
        hm.movement().tugLeft(vesselId, tugId);
        if (updated.stage() != VesselTracking.Stage.AWAITING_TUG) {
            log.info("{}: declined award refunded, but no re-tender at stage {} - the vessel is "
                    + "past the channel entry", vesselId, updated.stage());
            return;
        }
        // S16 (ADR-20): the shortfall goes through the ONE decision point. evaluate() asks the
        // movement gate (this handler used to carry its own copy of the three weather queries,
        // and treated "no reading yet" as safe), holds the ship if the weather forbids, and
        // otherwise tenders for however many tugs R11 says are still missing - not the fixed
        // "1" that was written here.
        it.unige.portcommand.behaviours.MovementControl.evaluate(hm, vesselId);
        log.info("{} declined {}'s escort ({}) -> charge refunded, shortfall re-evaluated",
                tugId, vesselId, reason);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }
}
