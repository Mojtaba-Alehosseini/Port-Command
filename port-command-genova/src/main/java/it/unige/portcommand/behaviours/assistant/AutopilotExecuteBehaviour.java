package it.unige.portcommand.behaviours.assistant;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.artifacts.PolicyRule.PolicyAction;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The autopilot plan (planning/10 §10.7). While autopilot is enabled, every
 * {@link NegotiationOpenedEvent} first checks the player's policies ({@link PolicyRegistryArtifact
 * #firstMatching}) — a match short-circuits the EV algorithm entirely — and only runs
 * {@link RecommendationAlgorithm} when nothing matches. The chosen action is dispatched to HM
 * exactly as if it came from the player's own chat ({@link PlayerCommandEvent}), same as
 * {@link RecommendOnDemandBehaviour}'s subscribe-once-react-many shape.
 */
public final class AutopilotExecuteBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AutopilotExecuteBehaviour.class);

    private final RecommendationAlgorithm algorithm;
    private final PolicyRegistryArtifact policyRegistry;
    private final EventBus eventBus;
    private final RecommendationCache cache;
    private final AtomicBoolean autopilotEnabled;

    public AutopilotExecuteBehaviour(Agent agent, RecommendationAlgorithm algorithm,
                                     PolicyRegistryArtifact policyRegistry, EventBus eventBus,
                                     RecommendationCache cache, AtomicBoolean autopilotEnabled) {
        super(agent);
        this.algorithm = algorithm;
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus;
        this.cache = cache;
        this.autopilotEnabled = autopilotEnabled;
    }

    /** Held so AssistantAgent cancels on takedown (task 22): the bus outlives the agent,
     * and a respawn would otherwise leave this handler subscribed alongside the new one,
     * double-firing every reaction. */
    private volatile Subscription<NegotiationOpenedEvent> subscription;

    @Override
    public void action() {
        subscription = eventBus.subscribe(NegotiationOpenedEvent.class, this::onNegotiationOpened, DeliveryMode.ASYNC);
        log.debug("subscribed to NegotiationOpenedEvent");
    }

    /** Cancels the bus subscription; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        Subscription<NegotiationOpenedEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
    }

    public void onNegotiationOpened(NegotiationOpenedEvent event) {
        if (!autopilotEnabled.get()) {
            return;
        }
        WalkInDialogueSnapshot snapshot = event.snapshot();
        Optional<PolicyRule> matched = policyRegistry.firstMatching(snapshot);

        // Closed 2026-08-16 (S5, audit P1-3): the gate this TODO demanded now exists downstream.
        // Both the policy path and the EV path dispatch as a player-equivalent PlayerCommandEvent,
        // and DispatchPlayerCommandBehaviour runs every binding ACCEPT/PROPOSE through the Prolog
        // gate (R7 compatibility + the R15-R19 weather trio) on the HM thread BEFORE anything
        // goes on the wire — an autopilot "accept" against an incompatible berth or an unsafe sea
        // is refused with the failing rule id in the dialogue tab, exactly as for a human typing
        // it. Nothing to check here: this class stays a translator.
        String action;
        double price;
        if (matched.isPresent()) {
            PolicyAction policyAction = matched.get().action();
            action = policyAction.kind();
            price = priceFor(policyAction, snapshot);
            log.info("{}: policy '{}' matched -> {}", event.dialogueId(), matched.get().trigger(), action);
        } else {
            Recommendation rec = algorithm.run(snapshot);
            cache.put(RecommendOnDemandBehaviour.decisionId(event.dialogueId(), snapshot), rec);
            action = rec.action();
            price = rec.price();
        }
        dispatch(event.dialogueId(), snapshot, action, price);
    }

    private static double priceFor(PolicyAction action, WalkInDialogueSnapshot snapshot) {
        return switch (action.kind()) {
            case RecommendationCandidate.COUNTER -> snapshot.lastVesselOffer() * (1.0 + action.counterPct());
            // Mirrors RecommendationAlgorithm.scoreRejectSilent: no price is on the table.
            case RecommendationCandidate.REJECT_SILENT -> 0.0;
            default -> snapshot.lastVesselOffer(); // accept: take the vessel's own standing offer
        };
    }

    private void dispatch(String dialogueId, WalkInDialogueSnapshot snapshot, String action, double price) {
        // reject_silent means exactly that: nothing is dispatched, the round simply passes.
        if (!RecommendationCandidate.REJECT_SILENT.equals(action)) {
            PlayerCommandKind kind = switch (action) {
                case RecommendationCandidate.ACCEPT -> PlayerCommandKind.ACCEPT;
                case RecommendationCandidate.COUNTER -> PlayerCommandKind.PROPOSE;
                default -> throw new IllegalStateException("unknown autopilot action: " + action);
            };
            // "conversation_id" lets HM (task 11) address the real ACL reply to the right
            // standing negotiation — dialogueId is that negotiation's FIPA conversationId.
            eventBus.publish(new PlayerCommandEvent(kind, snapshot.vesselId(),
                    Map.of("price", price, "conversation_id", dialogueId)));
        }
        String priceSuffix = price > 0 ? " at €" + Math.round(price) : "";
        eventBus.publish(new AssistantChatEvent(dialogueId, "🤖 Assistant (autopilot): " + action + priceSuffix));
    }
}
