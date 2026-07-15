package it.unige.portcommand.behaviours.assistant;

import java.util.Optional;

import it.unige.portcommand.assistant.AssistantPromptBuilder;
import it.unige.portcommand.assistant.AssistantPromptBuilder.PromptPayload;
import it.unige.portcommand.assistant.HallucinationValidator;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.ExplainRequestEvent;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Assistant's fourth plan, {@code explain_event} (PROJECT_DEFINITION §4.8): regenerates a
 * plain-English explanation for a PAST decision when the player clicks "Why?" (planning/10
 * §10.8b). Looks the decision up in {@link RecommendationCache} and reuses the same explain path
 * as {@link RecommendOnDemandBehaviour}: build prompt + template, call the LLM, validate, fall
 * back to template.
 */
public final class ExplainEventBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(ExplainEventBehaviour.class);

    private final RecommendationCache cache;
    private final LLMBridge llmBridge;
    private final EventBus eventBus;

    public ExplainEventBehaviour(Agent agent, RecommendationCache cache, LLMBridge llmBridge, EventBus eventBus) {
        super(agent);
        this.cache = cache;
        this.llmBridge = llmBridge;
        this.eventBus = eventBus;
    }

    @Override
    public void action() {
        eventBus.subscribe(ExplainRequestEvent.class, this::onExplainRequest, DeliveryMode.ASYNC);
        log.debug("subscribed to ExplainRequestEvent");
    }

    public void onExplainRequest(ExplainRequestEvent event) {
        Optional<Recommendation> cached = cache.get(event.decisionId());
        if (cached.isEmpty()) {
            log.debug("no cached recommendation for decisionId={}", event.decisionId());
            eventBus.publish(new AssistantChatEvent(event.decisionId(),
                    "Sorry, I no longer remember the reasoning for that decision."));
            return;
        }
        Recommendation rec = cached.get();
        String template = AssistantPromptBuilder.template(rec);
        PromptPayload prompt = AssistantPromptBuilder.prompt(rec);
        LLMRequest request = new LLMRequest(prompt.user(), prompt.system(),
                rec.allFigures().stream().toList(), rec.namedEntities().stream().toList(), true);

        llmBridge.explain(request)
                .thenAccept(response -> {
                    String text = HallucinationValidator.validate(response.text(), rec) ? response.text() : template;
                    eventBus.publish(new AssistantChatEvent(event.decisionId(), text));
                })
                .exceptionally(ex -> {
                    log.debug("{}: llm explain failed/timed out; falling back to template",
                            event.decisionId(), ex);
                    eventBus.publish(new AssistantChatEvent(event.decisionId(), template));
                    return null;
                });
    }
}
