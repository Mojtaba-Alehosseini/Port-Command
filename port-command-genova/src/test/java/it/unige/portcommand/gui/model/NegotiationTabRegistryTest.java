package it.unige.portcommand.gui.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import it.unige.portcommand.assistant.HintCard;
import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NegotiationTabRegistryTest {

    private static VesselSpec spec(String vesselId, String type) {
        return new VesselSpec(vesselId, type, 9.0, 150.0, 30000, "general_cargo", 0L);
    }

    private static NegotiationOpenedEvent opened(String dialogueId, String vesselId, String type,
                                                 String berthId, int roundsUsed, double vesselOffer) {
        WalkInDialogueSnapshot snapshot = WalkInDialogueSnapshot.of(
                vesselId, spec(vesselId, type), berthId, 6, vesselOffer, 0.0, roundsUsed, 0.0, false);
        return new NegotiationOpenedEvent(dialogueId, snapshot);
    }

    @Test
    void openingCreatesExactlyOneNewTabWithAnOrientationLine() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();

        NegotiationTabRegistry.OpenResult result = registry.onNegotiationOpened(
                opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertEquals("nego-W001", result.dialogueId());
        assertTrue(result.isNewTab());
        assertEquals(List.of("nego-W001"), registry.dialogueIds());
        DialogueTabModel tab = registry.tab("nego-W001");
        assertEquals(2, tab.transcript().size(),
                "a new tab opens with the orientation SYSTEM line + the opening offer bubble");
        assertEquals(DialogueTabModel.Speaker.SYSTEM, tab.transcript().get(0).speaker(),
                "first entry orients the player (who pays whom, what to type — task 19 play-test)");
        assertTrue(tab.transcript().get(0).text().contains("Harbour Master"),
                "the orientation line must say who the player is");
        assertEquals(DialogueTabModel.Speaker.VESSEL, tab.transcript().get(1).speaker());
        assertFalse(tab.isClosed());
    }

    @Test
    void aRoundTwoUpdateOnTheSameDialogueIdUpdatesNotDuplicates() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        NegotiationTabRegistry.OpenResult result = registry.onNegotiationOpened(
                opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1700.0));

        assertFalse(result.isNewTab(), "an existing dialogueId must update, not create a second tab");
        assertEquals(1, registry.dialogueIds().size(), "still exactly one tab");
        DialogueTabModel tab = registry.tab("nego-W001");
        assertEquals(3, tab.transcript().size(),
                "orientation line + round-1 + round-2 bubbles, not overwritten (and no second intro)");
        assertEquals(1700.0, tab.snapshot().lastVesselOffer(), 0.001, "the strip must read the refreshed offer");
        assertEquals(2, tab.snapshot().roundsUsed(), "the strip must read the refreshed round");
    }

    @Test
    void recordPlayerOfferUpdatesTheSnapshotAndTheCtxStandingOffer() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        registry.recordPlayerOffer("nego-W001", 5000.0, 10);

        assertEquals(5000.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001,
                "the offer strip reads lastPlayerOffer from the snapshot — it was stuck at €0 before");
        DialogueCtx ctx = registry.buildCtx("W001");
        assertEquals(5000.0, ctx.standingOffer().playerOffer().price(), 0.001,
                "the DCG's 'split the difference' needs the player side of the standing offer");
    }

    @Test
    void aRoundUpdateCarriesThePlayerOfferForward() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 5000.0, 10);

        // The HM builds every snapshot with lastPlayerOffer=0.0 (it never tracks the player's
        // side) — a vessel counter must not wipe the GUI's record of the player's standing offer.
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1700.0));

        assertEquals(5000.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);
        assertEquals(1700.0, registry.tab("nego-W001").snapshot().lastVesselOffer(), 0.001,
                "the vessel side must still refresh from the event");
    }

    @Test
    void recordPlayerOfferOnAClosedOrUnknownDialogueIsANoOp() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);

        registry.recordPlayerOffer("nego-W001", 5000.0, 10); // closed
        registry.recordPlayerOffer("nego-GHOST", 5000.0, 10); // unknown

        assertEquals(0.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001,
                "a closed dialogue's snapshot must not mutate");
    }

    @Test
    void closeMarksTheRightTabByDialogueId() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));

        Optional<String> closed = registry.onNegotiationClosed(
                new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);

        assertEquals(Optional.of("nego-W001"), closed);
        assertTrue(registry.tab("nego-W001").isClosed());
        assertEquals(Deal.Outcome.DEAL, registry.tab("nego-W001").outcome());
        assertFalse(registry.tab("nego-W002").isClosed(), "the OTHER tab must be untouched");
    }

    @Test
    void closingAnUnknownDialogueIdReturnsEmpty() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();

        Optional<String> closed = registry.onNegotiationClosed(
                new NegotiationClosedEvent("nego-GHOST", "GHOST", Deal.Outcome.TIMEOUT), 0L);

        assertEquals(Optional.empty(), closed);
    }

    @Test
    void assistantChatRoutesByExactDialogueIdMatch() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        Optional<String> routed = registry.onAssistantChat(new AssistantChatEvent("nego-W001", "🤖 Assistant: take it"));

        assertEquals(Optional.of("nego-W001"), routed);
        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        assertEquals(DialogueTabModel.Speaker.ASSISTANT, transcript.get(transcript.size() - 1).speaker());
    }

    @Test
    void dialogueIdForVesselReverseLookupWorks() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertEquals(Optional.of("nego-W001"), registry.dialogueIdForVessel("W001"));
        assertEquals(Optional.empty(), registry.dialogueIdForVessel("UNKNOWN"));
    }

    @Test
    void buildCtxFocusesOnTheGivenVesselAndExcludesClosedDialogues() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W002", "W002", Deal.Outcome.TIMEOUT), 0L);

        DialogueCtx ctx = registry.buildCtx("W001");

        assertEquals("W001", ctx.activeNegotiationId());
        assertEquals(1, ctx.roster().size(), "the closed W002 dialogue must not appear in the roster");
        assertEquals("W001", ctx.roster().get(0).id());
        assertTrue(ctx.hasStandingOffer());
    }

    @Test
    void onChangeFiresOnOpenCloseAndAssistantChat() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onAssistantChat(new AssistantChatEvent("nego-W001", "hint"));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);

        assertEquals(3, changes[0]);
    }

    // ==================== task 19b: the duration dimension in the tab model ====================

    @Test
    void aVesselCounterAboveThePlayersHoursGetsTheFloorPushbackNote() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 1300.0, 4); // player proposed a 4h stay

        // The vessel's counter comes back at 6h — its hours term ABOVE the player's proposal is
        // the duration floor pushing back, and the transcript line must say so.
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1400.0));

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        String counterLine = transcript.get(transcript.size() - 1).text();
        assertTrue(counterLine.contains("they need at least 6h"),
                "the floor pushback must be legible bargaining feedback, got: " + counterLine);
    }

    @Test
    void aVesselCounterEchoingThePlayersHoursGetsNoPushbackNote() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 1300.0, 6); // matches the snapshot's 6h

        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1400.0));

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        String counterLine = transcript.get(transcript.size() - 1).text();
        assertFalse(counterLine.contains("they need at least"),
                "an hours-agreed counter is not a pushback: " + counterLine);
    }

    @Test
    void aPriceOnlyCounterResolvesToTheHoursUnderDiscussionLikeTheVesselDoes() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        registry.recordPlayerOffer("nego-W001", 1300.0, 10);
        registry.recordPlayerOffer("nego-W001", 1400.0, null); // a price-only counter names no hours

        // The vessel resolves an absent hours key to ITS standing term (the snapshot's 6h here),
        // not to the player's earlier 10 — a price-only reply implicitly concedes the hours on
        // the table. The GUI must record what the engine actually heard, or the strip and the
        // floor-pushback note drift from the real negotiation state.
        assertEquals(6, registry.tab("nego-W001").lastPlayerHours(),
                "a duration-less utterance resolves to the hours under discussion (the vessel-side rule)");
        assertEquals(1400.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);
    }

    @Test
    void aDurationWithdrawalClosesWithItsOwnHonestSummary() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        registry.onNegotiationClosed(
                new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.WITHDRAW_DURATION), 0L);

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        String closeLine = transcript.get(transcript.size() - 1).text();
        assertTrue(closeLine.contains("hours"),
                "a duration withdrawal must not masquerade as a price one: " + closeLine);
    }

    // ==================== S12 (live-playtest F-8): template first, model text in place ====================

    private static String lastText(DialogueTabModel tab) {
        List<DialogueTabModel.ChatEntry> transcript = tab.transcript();
        return transcript.get(transcript.size() - 1).text();
    }

    /** S19 (playtest 5, PT25): the revision used to REPLACE the template; it now goes under it. */
    @Test
    void aHintRevisionGoesUnderItsTemplateInPlace() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 7L, "template"));
        int templateIndex = registry.tab("nego-W001").transcript().size() - 1;
        // The vessel speaks again while the model is still generating: the revision must land on
        // the template's own line, not on whatever is last.
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1700.0));
        int sizeBefore = registry.tab("nego-W001").transcript().size();

        Optional<String> routed = registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 7L, "model"));

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        assertEquals(Optional.of("nego-W001"), routed);
        assertEquals(sizeBefore, transcript.size(), "a revision never appends");
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT, "template", "", "model", ""),
                transcript.get(templateIndex), "the short line stays; the model's words are its Why");
        assertEquals(DialogueTabModel.Speaker.VESSEL, transcript.get(transcript.size() - 1).speaker(),
                "the later vessel line stays where it was");
    }

    @Test
    void aLateRevisionNeverOverwritesANewerHint() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        int[] changes = {0};
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 1L, "first template"));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 2L, "second template"));
        registry.setOnChange(() -> changes[0]++);

        assertEquals(Optional.empty(),
                registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 1L, "stale model text")));
        List<String> texts = registry.tab("nego-W001").transcript().stream().map(DialogueTabModel.ChatEntry::text).toList();
        assertTrue(texts.contains("first template") && texts.contains("second template"),
                "a superseded hint's answer changes nothing: " + texts);
        assertFalse(texts.contains("stale model text"));
        assertTrue(registry.tab("nego-W001").transcript().stream().noneMatch(e -> e.why().contains("stale")),
                "nor under any line");
        assertEquals(0, changes[0], "a refused revision must not trigger a re-render");

        registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 2L, "fresh model text"));
        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        DialogueTabModel.ChatEntry latest = transcript.get(transcript.size() - 1);
        assertEquals("second template", latest.text(), "S19 (PT25): the short line stays");
        assertEquals("fresh model text", latest.why());
        assertEquals(1, changes[0]);
    }

    @Test
    void noHintTemplateOrRevisionLandsInAClosedTab() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 3L, "template"));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        List<DialogueTabModel.ChatEntry> before = registry.tab("nego-W001").transcript();

        assertEquals(Optional.empty(),
                registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 3L, "model text")));
        assertEquals(Optional.empty(),
                registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 4L, "late template")));

        assertEquals(before, registry.tab("nego-W001").transcript(),
                "advice for a finished negotiation must not appear in its tab");
    }

    @Test
    void anOrdinaryAssistantMessageStillLandsInAClosedTab() {
        // The berth-queue note is published AFTER the deal closed the tab — the hint guards must
        // not swallow it.
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);

        Optional<String> routed = registry.onAssistantChat(new AssistantChatEvent("nego-W001", "queued for berth_2"));

        assertEquals(Optional.of("nego-W001"), routed);
        assertEquals("queued for berth_2", lastText(registry.tab("nego-W001")));
    }

    @Test
    void aRevisionFromBeforeAResetMatchesNothingInTheReopenedTab() {
        // Dialogue ids recur after a load (nego-W001 again); hint ids do not.
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 5L, "old world's template"));
        registry.reset();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        List<DialogueTabModel.ChatEntry> before = registry.tab("nego-W001").transcript();

        assertEquals(Optional.empty(),
                registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 5L, "old world's model text")));
        assertEquals(before, registry.tab("nego-W001").transcript());
    }

    @Test
    void aRevisionMustNameTheHintItRevises() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantChatEvent("nego-W001", "text", AssistantChatEvent.NO_HINT, true));
        assertFalse(new AssistantChatEvent("nego-W001", "text").isHint());
        assertTrue(AssistantChatEvent.hintTemplate("nego-W001", 9L, "text").isHint());
    }

    // ==================== S17a (ADR-21, decision A3): the hint card ====================

    private static final String DETAILS = "EV €640 · chance 62% · R7 ✓";

    /** S19 (playtest 5, PT25): the Hint's line changed a few seconds after it appeared - the
     * model's paragraph replaced it. The line stays; the model's wording is added under it as the
     * Why, and the details stay too. */
    @Test
    void theModelsWordingIsAddedUnderTheHintNotInPlaceOfIt() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 7L, "Counter at €1580 for 6h.",
                new it.unige.portcommand.assistant.HintCard("counter", 1579.6, 6, 1, 1500.0, "counter at €1580 for 6h",
                        DETAILS, "the tip")));

        registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 7L, "Asking a bit more is safe."));

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        DialogueTabModel.ChatEntry hint = transcript.get(transcript.size() - 1);
        assertEquals("Counter at €1580 for 6h.", hint.text(), "the short line never changes");
        assertEquals("Asking a bit more is safe.", hint.why());
        assertEquals(DETAILS, hint.tag());
        assertEquals("the tip", hint.tip());
    }

    /** A card recommending a counter at €1580, made for round {@code round} of a vessel asking
     * {@code vesselOffer} for 6h — the terms {@link #opened} builds a tab with. */
    private static HintCard counterCard(int round, double vesselOffer) {
        return new HintCard(RecommendationCandidate.COUNTER, 1580.0, 6, round, vesselOffer,
                "counter at €1580 for 6h", DETAILS);
    }

    @Test
    void aHintTemplateWithACardStoresTheCardAndPutsItsDetailsOnTheHintsOwnEntry() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        DialogueTabModel tab = registry.tab("nego-W001");
        assertEquals(Optional.empty(), tab.hintCard(), "no Hint asked for yet");
        HintCard card = counterCard(1, 1500.0);

        Optional<String> routed = registry.onAssistantChat(
                AssistantChatEvent.hintTemplate("nego-W001", 7L, "Counter at €1580 for 6h.", card));

        assertEquals(Optional.of("nego-W001"), routed);
        assertEquals(Optional.of(card), tab.hintCard());
        List<DialogueTabModel.ChatEntry> transcript = tab.transcript();
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT,
                "Counter at €1580 for 6h.", DETAILS), transcript.get(transcript.size() - 1),
                "the details ride on the hint's own entry, so they are drawn with it and nowhere else");
        assertTrue(transcript.subList(0, transcript.size() - 1).stream().allMatch(e -> e.tag().isEmpty()),
                "and on no other line: " + transcript);
    }

    @Test
    void aRevisionAddsTheWhyTheLineTheDetailsAndTheCardStay() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        DialogueTabModel tab = registry.tab("nego-W001");
        HintCard card = counterCard(1, 1500.0);
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 7L, "template", card));
        int hintIndex = tab.transcript().size() - 1;

        // The model's wording arrives: a revision carries no card (it rewords, it recomputes nothing).
        AssistantChatEvent revision = AssistantChatEvent.hintRevision("nego-W001", 7L, "model wording");
        assertEquals(null, revision.card(), "setup: a revision brings no card of its own");
        assertEquals(Optional.of("nego-W001"), registry.onAssistantChat(revision));

        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT, "template", DETAILS,
                "model wording", ""), tab.transcript().get(hintIndex),
                "S19 (PT25): the sentence and the details stay; the model's wording is the Why");
        assertEquals(Optional.of(card), tab.hintCard(), "and the row still offers the same card");
    }

    @Test
    void aNewHintReplacesTheCardWhetherOrNotItBringsOne() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        DialogueTabModel tab = registry.tab("nego-W001");
        HintCard first = counterCard(1, 1500.0);
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 1L, "first", first));
        int firstIndex = tab.transcript().size() - 1;

        HintCard second = new HintCard(RecommendationCandidate.ACCEPT, 1500.0, 6, 1, 1500.0,
                "accept €1500 for 6h", "EV €410 · chance 100% · R7 ✓");
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 2L, "second", second));
        assertEquals(Optional.of(second), tab.hintCard(), "the row speaks for the newest Hint");
        assertEquals(DETAILS, tab.transcript().get(firstIndex).tag(),
                "the first hint keeps its own details in the transcript — they were true of it");

        // A Hint with no card (the pre-S17a event shape) still becomes the newest Hint: the row
        // must not go on offering advice the transcript has moved past.
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 3L, "third, no card"));
        assertEquals(Optional.empty(), tab.hintCard());
        assertEquals("", tab.transcript().get(tab.transcript().size() - 1).tag());

        // A late revision of a superseded hint changes neither the text nor the card (S12's rule).
        assertEquals(Optional.empty(),
                registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 2L, "late")));
        assertEquals(Optional.empty(), tab.hintCard());
    }

    @Test
    void aUsedCardIsGoneUntilTheNextHintBringsANewOne() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        DialogueTabModel tab = registry.tab("nego-W001");
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 1L, "template", counterCard(1, 1500.0)));

        tab.useHintCard();
        assertEquals(Optional.empty(), tab.hintCard(), "one card, one send");
        // The model's wording for that same hint must not bring the card back.
        registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 1L, "model wording"));
        assertEquals(Optional.empty(), tab.hintCard());
        assertEquals(DETAILS, tab.transcript().get(tab.transcript().size() - 1).tag(),
                "what was used up is the button, not the explanation in the transcript");

        HintCard next = counterCard(2, 1700.0);
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1700.0));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 2L, "template", next));
        assertEquals(Optional.of(next), tab.hintCard(), "a new Hint's card is unused, whatever happened to the last");
    }

    @Test
    void noCardLandsInAClosedTabAndTheOneItHadIsNotReplaced() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        DialogueTabModel tab = registry.tab("nego-W001");
        HintCard card = counterCard(1, 1500.0);
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 1L, "template", card));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        List<DialogueTabModel.ChatEntry> before = tab.transcript();

        assertEquals(Optional.empty(), registry.onAssistantChat(
                AssistantChatEvent.hintTemplate("nego-W001", 2L, "late template", counterCard(1, 1500.0))));

        assertEquals(before, tab.transcript(), "advice for a finished negotiation must not appear in its tab");
        assertEquals(Optional.of(card), tab.hintCard(), "a refused Hint changes nothing, the card included");
        assertEquals(HintRow.State.HIDDEN, HintRow.of(tab, false).state(),
                "and a closed tab draws no row for it (HintRowTest has the rest)");
    }

    @Test
    void anOrdinaryAssistantMessageNeitherCarriesDetailsNorTouchesTheCard() {
        // The autopilot's narration and the gate's refusals are AssistantChatEvents too.
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        DialogueTabModel tab = registry.tab("nego-W001");
        HintCard card = counterCard(1, 1500.0);
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 1L, "template", card));

        registry.onAssistantChat(new AssistantChatEvent("nego-W001", "berth_3 is incompatible with a ferry [R7]"));

        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT,
                "berth_3 is incompatible with a ferry [R7]"), tab.transcript().get(tab.transcript().size() - 1));
        assertEquals(Optional.of(card), tab.hintCard());
    }

    // ==================== S19 (playtest 5, PT26): the player closes a closed tab ====================
    // Audit C-07 let a closed tab leave the strip by itself, 30 seconds after it closed (F-9's pile-up
    // of closed tabs). Playtest 5: they vanished while the player still wanted them. A closed tab
    // now stays until the player presses its x; more than five closed tabs send the one that closed
    // first - never the one being read - to the Finished list.

    private static NegotiationTabRegistry withTabs(String... vesselIds) {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        for (String id : vesselIds) {
            registry.onNegotiationOpened(opened("nego-" + id, id, "cargo_vessel", "berth_1", 1, 1500.0));
        }
        return registry;
    }

    /** What the chat panel does when a negotiation closes: the close, then the limit. */
    private static List<String> close(NegotiationTabRegistry registry, String vesselId, String reading) {
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-" + vesselId, vesselId, Deal.Outcome.DEAL), 0L);
        return registry.trimClosedTabs(reading);
    }

    @Test
    void aClosedTabStaysUntilThePlayerClosesIt() {
        NegotiationTabRegistry registry = withTabs("W001");
        assertEquals(List.of(), close(registry, "W001", null), "one closed tab is within the limit");
        assertEquals(List.of("nego-W001"), registry.dialogueIds(), "and no clock takes it away");
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        assertTrue(registry.closeTab("nego-W001"), "the player's x");

        assertEquals(List.of(), registry.dialogueIds());
        assertNull(registry.tab("nego-W001"));
        assertEquals(Optional.empty(), registry.dialogueIdForVessel("W001"));
        assertEquals(List.of("nego-W001"), registry.finishedDialogues().stream().map(FinishedDialogue::dialogueId)
                .toList(), "nothing is lost: it is in the Finished list");
        assertEquals(1, changes[0]);
        assertFalse(registry.closeTab("nego-W001"), "a tab that has left cannot leave twice");
        assertEquals(1, changes[0]);
    }

    @Test
    void anOpenNegotiationsTabHasNoClose() {
        NegotiationTabRegistry registry = withTabs("W001");

        assertFalse(registry.closeTab("nego-W001"), "the vessel is still waiting for an answer");
        assertEquals(List.of("nego-W001"), registry.dialogueIds());
        assertFalse(registry.closeTab("nego-ghost"));
        assertFalse(registry.closeTab(null));
    }

    @Test
    void aSixthClosedTabSendsTheFirstToCloseToFinished() {
        NegotiationTabRegistry registry = withTabs("W001", "W002", "W003", "W004", "W005", "W006", "W007");
        for (String id : List.of("W001", "W002", "W003", "W004", "W005")) {
            assertEquals(List.of(), close(registry, id, null), "five closed tabs stay: " + id);
        }
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        assertEquals(List.of("nego-W001"), close(registry, "W006", null));

        assertEquals(List.of("nego-W002", "nego-W003", "nego-W004", "nego-W005", "nego-W006", "nego-W007"),
                registry.dialogueIds(), "five closed tabs and the open one");
        assertEquals(6, registry.finishedDialogues().size(), "the one that left is in the Finished list too");
        assertEquals(Optional.empty(), registry.dialogueIdForVessel("W001"));
        assertEquals(2, changes[0], "the close, and the tab that left");
        assertEquals(List.of(), registry.trimClosedTabs(null), "within the limit again: nothing more leaves");
    }

    @Test
    void theOldestIsTheFirstToCloseNotTheFirstToOpen() {
        NegotiationTabRegistry registry = withTabs("W001", "W002", "W003", "W004", "W005", "W006");
        for (String id : List.of("W006", "W005", "W004", "W003", "W002")) {
            close(registry, id, null);
        }

        assertEquals(List.of("nego-W006"), close(registry, "W001", null));
    }

    @Test
    void theTabBeingReadIsNeverTheOneThatLeaves() {
        NegotiationTabRegistry registry = withTabs("W001", "W002", "W003", "W004", "W005", "W006");
        for (String id : List.of("W001", "W002", "W003", "W004", "W005")) {
            close(registry, id, "nego-W001");
        }

        assertEquals(List.of("nego-W002"), close(registry, "W006", "nego-W001"),
                "the oldest is being read: the next oldest goes");
        assertTrue(registry.dialogueIds().contains("nego-W001"));
        assertEquals(List.of(), registry.trimClosedTabs(null), "five again: the player moving on changes nothing");
    }

    @Test
    void aClosedDialogueIdReopensAsAFreshTab() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        assertTrue(registry.closeTab("nego-W001"));

        NegotiationTabRegistry.OpenResult reopened = registry.onNegotiationOpened(
                opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertTrue(reopened.isNewTab(), "the view must add a page again");
        assertFalse(registry.tab("nego-W001").isClosed());
        assertEquals(2, registry.tab("nego-W001").transcript().size(), "orientation + opening, nothing inherited");
        assertEquals(Optional.of("nego-W001"), registry.dialogueIdForVessel("W001"));
    }

    @Test
    void closingATabNeverDropsANewerDialoguesVesselMapping() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-A", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-A", "W001", Deal.Outcome.DEAL), 0L);
        registry.onNegotiationOpened(opened("nego-B", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertTrue(registry.closeTab("nego-A"));
        assertEquals(Optional.of("nego-B"), registry.dialogueIdForVessel("W001"));
    }

    // ==================== S18b (2026-10-14): plain lines, the Finished list, the tab being read ====================

    private static String lastLine(NegotiationTabRegistry registry, String dialogueId) {
        List<DialogueTabModel.ChatEntry> transcript = registry.tab(dialogueId).transcript();
        return transcript.get(transcript.size() - 1).text();
    }

    /** Playtest 2, PT3: "Opens at…" / "Counters at…" were hard to read. S19 (playtest 5, PT32):
     * "who is 'they'? is there a middleman?" - the line names the ship. */
    @Test
    void theVesselsOfferAndRepliesArePlainLines() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(openedNamed("nego-W001", "W001", "cargo_vessel", "Faro Vecchio", 1, 1500.0));
        assertEquals("Faro Vecchio offers €1,500 for 6h at berth 2.", lastLine(registry, "nego-W001"));

        registry.recordPlayerOffer("nego-W001", 2000.0, 6);
        registry.onNegotiationOpened(openedNamed("nego-W001", "W001", "cargo_vessel", "Faro Vecchio", 2, 1640.0));
        assertEquals("Faro Vecchio counters: €1,640 for 6h.", lastLine(registry, "nego-W001"));
    }

    @Test
    void aCounterAtThePlayersOwnPriceSaysTheyAcceptItButNeedMoreHours() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(openedNamed("nego-W001", "W001", "cargo_vessel", "Faro Vecchio", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 1600.0, 4);

        // The vessel takes the 1600 and pushes only the hours back to its 6h floor. The HM stamps
        // no player price on this snapshot (0.0), so the tab's own record carries it forward.
        registry.onNegotiationOpened(openedNamed("nego-W001", "W001", "cargo_vessel", "Faro Vecchio", 2, 1600.0));

        assertEquals("Faro Vecchio accepts €1,600 but needs at least 6h.", lastLine(registry, "nego-W001"));
    }

    @Test
    void theClosingLineSaysHowItEndedWithTheAgreedTerms() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(openedNamed("nego-W001", "W001", "cargo_vessel", "Faro Vecchio", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL,
                1700.0, 6, true), 0L);

        assertEquals("✔ Faro Vecchio accepted €1,700 for 6h — deal done.", lastLine(registry, "nego-W001"));
        assertEquals(1700.0, registry.tab("nego-W001").agreedPrice(), 0.001);
        assertEquals(6, registry.tab("nego-W001").agreedHours());
    }

    /** Playtest 2, PT5: every ended negotiation is listed, newest first, with how it ended. */
    @Test
    void everyEndedNegotiationIsListedAsFinishedNewestFirst() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationOpened(opened("nego-W003", "W003", "ferry", "berth_4", 1, 900.0));
        assertEquals(List.of(), registry.finishedDialogues(), "nothing has ended yet");

        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL,
                1700.0, 6, true), 0L);
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W002", "W002", Deal.Outcome.TIMEOUT), 10L);
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL,
                1700.0, 6, true), 20L); // a repeated close (C-11) keeps its place

        assertEquals(List.of(
                new FinishedDialogue("nego-W002", "W002", Deal.Outcome.TIMEOUT, 0.0, 0),
                new FinishedDialogue("nego-W001", "W001", Deal.Outcome.DEAL, 1700.0, 6)), registry.finishedDialogues());
    }

    @Test
    void aFinishedNegotiationOutlivesItsTabAndAResetEmptiesTheList() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.WITHDRAW_PRICE), 0L);
        assertTrue(registry.closeTab("nego-W001"));
        assertNull(registry.tab("nego-W001"), "setup: the tab left the strip");

        assertEquals(1, registry.finishedDialogues().size(), "the Finished list keeps it");

        registry.reset();
        assertEquals(List.of(), registry.finishedDialogues(), "a new world starts with an empty list");
        assertFalse(registry.reopenFinished("nego-W001"), "and the old world's dialogue cannot come back");
    }

    /** Playtest 2, PT5: a click on a Finished line brings its transcript back, read-only. */
    @Test
    void aFinishedNegotiationCanBeBroughtBackWithItsTranscript() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        DialogueTabModel model = registry.tab("nego-W001");
        List<DialogueTabModel.ChatEntry> transcript = model.transcript();
        assertTrue(registry.closeTab("nego-W001"));
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        assertTrue(registry.reopenFinished("nego-W001"));

        assertSame(model, registry.tab("nego-W001"), "the same model: a parse still in flight finds its own tab");
        assertEquals(transcript, registry.tab("nego-W001").transcript());
        assertTrue(registry.tab("nego-W001").isClosed(), "read-only, as every closed tab");
        assertEquals(List.of("nego-W002"), registry.openDialogues().stream().map(OpenDialogue::dialogueId).toList(),
                "a finished negotiation is not waiting for anyone");
        assertEquals(Optional.empty(), registry.dialogueIdForVessel("W001"), "its vessel is out of play");
        assertEquals(1, changes[0]);

        assertTrue(registry.reopenFinished("nego-W001"), "already back: nothing to do");
        assertEquals(1, changes[0]);
        assertTrue(registry.reopenFinished("nego-W002"), "an open negotiation's tab is on the strip: nothing to do");
        assertEquals(List.of("nego-W002", "nego-W001"), registry.dialogueIds(), "and nothing was added twice");
        assertFalse(registry.reopenFinished("nego-ghost"));
        assertTrue(registry.closeTab("nego-W001"), "S19 (PT26): its x takes it off the strip again");
    }

    @Test
    void anAssistantLineForAFinishedNegotiationStillReachesItsTranscript() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        assertTrue(registry.closeTab("nego-W001"));

        assertEquals(Optional.of("nego-W001"),
                registry.onAssistantChat(new AssistantChatEvent("nego-W001", "Accepted at once — you might have asked for more.")));

        registry.reopenFinished("nego-W001");
        assertEquals("Accepted at once — you might have asked for more.", lastLine(registry, "nego-W001"));
    }

    // ==================== S13 (live-playtest F-7): a refused PROPOSE leaves the strip ====================

    /** {@code standingPrice} = what the HarbourMaster reports the vessel last heard at the refusal. */
    private static PlayerCommandRefusedEvent refused(String dialogueId, long commandId, double standingPrice) {
        return new PlayerCommandRefusedEvent(dialogueId, PlayerCommandEvent.PlayerCommandKind.PROPOSE, commandId,
                standingPrice, "berth_3 is incompatible with a ferry [R7]");
    }

    /** S19 (playtest 5, PT30): "-> Demanding EUR 2,000 for 5h" showed for a move the gate refused. */
    @Test
    void aHeldEchoIsShownWhenTheMoveWentOutAndDroppedWhenItWasRefused() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.tab("nego-W001").holdEcho(41L, "-> You ask EUR 2,000 for 5h.");
        registry.tab("nego-W001").holdEcho(42L, "-> You ask EUR 1,900 for 5h.");
        int before = registry.tab("nego-W001").transcript().size();
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        registry.onPlayerCommandRefused(refused("nego-W001", 41L, 0.0));
        assertEquals(Optional.of("nego-W001"), registry.onPlayerCommandDispatched(new PlayerCommandDispatchedEvent(
                "nego-W001", PlayerCommandEvent.PlayerCommandKind.PROPOSE, 42L)));

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        assertEquals(before + 1, transcript.size(), "only the move that went out is echoed: " + transcript);
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.SYSTEM, "-> You ask EUR 1,900 for 5h."),
                transcript.get(transcript.size() - 1));
        assertEquals(Optional.empty(), registry.onPlayerCommandDispatched(new PlayerCommandDispatchedEvent(
                "nego-W001", PlayerCommandEvent.PlayerCommandKind.PROPOSE, 41L)), "the refused one's echo is gone");
        assertEquals(Optional.empty(), registry.onPlayerCommandDispatched(new PlayerCommandDispatchedEvent(
                "nego-W001", PlayerCommandEvent.PlayerCommandKind.PROPOSE, 42L)), "and an echo is shown once");
        assertEquals(Optional.empty(), registry.onPlayerCommandDispatched(new PlayerCommandDispatchedEvent(
                "nego-W002", PlayerCommandEvent.PlayerCommandKind.ACCEPT, 7L)), "an autopilot move holds no echo");
        assertTrue(changes[0] >= 1);
    }

    @Test
    void aRefusedProposeGoesBackToTheLastStandingOffer() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 2000.0, 6, 1L); // passes the gate — no event at all
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 2L); // refused below
        assertEquals(1900.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001,
                "setup: the strip shows a send at once, before the gate's verdict");
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        Optional<String> changed = registry.onPlayerCommandRefused(refused("nego-W001", 2L, 2000.0));

        DialogueTabModel tab = registry.tab("nego-W001");
        assertEquals(Optional.of("nego-W001"), changed);
        assertEquals(2000.0, tab.snapshot().lastPlayerOffer(), 0.001, "the strip shows what the vessel last heard");
        assertEquals(6, tab.lastPlayerHours(), "the refused hours come off the strip too");
        assertEquals(2000.0, registry.buildCtx("W001").standingOffer().playerOffer().price(), 0.001,
                "the DCG's standing offer must not carry the refused terms either");
        assertEquals(1, changes[0]);
    }

    @Test
    void aRefusedFirstOfferLeavesTheStripWithNoOfferAgain() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 7L);

        registry.onPlayerCommandRefused(refused("nego-W001", 7L, 0.0));

        DialogueTabModel tab = registry.tab("nego-W001");
        assertEquals(0.0, tab.snapshot().lastPlayerOffer(), 0.001, "no offer ever stood: back to none");
        assertEquals(null, tab.lastPlayerHours(), "and no hours named: the strip falls back to the hours under discussion");
    }

    @Test
    void aRefusalOfASendAlreadyReplacedKeepsTheNewerOfferAndHandsItTheRollback() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 2000.0, 6, 1L); // passes
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 2L); // refused, but only after...
        registry.recordPlayerOffer("nego-W001", 1950.0, 6, 3L); // ...this newer send hit the strip

        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-W001", 2L, 2000.0)),
                "the strip shows the newer send, which the refusal does not concern");
        assertEquals(1950.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);

        registry.onPlayerCommandRefused(refused("nego-W001", 3L, 2000.0));
        assertEquals(2000.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001,
                "back to the last offer that stood — never to the refused 1900");
        assertEquals(6, registry.tab("nego-W001").lastPlayerHours());
    }

    @Test
    void aRefusalSettlesEveryOlderSendSoALateDuplicateChangesNothing() {
        // The gate judges commands in send order, so once send 2 is refused, send 1 has been
        // judged too — no refusal for it can still be on its way.
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 2000.0, 6, 1L);
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 2L);
        registry.onPlayerCommandRefused(refused("nego-W001", 2L, 2000.0));

        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-W001", 1L, 2000.0)));
        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-W001", 2L, 2000.0)), "settled once");
        assertEquals(2000.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);
    }

    @Test
    void onlyTheChatTabsOwnSendsAreTakenBack() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 9L);
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        // The autopilot assigns no id (it never writes the strip), and an unknown id matches nothing.
        assertEquals(Optional.empty(),
                registry.onPlayerCommandRefused(refused("nego-W001", PlayerCommandEvent.NO_COMMAND_ID, 0.0)));
        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-W001", 12_345L, 0.0)));
        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-GHOST", 9L, 0.0)));
        assertEquals(1900.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);
        assertEquals(0, changes[0], "nothing changed, so nothing re-renders");

        // A send recorded without an id (the legacy three-argument form) cannot be taken back.
        registry.recordPlayerOffer("nego-W001", 1800.0, 6);
        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-W001", 9L, 0.0)),
                "send 9 was replaced on the strip by the id-less send");
        assertEquals(1800.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);
    }

    @Test
    void theRestoredPriceIsTheHarbourMastersEvenAfterAnAutopilotCounterLandedInBetween() {
        // Review finding (S13): the autopilot's counters carry no id and are never recorded here,
        // but the HarbourMaster stamps them into the next vessel counter. A chat send refused after
        // such a counter must restore the HM's price, not the strip's memory from before the send.
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 2000.0, 6, 1L); // passes
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 2L); // refused below
        // Meanwhile the autopilot's €2100 counter went out; the vessel's reply carries the HM's stamp.
        registry.onNegotiationOpened(new NegotiationOpenedEvent("nego-W001", WalkInDialogueSnapshot.of(
                "W001", spec("W001", "cargo_vessel"), "berth_1", 6, 1700.0, 2100.0, 2, 0.0, false)));

        registry.onPlayerCommandRefused(refused("nego-W001", 2L, 2100.0));

        assertEquals(2100.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001,
                "what the vessel last heard — the autopilot's counter, not the pre-send €2000");
        assertEquals(2100.0, registry.buildCtx("W001").standingOffer().playerOffer().price(), 0.001);
        assertEquals(6, registry.tab("nego-W001").lastPlayerHours(), "the hours the strip showed before the refused send");
    }

    @Test
    void aRefusalFromBeforeAResetMatchesNothingInTheReopenedTab() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 31L);
        registry.reset();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.recordPlayerOffer("nego-W001", 2100.0, 6, 32L);

        assertEquals(Optional.empty(), registry.onPlayerCommandRefused(refused("nego-W001", 31L, 0.0)));
        assertEquals(2100.0, registry.tab("nego-W001").snapshot().lastPlayerOffer(), 0.001);
    }

    // ==================== S15 (2026-10-12): the orientation line ====================

    @Test
    void theOrientationLineNamesTheTwoButtonsAndQuotesNoPrice() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        String orientation = registry.tab("nego-W001").transcript().get(0).text();

        assertEquals("You are the Harbour Master — this vessel pays YOU for berth time. "
                + "Type your price and hours, or press Accept or Reject.", orientation);
        // A literal example price is wrong on most tabs and, copied onto a vessel already offering
        // more, closes the deal far too cheaply — the worked example is the input field's
        // placeholder, computed from this vessel's own offer (InputExamples).
        assertFalse(orientation.matches(".*\\d.*"), "no number may be quoted here: " + orientation);
        assertFalse(orientation.contains("Hint"),
                "the model cannot know the Hint button is hidden while the autopilot is on");
    }

    // ==================== S15 (2026-10-12): the open dialogues, as the other columns see them ====================

    private static NegotiationOpenedEvent openedNamed(String dialogueId, String vesselId, String type,
                                                      String vesselName, int roundsUsed, double vesselOffer) {
        VesselSpec named = new VesselSpec(vesselId, type, 9.0, 150.0, 30000, "general_cargo", 0L, vesselName);
        return new NegotiationOpenedEvent(dialogueId, WalkInDialogueSnapshot.of(
                vesselId, named, "berth_2", 6, vesselOffer, 0.0, roundsUsed, 0.0, false));
    }

    @Test
    void openDialoguesIsEmptyBeforeAnyWalkIn() {
        assertEquals(List.of(), new NegotiationTabRegistry().openDialogues());
    }

    @Test
    void openDialoguesListsEachOpenTabWithItsVesselIdAndDisplayName() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(openedNamed("nego-W002", "W002", "tanker", "Carthago", 1, 6000.0));

        assertEquals(List.of(
                        new OpenDialogue("nego-W001", "W001", "W001"), // no name: the id stands in, as on the tab
                        new OpenDialogue("nego-W002", "W002", "Carthago")),
                registry.openDialogues());
    }

    @Test
    void aVesselCounteringDoesNotMoveItsPlaceInTheList() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationOpened(opened("nego-W003", "W003", "ferry", "berth_3", 1, 900.0));

        // Round 2 from the FIRST vessel: the roster's recency moves it to the front...
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 2, 1700.0));
        assertEquals("W001", registry.rosterSnapshots().get(0).vesselId(),
                "setup: the roster did bump the countering vessel (anaphora needs that)");

        // ...but its tab has not moved on the strip, so neither may its row in the list.
        assertEquals(List.of("nego-W001", "nego-W002", "nego-W003"),
                registry.openDialogues().stream().map(OpenDialogue::dialogueId).toList());
    }

    @Test
    void aClosedTabLeavesTheListAtOnceAlthoughItsTabStaysOnTheStrip() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));

        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);

        assertEquals(List.of(new OpenDialogue("nego-W002", "W002", "W002")), registry.openDialogues(),
                "nobody is waiting on a finished negotiation");
        assertTrue(registry.dialogueIds().contains("nego-W001"), "its tab is still on the strip, greyed");

        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W002", "W002", Deal.Outcome.TIMEOUT), 0L);
        assertEquals(List.of(), registry.openDialogues());
    }

    @Test
    void aClosedDialogueThatReopensGoesToTheEndOfTheListLikeItsNewTab() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        assertTrue(registry.closeTab("nego-W001"));

        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertEquals(List.of("nego-W002", "nego-W001"),
                registry.openDialogues().stream().map(OpenDialogue::dialogueId).toList(),
                "the view appends a fresh page for it, so it is last — and listed once");
    }

    @Test
    void aResetEmptiesTheListAndTheNextWorldStartsItsOwnOrder() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));

        registry.reset();
        assertEquals(List.of(), registry.openDialogues());

        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        assertEquals(List.of("nego-W002", "nego-W001"),
                registry.openDialogues().stream().map(OpenDialogue::dialogueId).toList(),
                "nothing of the old world's order survives a load");
    }

    @Test
    void theListIsASnapshotTheCallerCannotChange() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        List<OpenDialogue> before = registry.openDialogues();

        assertThrows(UnsupportedOperationException.class,
                () -> before.add(new OpenDialogue("nego-X", "X", "X")));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        assertEquals(1, before.size(), "a list handed out earlier does not grow behind the caller's back");
        assertEquals(2, registry.openDialogues().size());
    }

    // ==================== S15 (2026-10-12): the player's messages — index, tag, count ====================

    private static DialogueTabModel freshTab(NegotiationTabRegistry registry) {
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        return registry.tab("nego-W001");
    }

    @Test
    void appendingAPlayerMessageReturnsItsIndexAndCountsIt() {
        DialogueTabModel tab = freshTab(new NegotiationTabRegistry());
        assertEquals(0, tab.playerMessageCount(), "the orientation line and the opening are not the player's");

        int first = tab.appendPlayerMessage("1900 for 6h");
        tab.appendSystemMessage("→ You ask €1900 for 6h.");
        int second = tab.appendPlayerMessage("deal");

        assertEquals(2, first, "after the orientation line (0) and the vessel's opening (1)");
        assertEquals(4, second);
        assertEquals(2, tab.playerMessageCount());
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.PLAYER, "1900 for 6h"),
                tab.transcript().get(first), "a message is born untagged — equal to the two-argument entry");
        assertEquals("", tab.transcript().get(first).tag());
    }

    @Test
    void taggingAPlayerMessageReplacesItWhereItStandsAndKeepsItsWords() {
        DialogueTabModel tab = freshTab(new NegotiationTabRegistry());
        int index = tab.appendPlayerMessage("1900 for 6h");
        tab.appendSystemMessage("→ You ask €1900 for 6h."); // the echo arrives after the bubble
        int sizeBefore = tab.transcript().size();

        assertTrue(tab.tagPlayerMessage(index, "(DCG)"));

        List<DialogueTabModel.ChatEntry> transcript = tab.transcript();
        assertEquals(sizeBefore, transcript.size(), "a tag replaces, it never appends");
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.PLAYER, "1900 for 6h", "(DCG)"),
                transcript.get(index));
        assertNotEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.PLAYER, "1900 for 6h"),
                transcript.get(index), "the tag is part of the entry");
        assertEquals(DialogueTabModel.Speaker.SYSTEM, transcript.get(index + 1).speaker(),
                "the later line stays where it was");
        assertEquals(1, tab.playerMessageCount(), "tagging is not sending: the input example must not advance");
    }

    @Test
    void onlyAPlayerMessageCanBeTagged() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        DialogueTabModel tab = freshTab(registry);
        registry.onAssistantChat(new AssistantChatEvent("nego-W001", "Assistant: hold your price"));
        registry.onAssistantChat(AssistantChatEvent.hintTemplate("nego-W001", 7L, "template"));
        int player = tab.appendPlayerMessage("deal");
        List<DialogueTabModel.ChatEntry> before = tab.transcript();

        for (int index = 0; index < before.size(); index++) {
            if (index != player) {
                assertFalse(tab.tagPlayerMessage(index, "(DCG)"),
                        "a " + before.get(index).speaker() + " line said nothing the pipeline parsed");
            }
        }
        assertFalse(tab.tagPlayerMessage(-1, "(DCG)"));
        assertFalse(tab.tagPlayerMessage(before.size(), "(DCG)"), "one past the end");
        assertFalse(tab.tagPlayerMessage(Integer.MAX_VALUE, "(DCG)"));

        assertEquals(before, tab.transcript(), "a refused tag changes nothing");
        for (DialogueTabModel.ChatEntry entry : tab.transcript()) {
            assertEquals("", entry.tag(), "nobody has been tagged yet: " + entry);
        }
        // The hint's own in-place revision still lands on the hint, untouched by any of this.
        assertEquals(Optional.of("nego-W001"),
                registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 7L, "model")));
    }

    @Test
    void twoMessagesAreTaggedIndependentlyWhateverOrderTheirParsesFinishIn() {
        DialogueTabModel tab = freshTab(new NegotiationTabRegistry());
        int first = tab.appendPlayerMessage("so?");   // falls through to Rasa: the slow parse
        int second = tab.appendPlayerMessage("deal"); // the grammar answers at once

        assertTrue(tab.tagPlayerMessage(second, "(DCG)"));
        assertEquals("", tab.transcript().get(first).tag(), "the first is still being read");
        assertTrue(tab.tagPlayerMessage(first, "(Rasa 0.99)"));

        assertEquals("(Rasa 0.99)", tab.transcript().get(first).tag());
        assertEquals("so?", tab.transcript().get(first).text());
        assertEquals("(DCG)", tab.transcript().get(second).tag());
        assertEquals("deal", tab.transcript().get(second).text());
    }

    @Test
    void aTagStillLandsOnAMessageSentJustBeforeTheTabClosed() {
        // "This negotiation ended while I was reading that": the turn was routed, so it is tagged.
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        DialogueTabModel tab = freshTab(registry);
        int index = tab.appendPlayerMessage("deal");
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.TIMEOUT), 0L);

        assertTrue(tab.tagPlayerMessage(index, "(DCG)"));
        assertEquals("(DCG)", tab.transcript().get(index).tag());
    }

    @Test
    void anEmptyOrMissingTagLeavesTheMessageUntagged() {
        DialogueTabModel tab = freshTab(new NegotiationTabRegistry());
        int index = tab.appendPlayerMessage("deal");
        DialogueTabModel.ChatEntry untagged = tab.transcript().get(index);

        assertTrue(tab.tagPlayerMessage(index, ""), "a result with no stated source: nothing to show");
        assertEquals(untagged, tab.transcript().get(index));

        assertTrue(tab.tagPlayerMessage(index, "(DCG)"));
        assertTrue(tab.tagPlayerMessage(index, null));
        assertEquals(untagged, tab.transcript().get(index), "null is 'no tag', never a null field");
        assertEquals("", new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.PLAYER, "deal", null).tag());
    }

    @Test
    void playerHasOfferedFollowsTheStandingOfferIncludingARefusal() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        DialogueTabModel tab = freshTab(registry);
        assertFalse(tab.playerHasOffered(), "the vessel has opened; the player has said nothing");

        tab.appendPlayerMessage("what berths are free");
        assertFalse(tab.playerHasOffered(), "a message is not an offer");

        registry.recordPlayerOffer("nego-W001", 1900.0, 4, 7L);
        assertTrue(tab.playerHasOffered());
        assertTrue(registry.buildCtx("W001").standingOffer().playerOffer().isPresent(),
                "the same fact the grammar's context is built from");

        registry.onPlayerCommandRefused(refused("nego-W001", 7L, 0.0));
        assertFalse(tab.playerHasOffered(), "the gate refused it: no offer of the player's ever stood");
        assertFalse(registry.buildCtx("W001").standingOffer().playerOffer().isPresent());

        // An offer the tab never saw (the autopilot's), stamped by the HarbourMaster on the next counter.
        registry.onNegotiationOpened(new NegotiationOpenedEvent("nego-W001", WalkInDialogueSnapshot.of(
                "W001", spec("W001", "cargo_vessel"), "berth_1", 6, 1700.0, 2100.0, 2, 0.0, false)));
        assertTrue(tab.playerHasOffered());
    }

    /** P-04: neither this registry nor the tab model may ever read a walk-in's hidden
     * beliefs — mirrors {@code DialogueCtxTest}'s own source-text grep gate. S15 (2026-10-12):
     * the pure models that read a tab or its snapshot for the chat column are held to it too.
     * S17a (ADR-21): and {@code HintRow}, which reads the tab's snapshot to tell a stale card. */
    @Test
    void neitherRegistryNorTabModelReadsAHiddenBelief() throws IOException {
        List<String> forbidden = List.of(".minAcceptablePrice(", ".targetPrice(", ".maxWaitMinutes(",
                ".personality(", ".minDurationHours(");
        for (Path file : List.of(
                Path.of("src/main/java/it/unige/portcommand/gui/model/NegotiationTabRegistry.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/DialogueTabModel.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/OfferLine.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/InputExamples.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/ParseTag.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/OpenDialogue.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/HintRow.java"),
                // S18b (PT3, PT5): the outcome lines and the Finished list read the tabs too.
                Path.of("src/main/java/it/unige/portcommand/gui/model/OutcomeLines.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/FinishedDialogue.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/FinishedListModel.java"))) {
            String source = Files.readString(file);
            for (String accessor : forbidden) {
                assertFalse(source.contains(accessor),
                        file + " must not read a hidden belief: " + accessor);
            }
        }
    }
}
