package it.unige.portcommand.behaviours.assistant;

import java.util.concurrent.CompletableFuture;
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
import it.unige.portcommand.nlp.LLMResponse;
import it.unige.portcommand.util.ConversationClock;
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

    /** How far past the bridge's own timeout an unreleased Hint freeze is given up on. */
    private static final long FREEZE_LEASE_MARGIN_MILLIS = 30_000L;

    private final RecommendationAlgorithm algorithm;
    private final LLMBridge llmBridge;
    private final EventBus eventBus;
    private final RecommendationCache cache;
    /** S14 (ADR-18): the vessel's patience stands still while this tab's Hint waits for the model
     * — the player asked for help and should not pay for the model's latency. */
    private final ConversationClock conversationClock;
    /** Captured at construction, NEVER read from {@code myAgent} in the handlers — JADE nulls
     * {@code myAgent} once a OneShot completes (the DispatchPlayerCommandBehaviour task-19
     * lesson), while the bus subscription lives on. */
    private final Agent agent;

    /** No conversation clock: nothing to freeze (a private clock no vessel is on). */
    public RecommendOnDemandBehaviour(Agent agent, RecommendationAlgorithm algorithm, LLMBridge llmBridge,
                                      EventBus eventBus, RecommendationCache cache) {
        this(agent, algorithm, llmBridge, eventBus, cache, new ConversationClock());
    }

    public RecommendOnDemandBehaviour(Agent agent, RecommendationAlgorithm algorithm, LLMBridge llmBridge,
                                      EventBus eventBus, RecommendationCache cache,
                                      ConversationClock conversationClock) {
        super(agent);
        this.agent = agent;
        this.algorithm = algorithm;
        this.llmBridge = llmBridge;
        this.eventBus = eventBus;
        this.cache = cache;
        this.conversationClock = conversationClock;
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

        // S14 (ADR-18): freeze this dialogue's patience for exactly as long as the model is
        // thinking. Released ONCE, from the future's own completion — success, failure and timeout
        // alike — and at once if the call never produced a future. A release that never comes
        // (a future nobody completes) is bounded by the lease, which is sized from the bridge's
        // own timeout so it can never fire while a legitimate call is still running.
        Runnable thaw = conversationClock.freeze(event.dialogueId(), freezeLeaseMillis());
        long askedAt = System.nanoTime(); // log-only: the LLM call's real latency, never game state
        CompletableFuture<LLMResponse> answer;
        try {
            answer = llmBridge.explain(request);
        } catch (RuntimeException e) {
            thaw.run();
            throw e;
        }
        if (answer == null) {
            thaw.run();
            log.info("hint {} ({}): no model call was made; the template stands", hintId, event.dialogueId());
            return;
        }
        answer.whenComplete((response, error) -> thaw.run());
        answer
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

    /** The bridge's configured timeout plus a margin, never less than the clock's default lease
     * (and exactly that default when the bridge states no timeout — a test double). */
    private long freezeLeaseMillis() {
        java.time.Duration timeout = llmBridge.timeout();
        return timeout == null ? ConversationClock.DEFAULT_FREEZE_LEASE_MILLIS
                : Math.max(ConversationClock.DEFAULT_FREEZE_LEASE_MILLIS,
                        timeout.toMillis() + FREEZE_LEASE_MARGIN_MILLIS);
    }

    static String decisionId(String dialogueId, WalkInDialogueSnapshot snapshot) {
        return dialogueId + "-r" + snapshot.roundsUsed();
    }
}
