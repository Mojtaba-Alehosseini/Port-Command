package it.unige.portcommand.behaviours.assistant;

import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PolicyParsedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers every parsed policy (planning/10 §10.8). Subscribes to {@link PolicyParsedEvent},
 * published by {@code nlp.PolicyParser} — NOT by a Rasa intent; the second Rasa pipeline for
 * policy expressions was cut (PROJECT_DEFINITION §9 item 6).
 */
public final class PolicyParseBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PolicyParseBehaviour.class);

    private final PolicyRegistryArtifact policyRegistry;
    private final EventBus eventBus;

    public PolicyParseBehaviour(Agent agent, PolicyRegistryArtifact policyRegistry, EventBus eventBus) {
        super(agent);
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus;
    }

    /** Held so AssistantAgent cancels on takedown (task 22): the bus outlives the agent,
     * and a respawn would otherwise leave this handler subscribed alongside the new one,
     * double-firing every reaction. */
    private volatile Subscription<PolicyParsedEvent> subscription;

    @Override
    public void action() {
        subscription = eventBus.subscribe(PolicyParsedEvent.class, this::onPolicyParsed, DeliveryMode.ASYNC);
        log.debug("subscribed to PolicyParsedEvent");
    }

    /** Cancels the bus subscription; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        Subscription<PolicyParsedEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
    }

    public void onPolicyParsed(PolicyParsedEvent event) {
        PolicyRule rule = event.rule();
        policyRegistry.register(rule);
        log.info("policy registered: {}", rule.trigger());
        // No separate POLICY_REGISTERED channel — the HUD update reuses the canonical NotificationEvent.
        eventBus.publish(new NotificationEvent(
                "Policy registered: " + rule.trigger(), NotificationEvent.Severity.INFO, 0L));
    }
}
