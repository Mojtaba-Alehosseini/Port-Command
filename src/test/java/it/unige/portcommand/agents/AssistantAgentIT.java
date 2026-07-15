package it.unige.portcommand.agents;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void hintButtonPublishesLlmPolishedTextWhenValidatorPasses() {
        MarketHistoryArtifact marketHistory = new MarketHistoryArtifact();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        EventBus eventBus = new EventBus();
        WalkInDialogueSnapshot snapshot = tankerSnapshot(6000.0, 1, 60.0);

        // Independently reproduce the same deterministic recommendation the behaviour will
        // compute, then hand back its own template text as the "LLM" response — a legitimate
        // LLM output (the system prompt explicitly allows "repeat the input verbatim") that is
        // guaranteed to pass the hallucination validator without hand-picking figures.
        Recommendation expected = algorithm.run(snapshot);
        String cannedText = AssistantPromptBuilder.template(expected);
        LLMBridge mockBridge = mock(LLMBridge.class);
        when(mockBridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new LLMResponse(cannedText, true)));

        RecommendOnDemandBehaviour behaviour = new RecommendOnDemandBehaviour(
                harnessAgent(), algorithm, mockBridge, eventBus, new RecommendationCache());
        behaviour.onHintButton(new HintButtonEvent("dlg-1", snapshot));

        List<AssistantChatEvent> chats = chatEvents(eventBus);
        assertEquals(1, chats.size(), "exactly one AssistantChatEvent must be published");
        assertEquals("dlg-1", chats.get(0).dialogueId());
        assertEquals(cannedText, chats.get(0).text());
    }

    @Test
    void hintButtonFallsBackToTemplateOnLlmTimeout() {
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
        assertEquals(1, chats.size());
        assertEquals(AssistantPromptBuilder.template(expected), chats.get(0).text());
    }

    @Test
    void hintButtonFallsBackToTemplateWhenLlmOutputFailsValidation() {
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
        assertEquals(1, chats.size());
        assertEquals(AssistantPromptBuilder.template(expected), chats.get(0).text(),
                "a hallucinated LLM response must fall back to the plain template");
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
        behaviour.onExplainRequest(new ExplainRequestEvent("dlg-8-r1"));

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
        behaviour.onExplainRequest(new ExplainRequestEvent("never-cached"));

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
}
