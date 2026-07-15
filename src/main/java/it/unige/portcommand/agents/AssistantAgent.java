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
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.util.EventBus;

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

        addBehaviour(new RecommendOnDemandBehaviour(this, algorithm, llmBridge, eventBus, cache));
        addBehaviour(new AutopilotExecuteBehaviour(this, algorithm, policyRegistry, eventBus, cache,
                autopilotEnabled));
        addBehaviour(new PolicyParseBehaviour(this, policyRegistry, eventBus));
        addBehaviour(new ExplainEventBehaviour(this, cache, llmBridge, eventBus));
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
