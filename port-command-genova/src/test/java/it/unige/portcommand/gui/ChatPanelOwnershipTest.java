package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.AutopilotOwnershipEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.events.TakeOverEvent;
import it.unige.portcommand.gui.model.DialogueTabModel;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21, decisions A2 and A10; playtest item PT12; backlog R-07): who is negotiating in a
 * chat tab, and what the tab does about it.
 *
 * <p>The chat column has ONE source for that: the Assistant's {@link AutopilotOwnershipEvent} —
 * the autopilot switch plus the dialogues the player took over. Pinned here: a tab the Assistant
 * owns is locked (input, Send, Accept, Reject, Hint) and shows the marker row with
 * {@code Take over}; a tab the player owns is exactly the pre-S17a tab; the Settings toggle on its
 * own changes nothing in the panel; a load does not reset the ownership (R-07); nothing can be sent
 * from a locked tab by any route; and {@code Take over} asks once and waits for the Assistant's
 * answer instead of unlocking the tab itself.
 *
 * <p>Headless, the {@code ChatPanelTest} idiom: a real {@link EventBus}, publish and flush the EDT,
 * a processor that records what it was asked to read. Nothing sleeps.
 */
class ChatPanelOwnershipTest {

    private static final String A = "nego-A";
    private static final String B = "nego-B";

    /** Records every text it is asked to read and answers with whatever the test last set. */
    private static final class RecordingProcessor implements ChatInputProcessor {
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private volatile PipelineResult result = new PipelineResult.Error("unset");

        void returning(PipelineResult next) {
            this.result = next;
        }

        /** The NEXT text is still being read when {@code process} returns: the test completes the
         * returned future itself, as a slow parse would. */
        CompletableFuture<PipelineResult> holdNext() {
            CompletableFuture<PipelineResult> parse = new CompletableFuture<>();
            this.held = parse;
            return parse;
        }

        private volatile CompletableFuture<PipelineResult> held;

        List<String> texts() {
            return List.copyOf(texts);
        }

        @Override
        public CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx) {
            texts.add(text);
            CompletableFuture<PipelineResult> parse = held;
            if (parse != null) {
                held = null;
                return parse;
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    private final EventBus bus = new EventBus();
    private final RecordingProcessor processor = new RecordingProcessor();
    private final ChatPanel panel = new ChatPanel(bus, processor);

    // --- driving the panel ---------------------------------------------------------------------

    private static NegotiationOpenedEvent openEvent(String dialogueId, String vesselId, String name) {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot(vesselId, "cargo_vessel", 9.0, 150.0, 30000,
                "general_cargo", "berth_1", 6, 1500.0, 0.0, 1, 0.0, false, name);
        return new NegotiationOpenedEvent(dialogueId, snapshot);
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** A walk-in hails on {@code bus}: its tab opens in {@code onPanel}. */
    private static DialogueTabView open(EventBus bus, ChatPanel onPanel, String dialogueId, String vesselId,
                                        String name) throws Exception {
        bus.publish(openEvent(dialogueId, vesselId, name));
        flush();
        DialogueTabView view = onPanel.viewForTest(dialogueId);
        assertNotNull(view, "setup: the tab must have opened");
        return view;
    }

    private DialogueTabView open(String dialogueId, String vesselId) throws Exception {
        return open(bus, panel, dialogueId, vesselId, vesselId);
    }

    /** The Assistant states who is negotiating where: the switch, and the dialogues taken over. */
    private void ownership(boolean autopilotOn, String... takenOver) throws Exception {
        bus.publish(new AutopilotOwnershipEvent(autopilotOn, Set.of(takenOver)));
        flush();
    }

    /** A player's click, on the EDT, and one more EDT pass for whatever the click queued. */
    private static void click(DialogueTabView view, String label) throws Exception {
        HeadlessSwing.onEdt(() -> view.clickForTest(label));
        flush();
    }

    private <T extends Event> List<T> published(Class<T> type) {
        return EventBusProbe.published(bus).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private List<DialogueTabModel.ChatEntry> transcript(String dialogueId) {
        return panel.registry().tab(dialogueId).transcript();
    }

    private long linesSaying(String dialogueId, String text) {
        return transcript(dialogueId).stream().filter(e -> e.text().equals(text)).count();
    }

    private static PipelineResult routed(int performative) {
        ACLMessage msg = MessageFactory.create(performative);
        msg.setContent(TerminalJson.write(Map.of()));
        return new PipelineResult.Routed(msg);
    }

    // --- what "locked" and "the player's" look like ---------------------------------------------

    /** Everything decision A2 asks of a tab the Assistant is negotiating in — except the state of
     * {@code Take over} itself, which the take-over tests look at. */
    private static void assertLocked(DialogueTabView view, String where) {
        assertFalse(view.inputFieldForTest().isEditable(), where + ": the input must swallow what is typed");
        assertTrue(view.inputFieldForTest().isEnabled() && view.inputFieldForTest().isFocusable(),
                where + ": and stay enabled and focusable, the way a closed tab's does (#9)");
        assertEquals("", view.placeholderForTest(), where + ": no example invites typing into it");
        for (String label : List.of("Send", "Accept", "Reject")) {
            assertFalse(view.buttonForTest(label).isEnabled(), where + ": " + label + " must be disabled");
        }
        assertFalse(view.hintButtonVisibleForTest(), where + ": Hint must be hidden");
        assertTrue(view.markerRowVisibleForTest(), where + ": the marker row must show");
        assertEquals("Assistant is negotiating…", view.markerTextForTest(), where);
        assertFalse(view.hintRowVisibleForTest(), where + ": and no hint row");
        assertEquals(List.of("Take over", "Send", "Accept", "Reject"), view.buttonLabelsForTest(), where);
    }

    /** A tab the player owns, with no Hint asked for: exactly the pre-S17a tab. */
    private static void assertPlayers(DialogueTabView view, String where) {
        assertTrue(view.inputFieldForTest().isEditable(), where + ": the input takes typing");
        for (String label : List.of("Send", "Accept", "Reject")) {
            assertTrue(view.buttonForTest(label).isEnabled(), where + ": " + label + " must be usable");
        }
        assertTrue(view.hintButtonVisibleForTest(), where + ": Hint must show");
        assertFalse(view.markerRowVisibleForTest(), where + ": no marker row");
        assertEquals("", view.markerTextForTest(), where);
        assertFalse(view.hintRowVisibleForTest(), where + ": no hint row either");
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest(), where);
    }

    // ==================== the lock follows the Assistant's statement ====================

    @Test
    @Timeout(5)
    void anOwnershipEventLocksAndUnlocksAnOpenTab() throws Exception {
        ownership(false);
        DialogueTabView view = open(A, "A");
        assertPlayers(view, "the player's tab");
        assertEquals("e.g. 1950 for 6h", view.placeholderForTest(), "setup: the input shows its example");

        ownership(true);
        assertLocked(view, "the autopilot is on and nothing was taken over");
        assertTrue(view.buttonForTest("Take over").isEnabled(), "the way out is there to be pressed");
        assertEquals(DialogueTabView.ASSISTANT_NEGOTIATING_TEXT, view.markerTextForTest());
        assertEquals(DialogueTabView.TAKE_OVER_LABEL, "Take over");

        ownership(false);
        assertPlayers(view, "the autopilot was switched off again");
        assertEquals("e.g. 1950 for 6h", view.placeholderForTest(), "and the example is back");
    }

    @Test
    @Timeout(5)
    void aTabThatOpensWhileTheAssistantOwnsEverythingIsBornLocked() throws Exception {
        // The event carries the whole state, so a tab that opens later needs no event of its own.
        ownership(true);

        DialogueTabView view = open(A, "A");

        assertLocked(view, "a tab opened after the statement");
    }

    @Test
    @Timeout(5)
    void aTakenOverTabIsThePlayersWhileEveryOtherTabStaysLocked() throws Exception {
        DialogueTabView viewA = open(A, "A");
        DialogueTabView viewB = open(B, "B");

        ownership(true, A);

        assertPlayers(viewA, "the tab the player took over");
        assertLocked(viewB, "the tab next to it");
        assertLocked(open("nego-C", "C"), "and a walk-in that hails afterwards");

        // The switch going off frees them all; going on again, A is still the player's.
        ownership(false, A);
        assertPlayers(viewB, "autopilot off");
        ownership(true, A);
        assertPlayers(viewA, "taken over stays taken over");
        assertLocked(viewB, "autopilot on again");
    }

    @Test
    @Timeout(5)
    void theSettingsToggleAloneChangesNothingInThePanel() throws Exception {
        // Before S17a the panel kept its own copy of the Settings toggle. Now the Assistant hears
        // the toggle and restates the ownership — the panel acts on that statement only.
        ownership(false);
        DialogueTabView view = open(A, "A");

        bus.publish(new SettingsChangedEvent("NORMAL", 300L, true, "microsoft/Phi-4-mini-instruct"));
        flush();
        assertPlayers(view, "the toggle went on, the Assistant has said nothing yet");

        ownership(true);
        bus.publish(new SettingsChangedEvent("NORMAL", 300L, false, "microsoft/Phi-4-mini-instruct"));
        flush();
        assertLocked(view, "the toggle went off, the Assistant has said nothing yet");
    }

    @Test
    @Timeout(5)
    void aNewPanelStartsFromTheSessionsAutopilotSetting() throws Exception {
        // Until the Assistant has spoken, the panel assumes what the Assistant itself boots from.
        Settings before = Settings.load();
        try {
            Settings.applyLive(before.withAutopilotEnabled(true));
            EventBus onBus = new EventBus();
            ChatPanel onPanel = new ChatPanel(onBus, processor);
            assertLocked(open(onBus, onPanel, A, "A", "A"), "autopilot ON in the session's settings");

            Settings.applyLive(before.withAutopilotEnabled(false));
            EventBus offBus = new EventBus();
            ChatPanel offPanel = new ChatPanel(offBus, processor);
            assertPlayers(open(offBus, offPanel, A, "A", "A"), "autopilot OFF in the session's settings");
        } finally {
            Settings.applyLive(before);
        }
    }

    // ==================== backlog R-07: a load does not reset the ownership ====================

    @Test
    @Timeout(5)
    void aLoadKeepsTheOwnershipTheAssistantLastStated() throws Exception {
        // R-07: the panel used to set its autopilot copy to "off" on every load, while the
        // respawned Assistant read the SAVED value — after loading an autopilot-ON save the next
        // tab offered Hint and a free input while the Assistant negotiated in it.
        ownership(true);
        assertLocked(open(A, "A"), "setup: locked before the load");

        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        flush();
        assertNull(panel.viewForTest(A), "setup: the load dropped the old world's tabs");

        DialogueTabView reopened = open(A, "A"); // the new world's ids recur
        assertLocked(reopened, "the first walk-in after loading an autopilot-ON game");
        assertFalse(reopened.hintButtonVisibleForTest(), "no Hint beside a negotiating Assistant");
    }

    @Test
    @Timeout(5)
    void afterALoadTheRespawnedAssistantsStatementIsTheWholeTruth() throws Exception {
        // The old world's take-over of nego-A must not carry into the new world's nego-A: the
        // respawned Assistant restates the ownership on the loaded marker, and that statement
        // replaces the last one whole.
        DialogueTabView before = open(A, "A");
        ownership(true, A);
        assertPlayers(before, "setup: taken over in the old world");

        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        ownership(true); // the respawned Assistant: on, nothing taken over
        assertLocked(open(A, "A"), "the new world's nego-A");

        // And a save with the autopilot off loads free.
        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        ownership(false);
        assertPlayers(open(A, "A"), "an autopilot-OFF game");
    }

    // ==================== nothing leaves a locked tab ====================

    @Test
    @Timeout(5)
    void nothingIsSentFromATabTheAssistantOwns() throws Exception {
        ownership(true);
        DialogueTabView view = open(A, "A");
        processor.returning(routed(ACLMessage.ACCEPT_PROPOSAL)); // armed with a move that must never be asked for
        List<DialogueTabModel.ChatEntry> before = transcript(A);

        HeadlessSwing.onEdt(() -> view.sendText("deal")); // what Enter, a button or a caller would reach
        HeadlessSwing.onEdt(() -> {
            view.inputFieldForTest().setText("1900 for 6h"); // a draft from before the lock
            view.inputFieldForTest().postActionEvent();      // Enter in the field
        });
        for (String label : List.of("Send", "Accept", "Reject")) {
            click(view, label); // disabled: a click does nothing
        }
        flush();

        assertEquals(List.of(), processor.texts(), "the pipeline is not even asked");
        assertEquals(List.of(), published(PlayerCommandEvent.class), "and no command reaches the HarbourMaster");
        assertEquals(before, transcript(A), "no player bubble, no echo, no system line");
        assertEquals("1900 for 6h", view.inputFieldForTest().getText(), "the draft is left where it was");

        // The same send goes through the moment the tab is the player's: the ownership was the
        // only thing in its way.
        ownership(true, A);
        HeadlessSwing.onEdt(() -> view.sendText("deal"));
        flush();
        assertEquals(List.of("deal"), processor.texts());
        List<PlayerCommandEvent> commands = published(PlayerCommandEvent.class);
        assertEquals(1, commands.size());
        assertEquals(PlayerCommandKind.ACCEPT, commands.get(0).kind());
        assertEquals("A", commands.get(0).targetVesselId());
    }

    /**
     * Adversarial review: the lock is on the VESSEL a move is for. A line typed in a tab the
     * player owns can be addressed to another vessel by name; if the Assistant is negotiating
     * with that vessel, the player's move and the Assistant's would both answer her one offer.
     */
    @Test
    @Timeout(5)
    void aMoveAddressedFromAnotherTabToAVesselTheAssistantNegotiatesWithIsNotSent() throws Exception {
        DialogueTabView a = open(A, "A");
        open(bus, panel, B, "B", "Stella Maris");
        ownership(true, A); // the player took A over; B is still the Assistant's
        double offerOnB = panel.registry().tab(B).snapshot().lastPlayerOffer();

        ACLMessage accept = MessageFactory.create(ACLMessage.ACCEPT_PROPOSAL);
        accept.setContent(TerminalJson.write(Map.of()));
        processor.returning(new PipelineResult.Routed(accept, "B"));
        HeadlessSwing.onEdt(() -> a.sendText("Stella Maris: deal"));
        flush();
        ACLMessage counter = MessageFactory.create(ACLMessage.PROPOSE);
        counter.setContent(TerminalJson.write(Map.of("move", "propose",
                "money", Map.of("amount", 2000.0, "currency", "EUR"), "duration", 6)));
        processor.returning(new PipelineResult.Routed(counter, "B"));
        HeadlessSwing.onEdt(() -> a.sendText("Stella Maris: 2000 for 6h"));
        flush();

        assertEquals(List.of(), published(PlayerCommandEvent.class),
                "before the review fix both lines reached vessel B, whose tab was locked");
        assertEquals(2, linesSaying(A, "The Assistant is negotiating with Stella Maris \u2014 nothing was sent. "
                + "Press Take over in that tab first."), "each line is answered where it was typed, with what to do");
        assertEquals(offerOnB, panel.registry().tab(B).snapshot().lastPlayerOffer(), 0.0,
                "and the locked tab's offer line was not written to");

        // The same line goes through once B is the player's as well.
        ownership(true, A, B);
        HeadlessSwing.onEdt(() -> a.sendText("Stella Maris: 2000 for 6h"));
        flush();
        List<PlayerCommandEvent> commands = published(PlayerCommandEvent.class);
        assertEquals(1, commands.size());
        assertEquals("B", commands.get(0).targetVesselId());
        assertEquals(PlayerCommandKind.PROPOSE, commands.get(0).kind());
    }

    /**
     * Adversarial review: a line that was already being read when the Assistant took the tab. The
     * check in {@code sendText} passed - the tab was the player's then - so the move is stopped
     * where it would leave.
     */
    @Test
    @Timeout(5)
    void aLineStillBeingReadWhenTheAssistantTakesTheTabIsNotSent() throws Exception {
        ownership(false);
        DialogueTabView view = open(A, "A");
        CompletableFuture<PipelineResult> parse = processor.holdNext();
        HeadlessSwing.onEdt(() -> view.sendText("deal"));
        flush();
        assertEquals(List.of("deal"), processor.texts(), "fixture: the line is with the pipeline");

        ownership(true); // the autopilot is switched on while the line is still being read
        parse.complete(routed(ACLMessage.ACCEPT_PROPOSAL));
        flush();
        flush();

        assertEquals(List.of(), published(PlayerCommandEvent.class),
                "the Assistant answers this offer now; the player's late move must not go out as well");
        assertEquals(1, linesSaying(A, "The Assistant is negotiating here now \u2014 nothing was sent. "
                + "Press Take over first."));
    }

    /** S19 review (PT27): a typed "hint" still being read when the Assistant takes the tab runs no
     * Hint there - the button is hidden in a tab the Assistant negotiates in. */
    @Test
    @Timeout(5)
    void aTypedHintStillBeingReadWhenTheAssistantTakesTheTabRunsNoHint() throws Exception {
        ownership(false);
        DialogueTabView view = open(A, "A");
        CompletableFuture<PipelineResult> parse = processor.holdNext();
        HeadlessSwing.onEdt(() -> view.sendText("hint"));
        flush();

        ownership(true);
        parse.complete(new PipelineResult.Routed(hintRequest()));
        flush();
        flush();

        assertEquals(List.of(), published(it.unige.portcommand.gui.events.HintButtonEvent.class));
        assertEquals(1, linesSaying(A, "The Assistant is negotiating here now \u2014 nothing was sent. "
                + "Press Take over first."));
    }

    private static ACLMessage hintRequest() {
        ACLMessage msg = MessageFactory.create(ACLMessage.REQUEST);
        msg.setContent(TerminalJson.write(Map.of(it.unige.portcommand.nlp.NLPPipeline.HINT_REQUEST_KEY, true)));
        return msg;
    }

    // ==================== Take over ====================

    @Test
    @Timeout(5)
    void takeOverAsksTheAssistantOnceAndTheTabStaysLockedUntilItAnswers() throws Exception {
        ownership(true);
        DialogueTabView viewA = open(A, "A");
        DialogueTabView viewB = open(B, "B");

        click(viewA, "Take over");

        assertEquals(List.of(new TakeOverEvent(A)), published(TakeOverEvent.class),
                "exactly one request, naming this dialogue");
        List<DialogueTabModel.ChatEntry> transcript = transcript(A);
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.SYSTEM,
                "→ Taking over from the Assistant."), transcript.get(transcript.size() - 1));
        assertEquals(DialogueTabView.TAKING_OVER_LINE, "→ Taking over from the Assistant.");
        // The tab does NOT unlock itself: the Assistant may have a move a moment from being sent.
        assertLocked(viewA, "after the click, before the Assistant's answer");
        assertFalse(viewA.buttonForTest("Take over").isEnabled(), "the click is seen to have registered");

        // Pressed again — and again after the Assistant wrote a line of its own in between, so
        // the take-over line is no longer the transcript's last: still one request, one line.
        click(viewA, "Take over");
        bus.publish(new AssistantChatEvent(A, "Assistant (autopilot): countered at €1580."));
        flush();
        click(viewA, "Take over");
        assertEquals(1, published(TakeOverEvent.class).size(), "the request is not repeated while it is pending");
        assertEquals(1, linesSaying(A, "→ Taking over from the Assistant."), "and the line is written once");
        assertLocked(viewA, "still waiting");

        // The other tab was not touched.
        assertLocked(viewB, "the tab next to it");
        assertTrue(viewB.buttonForTest("Take over").isEnabled());
        assertEquals(0, linesSaying(B, "→ Taking over from the Assistant."));

        // A statement that does not name this dialogue frees nothing.
        ownership(true, B);
        assertLocked(viewA, "the Assistant freed another tab, not this one");
        assertPlayers(viewB, "the other tab");

        // The Assistant's answer: the dialogue is listed as taken over. Only now is the tab free.
        ownership(true, A, B);
        assertPlayers(viewA, "after the Assistant's answer");
        assertEquals(1, linesSaying(A, "→ Taking over from the Assistant."));

        // And it is the player's in earnest: a send goes out.
        processor.returning(routed(ACLMessage.REJECT_PROPOSAL));
        click(viewA, "Reject");
        assertEquals(List.of("no"), processor.texts());
        assertEquals(List.of(PlayerCommandKind.REJECT),
                published(PlayerCommandEvent.class).stream().map(PlayerCommandEvent::kind).toList());
    }

    @Test
    @Timeout(5)
    void aSecondLockOfTheSameTabCanBeTakenOverAgain() throws Exception {
        // The "already asked" memory belongs to one lock, not to the tab: if the tab is the
        // player's and is locked again later, Take over works again.
        ownership(true);
        DialogueTabView view = open(A, "A");
        click(view, "Take over");
        ownership(false); // the switch went off: the tab is the player's without any take-over
        assertPlayers(view, "autopilot off");

        ownership(true);
        assertLocked(view, "locked again");
        assertTrue(view.buttonForTest("Take over").isEnabled(), "a new lock, a new request to make");
        click(view, "Take over");

        assertEquals(List.of(new TakeOverEvent(A), new TakeOverEvent(A)), published(TakeOverEvent.class));
    }

    // ==================== a closed tab ====================

    @Test
    @Timeout(5)
    void aClosedTabShowsNoMarkerRowAndBehavesLikeAnyClosedTab() throws Exception {
        ownership(true);
        DialogueTabView view = open(A, "A");
        assertLocked(view, "setup");

        bus.publish(new NegotiationClosedEvent(A, "A", Deal.Outcome.TIMEOUT));
        flush();

        assertFalse(view.markerRowVisibleForTest(), "nobody is negotiating in a finished negotiation");
        assertFalse(view.hintRowVisibleForTest());
        // S18b (PT6): the closed-tab look, whoever was negotiating — all four buttons greyed.
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest());
        for (String label : List.of("Send", "Hint", "Accept", "Reject")) {
            assertFalse(view.buttonForTest(label).isEnabled(), label);
        }
        assertFalse(view.inputFieldForTest().isEditable());
        int before = transcript(A).size();
        click(view, "Accept");
        assertEquals(before, transcript(A).size(), "a greyed button writes nothing");
        assertEquals(List.of(), published(TakeOverEvent.class));
        assertEquals(List.of(), published(PlayerCommandEvent.class));
    }

    // ==================== the marker row on screen ====================

    private static List<Component> withDescendants(Component root) {
        List<Component> all = new ArrayList<>();
        all.add(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                all.addAll(withDescendants(child));
            }
        }
        return all;
    }

    @Test
    @Timeout(5)
    void theMarkerRowSitsBetweenTheTranscriptAndTheInputAndFitsTheColumnsMinimumWidth() throws Exception {
        ownership(true);
        DialogueTabView view = open(bus, panel, A, "A", "Santa Lucia della Lanterna");

        HeadlessSwing.onEdt(() -> {
            int minimumWidth = view.getMinimumSize().width;
            assertTrue(minimumWidth <= ChatPanel.MIN_COLUMN_WIDTH,
                    "a locked tab's minimum width is " + minimumWidth + " px, over the column's "
                            + ChatPanel.MIN_COLUMN_WIDTH);
            HeadlessSwing.layOut(view, ChatPanel.MIN_COLUMN_WIDTH, 480);

            Component row = HeadlessSwing.byName(view, DialogueTabView.MARKER_ROW_NAME);
            assertNotNull(row, "the marker row is found by its name");
            Rectangle rowBounds = HeadlessSwing.boundsIn(row, view);
            Rectangle input = HeadlessSwing.boundsIn(view.inputFieldForTest(), view);
            Rectangle accept = HeadlessSwing.boundsIn(view.buttonForTest("Accept"), view);
            JScrollPane scroll = null;
            for (Component child : view.getComponents()) {
                if (child instanceof JScrollPane pane) {
                    scroll = pane;
                }
            }
            assertNotNull(scroll, "setup: the transcript's scroll pane");
            Rectangle transcript = HeadlessSwing.boundsIn(scroll, view);

            // Top to bottom: transcript, marker row, input row, action row.
            assertTrue(rowBounds.height > 0 && rowBounds.width > 0, "the row takes room: " + rowBounds);
            assertTrue(transcript.y + transcript.height <= rowBounds.y,
                    "the row is under the transcript: " + transcript + " / " + rowBounds);
            assertTrue(rowBounds.y + rowBounds.height <= input.y,
                    "and directly above the input row: " + rowBounds + " / " + input);
            assertTrue(input.y + input.height <= accept.y, "which is above the action row: " + input + " / " + accept);

            // Laid out AT the minimum width, the button is whole and the text is not cut.
            JButton takeOver = view.buttonForTest("Take over");
            Rectangle button = HeadlessSwing.boundsIn(takeOver, view);
            assertTrue(button.width >= takeOver.getPreferredSize().width && button.height > 0,
                    "Take over is squeezed: " + button);
            assertTrue(button.x >= 0 && button.x + button.width <= ChatPanel.MIN_COLUMN_WIDTH,
                    "Take over is cut off: " + button);
            assertTrue(rowBounds.contains(button), "and it is inside its row: " + rowBounds + " / " + button);
            JLabel label = null;
            for (Component part : withDescendants(row)) {
                if (part instanceof JLabel found) {
                    label = found;
                }
            }
            assertNotNull(label, "setup: the row's label");
            assertEquals("Assistant is negotiating…", label.getText());
            assertTrue(label.getWidth() >= label.getPreferredSize().width,
                    "the marker text must not be cut at the minimum width: it got " + label.getWidth()
                            + " px and needs " + label.getPreferredSize().width);
            assertEquals(0, label.getMinimumSize().width, "yet it is the part that gives way in a narrower tab");

            // Nothing in the row — nor in the hidden hint row — can take the keyboard focus from
            // the input, the two buttons included.
            Component hintRow = HeadlessSwing.byName(view, DialogueTabView.HINT_ROW_NAME);
            assertNotNull(hintRow, "the hint row exists (hidden) and is found by its name");
            List<Component> parts = new ArrayList<>(withDescendants(row));
            parts.addAll(withDescendants(hintRow));
            for (Component part : parts) {
                assertFalse(part.isFocusable(), part.getClass().getSimpleName() + " in a row above the input is focusable");
            }
        });
    }

    @Test
    @Timeout(5)
    void aTabThePlayerOwnsIsLaidOutExactlyAsItWasBeforeTheRowsExisted() throws Exception {
        // Both rows hidden: they take no room at all — the input row sits right under the
        // transcript's gap, as it did before S17a.
        ownership(false);
        DialogueTabView view = open(A, "A");

        HeadlessSwing.onEdt(() -> {
            HeadlessSwing.layOut(view, ChatPanel.MIN_COLUMN_WIDTH, 480);
            JScrollPane scroll = null;
            for (Component child : view.getComponents()) {
                if (child instanceof JScrollPane pane) {
                    scroll = pane;
                }
            }
            assertNotNull(scroll, "setup: the transcript's scroll pane");
            Rectangle transcript = HeadlessSwing.boundsIn(scroll, view);
            Rectangle input = HeadlessSwing.boundsIn(view.inputFieldForTest(), view);
            Rectangle send = HeadlessSwing.boundsIn(view.buttonForTest("Send"), view);
            int inputRowTop = Math.min(input.y, send.y);
            assertEquals(4, inputRowTop - (transcript.y + transcript.height),
                    "only the tab's own 4 px gap lies between the transcript and the input row");
        });
    }
}
