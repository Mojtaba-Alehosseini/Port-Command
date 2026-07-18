package it.unige.portcommand.agents;

import java.util.concurrent.atomic.AtomicBoolean;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.behaviours.assistant.AutopilotExecuteBehaviour;
import it.unige.portcommand.behaviours.assistant.ExplainEventBehaviour;
import it.unige.portcommand.behaviours.assistant.PolicyParseBehaviour;
import it.unige.portcommand.behaviours.assistant.RecommendOnDemandBehaviour;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The ChatBDI-pattern assistant (one instance) — the project centerpiece. Full v1.1
 * implementation (task 10): four plans, each a {@code OneShotBehaviour} that subscribes once to
 * its {@link EventBus} event and reacts on every subsequent firing —
 * {@link RecommendOnDemandBehaviour} (Hint button), {@link AutopilotExecuteBehaviour}
 * (autopilot), {@link PolicyParseBehaviour} (policy registration), {@link ExplainEventBehaviour}
 * ("Why?"). Sends no ACL messages and makes no Swing calls of its own — every effect is an
 * {@link EventBus} event (planning/10's "No GUI calls" hard constraint).
 *
 * <p>Args: {@code [MarketHistoryArtifact, PolicyRegistryArtifact, LLMBridge, EventBus]}.
 */
public final class AssistantAgent extends PortCommandAgent {

    private final AtomicBoolean autopilotEnabled = new AtomicBoolean(false);

    private RecommendOnDemandBehaviour recommendPlan;
    private AutopilotExecuteBehaviour autopilotPlan;
    private PolicyParseBehaviour policyPlan;
    private ExplainEventBehaviour explainPlan;
    /** Task 21: the Settings screen's live autopilot toggle, delivered over the bus (never a
     * direct GUI→agent call — INVARIANTS: GUI ↔ agents only through EventBus). */
    private Subscription<SettingsChangedEvent> settingsSubscription;

    @Override
    protected void registerServices() {
        registerDfService("assistant", getLocalName());
    }

    @Override
    protected void onSetup() {
        MarketHistoryArtifact marketHistory = argAt(0, MarketHistoryArtifact.class);
        PolicyRegistryArtifact policyRegistry = argAt(1, PolicyRegistryArtifact.class);
        LLMBridge llmBridge = argAt(2, LLMBridge.class);
        EventBus eventBus = argAt(3, EventBus.class);

        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        RecommendationCache cache = new RecommendationCache();

        recommendPlan = new RecommendOnDemandBehaviour(this, algorithm, llmBridge, eventBus, cache);
        autopilotPlan = new AutopilotExecuteBehaviour(this, algorithm, policyRegistry, eventBus, cache,
                autopilotEnabled);
        policyPlan = new PolicyParseBehaviour(this, policyRegistry, eventBus);
        explainPlan = new ExplainEventBehaviour(this, cache, llmBridge, eventBus);
        addBehaviour(recommendPlan);
        addBehaviour(autopilotPlan);
        addBehaviour(policyPlan);
        addBehaviour(explainPlan);

        // Task 21: the Settings screen flips Channel-B autopilot live. CALLER_THREAD delivery
        // runs the toggle synchronously on the publisher's thread (the EDT) — the target is a
        // thread-safe AtomicBoolean AutopilotExecuteBehaviour re-reads, so no JADE-thread hop is
        // needed and no Swing is touched. Subscribed here (not in a behaviour) because the handler
        // needs no myAgent — sidestepping the OneShot-nulls-myAgent trap the four plans work
        // around; cancelled in onTakeDown so an Assistant respawn (load teardown/rebuild) does not
        // leave a dead lambda writing a garbage-collected agent's flag.
        settingsSubscription = eventBus.subscribe(SettingsChangedEvent.class,
                e -> autopilotEnabled.set(e.autopilotEnabled()), DeliveryMode.CALLER_THREAD);
    }

    /**
     * Unsubscribes the four subscribe-once plans (task 22). Load-bearing, same rationale as
     * {@code HarbourMasterAgent.onTakeDown}: the bus outlives the agent, so an Assistant
     * respawn (save/load teardown-rebuild) would otherwise double-fire every Hint reply,
     * autopilot action, policy registration and explanation.
     */
    @Override
    protected void onTakeDown() {
        if (recommendPlan != null) {
            recommendPlan.cancelSubscription();
        }
        if (autopilotPlan != null) {
            autopilotPlan.cancelSubscription();
        }
        if (policyPlan != null) {
            policyPlan.cancelSubscription();
        }
        if (explainPlan != null) {
            explainPlan.cancelSubscription();
        }
        if (settingsSubscription != null) {
            settingsSubscription.cancel();
        }
    }

    /**
     * Test/ops seam: flips the autopilot toggle. Task 21's Settings screen wires a real UI
     * control to this once it exists; autopilot defaults OFF.
     */
    public void setAutopilotEnabled(boolean enabled) {
        autopilotEnabled.set(enabled);
    }

    private <T> T argAt(int index, Class<T> type) {
        Object[] a = getArguments();
        if (a == null || a.length <= index || a[index] == null) {
            throw new IllegalStateException("AssistantAgent " + getLocalName() + " requires "
                    + type.getSimpleName() + " at args[" + index + "]");
        }
        return type.cast(a[index]);
    }
}
