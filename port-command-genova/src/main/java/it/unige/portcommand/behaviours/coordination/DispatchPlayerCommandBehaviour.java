package it.unige.portcommand.behaviours.coordination;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.harbourmaster.PlayerCommand;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.util.DeliveryMode;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates player-originated commands (from the GUI chat, or the Assistant's
 * autopilot — planning/10 §10.7 — both arrive as the SAME {@link PlayerCommandEvent})
 * into outbound ACL messages to the target vessel, preserving the negotiation
 * conversation id ({@code "nego-"+vesselId}, deterministic — matches
 * {@code WalkInVesselAgent}'s own construction) so the vessel's frozen task-07
 * negotiation behaviours pick them up. Subscribes to the EventBus ONCE
 * (INVARIANTS.md's established subscribe-once/public-handler pattern —
 * {@code onPlayerCommand} is also callable directly by tests, since real dispatch
 * is task 17).
 */
public final class DispatchPlayerCommandBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DispatchPlayerCommandBehaviour.class);

    public DispatchPlayerCommandBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        hm.eventBus().subscribe(PlayerCommandEvent.class, this::onPlayerCommand, DeliveryMode.ASYNC);
        log.debug("subscribed to PlayerCommandEvent");
    }

    public void onPlayerCommand(PlayerCommandEvent event) {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        PlayerCommand cmd = PlayerCommand.from(event);
        VesselTracking tracking = hm.activeVessels().get(cmd.targetVesselId());
        if (tracking == null) {
            log.warn("PlayerCommand {} for untracked vessel {} — dropped", cmd.kind(), cmd.targetVesselId());
            return;
        }
        AID target = tracking.aid();
        String conversationId = "nego-" + cmd.targetVesselId();

        ACLMessage out = switch (cmd.kind()) {
            case PROPOSE -> proposeMessage(hm, cmd, conversationId);
            case ACCEPT -> plainMessage(ACLMessage.ACCEPT_PROPOSAL, cmd);
            case REJECT -> plainMessage(ACLMessage.REJECT_PROPOSAL, cmd);
            case ASK -> plainMessage(ACLMessage.QUERY_REF, cmd);
            case WITHDRAW -> withdrawMessage();
        };
        out.addReceiver(target);
        out.setConversationId(conversationId);
        myAgent.send(out);
        log.info("PlayerCommand {} -> {} ({})", cmd.kind(), target.getLocalName(),
                ACLMessage.getPerformative(out.getPerformative()));
    }

    /**
     * PROPOSE needs {@code berth_id} — the walk-in vessel's own
     * {@code EvaluateCounterOfferBehaviour} REFUSEs on a null one — reusing the
     * berth {@code ForwardWalkInToPlayerBehaviour} already picked for this dialogue
     * (same conversationId key) unless the player's command explicitly supplied one.
     */
    private ACLMessage proposeMessage(HarbourMasterAgent hm, PlayerCommand cmd, String conversationId) {
        Map<String, Object> content = new LinkedHashMap<>(cmd.content());
        content.computeIfAbsent("berth_id", k -> hm.negotiationBerths().get(conversationId));
        ACLMessage m = MessageFactory.create(ACLMessage.PROPOSE);
        m.setContent(TerminalJson.write(content));
        return m;
    }

    private static ACLMessage plainMessage(int performative, PlayerCommand cmd) {
        ACLMessage m = MessageFactory.create(performative);
        m.setContent(TerminalJson.write(cmd.content()));
        return m;
    }

    private static ACLMessage withdrawMessage() {
        ACLMessage m = MessageFactory.create(ACLMessage.CANCEL);
        m.setContent(TerminalJson.write(Map.of("reason", "player_withdrew")));
        return m;
    }
}
