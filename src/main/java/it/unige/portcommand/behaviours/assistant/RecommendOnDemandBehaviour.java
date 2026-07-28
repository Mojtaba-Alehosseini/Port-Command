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
import it.unige.portcommand.util.Subscription;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Hint" button plan (planning/10 §10.6). A {@link OneShotBehaviour} that subscribes to the
 * {@link EventBus} ONCE for {@link HintButtonEvent} (INVARIANTS.md's established
 * subscribe-once/public-handler pattern); the real reactive work is {@link #onHintButton}, invoked
 * by the bus whenever it dispatches — {@code onHintButton} is also callable directly by tests,
 * which {@code AssistantAgentIT} does today to drive it deterministically on the calling thread
 * rather than relying on the real bus's ASYNC delivery timing.
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

    /** Held so AssistantAgent cancels on takedown (task 22): the bus outlives the agent,
     * and a respawn would otherwise leave this handler subscribed alongside the new one,
     * double-firing every reaction. */
    private volatile Subscription<HintButtonEvent> subscription;

    @Override
    public void action() {
        subscription = eventBus.subscribe(HintButtonEvent.class, this::onHintButton, DeliveryMode.ASYNC);
        log.debug("subscribed to HintButtonEvent");
    }

    /** Cancels the bus subscription; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        Subscription<HintButtonEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
    }

    /** Run the algorithm, ask the LLM to polish it, validate, publish — falling back to the
     * plain template on any LLM timeout/error or on a failed hallucination check. */
    public void onHintButton(HintButtonEvent event) {
        WalkInDialogueSnapshot snapshot = event.snapshot();
        Recommendation rec = algorithm.run(snapshot);
        cache.put(decisionId(event.dialogueId(), snapshot), rec);

        String template = AssistantPromptBuilder.template(rec);
        PromptPayload prompt = AssistantPromptBuilder.prompt(rec);
        // requiredFigures(), not allFigures(): the sidecar's own check 1 mirrors the Java one
        // (task 13 parity), so both sides must narrow together — see Recommendation#requiredFigures.
        // The sidecar's verdict is advisory anyway; HallucinationValidator below is what gates the
        // fall back to the plain template.
        LLMRequest request = new LLMRequest(prompt.user(), prompt.system(),
                rec.requiredFigures().stream().toList(), rec.namedEntities().stream().toList(), true);

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
