package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.HintCard;
import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.AutopilotOwnershipEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.model.DialogueTabModel;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.ParseProvenance;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21, decision A3; playtest item PT13): the hint card in a chat tab.
 *
 * <p>A Hint now arrives with its recommendation as data ({@link HintCard}). The tab shows the
 * hint's sentence with a details line under it, and — directly above the input — a row saying
 * {@code Assistant suggests: …} with one button. Pinned here: {@code Use this} sends exactly
 * {@code deal}, {@code no} or {@code <price> for <hours>h} down the same path as typed text, once;
 * the model's rewording keeps the details and the card; a vessel counter turns the button into
 * {@code Outdated — press Hint again}, which sends nothing; a new Hint makes it usable again; and
 * a closed tab, or one the Assistant is negotiating in, shows no row.
 *
 * <p>Headless, the {@code ChatPanelTest} idiom; what is "sent" is read off the
 * {@link ChatInputProcessor} double, as the Accept and Reject tests there do. Every test states
 * the ownership it runs under instead of leaning on the settings default.
 */
class ChatPanelHintCardTest {

    private static final String A = "nego-A";
    private static final String DETAILS = "EV €640 · chance 62% · R7 ✓";
    private static final String SUGGESTION = "counter at €1580 for 6h";
    private static final String TEMPLATE = "Counter at €1580 for 6h.";

    /** Records every text it is asked to read and answers with whatever the test last set. */
    private static final class RecordingProcessor implements ChatInputProcessor {
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private volatile PipelineResult result = new PipelineResult.Error("unset");

        void returning(PipelineResult next) {
            this.result = next;
        }

        List<String> texts() {
            return List.copyOf(texts);
        }

        @Override
        public CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx) {
            texts.add(text);
            return CompletableFuture.completedFuture(result);
        }
    }

    private final EventBus bus = new EventBus();
    private final RecordingProcessor processor = new RecordingProcessor();
    private final ChatPanel panel = new ChatPanel(bus, processor);
    private DialogueTabView view;

    /** Vessel A hails at €1500 for 6h (round 1), in a tab the player owns. */
    @BeforeEach
    void openThePlayersTab() throws Exception {
        ownership(false);
        vesselOffers(1, 1500.0);
        view = panel.viewForTest(A);
        assertNotNull(view, "setup: the tab must have opened");
    }

    // --- driving the panel ---------------------------------------------------------------------

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** The vessel's opening (round 1) or a later counter: €{@code vesselOffer} for 6h. */
    private void vesselOffers(int round, double vesselOffer) throws Exception {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("A", "cargo_vessel", 9.0, 150.0, 30000,
                "general_cargo", "berth_1", 6, vesselOffer, 0.0, round, 0.0, false, "Santa Lucia");
        bus.publish(new NegotiationOpenedEvent(A, snapshot));
        flush();
    }

    private void ownership(boolean autopilotOn, String... takenOver) throws Exception {
        bus.publish(new AutopilotOwnershipEvent(autopilotOn, Set.of(takenOver)));
        flush();
    }

    /** A card made for the offer on the table when the tab opened: round 1, the vessel at €1500. */
    private static HintCard card(String action, double price, String suggestion) {
        return new HintCard(action, price, 6, 1, 1500.0, suggestion, DETAILS);
    }

    private static HintCard counterCard() {
        return card(RecommendationCandidate.COUNTER, 1580.4, SUGGESTION);
    }

    /** The Assistant answers Hint press {@code hintId} with its template and {@code card}. */
    private void hint(long hintId, String text, HintCard card) throws Exception {
        bus.publish(AssistantChatEvent.hintTemplate(A, hintId, text, card));
        flush();
    }

    private static void click(DialogueTabView view, String label) throws Exception {
        HeadlessSwing.onEdt(() -> view.clickForTest(label));
        flush();
    }

    private <T extends Event> List<T> published(Class<T> type) {
        return EventBusProbe.published(bus).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private List<DialogueTabModel.ChatEntry> transcript() {
        return panel.registry().tab(A).transcript();
    }

    private List<String> playerBubbles() {
        return transcript().stream().filter(e -> e.speaker() == DialogueTabModel.Speaker.PLAYER)
                .map(DialogueTabModel.ChatEntry::text).toList();
    }

    private static ACLMessage acl(int performative, Map<String, Object> content) {
        ACLMessage msg = MessageFactory.create(performative);
        msg.setContent(TerminalJson.write(content));
        return msg;
    }

    // ==================== the card on screen ====================

    @Test
    @Timeout(5)
    void aHintWithACardShowsItsTextItsDetailsAndTheRowAboveTheInput() throws Exception {
        assertFalse(view.hintRowVisibleForTest(), "setup: no row before a Hint");
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest());

        hint(11L, TEMPLATE, counterCard());

        // The transcript: the sentence, and under it — on the same entry — the details line.
        List<DialogueTabModel.ChatEntry> transcript = transcript();
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT, TEMPLATE, DETAILS),
                transcript.get(transcript.size() - 1));
        // (Read off the rendered document by their plain-ASCII parts: what is pinned here is that
        // both are drawn and in which order, not how the text pane stores a euro sign.)
        String shown = view.transcriptTextForTest();
        assertTrue(shown.contains("Counter at"), shown);
        assertTrue(shown.contains("chance 62%"), "the details are on screen: " + shown);
        assertTrue(shown.indexOf("Counter at") < shown.indexOf("chance 62%"), "under the sentence: " + shown);

        // The row: what is suggested, and one button to do it.
        assertTrue(view.hintRowVisibleForTest());
        assertEquals("Assistant suggests: counter at €1580 for 6h", view.suggestionTextForTest());
        assertEquals(List.of("Use this", "Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest(),
                "Use this joins the buttons on offer, above the input row");
        assertTrue(view.buttonForTest("Use this").isEnabled());
        assertEquals(DialogueTabView.USE_THIS_LABEL, "Use this");
        assertFalse(view.markerRowVisibleForTest(), "the tab is the player's: no marker row");
        assertEquals(List.of(), processor.texts(), "showing a card sends nothing");
    }

    @Test
    @Timeout(5)
    void aHintWithoutACardShowsItsTextAndNoRow() throws Exception {
        bus.publish(AssistantChatEvent.hintTemplate(A, 11L, "Accept their offer (template)"));
        flush();

        assertTrue(view.transcriptTextForTest().contains("(template)"));
        assertFalse(view.hintRowVisibleForTest());
        assertEquals("", view.suggestionTextForTest());
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest());
    }

    @Test
    @Timeout(5)
    void theModelsRewordingKeepsTheDetailsAndTheCard() throws Exception {
        hint(11L, TEMPLATE, counterCard());
        int entries = transcript().size();

        bus.publish(AssistantChatEvent.hintRevision(A, 11L, "Hold out for a little more (model)"));
        flush();

        List<DialogueTabModel.ChatEntry> transcript = transcript();
        assertEquals(entries, transcript.size(), "added under the hint, not appended");
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT,
                TEMPLATE, DETAILS, "Hold out for a little more (model)", ""), transcript.get(transcript.size() - 1),
                "S19 (PT25): the sentence and its details stay; the model's words are the Why");
        String shown = view.transcriptTextForTest();
        assertTrue(shown.contains("Counter at") && shown.indexOf("Counter at") < shown.indexOf("Why: Hold out"),
                "the short line first, the Why under it: " + shown);
        assertTrue(shown.indexOf("(model)") < shown.indexOf("chance 62%"),
                "the details are still drawn, under the new sentence: " + shown);
        assertTrue(view.hintRowVisibleForTest(), "and the row still offers the same card");
        assertEquals("Assistant suggests: counter at €1580 for 6h", view.suggestionTextForTest());
        assertTrue(view.buttonForTest("Use this").isEnabled());
    }

    // ==================== Use this ====================

    @Test
    @Timeout(5)
    void useThisSendsACounterAsItsShownPriceAndHoursThroughThePipelineOnce() throws Exception {
        hint(11L, TEMPLATE, counterCard()); // the algorithm's price is €1580.40; every text shows €1580
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of(
                "money", Map.of("amount", 1580.0, "currency", "EUR"), "duration", 6)), null, ParseProvenance.DCG));
        int before = transcript().size();

        click(view, "Use this");

        assertEquals(List.of("1580 for 6h"), processor.texts(), "exactly what the row says, as a player would type it");
        // Everything a typed move gets: the bubble with its parse note, the command, the echo.
        List<DialogueTabModel.ChatEntry> transcript = transcript();
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.PLAYER, "1580 for 6h", "(DCG)"),
                transcript.get(before));
        assertEquals(new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.SYSTEM, "→ Demanding €1,580 for 6h."),
                transcript.get(before + 1));
        List<PlayerCommandEvent> commands = published(PlayerCommandEvent.class);
        assertEquals(1, commands.size());
        assertEquals(PlayerCommandKind.PROPOSE, commands.get(0).kind());
        assertEquals("A", commands.get(0).targetVesselId());
        assertEquals(1580.0, ((Number) commands.get(0).content().get("price")).doubleValue(), 0.001);
        assertEquals(6, ((Number) commands.get(0).content().get("hours")).intValue());

        // One card, one send: the row is gone, so there is nothing to press a second time.
        assertFalse(view.hintRowVisibleForTest(), "the card is used up");
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest());
        assertEquals(List.of("1580 for 6h"), playerBubbles());
        // The hint itself stays in the transcript, details and all.
        assertTrue(transcript.contains(
                new DialogueTabModel.ChatEntry(DialogueTabModel.Speaker.ASSISTANT, TEMPLATE, DETAILS)));
    }

    @Test
    @Timeout(5)
    void useThisSendsDealForARecommendationToAccept() throws Exception {
        hint(11L, "Accept their offer of €1500 for 6h.",
                card(RecommendationCandidate.ACCEPT, 1500.0, "accept €1500 for 6h"));
        assertEquals("Assistant suggests: accept €1500 for 6h", view.suggestionTextForTest());
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));

        click(view, "Use this");

        assertEquals(List.of("deal"), processor.texts());
        assertEquals(List.of("deal"), playerBubbles(), "the bubble shows the word that was sent");
        assertEquals(List.of(PlayerCommandKind.ACCEPT),
                published(PlayerCommandEvent.class).stream().map(PlayerCommandEvent::kind).toList());
        assertFalse(view.hintRowVisibleForTest());
    }

    @Test
    @Timeout(5)
    void useThisSendsNoForARecommendationToSayNo() throws Exception {
        // Decision A3: for a "no" recommendation the button sends "no".
        hint(11L, "Say no — costs (berth time, tugs, customs) are higher than this offer.",
                card(RecommendationCandidate.REJECT_SILENT, 0.0, "say no"));
        assertEquals("Assistant suggests: say no", view.suggestionTextForTest());
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.REJECT_PROPOSAL, Map.of())));

        click(view, "Use this");

        assertEquals(List.of("no"), processor.texts());
        assertEquals(List.of("no"), playerBubbles());
        assertEquals(List.of(PlayerCommandKind.REJECT),
                published(PlayerCommandEvent.class).stream().map(PlayerCommandEvent::kind).toList());
        assertFalse(view.hintRowVisibleForTest());
    }

    @Test
    @Timeout(5)
    void aUsedCardStaysUsedWhenTheModelsRewordingArrivesAfterwards() throws Exception {
        // The model's text can land seconds after the template — after the player already acted.
        hint(11L, TEMPLATE, counterCard());
        processor.returning(new PipelineResult.Error("unused"));
        click(view, "Use this");
        assertFalse(view.hintRowVisibleForTest(), "setup: used");

        bus.publish(AssistantChatEvent.hintRevision(A, 11L, "Hold out for a little more (model)"));
        flush();

        assertFalse(view.hintRowVisibleForTest(), "a rewording must not hand the same advice out twice");
        assertEquals(List.of("1580 for 6h"), processor.texts());
    }

    // ==================== a stale card ====================

    @Test
    @Timeout(5)
    void aVesselCounterOutdatesTheCardAndANewHintMakesItUsableAgain() throws Exception {
        hint(11L, TEMPLATE, counterCard());
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.ACCEPT_PROPOSAL, Map.of())));

        vesselOffers(2, 1650.0); // the vessel countered while the card was on screen

        assertTrue(view.hintRowVisibleForTest(), "the row stays — it says why the button is dead");
        assertEquals("Assistant suggests: counter at €1580 for 6h", view.suggestionTextForTest());
        assertEquals(List.of("Outdated — press Hint again", "Send", "Hint", "Accept", "Reject"),
                view.buttonLabelsForTest());
        assertEquals(DialogueTabView.OUTDATED_LABEL, "Outdated — press Hint again");
        JButton outdated = view.buttonForTest("Outdated — press Hint again");
        assertFalse(outdated.isEnabled(), "advice about an offer that is gone cannot be sent");
        click(view, "Outdated — press Hint again"); // a click on a disabled button
        assertEquals(List.of(), processor.texts(), "nothing was sent");
        assertEquals(List.of(), published(PlayerCommandEvent.class));
        assertEquals(List.of(), playerBubbles());

        // The player presses Hint again; the new card answers the offer now on the table.
        hint(12L, "Accept their offer of €1650 for 6h.", new HintCard(RecommendationCandidate.ACCEPT, 1650.0,
                6, 2, 1650.0, "accept €1650 for 6h", "EV €560 · chance 100% · R7 ✓"));

        assertEquals(List.of("Use this", "Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest());
        assertTrue(view.buttonForTest("Use this").isEnabled());
        assertEquals("Assistant suggests: accept €1650 for 6h", view.suggestionTextForTest());
        click(view, "Use this");
        assertEquals(List.of("deal"), processor.texts(), "and it sends the NEW card's move");
    }

    @Test
    @Timeout(5)
    void aChangedVesselOfferAloneOutdatesTheCardEvenInTheSameRound() throws Exception {
        hint(11L, TEMPLATE, counterCard());

        vesselOffers(1, 1525.0); // same round number, another price

        assertEquals("Outdated — press Hint again", view.buttonLabelsForTest().get(0));
        assertFalse(view.buttonForTest("Outdated — press Hint again").isEnabled());
    }

    @Test
    @Timeout(5)
    void aClickThatFindsTheCardStaleSendsNothing() throws Exception {
        // The freshness is asked again at the click itself, not trusted from the last render:
        // here the tab's model has moved on and the tab has not been re-rendered yet.
        hint(11L, TEMPLATE, counterCard());
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of("price", 1580, "hours", 6))));
        HeadlessSwing.onEdt(() -> {
            DialogueTabModel tab = panel.registry().tab(A);
            tab.updateSnapshot(new WalkInDialogueSnapshot("A", "cargo_vessel", 9.0, 150.0, 30000,
                    "general_cargo", "berth_1", 6, 1650.0, 0.0, 2, 0.0, false, "Santa Lucia"));
            assertTrue(view.buttonForTest("Use this").isEnabled(), "setup: the button still reads Use this");

            view.clickForTest("Use this");
        });
        flush();

        assertEquals(List.of(), processor.texts(), "the click found the card stale and sent nothing");
        assertEquals(List.of(), published(PlayerCommandEvent.class));
        assertEquals(List.of(), playerBubbles());
        assertEquals("Outdated — press Hint again", view.buttonLabelsForTest().get(0),
                "and the tab now says so");
        assertTrue(panel.registry().tab(A).hintCard().isPresent(), "the card was not used up by the dead click");
    }

    // ==================== when there is no row ====================

    @Test
    @Timeout(5)
    void aClosedTabShowsNeitherRow() throws Exception {
        hint(11L, TEMPLATE, counterCard());
        assertTrue(view.hintRowVisibleForTest(), "setup");

        bus.publish(new NegotiationClosedEvent(A, "A", Deal.Outcome.DEAL));
        flush();

        assertFalse(view.hintRowVisibleForTest(), "no advice to act on once the negotiation is over");
        assertFalse(view.markerRowVisibleForTest());
        assertEquals(List.of("Send", "Hint", "Accept", "Reject"), view.buttonLabelsForTest(),
                "a closed tab: all four greyed since S18b (PT6)");
        assertFalse(view.buttonForTest("Hint").isEnabled());

        // Not even if the Assistant is said to own everything.
        ownership(true);
        assertFalse(view.hintRowVisibleForTest());
        assertFalse(view.markerRowVisibleForTest());
    }

    @Test
    @Timeout(5)
    void theRowGivesWayToTheMarkerWhileTheAssistantNegotiatesAndComesBackWithTheTab() throws Exception {
        hint(11L, TEMPLATE, counterCard());
        processor.returning(new PipelineResult.Routed(acl(ACLMessage.PROPOSE, Map.of("price", 1580, "hours", 6))));

        ownership(true); // the autopilot was switched on

        assertFalse(view.hintRowVisibleForTest(), "the tab offers the player nothing to send");
        assertTrue(view.markerRowVisibleForTest());
        assertEquals(List.of("Take over", "Send", "Accept", "Reject"), view.buttonLabelsForTest());

        ownership(true, A); // the player took the tab over
        assertTrue(view.hintRowVisibleForTest(), "the unused card is the player's to use again");
        assertFalse(view.markerRowVisibleForTest());
        click(view, "Use this");
        assertEquals(List.of("1580 for 6h"), processor.texts());
    }

    // ==================== the row on screen ====================

    @Test
    @Timeout(5)
    void theHintRowFitsTheColumnsMinimumWidthEvenWithItsLongestButton() throws Exception {
        // The widest the row gets: a long suggestion beside "Outdated — press Hint again". (The
        // details are plain ASCII here on purpose: this test LAYS the transcript out, and a glyph
        // outside the text pane's font — the check mark — would send Java2D looking through the
        // system's fallback fonts inside a five-second test.)
        hint(11L, TEMPLATE, new HintCard(RecommendationCandidate.COUNTER, 12950.0, 6, 1, 1500.0,
                "counter at €12950 for 24h, which is well above what this vessel last offered",
                "EV 640, chance 62%, R7 ok"));
        vesselOffers(2, 1650.0);
        assertEquals("Outdated — press Hint again", view.buttonLabelsForTest().get(0), "setup: the long button");

        HeadlessSwing.onEdt(() -> {
            int minimumWidth = view.getMinimumSize().width;
            assertTrue(minimumWidth <= ChatPanel.MIN_COLUMN_WIDTH,
                    "a tab with a hint row has a minimum width of " + minimumWidth + " px, over the column's "
                            + ChatPanel.MIN_COLUMN_WIDTH);
            HeadlessSwing.layOut(view, ChatPanel.MIN_COLUMN_WIDTH, 480);

            Component row = HeadlessSwing.byName(view, DialogueTabView.HINT_ROW_NAME);
            assertNotNull(row, "the hint row is found by its name");
            Rectangle rowBounds = HeadlessSwing.boundsIn(row, view);
            Rectangle input = HeadlessSwing.boundsIn(view.inputFieldForTest(), view);
            assertTrue(rowBounds.height > 0 && rowBounds.y + rowBounds.height <= input.y,
                    "the row sits directly above the input row: " + rowBounds + " / " + input);

            // The button keeps its size and stays inside the column; the label is what gives way.
            JButton button = view.buttonForTest("Outdated — press Hint again");
            Rectangle buttonBounds = HeadlessSwing.boundsIn(button, view);
            assertTrue(buttonBounds.width >= button.getPreferredSize().width && buttonBounds.height > 0,
                    "the button is squeezed: " + buttonBounds);
            assertTrue(buttonBounds.x >= 0 && buttonBounds.x + buttonBounds.width <= ChatPanel.MIN_COLUMN_WIDTH,
                    "the button is cut off: " + buttonBounds);
            JLabel label = null;
            for (Component part : ((Container) row).getComponents()) {
                if (part instanceof JLabel found) {
                    label = found;
                }
            }
            assertNotNull(label, "setup: the row's label");
            assertTrue(label.getWidth() > 0 && label.getWidth() < label.getPreferredSize().width,
                    "setup: at this width the long suggestion does not fit (" + label.getWidth() + " of "
                            + label.getPreferredSize().width + " px), so it is cut with an ellipsis");
            assertTrue(label.getX() + label.getWidth() <= button.getX(), "and never runs under the button");
            assertEquals(label.getText(), label.getToolTipText(), "the tooltip has the suggestion whole");
            assertEquals(0, label.getMinimumSize().width);

            // The rest of the tab is where it always is: the four buttons, whole.
            for (String name : List.of("Send", "Hint", "Accept", "Reject")) {
                JButton other = view.buttonForTest(name);
                Rectangle bounds = HeadlessSwing.boundsIn(other, view);
                assertTrue(bounds.width >= other.getPreferredSize().width
                                && bounds.x >= 0 && bounds.x + bounds.width <= ChatPanel.MIN_COLUMN_WIDTH,
                        name + " is squeezed or cut off: " + bounds);
            }
        });
    }
}
