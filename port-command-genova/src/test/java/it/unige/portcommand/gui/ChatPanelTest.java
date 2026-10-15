package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.AutopilotOwnershipEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.gui.model.DialogueTabModel;
import it.unige.portcommand.gui.model.InputExamples;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.ClarificationButtons;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.ParseProvenance;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 19 STEP 2's core-wiring gate: the Send flow's Routed/NeedsClarification/Error
 * handling, addressee-vs-typing-tab routing, the four buttons (S15: Send / Hint / Accept /
 * Reject — Accept and Reject go through the same pipeline as typed text), Hint visibility, and
 * the NegotiationClosedEvent tab-close bracket. S15 (2026-10-12) also pins the two-row offer
 * line, the input's worked example and the small parse note on a routed player message. All
 * headless — real {@link EventBus}, a stub {@link ChatInputProcessor} the test
 * controls, publish + {@link SwingUtilities#invokeAndWait} to flush the EDT (delivery
 * is always {@code invokeLater}), assertions on the panel's model/test accessors, per
 * {@code GuiPanelsSubscriptionLifecycleTest}'s established idiom. No window shown.
 */
class ChatPanelTest {

    /** A controllable stub: the test sets exactly what the next {@code process()} call resolves
     * (or fails) with. Mirrors real {@code NLPPipeline}'s "never throws" contract when a result is
     * set, but can also simulate an unexpected failure via {@link #throwing}. S15: it also keeps
     * the texts it was asked to read, in order — a button click is only visible there. */
    private static final class StubProcessor implements ChatInputProcessor {
        private volatile PipelineResult result;
        private volatile Throwable failure;
        private final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        private final List<String> texts = new CopyOnWriteArrayList<>();

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

        List<String> texts() {
            return List.copyOf(texts);
        }

        @Override
        public CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx) {
            callCount.incrementAndGet();
            texts.add(text);
            if (failure != null) {
                CompletableFuture<PipelineResult> f = new CompletableFuture<>();
                f.completeExceptionally(failure);
                return f;
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    /** S15: a processor whose parses finish when the TEST says so — one pending future per send,
     * in send order — for the cases where the order or the moment a result arrives is the point. */
    private static final class DeferredProcessor implements ChatInputProcessor {
        private final List<CompletableFuture<PipelineResult>> pending = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx) {
            CompletableFuture<PipelineResult> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        int pendingCount() {
            return pending.size();
        }

        /** Finishes the parse of the {@code send}-th message (0-based) with {@code result}. */
        void complete(int send, PipelineResult result) {
            pending.get(send).complete(result);
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

    /** S15: a later round of the same dialogue — the vessel now asks {@code vesselOffer}. */
    private static NegotiationOpenedEvent counterEvent(String dialogueId, String vesselId, String type, String name,
                                                       double vesselOffer, int round) {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot(vesselId, type, 9.0, 150.0, 30000,
                "general_cargo", "berth_1", 6, vesselOffer, 0.0, round, 0.0, false, name);
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

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** S15: a player's click on one of the tab's buttons — on the EDT, where a click happens —
     * and then one more EDT pass for the parse result the click may have queued. */
    /** S18b (UI tree U8, adversarial review): the HarbourMaster's gate refused a move of the tab -
     * an answer, after which the tab's Accept and Reject are back. */
    private static void refused(EventBus bus, String dialogueId, PlayerCommandKind kind) throws Exception {
        bus.publish(new it.unige.portcommand.gui.events.PlayerCommandRefusedEvent(dialogueId, kind,
                PlayerCommandEvent.NO_COMMAND_ID, 0.0, "refused"));
        flush();
    }

    /** S19 (playtest 5, PT30): the HarbourMaster sent {@code command} - what its dispatch says. */
    private static void dispatched(EventBus bus, PlayerCommandEvent command) throws Exception {
        bus.publish(new PlayerCommandDispatchedEvent("nego-" + command.targetVesselId(), command.kind(),
                command.commandId()));
        flush();
    }

    private static void click(DialogueTabView view, String label) throws Exception {
        SwingUtilities.invokeAndWait(() -> view.clickForTest(label));
        flush();
    }

    private static List<DialogueTabModel.ChatEntry> transcriptOf(ChatPanel panel, String dialogueId) {
        return panel.registry().tab(dialogueId).transcript();
    }

    private static DialogueTabModel.ChatEntry lastEntry(ChatPanel panel, String dialogueId) {
        List<DialogueTabModel.ChatEntry> transcript = transcriptOf(panel, dialogueId);
        return transcript.get(transcript.size() - 1);
    }

    /** The player's own messages in that tab, oldest first. */
    private static List<DialogueTabModel.ChatEntry> playerEntries(ChatPanel panel, String dialogueId) {
        return transcriptOf(panel, dialogueId).stream()
                .filter(e -> e.speaker() == DialogueTabModel.Speaker.PLAYER).toList();
    }

    /** Lays a peerless tree out: {@code validate()} does nothing without a peer, so every
     * container is told to lay its children out, top down. */
    private static void layOutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layOutTree(nested);
            }
        }
    }

    /** {@code component}'s bounds in {@code ancestor}'s coordinates (no peer needed). */
    private static Rectangle boundsIn(Component component, Container ancestor) {
        Rectangle bounds = component.getBounds();
        for (Container parent = component.getParent(); parent != null && parent != ancestor;
                parent = parent.getParent()) {
            bounds.translate(parent.getX(), parent.getY());
        }
        return bounds;
    }

    private static JLabel labelStartingWith(Container container, String prefix) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null && label.getText().startsWith(prefix)) {
                return label;
            }
            if (child instanceof Container nested) {
                JLabel found = labelStartingWith(nested, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        assertTrue(last.text().contains("their offer: €1,500") && last.text().contains("round 1 of"),
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
        dispatched(bus, published(bus, PlayerCommandEvent.class).get(0)); // S19 (PT30): it went out
        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        assertTrue(transcript.get(transcript.size() - 1).text().contains("You accept €1,500 for 6h"),
                "an acceptance must be echoed, with what it accepts (S18b, U6): " + transcript);

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE,
                Map.of("money", Map.of("amount", 6500.0, "currency", "EUR")))));
        view.sendText("how about 6500");
        SwingUtilities.invokeAndWait(() -> { });
        dispatched(bus, published(bus, PlayerCommandEvent.class).get(1));
        transcript = panel.registry().tab("nego-A").transcript();
        assertTrue(transcript.get(transcript.size() - 1).text().contains("You ask €6,500"),
                "a demand must be echoed with its price: " + transcript);
    }

    /** S19 (playtest 5, PT30): "-> Demanding €2,000 for 5h" was shown although nothing was sent. */
    @Test
    @Timeout(5)
    void aMoveTheGateRefusedIsNeverEchoed() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE,
                Map.of("money", Map.of("amount", 2000.0, "currency", "EUR"), "duration", 5))));

        panel.viewForTest("nego-A").sendText("2000 for 5h");
        flush();
        PlayerCommandEvent sent = published(bus, PlayerCommandEvent.class).get(0);
        assertTrue(transcriptOf(panel, "nego-A").stream().noneMatch(e -> e.text().startsWith("→")),
                "nothing is echoed before the HarbourMaster has judged the move: " + transcriptOf(panel, "nego-A"));

        bus.publish(new PlayerCommandRefusedEvent("nego-A", PlayerCommandKind.PROPOSE, sent.commandId(), 0.0,
                "berth_4 is incompatible with a cargo_vessel [R7]"));
        flush();
        dispatched(bus, sent); // cannot follow a refusal; were it to, the echo went with the refusal
        assertTrue(transcriptOf(panel, "nego-A").stream().noneMatch(e -> e.text().startsWith("→")),
                "a refused move is never echoed: " + transcriptOf(panel, "nego-A"));
    }

    @Test
    @Timeout(5)
    void aMoveIsEchoedOnceTheHarbourMasterHasSentItAndOnlyOnce() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE,
                Map.of("money", Map.of("amount", 2200.0, "currency", "EUR"), "duration", 6))));
        panel.viewForTest("nego-A").sendText("2200 for 6h");
        flush();
        PlayerCommandEvent sent = published(bus, PlayerCommandEvent.class).get(0);
        int before = transcriptOf(panel, "nego-A").size();

        dispatched(bus, sent);
        dispatched(bus, sent);

        List<DialogueTabModel.ChatEntry> transcript = transcriptOf(panel, "nego-A");
        assertEquals(before + 1, transcript.size(), "one echo: " + transcript);
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.SYSTEM, "→ You ask €2,200 for 6h."),
                transcript.get(before), "S19 (playtest 5, PT32): the player's own move says who made it");
    }

    /** S19 review (PT30): a move typed in one tab for another ship, and refused - the refusal line goes
     * to that ship's tab, and the typing tab, which held the echo, says where the reason is. */
    @Test
    @Timeout(5)
    void aRefusedMoveForAnotherShipSaysWhereTheReasonIs() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE,
                Map.of("money", Map.of("amount", 2000.0, "currency", "EUR"), "duration", 5)), "B"));
        panel.viewForTest("nego-A").sendText("Genoa Star: 2000 for 5h");
        flush();
        PlayerCommandEvent sent = published(bus, PlayerCommandEvent.class).get(0);
        assertEquals("B", sent.targetVesselId(), "fixture: the move is for Genoa Star");

        bus.publish(new PlayerCommandRefusedEvent("nego-B", PlayerCommandKind.PROPOSE, sent.commandId(), 0.0,
                "berth_4 is incompatible with a tanker [R7]"));
        flush();

        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.SYSTEM,
                "Not sent to Genoa Star — the reason is in its tab."), lastEntry(panel, "nego-A"));
        assertTrue(transcriptOf(panel, "nego-A").stream().noneMatch(e -> e.text().startsWith("→")));
        assertTrue(panel.viewForTest("nego-A").transcriptTextForTest().contains("Not sent to Genoa Star"),
                "and the typing tab shows it");
    }

    /** S19 (PT30): the gate judges an acceptance too, so it carries an id the refusal can name. */
    @Test
    @Timeout(5)
    void anAcceptanceTheGateRefusedIsNeverEchoedEither() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));

        click(panel.viewForTest("nego-A"), "Accept");
        PlayerCommandEvent sent = published(bus, PlayerCommandEvent.class).get(0);
        assertTrue(sent.commandId() != PlayerCommandEvent.NO_COMMAND_ID, "the acceptance carries an id");
        bus.publish(new PlayerCommandRefusedEvent("nego-A", PlayerCommandKind.ACCEPT, sent.commandId(), 0.0,
                "berth_4 is incompatible with a cargo_vessel [R7]"));
        flush();

        assertTrue(transcriptOf(panel, "nego-A").stream().noneMatch(e -> e.text().startsWith("→")),
                transcriptOf(panel, "nego-A").toString());
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
        // S18a (decision N7): an unmarked REQUEST is what Rasa's set_constraint looks like, and
        // "fleet-wide" was a guess about it. The line now says only what is true of every such
        // turn - it was read, and nothing of the kind is executed.
        assertEquals("I read that as an instruction for the port — but instructions are not executed in "
                        + "this version, so nothing was sent. To move this negotiation write '1950 for 6h'.",
                systemTexts.get(2));
        assertFalse(systemTexts.get(2).contains("Assistant"),
                "an unmarked REQUEST must NOT be re-routed to the Assistant");
    }

    // ==================== NeedsClarification / the four buttons (S15) ====================

    @Test
    @Timeout(5)
    void anOpenTabOffersExactlySendHintAcceptReject() throws Exception {
        // S15 (2026-10-12): the five-button quick-action bar is gone. What an open tab offers is
        // the input with Send, and Hint / Accept / Reject on the row below — there from the moment
        // the tab opens (the task-19 play-test rule: never a fallback that appears after a failed
        // parse), all four enabled.
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest(),
                "exactly these four, in reading order, before any send");
        for (String label : view.buttonLabelsForTest()) {
            assertTrue(view.buttonForTest(label).isEnabled(), label + " must be usable on an open tab");
        }
    }

    @Test
    @Timeout(5)
    void needsClarificationAppendsAVisibleSystemReply() throws Exception {
        // The play-test's "I wrote something and it doesn't reply": a low-confidence parse must
        // produce visible feedback, not silently rely on the (always-present) buttons.
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
        // S15: the line's example is THIS vessel's own — 30% over the €1500 it is asking, for the
        // 6h under discussion — never a fixed price that could undercut a richer vessel's offer;
        // and it points at the two buttons the tab has, not at the bar it no longer has.
        String example = InputExamples.priceAndHoursExample(panel.registry().tab("nego-A"));
        assertEquals("1950 for 6h", example, "setup: the worked example for a vessel asking €1500 for 6h");
        assertEquals("I didn't get that — try something like '1950 for 6h', or press Accept or Reject.",
                last.text());
        assertFalse(last.text().contains("buttons below"), "the bar that wording pointed at is gone");
    }

    @Test
    @Timeout(5)
    void aSpecificClarificationMessageStillWinsOverTheGenericLine() throws Exception {
        // 2026-07-28 (D-05's cross-tab guard): when the pipeline understood the sentence and
        // declined it on purpose, "I didn't get that" would be a lie. S15 dropped the options the
        // result carries from the view, not the message.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(),
                "That reads as an offer to Genoa Star, but you are in A's tab."));
        panel.viewForTest("nego-A").sendText("Genoa Star: 2000");
        flush();

        assertEquals("That reads as an offer to Genoa Star, but you are in A's tab.",
                lastEntry(panel, "nego-A").text());
    }

    @Test
    @Timeout(5)
    void acceptAndRejectGoThroughTheProcessorAsDealAndNo() throws Exception {
        // S15 (2026-10-12): the two buttons type for the player. The pre-S15 bar published its
        // ACCEPT / REJECT directly — no pipeline, no world-identity guard, no "→ …" echo. Now a
        // click is the word "deal" (or "no") sent down the same path as anything typed.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        click(view, "Accept");
        refused(bus, "nego-A", PlayerCommandKind.ACCEPT); // S18b (U8, review): each move is answered first
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.REJECT_PROPOSAL, Map.of())));
        click(view, "Reject");

        assertEquals(List.of("deal", "no"), processor.texts(),
                "each click reaches the pipeline as the word the player could have typed");
        assertEquals(List.of(InputExamples.ACCEPT_UTTERANCE, InputExamples.REJECT_UTTERANCE), processor.texts());
        assertEquals(2, processor.callCount());
        List<PlayerCommandEvent> events = published(bus, PlayerCommandEvent.class);
        assertEquals(List.of(PlayerCommandKind.ACCEPT, PlayerCommandKind.REJECT),
                events.stream().map(PlayerCommandEvent::kind).toList());
        assertTrue(events.stream().allMatch(e -> "A".equals(e.targetVesselId())));
        assertEquals(List.of("deal", "no"),
                playerEntries(panel, "nego-A").stream().map(DialogueTabModel.ChatEntry::text).toList(),
                "the bubbles show the literal words, because the parse note describes exactly that text");

        // A button is not a way round the pipeline: when the pipeline does not route the word,
        // nothing goes out — the player gets the same "I didn't get that" a typed turn gets.
        refused(bus, "nego-A", PlayerCommandKind.REJECT);
        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions()));
        click(view, "Accept");

        assertEquals(3, processor.callCount());
        assertEquals(2, published(bus, PlayerCommandEvent.class).size(), "an unrouted click publishes nothing");
        DialogueTabModel.ChatEntry last = lastEntry(panel, "nego-A");
        assertEquals(DialogueTabModel.Speaker.SYSTEM, last.speaker());
        assertTrue(last.text().contains("didn't get that"), last.text());
    }

    @Test
    @Timeout(5)
    void theHintButtonFiresAHintButtonEvent() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        click(panel.viewForTest("nego-A"), "Hint");

        List<HintButtonEvent> hints = published(bus, HintButtonEvent.class);
        assertEquals(1, hints.size());
        assertEquals("nego-A", hints.get(0).dialogueId());
        assertEquals(0, processor.callCount(), "a Hint is not a chat message — the pipeline is not asked");
        assertTrue(playerEntries(panel, "nego-A").isEmpty(), "and it leaves no player bubble");
    }

    /** S19 (playtest 5, PT27): typing "hint" sent San Giorgio away - Rasa read it as a refusal.
     * The pipeline reads the line as a request for the Hint, and the tab does what the Hint button
     * does; nothing goes to the vessel. */
    @Test
    @Timeout(5)
    void aTypedHintRunsTheHint() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.REQUEST, Map.of(it.unige.portcommand.nlp.NLPPipeline.HINT_REQUEST_KEY, true))));
        panel.viewForTest("nego-A").sendText("hint");
        SwingUtilities.invokeAndWait(() -> { });

        List<HintButtonEvent> hints = published(bus, HintButtonEvent.class);
        assertEquals(1, hints.size(), "the Hint ran");
        assertEquals("nego-A", hints.get(0).dialogueId());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "nothing went to the vessel");
        assertEquals(DialogueTabView.HINT_ECHO, lastEntry(panel, "nego-A").text());
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
    void aTypedHelpIsAnsweredWithWhatThisTabUnderstands() throws Exception {
        // S18a (decision N8) - born red. Until S18a a typed "help" pressed the Hint button for the
        // player: the Assistant then recommended a PRICE, which is an answer to another question.
        // "help" asks what can be typed here, and the tab knows: its own examples (computed above
        // this vessel's offer), the two words the buttons send, "status", and where policies live.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.REQUEST, Map.of(it.unige.portcommand.nlp.NLPPipeline.HELP_REQUEST_KEY, true))));
        panel.viewForTest("nego-A").sendText("help");
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals("You can type an offer ('1950 for 6h', '200 more', 'make it 1800'), 'deal' or 'no', "
                        + "or 'status'. Accept and Reject send 'deal' and 'no'; Hint asks the Assistant for a "
                        + "suggestion. Standing policies are set in Settings → Autopilot policy.",
                lastEntry(panel, "nego-A").text());
        assertTrue(published(bus, HintButtonEvent.class).isEmpty(),
                "asking what can be typed is not asking the Assistant for a price");
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
        // S18a (decision N7): and told, in the same line, that it does nothing and what does.
        assertEquals("Understood as a fleet-wide command — but commands are not executed in this version, "
                        + "so nothing was sent. To move this negotiation write '1950 for 6h'.",
                lastEntry(panel, "nego-A").text());
    }

    /**
     * S18a (decision N7) - born red. The grammar's negation block reads "nothing below 2000" as a
     * limit the player sets on THIS negotiation, and the tab called it "a fleet-wide instruction":
     * a correct parse answered with a false description. A limit is not fleet-wide, and it is not
     * enforced either - the line says both, and what to type to actually move the price.
     */
    @Test
    @Timeout(5)
    void aLimitIsNotCalledFleetWideAndIsHonestAboutDoingNothing() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("move", "constrain");
        limit.put("polarity", "negative");
        limit.put("bound", "below");
        limit.put("money", Map.of("amount", 2000, "currency", "EUR"));
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.REQUEST, limit), null, ParseProvenance.DCG));
        panel.viewForTest("nego-A").sendText("nothing below 2000");
        flush();

        assertEquals("Understood as a limit for this negotiation — but limits are not enforced in this "
                        + "version, so nothing was sent. To move this negotiation write '1950 for 6h'.",
                lastEntry(panel, "nego-A").text());
        assertEquals("(DCG)", playerEntries(panel, "nego-A").get(0).tag(), "the reading is still credited");
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());
    }

    /** S18a (decision N5, the Rasa half): a policy only Rasa recognised gets the same answer the
     * pipeline gives one the policy DSL parses - where policies are set. */
    @Test
    @Timeout(5)
    void aPolicyTypedInChatIsPointedAtSettings() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.REQUEST, Map.of("policy_text", "always take tankers above 6000"))));
        panel.viewForTest("nego-A").sendText("always take tankers above 6000");
        flush();

        assertEquals("Policies are set in Settings → Autopilot policy — nothing was sent to the vessel.",
                lastEntry(panel, "nego-A").text());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());
    }

    /**
     * S18a (decision N6) - born red. Every question used to get the same status line: "what
     * berths are free" was answered with this negotiation's round and price. A question about the
     * port is answered with where the port's answer is on screen; one about this negotiation
     * with this negotiation.
     */
    @Test
    @Timeout(5)
    void aQuestionIsAnsweredByItsTopic() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("free_berths", "Free berths are in the Port table on the left — a berth's row reads "
                + "'free' when nothing holds it.");
        answers.put("tug_availability", "Tugs are in the Port table on the left — the Tugs line counts "
                + "the free and the busy ones.");
        answers.put("weather", "The weather is in the top bar — click it for the forecast.");
        answers.put("berth_assignment", "This negotiation is for berth 1 for 6h — the Port table on the "
                + "left shows who holds each berth.");
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            processor.returning(new PipelineResult.Routed(
                    acl(ACLMessage.QUERY_REF, Map.of("move", "ask", "topic", answer.getKey())),
                    null, ParseProvenance.DCG));
            view.sendText("a question about " + answer.getKey());
            flush();
            assertEquals(answer.getValue(), lastEntry(panel, "nego-A").text(), answer.getKey());
        }

        // "what's your best price" and a plain "status" are about this negotiation: the status line.
        for (Map<String, Object> content : List.<Map<String, Object>>of(
                Map.of("move", "ask", "topic", "best_price"), Map.of())) {
            processor.returning(new PipelineResult.Routed(acl(ACLMessage.QUERY_REF, content)));
            view.sendText("status");
            flush();
            assertTrue(lastEntry(panel, "nego-A").text().startsWith("Status — their offer: €1,500"),
                    content + " -> " + lastEntry(panel, "nego-A").text());
        }
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "a question sends nothing");
    }

    /** S18a (ADR-23): a pipeline message that wants a worked example gets THIS tab's - the
     * pipeline cannot know a number that is safely above this vessel's offer; the tab does. */
    @Test
    @Timeout(5)
    void aPipelineMessageGetsThisTabsOwnExample() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");

        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(),
                "I see an offer but couldn't read the terms — write it like '{example}'."));
        panel.viewForTest("nego-A").sendText("could you stretch to nineteen hundred");
        flush();

        assertEquals("I see an offer but couldn't read the terms — write it like '1950 for 6h'.",
                lastEntry(panel, "nego-A").text());
    }

    /**
     * Review finding. A refusal about ANOTHER tab's vessel ("Carthago: 1000", typed in A's tab)
     * used to get THIS tab's example - EUR 1950, safely above A's EUR 1500 and far below
     * Carthago's EUR 4500. Typed to Carthago it would have closed EUR 2,550 under its offer. The
     * example is Carthago's, written with the name in front so that copying it sends it there.
     */
    @Test
    @Timeout(5)
    void aRefusalAboutAnotherVesselGetsThatVesselsExample() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Carthago");
        bus.publish(counterEvent("nego-B", "B", "tanker", "Carthago", 4500.0, 2));
        flush();

        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(),
                "Did you mean €1000? That is less than half of Carthago's offer (€4500), so nothing was "
                        + "sent — a price looks like '{example}'.", "B"));
        panel.viewForTest("nego-A").sendText("Carthago: 1000");
        flush();

        assertEquals("Did you mean €1000? That is less than half of Carthago's offer (€4500), so nothing "
                        + "was sent — a price looks like 'Carthago: 5850 for 6h'.",
                lastEntry(panel, "nego-A").text());

        // A vessel with no tab to take an example from: the generic shape, never this tab's number.
        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(),
                "a price looks like '{example}'.", "nobody-here"));
        panel.viewForTest("nego-A").sendText("Nobody: 1000");
        flush();

        assertEquals("a price looks like 'PRICE for Nh'.", lastEntry(panel, "nego-A").text());
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
    void aButtonClickEchoesAPlayerBubbleSoTheOutcomeHasAVisibleCause() throws Exception {
        // Task 19 play-test: an un-echoed "Accept the current offer" click made the following
        // "Deal closed." look causeless — every dispatching click leaves a player bubble. S15:
        // the bubble is the word the button sent ("deal"), and because the click now goes through
        // the pipeline it is followed by the same "→ …" echo a typed acceptance gets.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        int before = transcriptOf(panel, "nego-A").size();

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        click(panel.viewForTest("nego-A"), "Accept");
        dispatched(bus, published(bus, PlayerCommandEvent.class).get(0)); // S19 (PT30): it went out

        List<DialogueTabModel.ChatEntry> transcript = transcriptOf(panel, "nego-A");
        assertEquals(before + 2, transcript.size(), "the player's bubble, then the echo: " + transcript);
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.PLAYER, "deal"),
                transcript.get(before));
        // S18b (UI tree U6): the echo says what was accepted. S19 (PT32): and who accepted it.
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.SYSTEM, "→ You accept €1,500 for 6h."),
                transcript.get(before + 1));
        assertEquals(1, published(bus, PlayerCommandEvent.class).size(), "and the command still dispatches");
        assertEquals(PlayerCommandKind.ACCEPT, published(bus, PlayerCommandEvent.class).get(0).kind());
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
        dispatched(bus, published(bus, PlayerCommandEvent.class).get(0)); // S19 (PT30): it went out

        List<DialogueTabModel.ChatEntry> transcript = panel.registry().tab("nego-A").transcript();
        assertFalse(transcript.stream().anyMatch(e -> e.text().contains("only the fee is negotiable")),
                "the task-19 duration-is-fixed note must be GONE: " + transcript);
        assertTrue(transcript.stream().anyMatch(e -> e.speaker() == DialogueTabModel.Speaker.SYSTEM
                        && e.text().contains("€2,000") && e.text().contains("10h")),
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
    void onAClosedTabEveryButtonIsGreyed() throws Exception {
        // S18b (playtest 2, PT6): S15 kept Accept and Reject enabled on a closed tab, to answer in
        // the transcript; the playtest read clickable buttons as "something is still possible".
        // All four are greyed now, Hint included. A click does nothing: nothing is sent, the
        // pipeline is not asked, and nothing is written.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        // Armed with an acceptance that must never be asked for.
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.TIMEOUT));
        flush();

        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest(),
                "the row keeps its shape: nothing jumps when the negotiation ends");
        for (String label : List.of("Send", "Hint", "Accept", "Reject")) {
            assertFalse(view.buttonForTest(label).isEnabled(), label + " is greyed on a closed tab");
        }
        assertFalse(view.inputFieldForTest().isEditable(), "and the input swallows keystrokes, as before (#9)");
        int before = transcriptOf(panel, "nego-A").size();

        click(view, "Accept");
        click(view, "Reject");
        click(view, "Hint");
        assertEquals(before, transcriptOf(panel, "nego-A").size(), "a greyed button writes nothing");
        assertTrue(published(bus, HintButtonEvent.class).isEmpty(), "and asks for no Hint");

        assertEquals(0, processor.callCount(), "a closed negotiation is never parsed");
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "and never sent");
        assertTrue(playerEntries(panel, "nego-A").isEmpty(), "no 'deal' / 'no' bubble for a move that was not made");
    }

    @Test
    @Timeout(5)
    void acceptWithNoOfferOnTheTableSendsNothing() throws Exception {
        // No tab is ever opened without the vessel's opening offer; if one were, "deal" would be
        // an acceptance of nothing. The button says so instead of sending it.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        ChatPanel panel = new ChatPanel(bus, processor);
        bus.publish(counterEvent("nego-A", "A", "cargo_vessel", "A", 0.0, 1)); // a first round with no price
        flush();
        DialogueTabView view = panel.viewForTest("nego-A");

        click(view, "Accept");

        assertEquals("There is no offer on the table to accept.", lastEntry(panel, "nego-A").text());
        assertEquals(0, processor.callCount());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());
        assertEquals("", view.placeholderForTest(), "and with nothing to build on, the input suggests nothing");
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

        // Settings.load()'s classpath default has autopilotEnabled=false, and that is what a panel
        // starts from until the Assistant has said who is negotiating where. S17a (ADR-21): the ON
        // case is the AutopilotOwnershipEvent path — the test after next, and ChatPanelOwnershipTest.
        assertTrue(panel.viewForTest("nego-A").hintButtonVisibleForTest());
    }

    @Test
    @Timeout(5)
    void hintButtonGreysOnceTheTabCloses() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertTrue(view.hintButtonVisibleForTest(), "setup");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL));
        SwingUtilities.invokeAndWait(() -> { });

        // S18b (PT6): greyed with the other three, rather than hidden.
        assertTrue(view.hintButtonVisibleForTest());
        assertFalse(view.buttonForTest("Hint").isEnabled());
    }

    /**
     * S17a (ADR-21, decisions A2 and A10): the Hint button follows the Assistant's own statement
     * of who is negotiating in the tab ({@link AutopilotOwnershipEvent}), LIVE. Task 21 had it
     * follow the Settings screen's toggle through a copy the panel kept; that copy is gone, and
     * {@code ChatPanelOwnershipTest} pins that the toggle alone now changes nothing here. Also the
     * leak guard: after {@code removeNotify()} the ownership subscription is cancelled, so a later
     * statement must not touch the detached panel's tabs.
     */
    @Test
    @Timeout(5)
    void theAssistantsOwnershipTogglesTheHintButtonLiveAndUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertTrue(view.hintButtonVisibleForTest(), "hint visible under the default autopilot=off");

        bus.publish(AutopilotOwnershipEvent.initial(true));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(view.hintButtonVisibleForTest(), "the Assistant negotiating here hides the Hint button live");

        bus.publish(AutopilotOwnershipEvent.initial(false));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(view.hintButtonVisibleForTest(), "the tab being the player's again re-shows it");

        panel.removeNotify();
        bus.publish(AutopilotOwnershipEvent.initial(true));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(view.hintButtonVisibleForTest(),
                "removeNotify() must cancel the ownership subscription — no update on a detached panel");
        assertTrue(view.inputFieldForTest().isEditable(), "and the detached tab was not locked either");
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
    void aHintRevisionReRendersTheTemplatesBubbleInPlaceWithItsWhy() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        bus.publish(AssistantChatEvent.hintTemplate("nego-A", 11L, "Accept their offer of 1436 (template)"));
        SwingUtilities.invokeAndWait(() -> { });
        int entries = panel.registry().tab("nego-A").transcript().size();
        assertTrue(view.transcriptTextForTest().contains("(template)"), "the template shows at once");

        bus.publish(AssistantChatEvent.hintRevision("nego-A", 11L, "Taking 1436 now is the better bet (model)"));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(entries, panel.registry().tab("nego-A").transcript().size(), "in place, not appended");
        String shown = view.transcriptTextForTest();
        // S19 (playtest 5, PT25): the template stays; the model's text is drawn under it as "Why: ...".
        assertTrue(shown.contains("(template)") && shown.contains("Why: Taking 1436 now")
                && shown.indexOf("(template)") < shown.indexOf("Why:"), shown);
        assertEquals(view.transcriptLengthForTest(), view.caretPositionForTest(), "C-28 still holds after a revision");
    }

    // ==================== S13 (live-playtest F-7): a refused PROPOSE leaves the strip ====================

    @Test
    @Timeout(5)
    void aProposeThePrologGateRefusedComesBackOffTheOfferStrip() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "ferry", "Rada Calma");
        DialogueTabView view = panel.viewForTest("nego-A");
        String standing = view.offerStripTextForTest();

        // The playtest's Beat 3b: the DCG parsed it, then the gate refused berth_3 for a ferry.
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "move", "counter",
                "money", Map.of("amount", 1900.0, "currency", "EUR"),
                "duration", 4,
                "berth", "berth_3"))));
        view.sendText("1900 for 4 hours at berth 3");
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(view.offerStripTextForTest().contains("Your offer €1,900 · 4h"),
                "setup: the send shows on the strip at once: " + view.offerStripTextForTest());

        PlayerCommandEvent sent = published(bus, PlayerCommandEvent.class).get(0);
        assertTrue(sent.commandId() != PlayerCommandEvent.NO_COMMAND_ID,
                "a PROPOSE the strip shows early must carry an id a refusal can name");
        assertEquals(java.util.Set.of("price", "hours", "berth_id"), sent.content().keySet(),
                "the id is bus-only — the wire content is unchanged");

        bus.publish(new PlayerCommandRefusedEvent("nego-A", PlayerCommandKind.PROPOSE, sent.commandId(),
                0.0, "berth_3 is incompatible with a ferry [R7]")); // the HM dispatched no offer yet
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(standing, view.offerStripTextForTest(),
                "nothing reached the vessel, so the strip must show the last standing offer again");
    }

    @Test
    @Timeout(5)
    void anotherSendsRefusalLeavesTheStripAlone() throws Exception {
        // The autopilot's commands carry no id (it never writes the strip): its refusals, and any
        // id this tab did not record, must not touch what the strip shows.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of("price", 2000, "hours", 5))));
        view.sendText("2000 for 5 hours");
        SwingUtilities.invokeAndWait(() -> { });
        String shown = view.offerStripTextForTest();

        bus.publish(new PlayerCommandRefusedEvent("nego-A", PlayerCommandKind.PROPOSE,
                PlayerCommandEvent.NO_COMMAND_ID, 0.0, "operations unsafe [R15,R16]"));
        bus.publish(new PlayerCommandRefusedEvent("nego-A", PlayerCommandKind.ACCEPT,
                published(bus, PlayerCommandEvent.class).get(0).commandId() + 1_000, 0.0, "swell [R17]"));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(shown, view.offerStripTextForTest());
    }

    // ==================== S15 (2026-10-12): the two-row offer line ====================

    @Test
    @Timeout(5)
    void theOfferLineIsTwoRowsAndSaysThereIsNoOfferUntilThePlayerMakesOne() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        String round = "round 1 of " + Settings.load().roundLimit();

        // The pre-S15 strip printed the "no offer yet" sentinel as "Your offer: €0 for 6h".
        assertEquals(List.of("Their offer €1,500 · 6h", "Your offer — · " + round), view.offerRowsForTest());
        assertEquals("Their offer €1,500 · 6h / Your offer — · " + round, view.offerStripTextForTest(),
                "the one-sentence form is the two rows joined at the ' / '");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "money", Map.of("amount", 1900.0, "currency", "EUR"), "duration", 4))));
        view.sendText("1900 for 4 hours");
        flush();
        assertEquals(List.of("Their offer €1,500 · 6h", "Your offer €1,900 · 4h · " + round),
                view.offerRowsForTest(), "the player's price with the player's OWN hours, beside the round");

        // S18b (UI tree U3, playtest 2 PT3): the closed line keeps the agreed figures, and the
        // transcript's last line says who accepted what.
        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL, 1900.0, 6, true));
        flush();
        assertEquals(List.of("Deal: €1,900 · 6h", ""), view.offerRowsForTest());
        assertEquals("Deal: €1,900 · 6h", view.offerStripTextForTest());
        assertEquals("✔ A accepted €1,900 for 6h — deal done.", lastEntry(panel, "nego-A").text(),
                "S19 (playtest 5, PT32): the ship is named");
    }

    // ==================== S15 (2026-10-12): the input's worked example ====================

    @Test
    @Timeout(5)
    void theEmptyInputShowsAnExampleBuiltFromThisVesselsOwnOffer() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A"); // asks €1500 for 6h
        DialogueTabView view = panel.viewForTest("nego-A");

        assertEquals("e.g. 1950 for 6h", view.placeholderForTest(),
                "30% over what the vessel asks: copied as it stands, it cannot close the deal below their offer");
        assertEquals(InputExamples.placeholderFor(panel.registry().tab("nego-A")), view.placeholderForTest());
        assertEquals("", view.inputFieldForTest().getText(), "the example is painted, it is never the field's text");

        // The vessel counters higher: the example follows ITS number at once (still entry 0 of
        // the rotation — a vessel message does not advance it).
        bus.publish(counterEvent("nego-A", "A", "cargo_vessel", "A", 1800.0, 2));
        flush();
        String followed = "e.g. " + InputExamples.priceAndHoursExample(1800.0, 6);
        assertEquals(followed, view.placeholderForTest());
        assertFalse(followed.equals("e.g. 1950 for 6h"), "setup: the two offers give different examples");
    }

    @Test
    @Timeout(5)
    void theExampleMovesOnWithEachSendAndOnlyThen() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(3_600);
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor, clock);
        clock.conversationClock().open("nego-A", 240_000L);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        // Sends the pipeline does not route change nothing but the count of sends.
        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions()));

        List<String> seen = new ArrayList<>();
        seen.add(view.placeholderForTest());
        for (int send = 0; send < 4; send++) {
            view.sendText("mumble");
            flush();
            seen.add(view.placeholderForTest());
        }
        assertEquals(List.of("e.g. 1950 for 6h", "e.g. 200 more", "e.g. make it 1800",
                "e.g. 1950 for 6h", "e.g. 200 more"), seen,
                "three examples before the player has offered, then round again (S18b, U2: no 'deal')");

        // Nothing but a send moves it: not the Assistant, not a Hint, not the one-second tick.
        bus.publish(new AssistantChatEvent("nego-A", "Assistant: take it"));
        flush();
        click(view, "Hint");
        clock.advance(5_000);
        SwingUtilities.invokeAndWait(panel::refreshCounters);
        assertEquals("e.g. 200 more", view.placeholderForTest(), "still the fifth send's example");
        assertEquals("3:55", view.counterTextForTest(), "setup: the tick did reach the tab");
    }

    @Test
    @Timeout(5)
    void theExamplesFollowThePlayersStandingDemand() throws Exception {
        // S18b (UI tree U2): no example is below the player's own demand, so an offer lifts the
        // lower one — and a refusal of that offer by the Prolog gate puts it back (the refusal
        // re-renders the offer line only, so the example has to be refreshed with it).
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A"); // their 1500: 1950 / make it 1800
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "move", "counter",
                "money", Map.of("amount", 1900.0, "currency", "EUR"),
                "duration", 4,
                "berth", "berth_1"))));
        view.sendText("1900 for 4 hours");
        flush();
        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions()));
        view.sendText("mumble");
        flush();
        assertEquals("e.g. make it 1900", view.placeholderForTest(),
                "two sends in, the third example: lifted from 1800 to the player's own 1900");

        PlayerCommandEvent sent = published(bus, PlayerCommandEvent.class).get(0);
        bus.publish(new PlayerCommandRefusedEvent("nego-A", PlayerCommandKind.PROPOSE, sent.commandId(),
                0.0, "operations unsafe for a cargo_vessel at 45 kn [R15,R16]"));
        flush();
        assertEquals("e.g. make it 1800", view.placeholderForTest(),
                "the refused offer never stood: nothing lifts the lower example any more");
    }

    @Test
    @Timeout(5)
    void pressingSendOnAnEmptyFieldSendsNothingEvenWithAnExampleShowing() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        processor.returning(new PipelineResult.Error("unused"));
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        int before = transcriptOf(panel, "nego-A").size();
        assertFalse(view.placeholderForTest().isEmpty(), "setup: an example is showing");

        click(view, "Send");
        SwingUtilities.invokeAndWait(() -> view.inputFieldForTest().postActionEvent()); // Enter in the field
        flush();

        assertEquals(0, processor.callCount(), "the example is not text: there was nothing to send");
        assertEquals(before, transcriptOf(panel, "nego-A").size());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());

        // What the player TYPES does go, and the field is cleared for the next message.
        SwingUtilities.invokeAndWait(() -> {
            view.inputFieldForTest().setText("  deal ");
            view.clickForTest("Send");
        });
        flush();
        assertEquals(List.of("deal"), processor.texts());
        assertEquals("", view.inputFieldForTest().getText());
        assertEquals("e.g. 200 more", view.placeholderForTest(), "and the next example is showing");
    }

    @Test
    @Timeout(5)
    void aClosedTabShowsNoExample() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertFalse(view.placeholderForTest().isEmpty(), "setup");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.WITHDRAW_PRICE));
        flush();

        assertEquals("", view.placeholderForTest(), "a field that ignores typing must not invite it");
        assertEquals("", view.inputFieldForTest().getText());
    }

    // ==================== S15 (2026-10-12): the parse note on a routed message ====================

    @Test
    @Timeout(5)
    void aRoutedDcgTurnTagsThePlayersBubble() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.PROPOSE, Map.of("price", 2000, "hours", 5)), null, ParseProvenance.DCG));
        view.sendText("2000 for 5 hours");
        flush();

        List<DialogueTabModel.ChatEntry> players = playerEntries(panel, "nego-A");
        assertEquals(1, players.size());
        assertEquals("2000 for 5 hours", players.get(0).text(), "the message itself is untouched");
        assertEquals("(DCG)", players.get(0).tag());
        assertTrue(view.transcriptTextForTest().contains("(DCG)"),
                "and the note is on screen: " + view.transcriptTextForTest());
        assertTrue(transcriptOf(panel, "nego-A").stream()
                        .filter(e -> e.speaker() != DialogueTabModel.Speaker.PLAYER)
                        .allMatch(e -> e.tag().isEmpty()),
                "only the player's own message is ever tagged");
        assertEquals(1, published(bus, PlayerCommandEvent.class).size(), "the move itself went out as before");
    }

    @Test
    @Timeout(5)
    void aRoutedRasaTurnShowsItsConfidence() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.REJECT_PROPOSAL, Map.of()), null, ParseProvenance.rasa(0.97)));
        view.sendText("not interested");
        flush();
        // The play-test's "so?", which Rasa read as an acceptance at 0.9997: cut to two decimals,
        // never rounded up to a certainty Rasa did not claim.
        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), null, ParseProvenance.rasa(0.9997)));
        view.sendText("so?");
        flush();

        List<DialogueTabModel.ChatEntry> players = playerEntries(panel, "nego-A");
        assertEquals("(Rasa 0.97)", players.get(0).tag());
        assertEquals("(Rasa 0.99)", players.get(1).tag());
        String shown = view.transcriptTextForTest();
        assertTrue(shown.contains("(Rasa 0.97)") && shown.contains("(Rasa 0.99)"), shown);
    }

    @Test
    @Timeout(5)
    void aRoutedResultWithNoProvenanceShowsNoTag() throws Exception {
        // A result that does not say where it came from (every stub in this file, any older
        // ChatInputProcessor) gets no note — never a guessed "(DCG)".
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of("price", 2000, "hours", 5))));
        view.sendText("2000 for 5 hours");
        flush();
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), "A"));
        view.sendText("deal");
        flush();
        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), null, ParseProvenance.UNKNOWN));
        view.sendText("deal");
        flush();

        assertEquals(3, playerEntries(panel, "nego-A").size());
        assertTrue(playerEntries(panel, "nego-A").stream().allMatch(e -> e.tag().isEmpty()));
        String shown = view.transcriptTextForTest();
        assertFalse(shown.contains("(DCG)") || shown.contains("(Rasa"), shown);
    }

    @Test
    @Timeout(5)
    void clarifiedAndFailedTurnsGetNoTag() throws Exception {
        // Neither stage's reading was acted on, and the line saying so sits right under the bubble.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions()));
        view.sendText("mumble");
        flush();
        processor.returning(new PipelineResult.Error("rasa connection refused"));
        view.sendText("whatever");
        flush();
        processor.throwing(new RuntimeException("connector down"));
        view.sendText("anything");
        flush();

        assertEquals(3, playerEntries(panel, "nego-A").size());
        assertTrue(playerEntries(panel, "nego-A").stream().allMatch(e -> e.tag().isEmpty()),
                playerEntries(panel, "nego-A").toString());
    }

    @Test
    @Timeout(5)
    void aLocallyAnsweredStatusQueryIsStillTagged() throws Exception {
        // Routed, but answered by the tab itself with nothing dispatched: it was still READ by a
        // stage, and the note is about the reading.
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        processor.returning(new PipelineResult.Routed(
                acl(ACLMessage.QUERY_REF, Map.of()), null, ParseProvenance.rasa(0.91)));
        view.sendText("what's the status?");
        flush();

        assertEquals("(Rasa 0.91)", playerEntries(panel, "nego-A").get(0).tag());
        assertTrue(lastEntry(panel, "nego-A").text().startsWith("Status —"), lastEntry(panel, "nego-A").text());
        assertTrue(view.transcriptTextForTest().contains("(Rasa 0.91)"), "rendered with the status line");
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());
    }

    @Test
    @Timeout(5)
    void aTurnThatEndedWhileItWasBeingReadIsStillTagged() throws Exception {
        // The negotiation closes while the parse is in flight: nothing is sent (S10), but the
        // message was read, and the note says by what.
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        panel.viewForTest("nego-A").sendText("deal");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.TIMEOUT));
        flush();
        processor.complete(0, new PipelineResult.Routed(
                acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), null, ParseProvenance.DCG));
        flush();

        assertEquals("(DCG)", playerEntries(panel, "nego-A").get(0).tag());
        assertTrue(lastEntry(panel, "nego-A").text().contains("nothing was sent"), lastEntry(panel, "nego-A").text());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty());
    }

    @Test
    @Timeout(5)
    void theTagLandsOnItsOwnMessageWhenTwoParsesFinishOutOfOrder() throws Exception {
        // Two sends in quick succession; the second parse (a DCG hit, milliseconds) comes back
        // before the first (a Rasa fallback, up to seconds). Each note must find its own bubble.
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        view.sendText("so?");
        view.sendText("1900 for 6h");
        assertEquals(2, processor.pendingCount(), "setup: both parses are in flight");

        processor.complete(1, new PipelineResult.Routed(
                acl(ACLMessage.PROPOSE, Map.of("price", 1900, "hours", 6)), null, ParseProvenance.DCG));
        flush();
        List<DialogueTabModel.ChatEntry> players = playerEntries(panel, "nego-A");
        assertEquals("", players.get(0).tag(), "the first message is still being read");
        assertEquals("(DCG)", players.get(1).tag());

        processor.complete(0, new PipelineResult.Routed(
                acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), null, ParseProvenance.rasa(0.97)));
        flush();
        players = playerEntries(panel, "nego-A");
        assertEquals(List.of("so?", "1900 for 6h"), players.stream().map(DialogueTabModel.ChatEntry::text).toList());
        assertEquals("(Rasa 0.97)", players.get(0).tag());
        assertEquals("(DCG)", players.get(1).tag(), "and the later result did not disturb the earlier note");
    }

    @Test
    @Timeout(5)
    void aParseThatFinishesAfterALoadTagsNothingInTheNewWorld() throws Exception {
        // S10's world-identity guard, now also the note's: a load clears the tabs, and the new
        // world's deterministic ids recreate the SAME dialogue id — with another message sitting
        // at the very index the stale result would write to.
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        panel.viewForTest("nego-A").sendText("deal"); // parse 0, old world

        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        flush();
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        panel.viewForTest("nego-A").sendText("how about 2000"); // parse 1, new world — same index
        List<DialogueTabModel.ChatEntry> before = transcriptOf(panel, "nego-A");

        processor.complete(0, new PipelineResult.Routed(
                acl(ACLMessage.ACCEPT_PROPOSAL, Map.of()), null, ParseProvenance.DCG));
        flush();

        assertEquals(before, transcriptOf(panel, "nego-A"), "the new world's tab is untouched: no note, no echo");
        assertEquals("", playerEntries(panel, "nego-A").get(0).tag());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(),
                "and the old world's 'deal' reaches no vessel of the new one");
    }

    // ==================== S15 (2026-10-12): the tab fits the middle column ====================

    @Test
    @Timeout(5)
    void aTabFitsTheMiddleColumnsMinimumWidth() throws Exception {
        // A JTabbedPane's minimum width tracks the widest component of ANY of its tabs, so one
        // over-wide tab would push the whole three-column screen past its minimum. Measured with
        // everything that could widen it in place: the counter showing, the player's offer and
        // the round on the second row, and a long draft in the field.
        assertEquals(380, ChatPanel.MIN_COLUMN_WIDTH);
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(3_600);
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor, clock);
        clock.conversationClock().open("nego-A", 240_000L);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "Santa Lucia della Lanterna");
        DialogueTabView view = panel.viewForTest("nego-A");
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "money", Map.of("amount", 1900.0, "currency", "EUR"), "duration", 4))));
        view.sendText("1900 for 4 hours");
        flush();
        assertEquals("4:00", view.counterTextForTest(), "setup: the counter is showing");

        int[] minimumWidth = new int[1];
        Map<String, Rectangle> buttons = new LinkedHashMap<>();
        Map<String, Integer> preferredWidths = new LinkedHashMap<>();
        int[] fieldWidth = new int[1];
        int[] roundRow = new int[2]; // the second offer row: the width it got, the width it wants
        SwingUtilities.invokeAndWait(() -> {
            view.inputFieldForTest().setText("x".repeat(200));
            minimumWidth[0] = view.getMinimumSize().width;
            view.setSize(ChatPanel.MIN_COLUMN_WIDTH, 480);
            layOutTree(view);
            for (String label : view.buttonLabelsForTest()) {
                JButton button = view.buttonForTest(label);
                buttons.put(label, boundsIn(button, view));
                preferredWidths.put(label, button.getPreferredSize().width);
            }
            fieldWidth[0] = view.inputFieldForTest().getWidth();
            JLabel row = labelStartingWith(view, "Your offer");
            roundRow[0] = row.getWidth();
            roundRow[1] = row.getPreferredSize().width;
        });

        assertTrue(minimumWidth[0] <= ChatPanel.MIN_COLUMN_WIDTH,
                "a tab's minimum width is " + minimumWidth[0] + " px, over the column's " + ChatPanel.MIN_COLUMN_WIDTH);

        // And laid out AT that width, nothing is squeezed or cut off.
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), List.copyOf(buttons.keySet()));
        for (Map.Entry<String, Rectangle> button : buttons.entrySet()) {
            Rectangle bounds = button.getValue();
            assertTrue(bounds.width >= preferredWidths.get(button.getKey()) && bounds.height > 0,
                    button.getKey() + " is squeezed: " + bounds);
            assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= ChatPanel.MIN_COLUMN_WIDTH,
                    button.getKey() + " is cut off: " + bounds);
        }
        assertTrue(buttons.get("Hint").x + buttons.get("Hint").width <= buttons.get("Accept").x
                        && buttons.get("Accept").x + buttons.get("Accept").width <= buttons.get("Reject").x,
                "Hint, Accept, Reject left to right, not overlapping: " + buttons);
        assertTrue(buttons.get("Hint").y > buttons.get("Send").y,
                "the three action buttons sit on the row BELOW the input and Send: " + buttons);
        int sendRight = buttons.get("Send").x + buttons.get("Send").width;
        int rejectRight = buttons.get("Reject").x + buttons.get("Reject").width;
        assertTrue(Math.abs(sendRight - rejectRight) <= 1,
                "the action row is right-aligned under Send: " + buttons);
        assertTrue(fieldWidth[0] >= 200, "the input keeps a usable width beside Send: " + fieldWidth[0]);
        assertTrue(roundRow[0] >= roundRow[1],
                "the round must not be cut off: the row got " + roundRow[0] + " px and needs " + roundRow[1]);
    }

    // ==================== S19 (playtest 5, PT26): a closed tab stays until the player closes it ====================
    // Audit C-07 swept a closed tab off the strip 30 s after it closed (F-9's pile-up). Playtest 5:
    // they disappeared by themselves while the player still wanted them.

    private static SimClockTickEvent tick(SimClock clock) {
        return new SimClockTickEvent(clock.nowSimMillis(), clock.gameDay(), clock.simHour(), clock.simMinute());
    }

    @Test
    @Timeout(5)
    void aClosedTabWaitsForItsCloseButtonNotForAClock() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        ChatPanel panel = new ChatPanel(bus,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");
        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL));
        flush();
        SwingUtilities.invokeAndWait(() -> assertTrue(panel.selectDialogue("nego-B")));
        assertTrue(panel.hasCloseButtonForTest("nego-A"), "a closed tab carries its x");
        assertFalse(panel.hasCloseButtonForTest("nego-B"), "an open negotiation does not");

        clock.advance(600_000); // ten real minutes of running game
        bus.publish(tick(clock));
        flush();
        assertEquals(2, panel.stripTabCountForTest(), "no timer takes a closed tab away any more");
        assertTrue(panel.tabTitleForTest("nego-A").endsWith("(closed)"));

        SwingUtilities.invokeAndWait(() -> panel.closeTabForTest("nego-A"));
        flush();
        assertEquals(1, panel.stripTabCountForTest(), "the x takes it off the strip");
        assertEquals(1, panel.tabCountForTest());
        assertNull(panel.viewForTest("nego-A"));
        assertNull(panel.registry().tab("nego-A"));
        assertEquals("Genoa Star (tanker)", panel.tabTitleForTest("nego-B"), "the open tab is untouched");
        assertEquals(List.of("nego-A"), panel.finishedDialogues().stream()
                .map(it.unige.portcommand.gui.model.FinishedDialogue::dialogueId).toList(),
                "nothing is lost — it is in the Finished list");
    }

    @Test
    @Timeout(5)
    void theLastClosedTabStaysWhileTheNextWalkInTakesItsPlaceAndItsCloseBringsTheCardBack() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        ChatPanel panel = new ChatPanel(bus,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);
        // S18b (playtest 2, PT15): before the first walk-in the column says how to play.
        assertTrue(panel.getComponent(0) instanceof javax.swing.JLabel label
                && label.getText().contains("How to play") && label.getText().contains(ChatPanel.WAITING_TEXT),
                "the empty column shows the How to play card");
        assertTrue(panel.getComponent(0).isFocusable(),
                "the card can take a closed tab's focus, so keys never fall through to the comm log (#9)");

        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL));
        flush();
        openTab(bus, panel, "nego-C", "C", "ferry", "Lanterna Rossa");
        assertEquals("nego-C", panel.selectedDialogueId(), "S18b (U5): the new walk-in takes the closed tab's place");
        assertEquals(2, panel.stripTabCountForTest(), "the closed tab stays, one click away on the strip");

        bus.publish(new NegotiationClosedEvent("nego-C", "C", Deal.Outcome.TIMEOUT));
        flush();
        SwingUtilities.invokeAndWait(() -> panel.closeTabForTest("nego-C")); // the tab being read: its x works too
        SwingUtilities.invokeAndWait(() -> panel.closeTabForTest("nego-A"));
        flush();
        assertEquals(0, panel.stripTabCountForTest());
        assertTrue(panel.getComponent(0) instanceof javax.swing.JLabel label
                && label.getText().contains("How to play"), "an empty strip shows the card again");

        openTab(bus, panel, "nego-D", "D", "ferry", "Faro Vecchio");
        bus.publish(new it.unige.portcommand.persistence.events.GameLoadedEvent(1, 15_000.0, 50));
        flush();
        assertTrue(panel.getComponent(0) instanceof javax.swing.JLabel label
                && label.getText().contains("How to play"), "a new world starts with the card again");
    }

    @Test
    @Timeout(5)
    void aSixthClosedTabSendsTheOldestOneNotBeingReadToFinished() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        for (String id : List.of("A", "B", "C", "D", "E", "F", "G")) {
            openTab(bus, panel, "nego-" + id, id, "cargo_vessel", id);
        }
        assertEquals("nego-A", panel.selectedDialogueId(), "setup: the first tab is the one being read");
        for (String id : List.of("A", "B", "C", "D", "E")) {
            bus.publish(new NegotiationClosedEvent("nego-" + id, id, Deal.Outcome.DEAL));
            flush();
        }
        assertEquals(7, panel.stripTabCountForTest(), "five closed tabs stay");

        bus.publish(new NegotiationClosedEvent("nego-F", "F", Deal.Outcome.DEAL));
        flush();

        assertEquals(6, panel.stripTabCountForTest());
        assertTrue(panel.viewForTest("nego-A") != null, "the oldest is being read, so it stays");
        assertNull(panel.viewForTest("nego-B"), "the next oldest went to the Finished list");
        assertEquals("nego-A", panel.selectedDialogueId(), "and the selection did not move");
        assertEquals(6, panel.finishedDialogues().size());
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

    // ==================== S18b (2026-10-14): playtests 2-4 and the UI tree ====================

    /** UI tree U5: a new walk-in comes forward when the selected tab is closed — and only then. */
    @Test
    @Timeout(5)
    void aNewWalkInTakesOverAClosedSelectionButNotAnOpenOne() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");
        assertEquals("nego-A", panel.selectedDialogueId(), "an open tab keeps the selection: the player may be typing");

        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.TIMEOUT));
        flush();
        openTab(bus, panel, "nego-C", "C", "ferry", "Lanterna Rossa");
        assertEquals("nego-C", panel.selectedDialogueId(), "nothing is left to do in the closed tab");
    }

    /** UI tree U8: a double click on Accept used to send it twice. S18b (adversarial review): the
     * parse of "deal" is back in milliseconds, well inside a double click - so once the move is
     * sent, the buttons wait for the vessel side's answer to it, not only for the parse. */
    @Test
    @Timeout(5)
    void acceptAndRejectAreGreyedUntilTheirMoveIsAnswered() throws Exception {
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        click(view, "Accept");
        assertFalse(view.buttonForTest("Accept").isEnabled(), "while 'deal' is being read");
        assertFalse(view.buttonForTest("Reject").isEnabled());
        assertTrue(view.buttonForTest("Send").isEnabled(), "typing is not blocked");
        click(view, "Accept");
        click(view, "Reject");
        assertEquals(1, processor.pendingCount(), "the second click sent nothing");

        processor.complete(0, new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        flush();
        assertEquals(1, published(bus, PlayerCommandEvent.class).size());
        assertFalse(view.buttonForTest("Accept").isEnabled(), "read and sent: they wait for the answer");
        assertFalse(view.buttonForTest("Reject").isEnabled());
        click(view, "Accept");
        assertEquals(1, processor.pendingCount(), "a click while the move waits sends nothing");

        bus.publish(new it.unige.portcommand.gui.events.PlayerCommandRefusedEvent("nego-A",
                PlayerCommandEvent.PlayerCommandKind.ACCEPT, PlayerCommandEvent.NO_COMMAND_ID, 0.0, "R16"));
        flush();
        assertTrue(view.buttonForTest("Accept").isEnabled(), "answered - refused, here: the buttons are back");
        assertTrue(view.buttonForTest("Reject").isEnabled());
    }

    /** S18b (adversarial review): a quick move that is not sent waits for nothing. */
    @Test
    @Timeout(5)
    void aQuickMoveThatIsNotSentGivesTheButtonsBackAtOnce() throws Exception {
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        click(view, "Reject");
        processor.complete(0, new PipelineResult.NeedsClarification(List.of(), "Which offer?", null));
        flush();

        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "nothing went out");
        assertTrue(view.buttonForTest("Accept").isEnabled());
        assertTrue(view.buttonForTest("Reject").isEnabled());
    }

    /** S18b (adversarial review): a new round from the vessel answers the move too. */
    @Test
    @Timeout(5)
    void aNewRoundAnswersTheMoveTheButtonsWaitedOn() throws Exception {
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        click(view, "Accept");
        processor.complete(0, new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));
        flush();
        assertFalse(view.buttonForTest("Accept").isEnabled(), "setup: the move waits");

        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A"); // the vessel's next round
        assertTrue(view.buttonForTest("Accept").isEnabled());
    }

    @Test
    @Timeout(5)
    void aTypedSendDoesNotGreyTheButtons() throws Exception {
        EventBus bus = new EventBus();
        DeferredProcessor processor = new DeferredProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");

        SwingUtilities.invokeAndWait(() -> view.sendText("2000 for 6h"));
        assertTrue(view.buttonForTest("Accept").isEnabled(), "only a button's own send greys them");
    }

    /** UI tree U7: a refresh follows the newest line only while the view is at the bottom. */
    @Test
    @Timeout(5)
    void theTranscriptFollowsTheNewestLineOnlyFromTheBottom() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertTrue(view.followingTailForTest(), "a new tab follows its newest line");
        assertTrue(view.lastRefreshPinnedForTest());

        SwingUtilities.invokeAndWait(() -> view.setFollowingTailForTest(false)); // the player scrolled up
        bus.publish(new AssistantChatEvent("nego-A", "Assistant: take it"));
        flush();
        assertFalse(view.lastRefreshPinnedForTest(), "a line arriving while the player reads higher up stays out of the way");

        SwingUtilities.invokeAndWait(() -> view.setFollowingTailForTest(true)); // back at the bottom
        bus.publish(new AssistantChatEvent("nego-A", "Assistant: or not"));
        flush();
        assertTrue(view.lastRefreshPinnedForTest(), "at the bottom, the newest line is followed");
        assertEquals(view.transcriptLengthForTest(), view.caretPositionForTest(), "C-28: the caret is at the end");
    }

    /** UI tree U7: the player's own scrolling decides whether the transcript follows its bottom. */
    @Test
    @Timeout(10)
    void thePlayersOwnScrollingDecidesWhetherTheTranscriptFollows() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        DialogueTabView view = panel.viewForTest("nego-A");
        for (int i = 0; i < 40; i++) {
            bus.publish(new AssistantChatEvent("nego-A", "Assistant: line " + i + " of a long conversation"));
        }
        flush();
        SwingUtilities.invokeAndWait(() -> {
            view.setSize(ChatPanel.MIN_COLUMN_WIDTH, 300);
            layOutTree(view);
        });
        javax.swing.JScrollBar bar = ((javax.swing.JScrollPane) SwingUtilities.getAncestorOfClass(
                javax.swing.JScrollPane.class, view.transcriptPaneForTest())).getVerticalScrollBar();
        SwingUtilities.invokeAndWait(() -> assertTrue(bar.getMaximum() > bar.getVisibleAmount() + 50,
                "setup: the transcript overflows its view (" + bar.getMaximum() + " / " + bar.getVisibleAmount() + ")"));

        SwingUtilities.invokeAndWait(() -> bar.setValue(0)); // the player scrolls up to the top
        assertFalse(view.followingTailForTest(), "scrolled up: the view no longer follows");

        SwingUtilities.invokeAndWait(() -> bar.setValue(bar.getMaximum())); // and back down
        assertTrue(view.followingTailForTest(), "at the bottom again: it follows");
    }

    /** Playtest 4, PT22: "ok 2000" got the generic line. One price above their offer is offered back. */
    @Test
    @Timeout(5)
    void aLineWithOnePriceInItIsOfferedBackAsAMove() throws Exception {
        EventBus bus = new EventBus();
        StubProcessor processor = new StubProcessor();
        ChatPanel panel = new ChatPanel(bus, processor);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A"); // their 1500 for 6h
        DialogueTabView view = panel.viewForTest("nego-A");
        processor.returning(new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions()));

        view.sendText("ok 2000");
        flush();
        assertEquals("Did you mean €2,000? Write '2000 for 6h' — or press Accept for their €1,500.",
                lastEntry(panel, "nego-A").text());
        assertTrue(published(bus, PlayerCommandEvent.class).isEmpty(), "nothing is sent: the player decides");

        view.sendText("ok 1000");
        flush();
        assertTrue(lastEntry(panel, "nego-A").text().startsWith("I didn't get that"),
                "1000 is under their 1500: copied, it would close below their own offer — no guess");
    }

    /** Playtest 2, PT5: a finished negotiation comes back from the Finished list, read-only. */
    @Test
    @Timeout(5)
    void aFinishedNegotiationComesBackReadOnlyWithItsWholeTranscript() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        ChatPanel panel = new ChatPanel(bus,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);
        openTab(bus, panel, "nego-A", "A", "cargo_vessel", "A");
        openTab(bus, panel, "nego-B", "B", "tanker", "Genoa Star");
        bus.publish(new NegotiationClosedEvent("nego-A", "A", Deal.Outcome.DEAL, 1500.0, 6, false));
        flush();
        List<DialogueTabModel.ChatEntry> before = transcriptOf(panel, "nego-A");
        assertEquals("✔ Deal done: €1,500 for 6h.", before.get(before.size() - 1).text(),
                "their own offer, accepted on the player's side: the line names nobody");
        SwingUtilities.invokeAndWait(() -> assertTrue(panel.selectDialogue("nego-B")));
        SwingUtilities.invokeAndWait(() -> panel.closeTabForTest("nego-A")); // S19 (PT26): the player's x
        flush();
        assertNull(panel.viewForTest("nego-A"), "setup: A has left the strip");

        SwingUtilities.invokeAndWait(() -> assertTrue(panel.selectDialogue("nego-A"), "the Finished list's click"));
        DialogueTabView view = panel.viewForTest("nego-A");
        assertEquals("nego-A", panel.selectedDialogueId());
        assertEquals(before, transcriptOf(panel, "nego-A"), "the whole transcript, as it was");
        assertTrue(panel.tabTitleForTest("nego-A").endsWith("(closed)"));
        assertFalse(view.inputFieldForTest().isEditable(), "read-only");
        assertFalse(view.buttonForTest("Accept").isEnabled());
        assertEquals("Deal: €1,500 · 6h", view.offerStripTextForTest());
        assertTrue(panel.openDialogues().stream().noneMatch(d -> d.dialogueId().equals("nego-A")),
                "a finished negotiation is not waiting for anyone");

        SwingUtilities.invokeAndWait(() -> assertTrue(panel.selectDialogue("nego-B"))); // the player moves on
        assertTrue(panel.hasCloseButtonForTest("nego-A"), "S19 (PT26): a tab brought back is closed like any other");
        SwingUtilities.invokeAndWait(() -> panel.closeTabForTest("nego-A"));
        flush();
        assertNull(panel.viewForTest("nego-A"), "and its x takes it off the strip again");
        SwingUtilities.invokeAndWait(() -> assertFalse(panel.selectDialogue("nego-ghost"),
                "an id this world never finished is refused"));
    }
}
