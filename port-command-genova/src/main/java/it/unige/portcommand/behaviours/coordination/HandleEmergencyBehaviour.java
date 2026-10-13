package it.unige.portcommand.behaviours.coordination;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.CustomsClearedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customs reply handler — resolves task-09's deferred note ("HM customs-REQUEST
 * keys + reply parse — task 11"). Every reply to a customs pre-clearance REQUEST
 * (sent by {@link AutoFlowDispatcherBehaviour}) lands here, correlated back to a
 * vesselId via the conversationId {@code AutoFlowDispatcherBehaviour} recorded (the
 * reply content itself carries no vessel_id). CONFIRM = cleared; INFORM
 * {@code event=flagged} (always {@code priority=high}) = mark the vessel BLOCKED and
 * notify the GUI; INFORM {@code event=routine_inspection} = non-blocking, logged
 * only.
 *
 * <p>Matched by protocol tag ({@link #CUSTOMS_CLEARANCE_PROTOCOL}, set on the
 * REQUEST by {@code AutoFlowDispatcherBehaviour} and preserved into the reply by
 * {@code ACLMessage.createReply()}), not sender AID — see
 * {@link HandleWeatherAlertBehaviour}'s javadoc for why AID-based matching had a
 * real gap (adversarial review finding, task 11). Customs happened to be safe from
 * that specific race (the AID is resolved before any reply can exist), but the
 * protocol tag removes the dependency entirely and keeps both handlers consistent.
 */
public final class HandleEmergencyBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleEmergencyBehaviour.class);

    /** Set on the customs pre-clearance REQUEST by {@code AutoFlowDispatcherBehaviour}. */
    public static final String CUSTOMS_CLEARANCE_PROTOCOL = "port-command-customs-clearance";
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(CUSTOMS_CLEARANCE_PROTOCOL),
            MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));

    public HandleEmergencyBehaviour(Agent agent) {
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

        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        String event = msg.getPerformative() == ACLMessage.INFORM ? text(content, "event") : null;
        if ("inspection_started".equals(event)) {
            // S16 (C5, ADR-20): customs is INSPECTING the ship. The clearance is still pending -
            // the conversation stays in the map - and the ship waits at the channel entry for it;
            // the Port table says so, with the rules customs cited.
            String inspected = hm.pendingCustomsChecks().get(msg.getConversationId());
            if (inspected != null) {
                String reason = text(content, "reason");
                hm.movement().inspectionStarted(inspected, ruleIdsIn(reason));
                log.info("{}: customs INSPECTION started ({})", inspected, reason);
            }
            return;
        }
        String vesselId = hm.pendingCustomsChecks().remove(msg.getConversationId());
        if (vesselId == null) {
            log.warn("customs reply on untracked conversation {} — ignoring", msg.getConversationId());
            return;
        }
        if (msg.getPerformative() == ACLMessage.CONFIRM) {
            log.info("{}: customs CLEARED ({})", vesselId, text(content, "ref"));
            // Task 20: the €100 customs_clearance trigger. Published here, AFTER the
            // pendingCustomsChecks.remove(...) guard above consumed the conversation, so a
            // duplicate CONFIRM cannot charge twice. That ordering matters: receiveLogged already
            // published this message's raw CommLogEvent BEFORE the guard ran, so anything charging
            // off the comm-log firehose instead would double-count. vesselId comes from the
            // conversation map because the CONFIRM's own content carries none.
            hm.eventBus().publish(new CustomsClearedEvent(
                    vesselId, text(content, "ref"), hm.simClock().nowSimMillis()));
            // S16 (C5): the clearance was what the ship was waiting for (or one of the things).
            it.unige.portcommand.behaviours.MovementControl.onCustomsCleared(hm, vesselId);
            return;
        }
        if ("flagged".equals(event)) {
            hm.activeVessels().computeIfPresent(vesselId, (id, t) -> t.withBlocked(true));
            log.info("{}: customs FLAGGED ({}) -> refused", vesselId, text(content, "reason"));
            // S16 (C5): a ship customs refuses does not enter. Before S16 "blocked" was a flag
            // nothing read - the ship had docked long before the reply arrived.
            it.unige.portcommand.behaviours.MovementControl.onCustomsRefused(hm, vesselId,
                    text(content, "reason"));
        } else {
            log.info("{}: customs {} (non-blocking)", vesselId, event);
            it.unige.portcommand.behaviours.MovementControl.onCustomsCleared(hm, vesselId);
        }
    }

    /** The rule ids named in a customs rationale such as {@code inspected [R22,R24]}. */
    private static java.util.List<String> ruleIdsIn(String reason) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (reason != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("R\\d+").matcher(reason);
            while (m.find()) {
                ids.add(m.group());
            }
        }
        return ids;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }
}
