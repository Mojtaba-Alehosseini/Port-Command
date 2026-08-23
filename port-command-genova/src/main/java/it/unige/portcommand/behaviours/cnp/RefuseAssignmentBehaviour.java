package it.unige.portcommand.behaviours.cnp;

import java.util.Map;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.core.TerminalJson;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §4.4 tug plan 3 — {@code refuse_assignment (with reason)} — REAL since S9 (audit P1-8; the
 * task-05 stub survived four months while the ACCEPT handler's "declining" log line sent
 * nothing). A OneShot constructed by {@link HandleAcceptRejectBehaviour} at the moment a
 * post-award decline is decided: builds the reply to the winning ACCEPT, overrides the
 * inherited CFP protocol with {@link #POST_AWARD_DECLINE_PROTOCOL} (the HarbourMaster's
 * {@code HandleTugDeclineBehaviour} matches on it — the CFP-refuse shape belongs to the
 * bid-collection window, which is long closed by award time), and sends
 * {@code {reason, vessel_id, conversation_id}}.
 *
 * <p>The decline triggers the HarbourMaster's recovery: compensating refund of the
 * charge-at-award, escort removal, and a fresh shortfall CFP — see ADR-15's charge-at-award
 * note and {@code TugJobAwardedEvent}'s javadoc, both updated S9.
 */
public final class RefuseAssignmentBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RefuseAssignmentBehaviour.class);

    /** Distinct post-award tag — NEVER the CFP-refuse shape (that collector is deregistered
     * by award time and the HM's blanket CNP-protocol arm must not swallow this). */
    public static final String POST_AWARD_DECLINE_PROTOCOL = "port-command-post-award-decline";

    private final TugAgent tug;
    private final ACLMessage award;
    private final String reason;

    public RefuseAssignmentBehaviour(TugAgent tug, ACLMessage award, String reason) {
        super(tug);
        this.tug = tug;
        this.award = award;
        this.reason = reason;
    }

    @Override
    public void action() {
        // MessageFactory.reply is the single sanctioned reply constructor (design rule 5 /
        // its own grep gate) — S9 adversarial finding 1: the first draft's raw createReply
        // inherited the envelope instead of re-stamping it.
        ACLMessage refuse = it.unige.portcommand.core.MessageFactory.reply(award, ACLMessage.REFUSE);
        refuse.setProtocol(POST_AWARD_DECLINE_PROTOCOL);
        String vesselId = vesselIdOf(award);
        refuse.setContent(TerminalJson.write(Map.of(
                "reason", reason,
                "vessel_id", vesselId == null ? "" : vesselId,
                "conversation_id", String.valueOf(award.getConversationId()))));
        myAgent.send(refuse);
        log.info("{} DECLINED award on CNP {} ({}) — REFUSE sent to {}",
                tug.tugId(), award.getConversationId(), reason,
                award.getSender() == null ? "?" : award.getSender().getLocalName());
    }

    private static String vesselIdOf(ACLMessage award) {
        var content = TerminalJson.readTreeOrNull(award.getContent());
        if (content == null || content.get("vessel_id") == null) {
            return null;
        }
        return content.get("vessel_id").asText();
    }
}
