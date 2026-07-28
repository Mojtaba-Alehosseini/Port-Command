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
import it.unige.portcommand.util.Subscription;
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
 * {@code onPlayerCommand} is also callable directly by tests, which several
 * existing ITs still do to drive it deterministically on the calling thread
 * rather than relying on the real bus's ASYNC delivery timing).
 *
 * <p><b>Task 19 fix (found via real play-testing, not a unit test):</b> the agent
 * reference is captured in {@link #hm}, a plain field set once in the constructor —
 * NEVER read from the inherited {@code myAgent} inside {@link #onPlayerCommand}. JADE's
 * {@code Agent.removeBehaviour} calls {@code Behaviour.setAgent(null)}
 * (bytecode-verified against jade-4.6.0.jar), and the scheduler runs exactly that once a
 * {@link OneShotBehaviour}'s {@code action()} completes and {@code done()} reports
 * {@code true} — so {@code myAgent} goes null shortly after {@link #action()} returns,
 * while the {@code this::onPlayerCommand} method reference stays subscribed on the bus
 * indefinitely. The first real chat message sent after that window NPE'd here
 * (every other subscribe-once/public-handler behaviour in {@code behaviours.assistant}
 * already avoids this by capturing its own dependencies as constructor fields and
 * never touching {@code myAgent} post-construction — this class just hadn't).
 */
public final class DispatchPlayerCommandBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DispatchPlayerCommandBehaviour.class);

    private final HarbourMasterAgent hm;
    /** Held so the OWNING AGENT can cancel on takedown (task 22): the bus outlives the agent,
     * and a HarbourMaster respawn (save/load teardown-rebuild) would otherwise leave this
     * handler — with its dead {@code hm} reference — subscribed alongside the new agent's,
     * double-dispatching every player command. Written on the agent thread in {@link #action()};
     * volatile because {@code onTakeDown} may run after a kill from another thread. */
    private volatile Subscription<PlayerCommandEvent> subscription;

    public DispatchPlayerCommandBehaviour(Agent agent) {
        super(agent);
        this.hm = (HarbourMasterAgent) agent;
    }

    @Override
    public void action() {
        subscription = hm.eventBus().subscribe(PlayerCommandEvent.class, this::onPlayerCommand, DeliveryMode.ASYNC);
        log.debug("subscribed to PlayerCommandEvent");
    }

    /** Cancels the bus subscription; safe if {@link #action()} never ran. HM's takedown calls this. */
    public void cancelSubscription() {
        Subscription<PlayerCommandEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
    }

    public void onPlayerCommand(PlayerCommandEvent event) {
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
        hm.sendLogged(out);
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
