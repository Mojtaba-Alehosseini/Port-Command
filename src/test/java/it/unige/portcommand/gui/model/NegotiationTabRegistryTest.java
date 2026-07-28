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
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL));

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
                new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL));

        assertEquals(Optional.of("nego-W001"), closed);
        assertTrue(registry.tab("nego-W001").isClosed());
        assertEquals(Deal.Outcome.DEAL, registry.tab("nego-W001").outcome());
        assertFalse(registry.tab("nego-W002").isClosed(), "the OTHER tab must be untouched");
    }

    @Test
    void closingAnUnknownDialogueIdReturnsEmpty() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();

        Optional<String> closed = registry.onNegotiationClosed(
                new NegotiationClosedEvent("nego-GHOST", "GHOST", Deal.Outcome.TIMEOUT));

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
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W002", "W002", Deal.Outcome.TIMEOUT));

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
        registry.onNegotiationClosed(new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.DEAL));

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
                new NegotiationClosedEvent("nego-W001", "W001", Deal.Outcome.WITHDRAW_DURATION));

        List<DialogueTabModel.ChatEntry> transcript = registry.tab("nego-W001").transcript();
        String closeLine = transcript.get(transcript.size() - 1).text();
        assertTrue(closeLine.contains("hours"),
                "a duration withdrawal must not masquerade as a price one: " + closeLine);
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
