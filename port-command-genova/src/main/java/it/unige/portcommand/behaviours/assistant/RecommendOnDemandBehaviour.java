package it.unige.portcommand.behaviours.assistant;

import java.util.concurrent.atomic.AtomicLong;

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

    /** JVM-wide on purpose: the Assistant is respawned on every load while the bus (and any
     * in-flight LLM continuation) survives, and dialogue ids recur ({@code nego-WALKIN-1} again in
     * the next world). A per-instance counter would restart at 1 and let an old world's late
     * revision match the new world's first hint. */
    private static final AtomicLong HINT_IDS = new AtomicLong();

    private final RecommendationAlgorithm algorithm;
    private final LLMBridge llmBridge;
    private final EventBus eventBus;
    private final RecommendationCache cache;
    /** Captured at construction, NEVER read from {@code myAgent} in the handlers — JADE nulls
     * {@code myAgent} once a OneShot completes (the DispatchPlayerCommandBehaviour task-19
     * lesson), while the bus subscription lives on. */
    private final Agent agent;

    public RecommendOnDemandBehaviour(Agent agent, RecommendationAlgorithm algorithm, LLMBridge llmBridge,
                                      EventBus eventBus, RecommendationCache cache) {
        super(agent);
        this.agent = agent;
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
        subscription = eventBus.subscribe(HintButtonEvent.class, this::enqueueHint, DeliveryMode.ASYNC);
        log.debug("subscribed to HintButtonEvent");
    }

    /** ASYNC-pool half (S10 bug hunt, hunter C finding 5): {@link #onHintButton} runs
     * {@link RecommendationAlgorithm} — JPL under the process-wide engine lock — which the
     * EventBus contract forbids on the shared ASYNC pool (two concurrent handlers would park
     * BOTH pool threads behind the HM's Prolog work, starving every other subscriber). The
     * bus handler therefore only enqueues onto the Assistant's own agent thread — the same
     * hop {@code DispatchPlayerCommandBehaviour} makes for its binding gate. Tests keep
     * driving {@link #onHintButton} directly, on the calling thread, unchanged. */
    private void enqueueHint(HintButtonEvent event) {
        agent.addBehaviour(new OneShotBehaviour(agent) {
            @Override
            public void action() {
                onHintButton(event);
            }
        });
    }

    /** Cancels the bus subscription; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        Subscription<HintButtonEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
    }

    /**
     * Run the algorithm, show its plain template AT ONCE, then ask the LLM to polish it and — only
     * if the polish passes the hallucination check — replace that template in place.
     *
     * <p>S12 (live-playtest F-8): the template used to appear only from the failure branch, so the
     * player saw nothing until the LLM timed out, and a valid answer that arrived after the timeout
     * was thrown away. Measured: the production prompt takes ~18 s on a quiet laptop and 40-60 s
     * under ordinary background load (a Windows Update scan overlapped both playtest Hints), so a
     * fixed timeout cannot both stay short and let the model through. Publishing the template first
     * makes the wait free: the bridge timeout only bounds how long a revision may still arrive.
     */
    public void onHintButton(HintButtonEvent event) {
        WalkInDialogueSnapshot snapshot = event.snapshot();
        Recommendation rec = algorithm.run(snapshot);
        cache.put(decisionId(event.dialogueId(), snapshot), rec);

        String template = AssistantPromptBuilder.template(rec);
        long hintId = HINT_IDS.incrementAndGet();
        // Before explain() is even called, so the revision can never overtake its own template.
        eventBus.publish(AssistantChatEvent.hintTemplate(event.dialogueId(), hintId, template));

        PromptPayload prompt = AssistantPromptBuilder.prompt(rec);
        // requiredFigures(), not allFigures(): the sidecar's own check 1 mirrors the Java one
        // (task 13 parity), so both sides must narrow together — see Recommendation#requiredFigures.
        // The sidecar's verdict is advisory anyway; HallucinationValidator below is the gate.
        LLMRequest request = new LLMRequest(prompt.user(), prompt.system(),
                rec.requiredFigures().stream().toList(), rec.namedEntities().stream().toList(), true);

        long askedAt = System.nanoTime(); // log-only: the LLM call's real latency, never game state
        llmBridge.explain(request)
                .thenAccept(response -> {
                    long ms = (System.nanoTime() - askedAt) / 1_000_000L;
                    if (HallucinationValidator.validate(response.text(), rec)) {
                        log.info("hint {} ({}): validated model text replaces the template after {} ms",
                                hintId, event.dialogueId(), ms);
                        eventBus.publish(AssistantChatEvent.hintRevision(event.dialogueId(), hintId, response.text()));
                    } else {
                        log.info("hint {} ({}): model text failed the hallucination check after {} ms; "
                                + "the template stands", hintId, event.dialogueId(), ms);
                    }
                })
                .exceptionally(ex -> {
                    log.info("hint {} ({}): llm explain failed/timed out after {} ms ({}); the template stands",
                            hintId, event.dialogueId(), (System.nanoTime() - askedAt) / 1_000_000L, ex.toString());
                    return null;
                });
    }

    static String decisionId(String dialogueId, WalkInDialogueSnapshot snapshot) {
        return dialogueId + "-r" + snapshot.roundsUsed();
    }
}
