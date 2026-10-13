package it.unige.portcommand.agents;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.assistant.AssistantPromptBuilder;
import it.unige.portcommand.assistant.AssistantWording;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.behaviours.assistant.AutopilotExecuteBehaviour;
import it.unige.portcommand.behaviours.assistant.ExplainEventBehaviour;
import it.unige.portcommand.behaviours.assistant.RecommendOnDemandBehaviour;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.ExplainRequestEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.nlp.LLMResponse;
import it.unige.portcommand.nlp.LLMTimeoutException;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.ConversationClock;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import jade.core.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hint flow + autopilot flow against a mocked {@link LLMBridge} (hermetic — no Rasa/Flask
 * servers). The task-03 {@link EventBus} stub does not dispatch to subscribers yet (real
 * dispatch is task 17), so each flow is driven by calling the behaviour's public handler method
 * directly — exactly what the eventual bus dispatch will do — rather than round-tripping through
 * {@code publish}/{@code subscribe}; {@link EventBusProbe} reads back what got published.
 * {@link #assistantRegistersDfServiceOnRealBoot} separately proves the full agent boots cleanly
 * under a real JADE container.
 */
@Tag("integration")
class AssistantAgentIT {

    private static final int TEST_PORT = 18099;

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    private static WalkInDialogueSnapshot tankerSnapshot(double lastVesselOffer, int roundsUsed,
                                                          double timeUsedSec) {
        return new WalkInDialogueSnapshot("v1", "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, lastVesselOffer, 0.0, roundsUsed, timeUsedSec, false);
    }

    // --- Hint flow -----------------------------------------------------------

    @Test
    void hintButtonShowsTheTemplateAtOnceThenTheValidatedLlmTextReplacesIt() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);

        // Independently reproduce the same deterministic recommendation the behaviour will
        // compute, then hand back the prompt's own paragraph (behind a figure-free, name-free
        // lead-in so the revision is distinguishable) as the "LLM" response — guaranteed to pass
        // the hallucination validator without hand-picking figures.
        Recommendation expected = algorithm.run(snapshot);
        // S17a (PT13): what the player sees FIRST is the plain sentence; the paragraph below is
        // only what the model is asked to reword (and what the validator checks its answer by).
        String template = AssistantWording.hintText(expected, snapshot);
        String cannedText = "Here is my advice. " + AssistantPromptBuilder.template(expected);
        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new LLMResponse(cannedText, true)));

        RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(
                harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache());
        behaviour.onHintButton(new HintButtonEvent("dlg-1", snapshot));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(2, chats.size(), "S12: the template at once, then the model's wording as its revision");
        AssistantChatEvent shown = chats.get(0);
        AssistantChatEvent revision = chats.get(1);
        assertEquals("dlg-1", shown.dialogueId());
        assertEquals(template, shown.text());
        assertTrue(shown.isHint());
        assertFalse(shown.revision());
        assertTrue(shown.card() != null && shown.card().describes(snapshot),
                "S17a: the Hint carries its recommendation as a card, for the offer it answered");
        assertEquals(null, revision.card(), "the revision replaces the sentence; the card already shown stays");
        assertEquals("dlg-1", revision.dialogueId());
        assertTrue(revision.revision());
        assertEquals(shown.hintId(), revision.hintId(), "the revision must name the template it replaces");
        assertEquals(cannedText, revision.text());
    }

    @Test
    void anLlmTimeoutLeavesTheTemplateStanding() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);
        Recommendation expected = algorithm.run(snapshot);

        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn(
                CompletableFuture.failedFuture(new LLMTimeoutException("llm timed out", new RuntimeException())));

        RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(
                harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache());
        behaviour.onHintButton(new HintButtonEvent("dlg-2", snapshot));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(1, chats.size(), "a failed call publishes nothing after the template");
        assertEquals(AssistantWording.hintText(expected, snapshot), chats.get(0).text());
        assertFalse(chats.get(0).revision());
    }

    @Test
    void llmOutputThatFailsValidationNeverReplacesTheTemplate() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);
        Recommendation expected = algorithm.run(snapshot);

        LLMBridge mockBridge = mock(LLMBridge.class);
        // Fabricates an unknown vessel name and an unrelated number — must fail the guard.
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn(CompletableFuture.completedFuture(
                new LLMResponse("Vessel Neptune insists on €999999 immediately!", false)));

        RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(
                harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache());
        behaviour.onHintButton(new HintButtonEvent("dlg-3", snapshot));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(1, chats.size(), "a hallucinated LLM response must never be shown");
        assertEquals(AssistantWording.hintText(expected, snapshot), chats.get(0).text());
        assertFalse(chats.get(0).revision());
    }

    /**
     * S12 (live-playtest F-8), against the REAL {@link LLMBridge} and a fake sidecar that answers
     * only when the test lets it: the template must already be on the bus while the model is still
     * "generating", and the validated answer must follow as its revision.
     */
    @Test
    void theTemplateIsOutBeforeASlowModelAnswersAndTheAnswerFollowsAsItsRevision() throws Exception {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);
        String template = AssistantWording.hintText(algorithm.run(snapshot), snapshot);
        String modelText = "Here is my advice. " + AssistantPromptBuilder.template(algorithm.run(snapshot));
        CountDownLatch answer = new CountDownLatch(1);
        HttpServer sidecar = fakeSidecar(answer, modelText);
        try {
            RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(harnessAgent(), algorithm,
                    bridgeTo(sidecar, Duration.ofSeconds(10)), eventBus, new RecommendationCache());
            behaviour.onHintButton(new HintButtonEvent("dlg-slow", snapshot));

            List<AssistantChatEvent> beforeAnswer = chatEvents(eventBus);
            assertEquals(1, beforeAnswer.size(), "the template must not wait for the model");
            assertEquals(template, beforeAnswer.get(0).text());

            answer.countDown();
            assertTrue(pollUntilTrue(() -> chatEvents(eventBus).size() == 2, 10_000),
                    "the validated answer never arrived: " + chatEvents(eventBus));
            AssistantChatEvent revision = chatEvents(eventBus).get(1);
            assertTrue(revision.revision());
            assertEquals(beforeAnswer.get(0).hintId(), revision.hintId());
            assertEquals(modelText, revision.text());
        } finally {
            answer.countDown();
            sidecar.stop(0);
        }
    }

    /** The other side of the same budget: a model slower than the bridge timeout leaves the
     * template as the final word — the late answer is never shown. */
    @Test
    void aModelSlowerThanTheBridgeTimeoutLeavesOnlyTheTemplate() throws Exception {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);
        String template = AssistantWording.hintText(algorithm.run(snapshot), snapshot);
        CountDownLatch answer = new CountDownLatch(1);
        HttpServer sidecar = fakeSidecar(answer,
                "Here is my advice. " + AssistantPromptBuilder.template(algorithm.run(snapshot)));
        try {
            LLMBridge realBridge = bridgeTo(sidecar, Duration.ofMillis(300));
            AtomicReference<CompletableFuture<LLMResponse>> call = new AtomicReference<>();
            LLMBridge watched = mock(LLMBridge.class);
            when(watched.explain(any(LLMRequest.class))).thenAnswer(inv -> {
                CompletableFuture<LLMResponse> future = realBridge.explain(inv.getArgument(0));
                call.set(future);
                return future;
            });
            RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(
                    harnessAgent(), algorithm, watched, eventBus, new RecommendationCache());
            behaviour.onHintButton(new HintButtonEvent("dlg-late", snapshot));

            ExecutionException timedOut = assertThrows(ExecutionException.class,
                    () -> call.get().get(5, TimeUnit.SECONDS));
            assertTrue(timedOut.getCause() instanceof LLMTimeoutException, "got " + timedOut.getCause());
            answer.countDown(); // the model "finishes" after the bridge gave up — nothing may follow
            // A short bounded NEGATIVE wait after the late answer, so the test observes what its
            // javadoc claims (nothing follows the template) instead of reading the bus at once.
            assertFalse(pollUntilTrue(() -> chatEvents(eventBus).size() > 1, 500),
                    "only the template may ever show: " + chatEvents(eventBus));
            List<AssistantChatEvent> chats = chatEvents(eventBus);
            assertEquals(1, chats.size(), "only the template: " + chats);
            assertEquals(template, chats.get(0).text());
        } finally {
            answer.countDown();
            sidecar.stop(0);
        }
    }

    @Test
    void hintIdsNeverRepeatAcrossAssistantRespawns() {
        // A load respawns the Assistant while the bus — and any in-flight answer — survives, and
        // dialogue ids recur; a per-instance counter would let an old answer match a new hint.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        EventBus eventBus = new EventBus();
        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new LLMTimeoutException("t", new RuntimeException())));
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);

        new RecommendOnDemandBehaviour(harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache())
                .onHintButton(new HintButtonEvent("nego-v1", snapshot));
        new RecommendOnDemandBehaviour(harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache())
                .onHintButton(new HintButtonEvent("nego-v1", snapshot));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(2, chats.size());
        assertTrue(chats.get(0).isHint() && chats.get(1).isHint());
        assertTrue(chats.get(0).hintId() != chats.get(1).hintId(), "hint ids must be JVM-wide: " + chats);
    }

    @Test
    void hintButtonOnlyDisplaysAndNeverDispatchesAPlayerCommand() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);

        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new LLMTimeoutException("t", new RuntimeException())));

        RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(
                harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache());
        behaviour.onHintButton(new HintButtonEvent("dlg-4", snapshot));

        List<Event> published = EventBusProbe.published(eventBus);
        assertFalse(published.stream().anyMatch(PlayerCommandEvent.class::isInstance),
                "the Hint plan must only ever display a recommendation, never dispatch one");
    }

    // --- Hint freeze (S14, ADR-18) ---------------------------------------------
    //
    // A walk-in's patience is real conversation time, and the model behind a Hint takes 18-60 real
    // seconds to answer. The player asked for help and must not pay for that latency: the
    // dialogue's conversation stands still from the moment the model is asked until its call ends,
    // however it ends. Driven on a real ConversationClock, moved by hand — nothing sleeps.

    @Test
    void aPendingHintFreezesThatDialoguesPatienceUntilTheModelAnswers() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-freeze", 120_000L);
        clock.advance(5_000L);
        assertEquals(115_000L, remainingMillis(clock, "nego-freeze"), "fixture: the clock runs before the Hint");

        CompletableFuture<LLMResponse> modelCall = new CompletableFuture<>();
        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn(modelCall);
        hintPlanOn(clock, mockBridge, new EventBus())
                .onHintButton(new HintButtonEvent("nego-freeze", tankerSnapshot(6000.0, 1, 60.0)));

        clock.advance(10_000L);
        assertEquals(115_000L, remainingMillis(clock, "nego-freeze"),
                "while the model is thinking, this dialogue's patience must not run down");
        assertEquals(5_000L, elapsedMillis(clock, "nego-freeze"),
                "a frozen conversation does not count the frozen time as elapsed either");

        modelCall.complete(new LLMResponse("Here is my advice.", true));
        clock.advance(10_000L);
        assertEquals(105_000L, remainingMillis(clock, "nego-freeze"),
                "once the model has answered, patience runs again — by exactly the time that passed");
    }

    @Test
    void aFailedModelCallThawsTheDialogueToo() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-fail", 120_000L);

        CompletableFuture<LLMResponse> modelCall = new CompletableFuture<>();
        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn(modelCall);
        hintPlanOn(clock, mockBridge, new EventBus())
                .onHintButton(new HintButtonEvent("nego-fail", tankerSnapshot(6000.0, 1, 60.0)));

        clock.advance(10_000L);
        assertEquals(120_000L, remainingMillis(clock, "nego-fail"), "fixture: frozen while the call is open");

        // The bridge's own timeout is the common way a call ends without an answer.
        modelCall.completeExceptionally(new LLMTimeoutException("llm timed out", new RuntimeException()));
        clock.advance(10_000L);
        assertEquals(110_000L, remainingMillis(clock, "nego-fail"),
                "a call that FAILED must release the freeze as surely as one that answered — "
                        + "otherwise a sidecar outage would hold every hinted vessel in port");
    }

    @Test
    void aBridgeThatThrowsThawsAtOnceAndStillPropagates() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-throw", 120_000L);
        EventBus eventBus = new EventBus();

        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class))).thenThrow(new IllegalStateException("bridge down"));
        RecommendOnDemandBehaviour behaviour = hintPlanOn(clock, mockBridge, eventBus);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> behaviour.onHintButton(new HintButtonEvent("nego-throw", tankerSnapshot(6000.0, 1, 60.0))));
        assertEquals("bridge down", thrown.getMessage(), "the bridge's own failure must reach the caller unchanged");
        assertEquals(1, chatEvents(eventBus).size(), "the template was already shown before the call was attempted");

        clock.advance(10_000L);
        assertEquals(110_000L, remainingMillis(clock, "nego-throw"),
                "no future exists to release the freeze later, so it must be released at once");
    }

    @Test
    void aBridgeThatMakesNoCallThawsAtOnce() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-nocall", 120_000L);
        EventBus eventBus = new EventBus();

        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn((CompletableFuture<LLMResponse>) null);
        hintPlanOn(clock, mockBridge, eventBus)
                .onHintButton(new HintButtonEvent("nego-nocall", tankerSnapshot(6000.0, 1, 60.0)));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(1, chats.size(), "no model call means no revision: the template stands alone");
        assertFalse(chats.get(0).revision());

        clock.advance(10_000L);
        assertEquals(110_000L, remainingMillis(clock, "nego-nocall"),
                "a call that was never made has nothing to wait for — patience keeps running");
    }

    @Test
    void aPendingHintLeavesEveryOtherDialogueRunning() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-hinted", 120_000L);
        clock.open("nego-other", 180_000L);

        CompletableFuture<LLMResponse> modelCall = new CompletableFuture<>();
        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn(modelCall);
        hintPlanOn(clock, mockBridge, new EventBus())
                .onHintButton(new HintButtonEvent("nego-hinted", tankerSnapshot(6000.0, 1, 60.0)));

        clock.advance(10_000L);
        assertEquals(120_000L, remainingMillis(clock, "nego-hinted"), "the hinted dialogue is frozen");
        assertEquals(170_000L, remainingMillis(clock, "nego-other"),
                "the freeze belongs to ONE tab: another vessel's patience must keep running");

        modelCall.complete(new LLMResponse("Here is my advice.", true));
        clock.advance(10_000L);
        assertEquals(110_000L, remainingMillis(clock, "nego-hinted"), "thawed once the model has answered");
        assertEquals(160_000L, remainingMillis(clock, "nego-other"), "and the other dialogue never stopped");
    }

    // --- Autopilot flow --------------------------------------------------------

    @Test
    void autopilotDispatchesPlayerCommandAndChatEventWhenEnabled() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        PolicyRegistryArtifact policyRegistry = new PolicyRegistryArtifact(); // no policies registered
        EventBus eventBus = new EventBus();
        AtomicBoolean autopilotEnabled = new AtomicBoolean(true);

        AutopilotExecuteBehaviour behaviour = new AutopilotExecuteBehaviour(harnessAgent(), algorithm,
                policyRegistry, eventBus, new RecommendationCache(), autopilotEnabled);
        // Offer already at the fair-price band centre -> the EV algorithm picks "accept".
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 0, 0.0);
        behaviour.onNegotiationOpened(new NegotiationOpenedEvent("dlg-5", snapshot));

        List<Event> published = EventBusProbe.published(eventBus);
        Optional<PlayerCommandEvent> cmd = published.stream()
                .filter(PlayerCommandEvent.class::isInstance).map(PlayerCommandEvent.class::cast).findFirst();
        assertTrue(cmd.isPresent(), "autopilot must dispatch a PlayerCommandEvent to HM");
        assertEquals(PlayerCommandEvent.PlayerCommandKind.ACCEPT, cmd.get().kind());
        assertEquals("v1", cmd.get().targetVesselId());
        // S17a (A9): the move is narrated once the HarbourMaster has SENT it — not with the
        // command, before the Prolog gate has judged it. (AutopilotPlanTest pins the rest.)
        assertFalse(published.stream().anyMatch(AssistantChatEvent.class::isInstance),
                "nothing is said about a move the gate has not let through yet");
        behaviour.onCommandDispatched(new PlayerCommandDispatchedEvent("dlg-5",
                PlayerCommandEvent.PlayerCommandKind.ACCEPT, cmd.get().commandId()));
        assertTrue(EventBusProbe.published(eventBus).stream().anyMatch(AssistantChatEvent.class::isInstance),
                "autopilot must narrate its move via chat once it has been made");
    }

    @Test
    void autopilotDoesNothingWhenDisabled() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        PolicyRegistryArtifact policyRegistry = new PolicyRegistryArtifact();
        EventBus eventBus = new EventBus();
        AtomicBoolean autopilotEnabled = new AtomicBoolean(false);

        AutopilotExecuteBehaviour behaviour = new AutopilotExecuteBehaviour(harnessAgent(), algorithm,
                policyRegistry, eventBus, new RecommendationCache(), autopilotEnabled);
        behaviour.onNegotiationOpened(new NegotiationOpenedEvent("dlg-6", tankerSnapshot(6000.0, 0, 0.0)));

        assertTrue(EventBusProbe.published(eventBus).isEmpty(), "autopilot OFF must publish nothing");
    }

    @Test
    void matchingPolicyShortCircuitsTheEvPath() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        PolicyRegistryArtifact policyRegistry = new PolicyRegistryArtifact();
        // "price > 0" always matches -> reject_silent, even though the EV algorithm alone would
        // pick "accept" for this same snapshot (proven by the enabled-autopilot test above).
        PolicyRule rule = new PolicyParser().parse("auto reject if price > 0", new EventBus()).orElseThrow();
        policyRegistry.register(rule);
        EventBus eventBus = new EventBus();
        AtomicBoolean autopilotEnabled = new AtomicBoolean(true);

        AutopilotExecuteBehaviour behaviour = new AutopilotExecuteBehaviour(harnessAgent(), algorithm,
                policyRegistry, eventBus, new RecommendationCache(), autopilotEnabled);
        behaviour.onNegotiationOpened(new NegotiationOpenedEvent("dlg-7", tankerSnapshot(6000.0, 0, 0.0)));

        // S17a (A1): the policy's "reject" is an explicit NO on the wire. Before S17a it
        // dispatched nothing — this test used to pin exactly that — and the vessel then waited
        // out her whole patience and the port lost reputation for a timeout.
        PlayerCommandEvent cmd = EventBusProbe.published(eventBus).stream()
                .filter(PlayerCommandEvent.class::isInstance).map(PlayerCommandEvent.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("the policy's reject must be SAID"));
        assertEquals(PlayerCommandEvent.PlayerCommandKind.REJECT, cmd.kind(),
                "the policy decided, not the EV path (which accepts this snapshot)");
        behaviour.onCommandDispatched(new PlayerCommandDispatchedEvent("dlg-7", cmd.kind(), cmd.commandId()));
        AssistantChatEvent chat = EventBusProbe.published(eventBus).stream()
                .filter(AssistantChatEvent.class::isInstance)
                .map(AssistantChatEvent.class::cast).findFirst().orElseThrow();
        assertTrue(chat.text().contains("said no") && chat.text().contains("your policy: auto reject if price > 0"),
                "chat narration must reflect the policy's action: " + chat.text());
    }

    // --- Explain ("Why?") flow ------------------------------------------------

    @Test
    void explainRequestRegeneratesPastDecisionFromCache() {
        RecommendationCache cache = new RecommendationCache();
        EventBus eventBus = new EventBus();
        Recommendation rec = new RecommendationAlgorithm(new MarketHistoryArtifact())
                .run(tankerSnapshot(6000.0, 1, 60.0));
        cache.put("dlg-8-r1", rec);
        String cannedText = AssistantPromptBuilder.template(rec);

        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new LLMResponse(cannedText, true)));

        ExplainEventBehaviour behaviour = new ExplainEventBehaviour(harnessAgent(), cache, mockBridge, eventBus);
        behaviour.onExplainRequest(new ExplainRequestEvent("dlg-8-r1", "nego-W8"));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(1, chats.size());
        assertEquals(cannedText, chats.get(0).text());
    }

    @Test
    void explainRequestForUnknownDecisionIdNeverCallsTheLlm() {
        EventBus eventBus = new EventBus();
        LLMBridge mockBridge = mock(LLMBridge.class);

        ExplainEventBehaviour behaviour =
                new ExplainEventBehaviour(harnessAgent(), new RecommendationCache(), mockBridge, eventBus);
        behaviour.onExplainRequest(new ExplainRequestEvent("never-cached", "nego-W9"));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(1, chats.size(), "an unknown decisionId must still get a graceful reply");
        verifyNoInteractions(mockBridge);
    }

    // --- Real boot -----------------------------------------------------------

    @Test
    void assistantRegistersDfServiceOnRealBoot() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus());

        CompletableFuture<Map<String, Integer>> future = new CompletableFuture<>();
        boot.getSpawner().spawn("df_census_probe", DfCensusProbe.class, new Object[] {future});
        Map<String, Integer> counts = future.get(15, TimeUnit.SECONDS);
        assertEquals(1, counts.get("assistant"), "Assistant singleton must register DF cleanly with its 4 args");
    }

    /**
     * S14 (adversarial review): the freeze's lease is sized from the bridge's OWN timeout, so a
     * raised {@code llm.timeout_ms} can never let the lease thaw a vessel while its tab's Hint
     * is still legitimately waiting.
     */
    @Test
    void theFreezeOutlastsABridgeWithALongTimeout() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-slow", 120_000L);
        LLMBridge slowBridge = mock(LLMBridge.class);
        when(slowBridge.timeout()).thenReturn(Duration.ofMinutes(10));
        when(slowBridge.explain(any(LLMRequest.class))).thenReturn(new CompletableFuture<>());
        hintPlanOn(clock, slowBridge, new EventBus())
                .onHintButton(new HintButtonEvent("nego-slow", tankerSnapshot(6000.0, 1, 60.0)));

        clock.advance(400_000L); // far past the 150 s default lease, well inside the bridge's 10 min

        assertEquals(120_000L, remainingMillis(clock, "nego-slow"),
                "the model may still answer for another three minutes: the vessel must stay frozen");
    }

    /** ...and a freeze nobody ever releases still ends: the default lease, past the bridge's timeout. */
    @Test
    void anAbandonedFreezeEndsWithItsLease() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-lost", 120_000L);
        LLMBridge mockBridge = mock(LLMBridge.class); // states no timeout: the default lease applies
        when(mockBridge.explain(any(LLMRequest.class))).thenReturn(new CompletableFuture<>());
        hintPlanOn(clock, mockBridge, new EventBus())
                .onHintButton(new HintButtonEvent("nego-lost", tankerSnapshot(6000.0, 1, 60.0)));

        clock.advance(ConversationClock.DEFAULT_FREEZE_LEASE_MILLIS - 1);
        assertEquals(120_000L, remainingMillis(clock, "nego-lost"), "inside the lease: frozen");
        clock.advance(1L);
        clock.advance(10_000L);

        assertEquals(110_000L, remainingMillis(clock, "nego-lost"),
                "a future nobody completes must not hold a vessel in port forever");
    }

    /**
     * S14 (adversarial review): the WIRING. Every case above hands the clock to a hand-built
     * behaviour; this one presses Hint against the Assistant the roster really spawns and checks
     * that what freezes is the GAME's conversation clock — the one the vessels are on. With a
     * stand-in clock inside the agent the Hint would freeze nothing and no other test would notice.
     */
    @Test
    void aHintThroughTheRosterSpawnedAssistantFreezesTheGamesOwnClock() throws Exception {
        LLMBridge pendingBridge = mock(LLMBridge.class);
        when(pendingBridge.explain(any(LLMRequest.class))).thenReturn(new CompletableFuture<>());
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                pendingBridge, boot.getEventBus());
        ConversationClock clock = boot.getSimClock().conversationClock();
        clock.open("nego-wired", 120_000L);
        clock.open("nego-other", 120_000L);

        // The Assistant subscribes on its own thread just after setup; a Hint published before
        // that is simply not heard. Re-press (bounded) until the model has been asked.
        HintButtonEvent hint = new HintButtonEvent("nego-wired", tankerSnapshot(6000.0, 1, 0.0));
        long deadline = System.currentTimeMillis() + 20_000L;
        boolean asked = false;
        while (!asked && System.currentTimeMillis() < deadline) {
            boot.getEventBus().publish(hint);
            asked = pollUntilTrue(() -> !org.mockito.Mockito.mockingDetails(pendingBridge).getInvocations()
                    .stream().filter(i -> "explain".equals(i.getMethod().getName())).toList().isEmpty(), 500);
        }
        assertTrue(asked, "the roster-spawned Assistant never asked the model for the Hint");

        clock.advance(10_000L);

        assertEquals(120_000L, remainingMillis(clock, "nego-wired"),
                "the Hint must freeze the conversation on the game's own clock");
        assertEquals(110_000L, remainingMillis(clock, "nego-other"),
                "fixture: that clock really ran — the other dialogue lost its 10 s");
    }

    // --- helpers ---------------------------------------------------------------

    /** A plain (never {@code .setup()}'d / never container-attached) {@link Agent} handle to
     * satisfy {@code Behaviour}'s constructor — the behaviours under test never touch agent
     * internals (DF, container) directly, only their injected collaborators. */
    private static Agent harnessAgent() {
        return new AssistantAgent();
    }

    /** The Hint plan wired to {@code clock} through the S14 six-arg constructor — the form
     * {@code AssistantAgent} uses with the game's own conversation clock. */
    private static RecommendOnDemandBehaviour hintPlanOn(ConversationClock clock, LLMBridge bridge,
                                                         EventBus eventBus) {
        return new RecommendOnDemandBehaviour(harnessAgent(),
                new RecommendationAlgorithm(new MarketHistoryArtifact()), bridge, eventBus,
                new RecommendationCache(), clock);
    }

    private static long remainingMillis(ConversationClock clock, String dialogueId) {
        return view(clock, dialogueId).remainingMillis();
    }

    private static long elapsedMillis(ConversationClock clock, String dialogueId) {
        return view(clock, dialogueId).elapsedMillis();
    }

    private static ConversationClock.View view(ConversationClock clock, String dialogueId) {
        return clock.view(dialogueId)
                .orElseThrow(() -> new AssertionError("no conversation on the clock for " + dialogueId));
    }

    private static List<AssistantChatEvent> chatEvents(EventBus bus) {
        return EventBusProbe.published(bus).stream()
                .filter(AssistantChatEvent.class::isInstance)
                .map(AssistantChatEvent.class::cast)
                .toList();
    }

    /** A stand-in for the Flask sidecar's {@code POST /explain} that answers {@code text} only once
     * {@code answer} is released (bounded, so a forgotten latch can never hang the suite). Daemon
     * handler threads, like {@code LLMBridgeTest}'s. */
    private static HttpServer fakeSidecar(CountDownLatch answer, String text) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/explain", exchange -> {
            try {
                answer.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("text", text, "validated", true));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-sidecar");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return server;
    }

    private static LLMBridge bridgeTo(HttpServer server, Duration timeout) {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new LLMBridge(URI.create(base + "/explain"), URI.create(base + "/health"),
                HttpClient.newHttpClient(), new ObjectMapper(), timeout);
    }

    /** Bounded poll — the project's no-Awaitility wait (the design rules working-style rule 5). */
    private static boolean pollUntilTrue(BooleanSupplier condition, long totalMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
