package it.unige.portcommand.assistant;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.behaviours.assistant.RecommendOnDemandBehaviour;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.harbourmaster.MovementGate;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S16 (2026-10-13, decision B10): what the Assistant adds to a Hint when the weather would keep
 * that vessel waiting outside after a deal. {@link WeatherNote} only words the verdict of
 * {@code MovementGate}, which asks the real weather rules (R15 wind per vessel type, R16
 * visibility, R17 swell, R19 sea state) — so the engine is loaded here as in
 * {@code PrologQueriesTest}, and the limits below are the kernel's own (tanker 30 kn, ferry 35,
 * cargo vessel 45; swell 4.0 m).
 *
 * <p>S17a (ADR-21): the note is the last part of the Hint card's DETAILS LINE — in S16 it was a
 * message of its own after the template. The second block is that wiring: that
 * {@code RecommendOnDemandBehaviour} puts it on the card only when there is one, that it is not in
 * the Hint's sentence, and that neither the model's revision nor a failure of the weather check
 * disturbs the Hint itself. No JADE container and no sidecar: the handler is called directly
 * against a mocked {@code LLMBridge}.
 */
class WeatherNoteTest {

    private static final String LEAD = "ship will wait: ";

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private static Reading reading(double windKnots, String visibility, double swell, String state) {
        return new Reading(windKnots, visibility, swell, state);
    }

    @Test
    void unsafeWeatherForTheVesselGivesTheNoteWithTheGatesReasonAndRuleTag() {
        Optional<String> note = WeatherNote.forDetails("tanker", reading(32, "good", 1.0, "stormy"));

        assertEquals(Optional.of(LEAD + "wind 32 kn, sea state stormy [R15,R19]"), note);
        assertEquals("ship will wait: wind 32 kn, sea state stormy [R15,R19]", note.orElseThrow(),
                "the part as the player reads it");
    }

    @Test
    void theNoteIsTheGatesOwnVerdictWordForWord() {
        // The Port table prints the same verdict next to a held ship; the two must not be able to
        // disagree about a reading they share.
        Reading storm = reading(32, "good", 1.0, "stormy");
        MovementGate.Verdict verdict = MovementGate.check("tanker", storm);
        assertFalse(verdict.safe(), "fixture: the gate holds a tanker in this reading");

        assertEquals(LEAD + verdict.reason(), WeatherNote.forDetails("tanker", storm).orElseThrow());
        assertTrue(WeatherNote.forDetails("tanker", storm).orElseThrow().contains(verdict.ruleTag()));
    }

    @Test
    void safeWeatherGivesNoNote() {
        assertEquals(Optional.empty(), WeatherNote.forDetails("tanker", reading(18, "good", 0.5, "sunny")));
        assertEquals(Optional.empty(), WeatherNote.forDetails("ferry", reading(29, "fair", 4.0, "cloudy")),
                "fair visibility and a swell exactly on the limit are both within the rules");
    }

    @Test
    void noReadingGivesNoNote() {
        // "No reading" closes the movement gate, but a guess is not a warning.
        assertEquals(Optional.empty(), WeatherNote.forDetails("tanker", null));
        assertFalse(MovementGate.check("tanker", null).safe(), "non-vacuity: the gate itself is closed here");
    }

    @Test
    void aFerryIsSafeAtThirtyFourKnotsAndHeldAtThirtyFive() {
        // R15 is "wind BELOW the type's limit", and a ferry's limit is 35.
        assertEquals(Optional.empty(), WeatherNote.forDetails("ferry", reading(34, "good", 1.0, "cloudy")));
        assertEquals(Optional.of(LEAD + "wind 35 kn [R15]"),
                WeatherNote.forDetails("ferry", reading(35, "good", 1.0, "cloudy")));
    }

    @Test
    void theSameWeatherIsANoteForOneTypeAndNoneForAnother() {
        // The note is about THAT vessel: 32 kn of cloudy wind holds a tanker (limit 30) and
        // nobody else.
        Reading windy = reading(32, "good", 1.0, "cloudy");

        assertEquals(Optional.of(LEAD + "wind 32 kn [R15]"), WeatherNote.forDetails("tanker", windy));
        assertEquals(Optional.empty(), WeatherNote.forDetails("ferry", windy));
        assertEquals(Optional.empty(), WeatherNote.forDetails("cruise_ship", windy));
        assertEquals(Optional.empty(), WeatherNote.forDetails("container_vessel", windy));
        assertEquals(Optional.empty(), WeatherNote.forDetails("cargo_vessel", windy));
    }

    @Test
    void visibilitySwellAndSeaStateEachHoldOnTheirOwn() {
        assertEquals(Optional.of(LEAD + "visibility poor [R16]"),
                WeatherNote.forDetails("cargo_vessel", reading(12, "poor", 1.0, "cloudy")));
        assertEquals(Optional.of(LEAD + "swell 4.5 m [R17]"),
                WeatherNote.forDetails("cargo_vessel", reading(12, "good", 4.5, "cloudy")));
        assertEquals(Optional.of(LEAD + "sea state stormy [R19]"),
                WeatherNote.forDetails("cargo_vessel", reading(12, "good", 1.0, "stormy")));
        assertEquals(Optional.of(LEAD + "wind 46 kn, visibility poor, swell 5.0 m, sea state stormy [R15,R16,R17,R19]"),
                WeatherNote.forDetails("cargo_vessel", reading(46, "poor", 5.0, "stormy")),
                "all four at once, in rule order");
    }

    @Test
    void aMissingVesselTypeGivesNoNote() {
        Reading storm = reading(42, "poor", 5.0, "stormy");

        assertEquals(Optional.empty(), WeatherNote.forDetails(null, storm));
        assertEquals(Optional.empty(), WeatherNote.forDetails(" ", storm));
    }

    // --- on a Hint: the wiring in RecommendOnDemandBehaviour ----------------------------------------
    //
    // Driven like AssistantAgentIT drives the Hint plan: the handler is called directly, on this
    // thread, against a mocked LLMBridge, and what reached the bus is read back from its log.

    private static final Reading STORM = new Reading(32, "good", 1.0, "stormy");
    private static final Reading CALM = new Reading(18, "good", 0.5, "sunny");

    /** S19 (PT38a): late (ten seconds left), where the calculation still accepts her. */
    private static WalkInDialogueSnapshot tanker() {
        return new WalkInDialogueSnapshot("v1", "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, 6000.0, 0.0, 1, 60.0, false).withTime(170.0, 180.0);
    }

    /** One Hint for {@link #tanker()} with {@code readingOrNull} as the Assistant's last weather
     * and {@code bridge} as the model; every Assistant message it put on the bus, in order. */
    private static List<AssistantChatEvent> hint(Reading readingOrNull, LLMBridge bridge) {
        EventBus eventBus = new EventBus();
        RecommendOnDemandBehaviour plan = new RecommendOnDemandBehaviour(new Agent(),
                new RecommendationAlgorithm(new MarketHistoryArtifact()), bridge, eventBus,
                new RecommendationCache(), new ConversationClock(), () -> readingOrNull);

        plan.onHintButton(new HintButtonEvent("dlg-1", tanker()));

        return EventBusProbe.published(eventBus).stream()
                .filter(AssistantChatEvent.class::isInstance)
                .map(AssistantChatEvent.class::cast)
                .toList();
    }

    /** A model that fails at once: the template stands, as in a game with no sidecar running. */
    private static LLMBridge noModel() {
        LLMBridge bridge = mock(LLMBridge.class);
        when(bridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no model in this test")));
        return bridge;
    }

    @Test
    void aHintInUnsafeWeatherCarriesTheNoteAtTheEndOfItsDetailsLine() {
        List<AssistantChatEvent> chats = hint(STORM, noModel());

        assertEquals(1, chats.size(), "ONE message: the note is on the card, not a message of its own (S17a)");
        AssistantChatEvent template = chats.get(0);
        assertTrue(template.isHint(), "the Hint is still the first thing out");
        assertFalse(template.revision());
        assertFalse(template.text().contains("R15"), "the note is not part of the Hint's sentence");
        assertFalse(template.text().contains("wind"), "nor is the weather");

        String details = template.card().details();
        assertTrue(details.endsWith(" · ship will wait: wind 32 kn, sea state stormy [R15,R19]"),
                "the gate's reason and its rule ids close the details line: " + details);
        assertTrue(details.contains("fits berth ✓ (R7)"), "after the numbers and the berth check: " + details);
    }

    @Test
    void aHintInSafeWeatherOrBeforeAnyReadingHasNoWeatherInItsDetails() {
        List<AssistantChatEvent> calm = hint(CALM, noModel());
        assertEquals(1, calm.size());
        assertTrue(calm.get(0).isHint());
        assertFalse(calm.get(0).card().details().contains("ship will wait"), "safe for a tanker: nothing to warn of");
        assertTrue(calm.get(0).card().details().endsWith("fits berth ✓ (R7)"), calm.get(0).card().details());

        List<AssistantChatEvent> unknown = hint(null, noModel());
        assertEquals(1, unknown.size());
        assertFalse(unknown.get(0).card().details().contains("ship will wait"),
                "no reading yet: the Assistant does not guess");
    }

    @Test
    void theModelsRevisionReplacesTheSentenceAndNeverTheDetails() {
        // The same canned answer AssistantAgentIT uses: the prompt's own paragraph behind a
        // figure-free lead-in, so it passes the hallucination validator by construction.
        Recommendation expected = new RecommendationAlgorithm(new MarketHistoryArtifact()).run(tanker());
        String cannedText = "Here is my advice. " + AssistantPromptBuilder.template(expected);
        LLMBridge bridge = mock(LLMBridge.class);
        when(bridge.explain(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new LLMResponse(cannedText, true)));

        List<AssistantChatEvent> chats = hint(STORM, bridge);

        assertEquals(2, chats.size(), "the Hint, then the validated wording");
        AssistantChatEvent template = chats.get(0);
        AssistantChatEvent revision = chats.get(1);
        assertTrue(revision.revision(), "the model's text was validated as before: the note was not in it");
        assertEquals(template.hintId(), revision.hintId(), "it replaces the SENTENCE of that Hint");
        assertEquals(cannedText, revision.text());
        assertEquals(null, revision.card(), "a revision carries no card: the one already shown stays");
        assertTrue(template.card().details().endsWith("ship will wait: wind 32 kn, sea state stormy [R15,R19]"));
    }

    @Test
    void aWeatherCheckThatFailsCostsTheNoteNotTheHint() {
        // The supplier stands in for anything in the check that could throw.
        EventBus eventBus = new EventBus();
        RecommendOnDemandBehaviour plan = new RecommendOnDemandBehaviour(new Agent(),
                new RecommendationAlgorithm(new MarketHistoryArtifact()), noModel(), eventBus,
                new RecommendationCache(), new ConversationClock(), () -> {
                    throw new IllegalStateException("no weather to be had");
                });

        plan.onHintButton(new HintButtonEvent("dlg-1", tanker()));

        List<AssistantChatEvent> chats = EventBusProbe.published(eventBus).stream()
                .filter(AssistantChatEvent.class::isInstance)
                .map(AssistantChatEvent.class::cast)
                .toList();
        assertEquals(1, chats.size(), "the Hint is out; only the note is missing");
        assertTrue(chats.get(0).isHint());
        assertTrue(chats.get(0).card().details().endsWith("fits berth ✓ (R7)"), chats.get(0).card().details());
    }
}
