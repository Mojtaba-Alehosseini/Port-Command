package it.unige.portcommand.assistant;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.behaviours.assistant.RecommendOnDemandBehaviour;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.harbourmaster.MovementGate.Reading;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.nlp.LLMResponse;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.ConversationClock;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import jade.core.Agent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S17a (ADR-21): the Hint plan publishes a CARD (tree-analysis A3, playtest PT13) and answers one
 * Hint at a time per offer (UI-tree U1). {@link RecommendOnDemandBehaviour#onHintButton} is called
 * directly against a mocked {@link LLMBridge}, as in {@code WeatherNoteTest}; the engine is loaded
 * because the algorithm asks Prolog whether the vessel fits the berth.
 *
 * <p>What is NOT here because it did not change: the paragraph sent to the model, the validator
 * that judges the answer, and the 18/20 gate over them ({@code LlmHallucinationIT}).
 */
class HintPlanCardTest {

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private EventBus bus;
    private LLMBridge bridge;
    private RecommendationAlgorithm algorithm;
    private RecommendOnDemandBehaviour plan;

    @BeforeEach
    void world() {
        bus = new EventBus();
        bridge = mock(LLMBridge.class);
        algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        plan = planWithWeather(null);
    }

    private RecommendOnDemandBehaviour planWithWeather(Reading reading) {
        return new RecommendOnDemandBehaviour(new Agent(), algorithm, bridge, bus, new RecommendationCache(),
                new ConversationClock(), () -> reading);
    }

    private static WalkInDialogueSnapshot tanker(double offer, int round) {
        return new WalkInDialogueSnapshot("v1", "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, offer, 0.0, round, 60.0, false);
    }

    private static WalkInDialogueSnapshot longCheapCargo() {
        return new WalkInDialogueSnapshot("v2", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo",
                "berth_1", 17, 1477.0, 0.0, 1, 0.0, false);
    }

    private List<AssistantChatEvent> chats() {
        return EventBusProbe.published(bus).stream()
                .filter(AssistantChatEvent.class::isInstance).map(AssistantChatEvent.class::cast).toList();
    }

    private void modelFailsAtOnce() {
        when(bridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no model in this test")));
    }

    // ---- the card (A3, PT13) -------------------------------------------------------------------

    @Test
    void aHintIsAPlainSentenceWithACardForTheOfferItAnswered() {
        modelFailsAtOnce();
        WalkInDialogueSnapshot snapshot = tanker(6000.0, 1);
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.ACCEPT, rec.action(), "fixture: a tanker at her band's centre is accepted");

        plan.onHintButton(new HintButtonEvent("nego-v1", snapshot));

        AssistantChatEvent hint = chats().get(0);
        assertEquals("Accept their offer of €6000 for 6h.", hint.text(),
                "PT13: before S17a this line was the prompt's own paragraph, figures and berth ids included");
        HintCard card = hint.card();
        assertEquals(RecommendationCandidate.ACCEPT, card.action());
        assertEquals(6000.0, card.price(), 0.0);
        assertEquals(6, card.hours());
        assertEquals(1, card.round());
        assertEquals(6000.0, card.vesselOffer(), 0.0);
        assertEquals("accept €6000 for 6h", card.suggestion());
        // 2 tugs x 350 + 100 customs + 6 h x 50 = 1100; accepting her own offer is certain.
        assertEquals("EV €4900 · chance 100% · R7 ✓", card.details());
        assertTrue(card.describes(snapshot));
    }

    @Test
    void aSayNoHintGivesTheReasonAndTheOfferAgainstTheCosts() {
        modelFailsAtOnce();
        assertEquals(RecommendationCandidate.REJECT_SILENT, algorithm.run(longCheapCargo()).action(),
                "fixture: the playtest's 17-hour cargo vessel does not pay");

        plan.onHintButton(new HintButtonEvent("nego-v2", longCheapCargo()));

        AssistantChatEvent hint = chats().get(0);
        assertEquals("Say no — costs (berth time, tugs, customs) are higher than this offer.", hint.text());
        assertTrue(hint.card().isSayNo());
        assertEquals("say no", hint.card().suggestion());
        assertEquals("offer €1477 · costs €1650 · R7 ✓", hint.card().details());
    }

    @Test
    void theModelIsAskedExactlyWhatItWasAskedBeforeAndNeverSeesTheCard() {
        // The validator's contract is "no number the recommendation did not contain"; a rule id or
        // a wind figure in the prompt would come back in the answer and fail it (decision A4).
        modelFailsAtOnce();
        WalkInDialogueSnapshot snapshot = tanker(6000.0, 1);
        Recommendation rec = algorithm.run(snapshot);

        planWithWeather(new Reading(32, "good", 1.0, "stormy")).onHintButton(new HintButtonEvent("nego-v1", snapshot));

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(bridge).explain(sent.capture());
        assertEquals(AssistantPromptBuilder.prompt(rec).user(), sent.getValue().prompt(), "the frozen paragraph");
        assertEquals(AssistantPromptBuilder.prompt(rec).system(), sent.getValue().system());
        assertFalse(sent.getValue().prompt().contains("R15"), "no weather rule in what the model reads");
        assertFalse(sent.getValue().prompt().contains("ship will wait"));
        assertTrue(chats().get(0).card().details().contains("ship will wait: wind 32 kn, sea state stormy [R15,R19]"),
                "non-vacuity: the weather IS on the card");
    }

    // ---- one Hint at a time per offer (U1) -----------------------------------------------------

    @Test
    void aSecondPressWhileTheModelIsStillAnsweringIsIgnored() {
        CompletableFuture<LLMResponse> thinking = new CompletableFuture<>();
        when(bridge.explain(any(LLMRequest.class))).thenReturn(thinking);
        HintButtonEvent press = new HintButtonEvent("nego-v1", tanker(6000.0, 1));

        plan.onHintButton(press);
        plan.onHintButton(press);
        plan.onHintButton(press);

        assertEquals(1, chats().size(), "before S17a every click added a bubble, started another model call "
                + "and froze the vessel's patience once more");
        verify(bridge, times(1)).explain(any(LLMRequest.class));
    }

    @Test
    void onceTheModelHasAnsweredHintCanBePressedAgain() {
        CompletableFuture<LLMResponse> thinking = new CompletableFuture<>();
        when(bridge.explain(any(LLMRequest.class))).thenReturn(thinking);
        HintButtonEvent press = new HintButtonEvent("nego-v1", tanker(6000.0, 1));
        plan.onHintButton(press);

        thinking.complete(new LLMResponse("Vessel Neptune insists on €999999 immediately!", false)); // rejected text
        plan.onHintButton(press);

        assertEquals(2, chats().size(), "the first call is over - however it ended - so this is a new question");
        assertNotEquals(chats().get(0).hintId(), chats().get(1).hintId());
        verify(bridge, times(2)).explain(any(LLMRequest.class));
    }

    @Test
    void aModelCallThatFailsFreesTheOfferToo() {
        CompletableFuture<LLMResponse> thinking = new CompletableFuture<>();
        when(bridge.explain(any(LLMRequest.class))).thenReturn(thinking);
        HintButtonEvent press = new HintButtonEvent("nego-v1", tanker(6000.0, 1));
        plan.onHintButton(press);

        thinking.completeExceptionally(new RuntimeException("timed out"));
        plan.onHintButton(press);

        assertEquals(2, chats().size());
    }

    @Test
    void aNewOfferIsANewQuestionEvenWhileTheOldAnswerIsStillOut() {
        // "Outdated - press Hint again" must be true the moment it is shown: the vessel has
        // countered, and the model may still be busy with the offer that is gone.
        when(bridge.explain(any(LLMRequest.class))).thenReturn(new CompletableFuture<>());

        plan.onHintButton(new HintButtonEvent("nego-v1", tanker(6000.0, 1)));
        plan.onHintButton(new HintButtonEvent("nego-v1", tanker(6300.0, 2)));

        assertEquals(2, chats().size());
        assertEquals(2, chats().get(1).card().round());
        assertEquals(6300.0, chats().get(1).card().vesselOffer(), 0.0);
    }

    @Test
    void aPendingHintInOneTabDoesNotSilenceAnother() {
        when(bridge.explain(any(LLMRequest.class))).thenReturn(new CompletableFuture<>());

        plan.onHintButton(new HintButtonEvent("nego-v1", tanker(6000.0, 1)));
        plan.onHintButton(new HintButtonEvent("nego-v9", tanker(6000.0, 1)));

        assertEquals(List.of("nego-v1", "nego-v9"), chats().stream().map(AssistantChatEvent::dialogueId).toList());
    }

    @Test
    void theValidatedWordingStillReplacesTheSentenceAndLeavesTheCard() {
        WalkInDialogueSnapshot snapshot = tanker(6000.0, 1);
        String modelText = "Here is my advice. " + AssistantPromptBuilder.template(algorithm.run(snapshot));
        when(bridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new LLMResponse(modelText, true)));

        plan.onHintButton(new HintButtonEvent("nego-v1", snapshot));

        assertEquals(2, chats().size());
        AssistantChatEvent revision = chats().get(1);
        assertTrue(revision.revision());
        assertEquals(chats().get(0).hintId(), revision.hintId());
        assertEquals(modelText, revision.text());
        assertEquals(Optional.empty(), Optional.ofNullable(revision.card()),
                "the revision changes the words; the card the tab already has is not sent again");
    }
}
