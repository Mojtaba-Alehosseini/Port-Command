package it.unige.portcommand.gui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.gui.model.DialogueTabModel;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.ClarificationButtons;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 19 STEP 2's core-wiring gate: the Send flow's Routed/NeedsClarification/Error
 * handling, addressee-vs-typing-tab routing, clarification-button "clean intent"
 * dispatch, Hint visibility, and the NegotiationClosedEvent tab-close bracket. All
 * headless — real {@link EventBus}, a stub {@link ChatInputProcessor} the test
 * controls, publish + {@link SwingUtilities#invokeAndWait} to flush the EDT (delivery
 * is always {@code invokeLater}), assertions on the panel's model/test accessors, per
 * {@code GuiPanelsSubscriptionLifecycleTest}'s established idiom. No window shown.
 */
class ChatPanelTest {

    /** A controllable stub: the test sets exactly what the next {@code process()} call resolves
     * (or fails) with. Mirrors real {@code NLPPipeline}'s "never throws" contract when a result is
     * set, but can also simulate an unexpected failure via {@link #throwing}. */
    private static final class StubProcessor implements ChatInputProcessor {
        private volatile PipelineResult result;
        private volatile Throwable failure;
        private final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();

        void returning(PipelineResult result) {
            this.result = result;
            this.failure = null;
        }

        void throwing(Throwable failure) {
            this.failure = failure;
            this.result = null;
        }

        int callCount() {
            return callCount.get();
        }

        @Override
        public CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx) {
            callCount.incrementAndGet();
            if (failure != null) {
                CompletableFuture<PipelineResult> f = new CompletableFuture<>();
                f.completeExceptionally(failure);
                return f;
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    private static VesselSpec spec(String vesselId, String type) {
        return new VesselSpec(vesselId, type, 9.0, 150.0, 30000, "general_cargo", 0L);
    }

    private static NegotiationOpenedEvent openEvent(String dialogueId, String vesselId, String type, String name) {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot(vesselId, type, 9.0, 150.0, 30000,
                "general_cargo", "berth_1", 6, 1500.0, 0.0, 1, 0.0, false, name);
        return new NegotiationOpenedEvent(dialogueId, snapshot);
    }

    private static ACLMessage acl(int performative, Map<String, Object> content) {
        ACLMessage msg = MessageFactory.create(performative);
        msg.setContent(TerminalJson.write(content));
        return msg;
    }

    private static void openTab(EventBus bus, ChatPanel panel, String dialogueId, String vesselId, String type,
                                String name) throws Exception {
        bus.publish(openEvent(dialogueId, vesselId, type, name));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.registry().dialogueIds().contains(dialogueId), "setup: tab must have opened");
    }

    private static <T extends Event> List<T> published(EventBus bus, Class<T> type) {
        return EventBusProbe.published(bus).stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ==================== Routed: addressee vs typing-tab routing ====================

    @Test
    @Timeout(5)
    void routedWithNullAddresseeTargetsTheTypingTab() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of("price", 2000, "hours", 5))));
        panel.viewForTest("nego-A").sendText("2000 for 5 hours");
        SwingUtilities.invokeAndWait(() -> { });

        List<PlayerCommandEvent> events = published(bus, PlayerCommandEvent.class);
        assertEquals(1, events.size());
        assertEquals(PlayerCommandKind.PROPOSE, events.get(0).kind());
        assertEquals("A", events.get(0).targetVesselId());
    }

    @Test
    @Timeout(5)
    void routedWithADifferentAddresseeTargetsThatVesselRegardlessOfTypingTab() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");

        // Typed into tab A, but the vocative resolved to vessel B.
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), "B"));
        panel.viewForTest("nego-A").sendText("Genoa Star: deal");
        SwingUtilities.invokeAndWait(() -> { });

        List<PlayerCommandEvent> events = published(bus, PlayerCommandEvent.class);
        assertEquals(1, events.size());
        assertEquals(PlayerCommandKind.ACCEPT, events.get(0).kind());
        assertEquals("B", events.get(0).targetVesselId(), "the addressee must win over the typing tab");
    }

    @Test
    @Timeout(5)
    void dcgFrameKeysAreTranslatedToTheVesselProtocolKeys() throws Exception {
        // THE €0 play-test bug: the DCG frame speaks money/duration/berth (money as an
        // {amount, currency} object) while the vessel protocol reads price/hours/berth_id.
        // Untranslated, every player offer arrived priceless — the vessel saw €0, always
        // countered the same below-minimum number, and withdrew "over_priced" even when the
        // player typed its exact asking price.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "move", "propose",
                "money", Map.of("amount", 5435.0, "currency", "EUR"),
                "duration", 10,
                "berth", "berth_1"))));
        panel.viewForTest("nego-A").sendText("how about 5435 for 10 hours at berth 1");
        SwingUtilities.invokeAndWait(() -> { });

        List<PlayerCommandEvent> events = published(bus, PlayerCommandEvent.class);
        assertEquals(1, events.size());
        Map<String, Object> content = events.get(0).content();
        assertEquals(5435.0, ((Number) content.get("price")).doubleValue(), 0.001,
                "money.amount must arrive as the vessel's 'price'");
        assertEquals(10, ((Number) content.get("hours")).intValue(), "duration must arrive as 'hours'");
        assertEquals("berth_1", content.get("berth_id"), "berth must arrive as 'berth_id'");
        assertFalse(content.containsKey("money"), "the frame vocabulary must not leak onto the wire");
        assertFalse(content.containsKey("move"), "move is NLP-internal routing metadata");

        assertEquals(5435.0, panel.registry().tab("nego-A").snapshot().lastPlayerOffer(), 0.001,
                "the dispatched PROPOSE must also record the player's standing offer for the strip/ctx");
    }

    @Test
    @Timeout(5)
    void eachDispatchingPerformativeMapsToItsPlayerCommandKind() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        Map<Integer, PlayerCommandKind> expected = Map.of(
                ACLMessage.PROPOSE, PlayerCommandKind.PROPOSE,
                ACLMessage.ACCEPT_PROPOSAL, PlayerCommandKind.ACCEPT,
                ACLMessage.REJECT_PROPOSAL, PlayerCommandKind.REJECT,
                ACLMessage.CANCEL, PlayerCommandKind.WITHDRAW);

        for (Map.Entry<Integer, PlayerCommandKind> e : expected.entrySet()) {
            processor.returning(new PipelineResult.Routed(acl(e.getKey(), Map.of())));
            view.sendText("turn");
            SwingUtilities.invokeAndWait(() -> { });
        }

        List<PlayerCommandKind> kinds = published(bus, PlayerCommandEvent.class).stream()
                .map(PlayerCommandEvent::kind).toList();
        assertEquals(List.copyOf(expected.values()), kinds);
    }

    @Test
    @Timeout(5)
    void aRoutedStatusQueryIsAnsweredLocallyNotDispatched() throws Exception {
        // No vessel-side behaviour ever answers a QUERY_REF (verified against
        // EvaluateCounterOfferBehaviour's template) — the play-test's "Check status" was pure
        // dead air. The tab holds every fact a status reply could carry, so it answers itself.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.QUERY_REF, Map.of())));
        panel.viewForTest("nego-A").sendText("what's the status?");
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "nothing to dispatch — answered locally");
        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        DialogueTabModel.ChatEntry last = transcript.get(transcript.size() - 1);
        assertEquals(DialogueTabModel.Speaker.SYSTEM, last.speaker());
        assertTrue(last.text().contains("their offer: €1500") && last.text().contains("round 1 of"),
                "the local status line must carry the strip facts: " + last.text());
    }

    @Test
    @Timeout(5)
    void everyRoutedDispatchEchoesItsInterpretation() throws Exception {
        // Task 19 play-test: Rasa read a bare "so?" as an acceptance and the deal closed with
        // no visible cause. Every routed action now says what was understood.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        view.sendText("so?");
        SwingUtilities.invokeAndWait(() -> { });
        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        assertTrue(transcript.get(transcript.size() - 1).text().contains("Accepting their offer"),
                "an acceptance must be echoed: " + transcript);

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE,
                Map.of("money", Map.of("amount", 6500.0, "currency", "EUR")))));
        view.sendText("how about 6500");
        SwingUtilities.invokeAndWait(() -> { });
        transcript = panel.registry().tab("nego-A").transcript();
        assertTrue(transcript.get(transcript.size() - 1).text().contains("Demanding €6500"),
                "a demand must be echoed with its price: " + transcript);
    }

    @Test
    @Timeout(5)
    void informAndRequestNeverDispatchAndRenderASystemBubbleInstead() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.INFORM, Map.of())));
        view.sendText("cancel");
        SwingUtilities.invokeAndWait(() -> { });

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.REQUEST, Map.of())));
        view.sendText("cancel the tug");
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "neither INFORM nor REQUEST is a dispatchable move");
        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        List<String> systemTexts = transcript.stream()
                .filter(e -> e.speaker() == DialogueTabModel.Speaker.SYSTEM)
                .map(DialogueTabModel.ChatEntry::text).toList();
        // systemTexts[0] is the tab's orientation line; the two send outcomes follow it.
        // The REQUEST line's WORDING changed on 2026-07-27 (audit D-06): it used to be
        // "Fleet-wide commands aren't wired to a dialogue tab yet." for every REQUEST, which was
        // false for request_help and set_policy. This test's REQUEST carries no marker, so it is
        // the genuine fleet-wide case and still gets the fleet-wide answer — the assertion it
        // makes (neither dispatches; both leave exactly one system bubble) is unchanged.
        assertEquals(2, systemTexts.size() - 1, "exactly one system bubble per send, as before");
        assertEquals("Noted.", systemTexts.get(1));
        assertTrue(systemTexts.get(2).contains("fleet-wide"),
                "a REQUEST with no help/policy marker is the fleet-wide case: " + systemTexts.get(2));
        assertFalse(systemTexts.get(2).contains("Assistant"),
                "an unmarked REQUEST must NOT be re-routed to the Assistant");
    }

    // ==================== NeedsClarification / quick-action bar ====================

    @Test
    @Timeout(5)
    void quickActionButtonsAreAlwaysVisibleOnAnOpenTab() throws Exception {
        // Task 19 play-test fix: the buttons are a persistent action bar, visible from the
        // moment the tab opens — not a fallback that only appears after a failed parse.
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        assertTrue(panel.viewForTest("nego-A").clarificationVisibleForTest(),
                "buttons visible immediately, before any send");
    }

    @Test
    @Timeout(5)
    void needsClarificationAppendsAVisibleSystemReply() throws Exception {
        // The play-test's "I wrote something and it doesn't reply": a low-confidence parse must
        // produce visible feedback, not silently rely on the (now always-present) buttons.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions()));
        view.sendText("mumble mumble");
        SwingUtilities.invokeAndWait(() -> { });

        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        DialogueTabModel.ChatEntry last = transcript.get(transcript.size() - 1);
        assertEquals(DialogueTabModel.Speaker.SYSTEM, last.speaker());
        assertTrue(last.text().contains("didn't get that"), "clarification must visibly reply: " + last.text());
        assertTrue(view.clarificationVisibleForTest());
    }

    @Test
    @Timeout(5)
    void acceptAndRejectButtonsDispatchDirectlyAndStatusAnswersLocally() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        view.clickClarificationForTest("accept_deal");
        view.clickClarificationForTest("reject_deal");
        view.clickClarificationForTest("query_status"); // answered locally, no dispatch

        List<PlayerCommandEvent> events = published(bus, PlayerCommandEvent.class);
        assertEquals(List.of(PlayerCommandKind.ACCEPT, PlayerCommandKind.REJECT),
                events.stream().map(PlayerCommandEvent::kind).toList());
        assertTrue(events.stream().allMatch(e -> "A".equals(e.targetVesselId())));
        assertEquals(0, processor.callCount(), "clean-intent buttons must never re-invoke the NLP pipeline");
        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        assertTrue(transcript.stream().anyMatch(e -> e.speaker() == DialogueTabModel.Speaker.SYSTEM
                        && e.text().startsWith("Status —")),
                "the status button must answer locally: " + transcript);
    }

    @Test
    @Timeout(5)
    void requestHelpButtonFiresTheSameHintButtonEventAsTheHintButton() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        panel.viewForTest("nego-A").clickClarificationForTest("request_help");

        List<HintButtonEvent> hints = published(bus, HintButtonEvent.class);
        assertEquals(1, hints.size());
        assertEquals("nego-A", hints.get(0).dialogueId());
    }

    // ==================== Audit fixes (2026-07-27) ====================

    /**
     * Audit D-06. A typed {@code help} is classified {@code request_help} (holdout F1 1.000),
     * routed to an {@code ACLMessage.REQUEST}, and {@code kindFor} has no {@code PlayerCommandKind}
     * for REQUEST — so the tab answered with "Fleet-wide commands aren't wired to a dialogue tab
     * yet.", which is not merely unhelpful but <b>false</b>: a request for help is not a fleet
     * command. The clarification BUTTON already reached the Assistant; only the typed form died.
     */
    @Test
    @Timeout(5)
    void aTypedHelpRequestReachesTheAssistantJustLikeTheButton() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.REQUEST, Map.of(it.unige.portcommand.nlp.NLPPipeline.HELP_REQUEST_KEY, true))));
        panel.viewForTest("nego-A").sendText("help");
        SwingUtilities.invokeAndWait(() -> { });

        List<HintButtonEvent> hints = published(bus, HintButtonEvent.class);
        assertEquals(1, hints.size(), "a typed help must fire the same HintButtonEvent as the button");
        assertEquals("nego-A", hints.get(0).dialogueId());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(),
                "help is not a per-dialogue move — nothing binding may be dispatched");
    }

    /**
     * Audit D-06, the other half: a genuine fleet-wide instruction (the DCG's {@code command} and
     * {@code constrain} frames — PROJECT_DEFINITION §6.2's negation and command phenomenon blocks)
     * still says so, and still dispatches nothing. What must NOT happen is the help route firing
     * for it.
     */
    @Test
    @Timeout(5)
    void aFleetWideInstructionIsAnsweredTruthfullyAndDispatchesNothing() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.REQUEST, Map.of("action", "hold", "patient", "tanker"))));
        panel.viewForTest("nego-A").sendText("hold all tankers until the wind drops");
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(published(bus, HintButtonEvent.class).isEmpty(), "not a help request");
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "nothing fleet-wide is dispatched in v1");
        String transcript = panel.registry().tab("nego-A").transcript().toString();
        assertTrue(transcript.contains("fleet-wide"), "the player must be told what was understood: " + transcript);
    }

    /**
     * Audit C-28 — an invariant GUARD, <b>not</b> a reproduction. Stated plainly because the
     * difference matters: this test passes both with and without the fix.
     *
     * <p>The reported mechanism is that {@code JEditorPane.setText} replaces the document and
     * leaves the caret at offset 0, after which {@code DefaultCaret}'s update policy scrolls the
     * viewport to the caret — i.e. to the top — so every new message would jump a scrolling
     * transcript back to the orientation line and hide the vessel's newest counter. <b>Headlessly
     * that does not reproduce:</b> with no realized viewport, {@code DefaultCaret.insertUpdate}
     * carries the dot along with the full-document insert and the caret lands at the end anyway.
     * The auditor said as much — the finding is marked "needs one run to confirm".
     *
     * <p>So what shipped is a defensive one-liner ({@code setCaretPosition(getLength())} after
     * {@code setText}) that is a no-op when the caret is already at the end and correct when it is
     * not, plus this guard so a future refactor cannot silently leave the caret at the top. Whether
     * the transcript actually scrolled at 1280×800 is still open and belongs to the GUI walk —
     * see {@code docs/audit/AUDIT_RESOLUTION.md} (C-28).
     */
    @Test
    @Timeout(5)
    void theTranscriptStaysPinnedToTheNewestMessage() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        for (int i = 0; i < 8; i++) {
            processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of("price", 2000 + i, "hours", 5))));
            view.sendText("how about " + (2000 + i) + " for 5 hours");
            SwingUtilities.invokeAndWait(() -> { });
        }

        int length = view.transcriptLengthForTest();
        assertTrue(length > 0, "setup: the transcript must have content to scroll");
        assertEquals(length, view.caretPositionForTest(),
                "the caret must sit at the end so the viewport shows the newest message, not the top");
    }

    @Test
    @Timeout(5)
    void proposeOfferButtonPopulatesInputInsteadOfDispatching() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        view.clickClarificationForTest("propose_offer");

        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "propose_offer must not dispatch anything itself");
        assertFalse(view.inputFieldForTest().getText().isBlank(), "the input field must be pre-filled for editing");
    }

    @Test
    @Timeout(5)
    void aButtonClickEchoesAPlayerBubbleSoTheOutcomeHasAVisibleCause() throws Exception {
        // Task 19 play-test: an un-echoed "Accept the current offer" click made the following
        // "Deal closed." look causeless — every dispatching click now leaves a player bubble.
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        panel.viewForTest("nego-A").clickClarificationForTest("accept_deal");

        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        DialogueTabModel.ChatEntry last = transcript.get(transcript.size() - 1);
        assertEquals(DialogueTabModel.Speaker.PLAYER, last.speaker());
        assertEquals("Accept the current offer", last.text());
        assertEquals(1, published(bus, PlayerCommandEvent.class).size(), "and the command still dispatches");
    }

    @Test
    @Timeout(5)
    void aProposeWithHoursDispatchesBothDimensionsAndDropsTheFixedDurationNote() throws Exception {
        // Task 19b: duration is a REAL §7.3 dimension now. The task-19 "only the fee is
        // negotiable" honest-note must be gone — the bargaining feedback is the vessel's own
        // floor-pushback reply (NegotiationTabRegistryTest pins that line) — and the echo must
        // show both dimensions so the routed interpretation stays legible.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A"); // snapshot durationHours = 6

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "money", Map.of("amount", 2000.0, "currency", "EUR"), "duration", 10))));
        panel.viewForTest("nego-A").sendText("how about 2000 for 10 hours");
        SwingUtilities.invokeAndWait(() -> { });

        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        assertFalse(transcript.stream().anyMatch(e -> e.text().contains("only the fee is negotiable")),
                "the task-19 duration-is-fixed note must be GONE: " + transcript);
        assertTrue(transcript.stream().anyMatch(e -> e.speaker() == DialogueTabModel.Speaker.SYSTEM
                        && e.text().contains("€2000") && e.text().contains("10h")),
                "the interpretation echo must carry both dimensions: " + transcript);
        List<PlayerCommandEvent> events = published(bus, PlayerCommandEvent.class);
        assertEquals(1, events.size(), "the two-dimension offer must go through");
        assertEquals(2000.0, ((Number) events.get(0).content().get("price")).doubleValue(), 0.001);
        assertEquals(10, ((Number) events.get(0).content().get("hours")).intValue(),
                "the proposed duration must survive onto the wire");
        assertEquals(10, panel.registry().tab("nego-A").lastPlayerHours(),
                "the tab records the player's proposed stay for the strip and the pushback note");
    }

    @Test
    @Timeout(5)
    void theActionBarStaysVisibleAfterAClickAndHidesOnlyWhenTheTabCloses() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        view.clickClarificationForTest("query_status");
        assertTrue(view.clarificationVisibleForTest(), "a click must not hide the persistent action bar");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(view.clarificationVisibleForTest(), "a closed tab offers no actions");
    }

    // ==================== Error ====================

    @Test
    @Timeout(5)
    void errorResultRendersASystemBubble() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Error("rasa connection refused"));
        panel.viewForTest("nego-A").sendText("whatever");
        SwingUtilities.invokeAndWait(() -> { });

        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        DialogueTabModel.ChatEntry last = transcript.get(transcript.size() - 1);
        assertEquals(DialogueTabModel.Speaker.SYSTEM, last.speaker());
        assertEquals("I couldn't parse that.", last.text());
    }

    @Test
    @Timeout(5)
    void aProcessorThatFailsExceptionallyStillDegradesToAnErrorBubbleNotAStuckUi() throws Exception {
        // Real NLPPipeline never completes its future exceptionally, but DialogueTabView defends
        // against a broken ChatInputProcessor implementation anyway, matching the "never blocks,
        // never throws" contract the real pipeline documents -- the EDT must never hang.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.throwing(new RuntimeException("connector down"));
        panel.viewForTest("nego-A").sendText("whatever");
        SwingUtilities.invokeAndWait(() -> { });

        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        assertEquals(DialogueTabModel.Speaker.SYSTEM, transcript.get(transcript.size() - 1).speaker());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());
    }

    // ==================== Hint visibility ====================

    @Test
    @Timeout(5)
    void hintButtonIsVisibleOnAnOpenTabUnderDefaultAutopilotOffSettings() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        // Settings.load()'s classpath default has autopilotEnabled=false — the ON case has no
        // injection seam today (AssistantAgent's live AtomicBoolean has no GUI-reachable getter;
        // task 21 owns wiring a real toggle), so only the OFF/default case is exercisable here.
        assertTrue(panel.viewForTest("nego-A").hintButtonVisibleForTest());
    }

    @Test
    @Timeout(5)
    void hintButtonHidesOnceTheTabCloses() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertTrue(view.hintButtonVisibleForTest(), "setup");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL));
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(view.hintButtonVisibleForTest());
    }

    /**
     * Task 21: the Settings screen's autopilot toggle hides/re-shows the Hint button LIVE — the
     * ON case the task-19 test noted had "no injection seam" is now the {@link SettingsChangedEvent}
     * path. Also the leak guard: after {@code removeNotify()} the settings subscription is cancelled,
     * so a later event must not touch the detached panel's tabs.
     */
    @Test
    @Timeout(5)
    void autopilotSettingTogglesHintButtonLiveAndUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertTrue(view.hintButtonVisibleForTest(), "hint visible under the default autopilot=off");

        bus.publish(new SettingsChangedEvent("NORMAL", 300L, true, "microsoft/Phi-4-mini-instruct"));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(view.hintButtonVisibleForTest(), "autopilot on hides the Hint button live");

        bus.publish(new SettingsChangedEvent("NORMAL", 300L, false, "microsoft/Phi-4-mini-instruct"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(view.hintButtonVisibleForTest(), "autopilot off re-shows it");

        panel.removeNotify();
        bus.publish(new SettingsChangedEvent("NORMAL", 300L, true, "microsoft/Phi-4-mini-instruct"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(view.hintButtonVisibleForTest(),
                "removeNotify() must cancel the settings subscription — no update on a detached panel");
    }

    // ==================== Tab close bracket ====================

    @Test
    @Timeout(5)
    void negotiationClosedGreysTheRightTabAndMakesInputReadOnly() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.WITHDRAW_PRICE));
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(panel.registry().tab("nego-A").isClosed());
        // #9 (2026-07-18): the closed tab's input is now NON-EDITABLE rather than DISABLED — it
        // stays focusable so it swallows keystrokes in place instead of dropping focus and letting
        // them fall through to the comm-log filter combo. It remains enabled either way.
        assertFalse(panel.viewForTest("nego-A").inputFieldForTest().isEditable());
        assertTrue(panel.tabTitleForTest("nego-A").endsWith("(closed)"));
        // The OTHER tab must be entirely untouched.
        assertFalse(panel.registry().tab("nego-B").isClosed());
        assertTrue(panel.viewForTest("nego-B").inputFieldForTest().isEditable());
        assertFalse(panel.tabTitleForTest("nego-B").endsWith("(closed)"));
    }

    @Test
    @Timeout(5)
    void dealClosedAndWithdrawalEventsAloneDoNotCloseATab() throws Exception {
        // By design: NegotiationClosedEvent is the chat tab's own bracket signal;
        // DealClosedEvent/WithdrawalEvent are the paired, more specific signal for OTHER
        // consumers (task 20's HUD) and always arrive ALONGSIDE (not instead of) a
        // NegotiationClosedEvent in production (see ForwardWalkInToPlayerBehaviour).
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        bus.publish(new DealClosedEvent(new Deal("deal-A", "A", "berth_1", 1600.0, 6, 0L, Deal.Outcome.DEAL)));
        bus.publish(new WithdrawalEvent("A", Deal.Outcome.TIMEOUT, false, 0L));
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(panel.registry().tab("nego-A").isClosed());
    }

    // ==================== Assistant routing (regression: still works through ChatPanel) ====================

    @Test
    @Timeout(5)
    void assistantChatEventRendersInTheRightTabOnly() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");

        bus.publish(new AssistantChatEvent("nego-A", "🤖 Assistant: take it"));
        SwingUtilities.invokeAndWait(() -> { });

        List<DialogueTabModel.ChatEntry> aTranscript = panel.registry().tab("nego-A").transcript();
        assertEquals(DialogueTabModel.Speaker.ASSISTANT, aTranscript.get(aTranscript.size() - 1).speaker());
        assertTrue(panel.registry().tab("nego-B").transcript().stream()
                .noneMatch(e -> e.speaker() == DialogueTabModel.Speaker.ASSISTANT), "tab B must be untouched");
    }

    @Test
    @Timeout(5)
    void aNewDialogueIdOpensASecondTabWithoutDisturbingTheFirst() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");

        assertEquals(2, panel.tabCountForTest());
        assertEquals("Genoa Star (tanker)", panel.tabTitleForTest("nego-B"));
        assertNull(panel.tabTitleForTest("nego-ghost"));
    }
}
