package it.unige.portcommand.gui.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aHintRevisionReplacesItsTemplateInPlace() {
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
        assertEquals(sizeBefore, transcript.size(), "a revision replaces, it never appends");
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT, "model"),
                transcript.get(templateIndex));
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
        assertEquals(0, changes[0], "a refused revision must not trigger a re-render");

        registry.onAssistantChat(AssistantChatEvent.hintRevision("nego-W001", 2L, "fresh model text"));
        assertEquals("fresh model text", lastText(registry.tab("nego-W001")));
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

    // ==================== audit C-07: closed tabs leave the strip after a dwell ====================

    private static final long DWELL = 1_000L;

    @Test
    void aClosedTabExpiresExactlyWhenItsDwellRunsOut() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 5_000L);

        assertEquals(List.of(), registry.sweepClosed(5_000L + DWELL - 1, DWELL), "still inside its dwell");
        assertTrue(registry.dialogueIds().contains("nego-W001"));

        assertEquals(List.of("nego-W001"), registry.sweepClosed(5_000L + DWELL, DWELL));
        assertEquals(List.of(), registry.dialogueIds());
        assertEquals(Optional.empty(), registry.dialogueIdForVessel("W001"));
        assertEquals(List.of(), registry.sweepClosed(Long.MAX_VALUE / 2, DWELL), "a swept tab is gone for good");
    }

    @Test
    void openTabsNeverExpire() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertEquals(List.of(), registry.sweepClosed(Long.MAX_VALUE / 2, 0L));
        assertEquals(List.of("nego-W001"), registry.dialogueIds());
    }

    @Test
    void eachClosedTabRunsItsOwnDwell() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationOpened(opened("nego-W002", "W002", "tanker", "berth_2", 1, 6000.0));
        registry.onNegotiationOpened(opened("nego-W003", "W003", "ferry", "berth_3", 1, 900.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W002", "W002", Deal.Outcome.TIMEOUT), 500L);

        assertEquals(List.of("nego-W001"), registry.sweepClosed(DWELL, DWELL));
        assertEquals(List.of("nego-W002", "nego-W003"), registry.dialogueIds());
        assertEquals(List.of("nego-W002"), registry.sweepClosed(500L + DWELL, DWELL));
        assertEquals(List.of("nego-W003"), registry.dialogueIds(), "the open dialogue stays");
    }

    @Test
    void aRepeatedCloseDoesNotRestartTheDwell() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 900L);

        assertEquals(List.of("nego-W001"), registry.sweepClosed(DWELL, DWELL));
    }

    @Test
    void aSweptDialogueIdReopensAsAFreshTab() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        registry.sweepClosed(DWELL, DWELL);

        NegotiationTabRegistry.OpenResult reopened = registry.onNegotiationOpened(
                opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertTrue(reopened.isNewTab(), "the view must add a page again");
        assertFalse(registry.tab("nego-W001").isClosed());
        assertEquals(2, registry.tab("nego-W001").transcript().size(), "orientation + opening, nothing inherited");
        assertEquals(Optional.of("nego-W001"), registry.dialogueIdForVessel("W001"));
    }

    @Test
    void sweepingNeverDropsANewerDialoguesVesselMapping() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-A", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-A", "W001", Deal.Outcome.DEAL), 0L);
        registry.onNegotiationOpened(opened("nego-B", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertEquals(List.of("nego-A"), registry.sweepClosed(DWELL, DWELL));
        assertEquals(Optional.of("nego-B"), registry.dialogueIdForVessel("W001"));
    }

    @Test
    void aResetDiscardsPendingExpiriesSoASameIdTabInTheNextWorldStays() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        registry.reset();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));

        assertEquals(List.of(), registry.sweepClosed(Long.MAX_VALUE / 2, DWELL));
        assertEquals(List.of("nego-W001"), registry.dialogueIds());
    }

    @Test
    void onChangeFiresOnlyWhenASweepRemovesSomething() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opened("nego-W001", "W001", "cargo_vessel", "berth_1", 1, 1500.0));
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL), 0L);
        int[] changes = {0};
        registry.setOnChange(() -> changes[0]++);

        registry.sweepClosed(DWELL - 1, DWELL);
        assertEquals(0, changes[0]);
        registry.sweepClosed(DWELL, DWELL);
        assertEquals(1, changes[0]);
    }

    /** P-04: neither this registry nor the tab model may ever read a walk-in's hidden
     * beliefs — mirrors {@code DialogueCtxTest}'s own source-text grep gate. */
    @Test
    void neitherRegistryNorTabModelReadsAHiddenBelief() throws IOException {
        List<String> forbidden = List.of(".minAcceptablePrice(", ".targetPrice(", ".maxWaitMinutes(",
                ".personality(", ".minDurationHours(");
        for (Path file : List.of(
                Path.of("src/main/java/it/unige/portcommand/gui/model/NegotiationTabRegistry.java"),
                Path.of("src/main/java/it/unige/portcommand/gui/model/DialogueTabModel.java"))) {
            String source = Files.readString(file);
            for (String accessor : forbidden) {
                assertFalse(source.contains(accessor),
                        file + " must not read a hidden belief: " + accessor);
            }
        }
    }
}
