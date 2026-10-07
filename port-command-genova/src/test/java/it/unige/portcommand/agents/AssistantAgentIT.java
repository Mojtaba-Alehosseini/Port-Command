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
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.nlp.LLMResponse;
import it.unige.portcommand.nlp.LLMTimeoutException;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.prolog.PrologEngine;
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
        // compute, then hand back its own template text (behind a figure-free, name-free lead-in so
        // the revision is distinguishable) as the "LLM" response — guaranteed to pass the
        // hallucination validator without hand-picking figures.
        Recommendation expected = algorithm.run(snapshot);
        String template = AssistantPromptBuilder.template(expected);
        String cannedText = "Here is my advice. " + template;
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
        assertEquals(AssistantPromptBuilder.template(expected), chats.get(0).text());
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
        assertEquals(AssistantPromptBuilder.template(expected), chats.get(0).text());
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
        String template = AssistantPromptBuilder.template(algorithm.run(snapshot));
        String modelText = "Here is my advice. " + template;
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
        String template = AssistantPromptBuilder.template(algorithm.run(snapshot));
        CountDownLatch answer = new CountDownLatch(1);
        HttpServer sidecar = fakeSidecar(answer, "Here is my advice. " + template);
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
        assertTrue(published.stream().anyMatch(AssistantChatEvent.class::isInstance),
                "autopilot must also narrate its move via chat");
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

        List<Event> published = EventBusProbe.published(eventBus);
        assertFalse(published.stream().anyMatch(PlayerCommandEvent.class::isInstance),
                "reject_silent (from the matched policy) must not dispatch a PlayerCommandEvent");
        AssistantChatEvent chat = published.stream().filter(AssistantChatEvent.class::isInstance)
                .map(AssistantChatEvent.class::cast).findFirst().orElseThrow();
        assertTrue(chat.text().contains("reject_silent"), "chat narration must reflect the policy's action");
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

    // --- helpers ---------------------------------------------------------------

    /** A plain (never {@code .setup()}'d / never container-attached) {@link Agent} handle to
     * satisfy {@code Behaviour}'s constructor — the behaviours under test never touch agent
     * internals (DF, container) directly, only their injected collaborators. */
    private static Agent harnessAgent() {
        return new AssistantAgent();
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
