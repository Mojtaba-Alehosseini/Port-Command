package it.unige.portcommand.behaviours.assistant;

import it.unige.portcommand.assistant.AssistantPromptBuilder;
import it.unige.portcommand.assistant.AssistantPromptBuilder.PromptPayload;
import it.unige.portcommand.assistant.HallucinationValidator;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Hint" button plan (planning/10 §10.6). A {@link OneShotBehaviour} that registers an
 * {@link EventBus} subscription for {@link HintButtonEvent} once; the real reactive work is
 * {@link #onHintButton}, invoked by the bus whenever it dispatches. (The task-03 {@code EventBus}
 * stub does not dispatch yet — real dispatch arrives in task 17 — so {@link #onHintButton} is
 * also directly callable, which is how {@code AssistantAgentIT} exercises it today.)
 *
 * <p>Never blocks the agent thread: the LLM call is a {@link java.util.concurrent.CompletableFuture}
 * continuation, never {@code .get()}/{@code .join()}.
 */
public final class RecommendOnDemandBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RecommendOnDemandBehaviour.class);

    private final RecommendationAlgorithm algorithm;
    private final LLMBridge llmBridge;
    private final EventBus eventBus;
    private final RecommendationCache cache;

    public RecommendOnDemandBehaviour(Agent agent, RecommendationAlgorithm algorithm, LLMBridge llmBridge,
                                      EventBus eventBus, RecommendationCache cache) {
        super(agent);
        this.algorithm = algorithm;
        this.llmBridge = llmBridge;
        this.eventBus = eventBus;
        this.cache = cache;
    }

    @Override
    public void action() {
        eventBus.subscribe(HintButtonEvent.class, this::onHintButton, DeliveryMode.ASYNC);
        log.debug("subscribed to HintButtonEvent");
    }

    /** Run the algorithm, ask the LLM to polish it, validate, publish — falling back to the
     * plain template on any LLM timeout/error or on a failed hallucination check. */
    public void onHintButton(HintButtonEvent event) {
        WalkInDialogueSnapshot snapshot = event.snapshot();
        Recommendation rec = algorithm.run(snapshot);
        cache.put(decisionId(event.dialogueId(), snapshot), rec);

        String template = AssistantPromptBuilder.template(rec);
        PromptPayload prompt = AssistantPromptBuilder.prompt(rec);
        LLMRequest request = new LLMRequest(prompt.user(), prompt.system(),
                rec.allFigures().stream().toList(), rec.namedEntities().stream().toList(), true);

        llmBridge.explain(request)
                .thenAccept(response -> {
                    String text = HallucinationValidator.validate(response.text(), rec) ? response.text() : template;
                    eventBus.publish(new AssistantChatEvent(event.dialogueId(), text));
                })
                .exceptionally(ex -> {
                    log.debug("{}: llm explain failed/timed out; falling back to template",
                            event.dialogueId(), ex);
                    eventBus.publish(new AssistantChatEvent(event.dialogueId(), template));
                    return null;
                });
    }

    static String decisionId(String dialogueId, WalkInDialogueSnapshot snapshot) {
        return dialogueId + "-r" + snapshot.roundsUsed();
    }
}
