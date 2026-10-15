package it.unige.portcommand.behaviours.coordination;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.harbourmaster.GateRefusalLine;
import it.unige.portcommand.harbourmaster.PlayerCommand;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
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
 * negotiation behaviours pick them up. S5 (audit P1-3): binding kinds aimed at a live
 * negotiation pass the Prolog gate first — see {@link #onPlayerCommand} — which is what makes
 * design rule 4 hold on the player/autopilot path, not just the agent-internal ones. Subscribes to the EventBus ONCE
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
        // S5 (audit P1-3, design rule 4): a BINDING command aimed at a live negotiation —
        // ACCEPT closes the deal, PROPOSE puts a berth-carrying counter the vessel may accept —
        // must pass the Prolog gate BEFORE anything goes on the wire. This handler runs on the
        // EventBus's shared ASYNC pool, where JPL under the global engine lock could starve every
        // other async subscriber (INVARIANTS' own contract), so the gate+send hop to the HM's own
        // thread via an anonymous OneShotBehaviour — which also closes the TOCTOU against
        // forgetDialogue/stage advance: the stage is re-read after the hop. Non-binding kinds and
        // post-negotiation commands (a granted contracted vessel, a queued grant) keep today's
        // synchronous path — the gate exists for the moment the player can still bind the port.
        boolean binding = cmd.kind() == PlayerCommandEvent.PlayerCommandKind.PROPOSE
                || cmd.kind() == PlayerCommandEvent.PlayerCommandKind.ACCEPT;
        if (binding && tracking.stage() == VesselTracking.Stage.NEGOTIATING) {
            hm.addBehaviour(new OneShotBehaviour(hm) {
                @Override
                public void action() {
                    gateAndDispatch(cmd);
                }
            });
            return;
        }
        dispatch(cmd, tracking);
    }

    /**
     * HM-thread half of the binding path. Re-reads the tracking (the hop is the barrier): a
     * dialogue that advanced or closed in between falls through to the plain dispatch, whose
     * downstream paths already handle post-negotiation commands — never a false refusal at
     * deal close.
     */
    private void gateAndDispatch(PlayerCommand cmd) {
        VesselTracking tracking = hm.activeVessels().get(cmd.targetVesselId());
        if (tracking == null) {
            log.warn("PlayerCommand {} for {} — vessel left before the gate ran, dropped",
                    cmd.kind(), cmd.targetVesselId());
            return;
        }
        if (tracking.stage() == VesselTracking.Stage.NEGOTIATING) {
            Refusal refusal = gateRefusal(cmd, tracking);
            if (refusal != null) {
                refuse(cmd, tracking, refusal);
                return;
            }
        }
        dispatch(cmd, tracking);
    }

    /**
     * The binding gate itself — Prolog first, always (design rule 4). Null = pass. The
     * compatibility leg is belt-and-braces over the vessel's own R7 self-check (a walk-in
     * REFUSEs an incompatible berth too — this catches it BEFORE the wire, and catches the
     * autopilot the same as a human typing "accept"). S16 (B0a): the weather is NOT part of this
     * gate - a deal is talk, and the weather rules gate movements (see the end of the method).
     */
    /** S19 (playtest 5, PT30): a refusal - the technical reason the typed event and the log carry,
     * and the plain line the player reads ({@link GateRefusalLine}). */
    private record Refusal(String reason, String line) {
    }

    private Refusal gateRefusal(PlayerCommand cmd, VesselTracking tracking) {
        VesselSpec spec = tracking.spec();
        String conversationId = "nego-" + cmd.targetVesselId();
        // String.valueOf, not instanceof-String (S5 adversarial finding 6): a numeric berth_id
        // would otherwise skip the explicit check here yet still SHIP via proposeMessage's
        // computeIfAbsent — the gate must judge exactly the value that goes on the wire.
        Object explicit = cmd.content().get("berth_id");
        String berthId = explicit != null ? String.valueOf(explicit)
                : hm.negotiationBerths().get(conversationId);
        if (berthId != null) {
            if (!PrologQueries.isCompatible(berthId, spec.vesselType(),
                    spec.draft(), spec.length(), spec.tonnage())) {
                return new Refusal(berthId + " is incompatible with a " + spec.vesselType() + " [R7]",
                        GateRefusalLine.incompatibleBerth(berthId, spec.vesselType(), PrologQueries.findCompatibleBerths(
                                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage())));
            }
        } else if (PrologQueries.findCompatibleBerths(spec.vesselType(),
                spec.draft(), spec.length(), spec.tonnage()).isEmpty()) {
            return new Refusal("no compatible berth exists for a " + spec.vesselType() + " [R8]",
                    GateRefusalLine.noBerthFits(spec.vesselType()));
        }
        // S16 (B0a, ADR-20): no weather leg here any more. Bad weather blocks MOVING, not
        // TALKING: the player (and the autopilot) may negotiate and close a deal in a storm, and
        // the ship then waits at the channel entry, held by the movement gate with its rule id
        // (MovementControl.afterGrant). The old leg refused PROPOSE and ACCEPT under R15-R19 while
        // the vessel's patience kept running - a storm cost the player the deal and a
        // reputation point for a movement nobody had asked for yet.
        return null;
    }

    /**
     * Refusal = player-visible, wire-silent (stated deviation, ADR-16): the comm log renders the
     * HM's send/receive instrumentation and nothing was sent — the rule id lands where the player
     * actually reads, the dialogue tab and the HUD, exactly like the queue notice. S13 (F-7): the
     * typed {@link it.unige.portcommand.gui.events.PlayerCommandRefusedEvent} carries the refused
     * command's id so the tab takes back the offer it already put on its strip. S19 (playtest 5,
     * PT30): the chat and the notification strip say it in plain words ("Berth 4 can't take a
     * cargo ship [R7] — not sent. Berths that fit: 3."); the event and the log keep the reason.
     */
    private void refuse(PlayerCommand cmd, VesselTracking tracking, Refusal refusal) {
        String reason = refusal.reason();
        String note = "Command " + cmd.kind() + " for " + cmd.targetVesselId()
                + " refused: " + reason;
        // The standing price is read HERE, on the HM thread after every earlier dispatch for this
        // dialogue (the autopilot's too): proposeMessage records a price only once it is on the wire.
        String dialogueId = "nego-" + cmd.targetVesselId();
        hm.eventBus().publish(new it.unige.portcommand.gui.events.PlayerCommandRefusedEvent(
                dialogueId, cmd.kind(), cmd.commandId(),
                hm.negotiationPlayerOffers().getOrDefault(dialogueId, 0.0), reason));
        String name = tracking.spec() == null || tracking.spec().vesselName() == null
                || tracking.spec().vesselName().isBlank() ? cmd.targetVesselId() : tracking.spec().vesselName();
        hm.eventBus().publish(new it.unige.portcommand.gui.events.NotificationEvent(name + ": " + refusal.line(),
                it.unige.portcommand.gui.events.NotificationEvent.Severity.WARNING,
                hm.simClock().nowSimMillis()));
        hm.eventBus().publish(new it.unige.portcommand.gui.events.AssistantChatEvent(
                "nego-" + cmd.targetVesselId(), refusal.line()));
        log.warn("binding PlayerCommand refused by the Prolog gate — {}", note);
    }

    /** The pre-S5 dispatch, unchanged: translate and send on whatever thread we hold. */
    private void dispatch(PlayerCommand cmd, VesselTracking tracking) {
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
        // S17a (ADR-21, A9): the counterpart of the refusal event. A sender that gave its command
        // an id - the autopilot does, for every move - hears here that it really went out, and
        // only then says so in the chat. Before S17a the autopilot wrote "counter at ..." when it
        // PUBLISHED the command, so a move this gate refused read exactly like one that was made.
        if (cmd.commandId() != PlayerCommandEvent.NO_COMMAND_ID) {
            hm.eventBus().publish(new it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent(
                    conversationId, cmd.kind(), cmd.commandId()));
        }
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
        // Remember the player's own price for this dialogue (2026-08-10, audit P1-9). This is the
        // ONLY point in the HarbourMaster where it is visible: the vessel's reply carries just
        // {intent, price, hours, vessel_spec}, so without stashing it here the snapshot handed to
        // the Assistant reported lastPlayerOffer=0.0 forever. Same conversationId key as the berth
        // lookup one line above.
        // Gated on a LIVE walk-in dialogue (negotiationBerths is stamped when one opens and
        // dropped on every terminal path). Without the gate this also fires for a PROPOSE aimed at
        // a CONTRACTED vessel — which never opens a dialogue and terminates through handleDeparted,
        // so nothing would ever remove the entry — and it could re-add a key just after a grant or
        // withdraw cleaned up, since this can run on the EventBus ASYNC pool while the cleanup
        // runs on the HarbourMaster's JADE thread. (2026-08-10 adversarial review. S5: a PROPOSE
        // aimed at a LIVE dialogue now arrives here via the gate's HM-thread hop — same thread as
        // the cleanup, race dead — but the contracted-vessel case above still comes in ASYNC, so
        // the gate stays.)
        if (content.get("price") instanceof Number playerPrice
                && hm.negotiationBerths().containsKey(conversationId)) {
            hm.negotiationPlayerOffers().put(conversationId, playerPrice.doubleValue());
        }
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
