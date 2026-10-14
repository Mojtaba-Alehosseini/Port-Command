package it.unige.portcommand.behaviours.assistant;

import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PolicyClearRequestEvent;
import it.unige.portcommand.gui.events.PolicyListEvent;
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
 *
 * <p>S18a (ADR-23, decision N11): it is also the registry's voice. Until S18a a policy could be
 * set and never seen again - nothing reported what was in force, and nothing could remove it. The
 * behaviour now answers every change with a {@link PolicyListEvent} (and announces the list once
 * when it starts, so a new or loaded world shows its own), and empties the registry on a
 * {@link PolicyClearRequestEvent}. Still one behaviour: it subscribes to two events instead of one.
 *
 * <p>It does NOT listen for the window. A cold {@code --load <save> --gui} start loads the world -
 * and so announces the list - before the window exists, and the bus has no replay; the list is
 * said again by {@code GameSession.publishLiveHudState} on the window's first paint, with the rest
 * of that refresh. That hook is the session's one {@code GuiReadyEvent} subscriber, and
 * {@code FreePlaySessionIT} pins it at one: a second subscriber here made the gate red.
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
    private volatile Subscription<PolicyClearRequestEvent> clearSubscription;

    @Override
    public void action() {
        subscription = eventBus.subscribe(PolicyParsedEvent.class, this::onPolicyParsed, DeliveryMode.ASYNC);
        clearSubscription = eventBus.subscribe(PolicyClearRequestEvent.class, this::onClearRequested,
                DeliveryMode.ASYNC);
        log.debug("subscribed to PolicyParsedEvent and PolicyClearRequestEvent");
        // S18a: a world announces its own list as its Assistant starts. A loaded game's policies
        // were restored into the registry before any agent booted (GameSession.restorePolicies),
        // and a new game starts from an emptied one - either way the window must not go on
        // showing the previous world's.
        announce();
    }

    /** Cancels the bus subscriptions; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        Subscription<PolicyParsedEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
        Subscription<PolicyClearRequestEvent> c = clearSubscription;
        if (c != null) {
            c.cancel();
        }
    }

    /** {@code synchronized} with {@link #onClearRequested}: the two arrive on a background pool,
     * and each must publish the list its OWN change produced - never an older one after a newer. */
    public synchronized void onPolicyParsed(PolicyParsedEvent event) {
        PolicyRule rule = event.rule();
        boolean added = policyRegistry.register(rule);
        log.info(added ? "policy registered: {}" : "policy already registered: {}", rule.trigger());
        // No separate POLICY_REGISTERED channel — the HUD update reuses the canonical NotificationEvent.
        eventBus.publish(new NotificationEvent(
                (added ? "Policy registered: " : "Policy already set: ") + rule.trigger(),
                NotificationEvent.Severity.INFO, 0L));
        announce();
    }

    /** S18a (decision N11): "Clear policies" in Settings. */
    public synchronized void onClearRequested(PolicyClearRequestEvent event) {
        int held = policyRegistry.all().size();
        policyRegistry.clear();
        log.info("policies cleared: {}", held);
        eventBus.publish(new NotificationEvent(
                held == 0 ? "There were no policies to clear." : "Policies cleared: " + held + ".",
                NotificationEvent.Severity.INFO, 0L));
        announce();
    }

    /** {@code synchronized} like the handlers that call it: the announcement made at start-up
     * must not read the list, lose the race to a handler, and then publish the older one last. */
    private synchronized void announce() {
        eventBus.publish(new PolicyListEvent(
                policyRegistry.all().stream().map(PolicyRule::description).toList()));
    }
}
