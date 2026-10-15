package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.TakeOverEvent;
import it.unige.portcommand.gui.model.DialogueTabModel;
import it.unige.portcommand.gui.model.ChatReplies;
import it.unige.portcommand.gui.model.Euros;
import it.unige.portcommand.gui.model.HintRow;
import it.unige.portcommand.gui.model.InputExamples;
import it.unige.portcommand.gui.model.NegotiationTabRegistry;
import it.unige.portcommand.gui.model.OfferLine;
import it.unige.portcommand.gui.model.ParseTag;
import it.unige.portcommand.gui.model.PatienceCounterModel;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.InputSafety;
import it.unige.portcommand.nlp.NLPPipeline;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.util.ConversationClock;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;

/**
 * One walk-in dialogue's tab: the offer line and the live counter, the transcript, the input row
 * and the three buttons under it. Entirely self-contained — it never needs to reach
 * into a SIBLING tab's view, because every rendering effect of a send (the player's
 * own bubble, a clarification prompt, an error/system notice) is local to the tab the
 * player typed into; only the resulting {@link PlayerCommandEvent}'s {@code
 * targetVesselId} may point at a different vessel (the 16-M2 vocative case), and
 * that's just data on the published event; {@code ChatPanel} needs no direct
 * involvement in ANY tab's send flow, only in creating tabs and forwarding
 * server-originated events (opened/closed/assistant-chat) to {@link #refresh}.
 *
 * <p><b>S15 (2026-10-12) — the layout, top to bottom.</b>
 * <ul>
 *   <li>The offer line on two rows ({@link OfferLine}: {@code Their offer €1436 · 6h} over
 *       {@code Your offer €1900 · 4h · round 2 of 6}) with the bare {@code m:ss} counter to its
 *       right. One row was too wide for the chat column at its minimum width, and the part an
 *       ellipsis would have eaten was the round.</li>
 *   <li>The transcript. A player message the pipeline routed carries a small grey note saying
 *       which stage read it ({@code (DCG)} / {@code (Rasa 0.97)}).</li>
 *   <li>{@code [input][Send]}, then {@code [Hint][Accept][Reject]} right-aligned on a row of their
 *       own. The empty input shows a worked example computed from THIS vessel's standing offer
 *       ({@link InputExamples}), as a grey prompt that is never part of the field's text.</li>
 * </ul>
 *
 * <p><b>Accept and Reject type for the player.</b> They send the literal words {@code deal} and
 * {@code no} down the same path as typed text: the NLP pipeline, the world-identity guard, the
 * "→ …" echo and the parse note. The five-button quick-action bar they replace published its
 * commands directly, skipping all four — and three of its buttons (make an offer, check status,
 * talk to the assistant) only duplicated the input field, a typed {@code status}, and Hint.
 *
 * <p><b>S17a (ADR-21) — who is negotiating here, and what the Assistant suggested.</b> Two rows
 * may appear between the transcript and the input row, the first above the second; they are never
 * both on screen, because each belongs to one answer to "whose tab is this?":
 * <ul>
 *   <li><b>The Assistant's</b> (the autopilot is on and the player has not taken this negotiation
 *       over — {@code AutopilotOwnershipEvent}, read through {@link #assistantOwns}): a marker row,
 *       {@code Assistant is negotiating…} with a {@code Take over} button, and the tab is locked.
 *       The input swallows keystrokes, Send, Accept and Reject are disabled, Hint and the example
 *       are gone, and {@link #sendText} sends nothing. Before S17a the player and the autopilot
 *       could both answer the same offer, and the deal closed at whichever price reached the
 *       vessel first (decision A2). {@code Take over} asks the Assistant to let go
 *       ({@link TakeOverEvent}); the tab does not unlock itself — it unlocks when the Assistant's
 *       next ownership statement says the tab is the player's, so there is never a moment in
 *       which both think it is theirs.</li>
 *   <li><b>The player's</b>, after a Hint: a hint row, {@code Assistant suggests: counter at
 *       €1580 for 6h} with a {@code Use this} button ({@link HintRow}). Like Accept and Reject the
 *       button types for the player — {@code deal}, {@code no}, or the price and hours — so the
 *       move gets the pipeline, the echo and the parse note. One card, one send. If the vessel has
 *       countered since the Hint, the button reads {@code Outdated — press Hint again} and cannot
 *       be pressed: the advice was about an offer that is no longer on the table (decision A3).</li>
 * </ul>
 * A closed tab shows neither row, and a tab that is the player's with no Hint asked for looks and
 * behaves exactly as it did before S17a.
 */
final class DialogueTabView extends JPanel {

    /** Component name of the live counter, so a test (and the coming redesign) can find it. */
    static final String COUNTER_NAME = "dialogue-counter";
    /** S14: the counter's three colours — normal, under 30 s, under 10 s. S15: aliases of
     * {@link CounterColours}, which the "Waiting for you" list shares; kept for the tests that
     * name them here. */
    static final Color COUNTER_NORMAL = CounterColours.NORMAL;
    static final Color COUNTER_WARNING = CounterColours.WARNING;
    static final Color COUNTER_CRITICAL = CounterColours.CRITICAL;

    /** S15: the four buttons, by the text on them — exactly these, in this reading order. */
    static final String SEND_LABEL = "Send";
    static final String HINT_LABEL = "Hint";
    static final String ACCEPT_LABEL = "Accept";
    static final String REJECT_LABEL = "Reject";

    /** S17a (ADR-21): the marker row of a tab the Assistant is negotiating in — its label, and the
     * button that asks for the tab back. */
    static final String ASSISTANT_NEGOTIATING_TEXT = "Assistant is negotiating…";
    static final String TAKE_OVER_LABEL = "Take over";
    /** S17a (ADR-21): the hint row's one button — while the card still answers the offer on the
     * table, and once the vessel has countered since. */
    static final String USE_THIS_LABEL = "Use this";
    static final String OUTDATED_LABEL = "Outdated — press Hint again";
    /** S17a: the line the transcript gets, once, when the player presses {@link #TAKE_OVER_LABEL}. */
    static final String TAKING_OVER_LINE = "→ Taking over from the Assistant.";
    /** S17a: component names of the two rows, so a test can find them (and tell them apart from
     * the input and action rows, which have none). */
    static final String MARKER_ROW_NAME = "dialogue-assistant-marker";
    static final String HINT_ROW_NAME = "dialogue-hint-row";

    private final String dialogueId;
    /** S15: the vessel this tab talks to, fixed when the tab is built ({@code null} only if the
     * registry had no model for it yet — never the case for a tab {@code ChatPanel} adds). Held
     * here so {@code ChatPanel} can still say whose tab is selected in the moment between the
     * registry sweeping a closed tab's model and the pane dropping its page. */
    private final String vesselId;
    private final NegotiationTabRegistry registry;
    private final EventBus eventBus;
    private final ChatInputProcessor processor;
    private final SimClock simClock;
    /** S17a (ADR-21, decisions A2 and A10): whether the Assistant — not the player — is negotiating
     * in THIS tab, as the Assistant last said ({@code AutopilotOwnershipEvent.assistantOwns}). The
     * parent {@code ChatPanel} keeps that statement and re-renders every tab when a new one
     * arrives; the tab only asks, each time it renders and each time it is about to send. Before
     * S17a this supplier answered "is the autopilot switch on?" from a copy of the Settings toggle
     * the panel kept itself — a second source, which a load left wrong (backlog R-07) and which
     * knew nothing of a single tab the player had taken over. */
    private final BooleanSupplier assistantOwns;
    /** S17a (adversarial review): the same question about ANY dialogue. A line typed here can be
     * addressed to another vessel by name ("Stella Maris: deal"), and the lock that matters then
     * is the one on HER tab, not on this one. */
    private final java.util.function.Predicate<String> assistantOwnsDialogue;
    /** S19 (playtest 5, PT25 / PT33 / PT42): a Hint's details line explains itself on hover. */
    private final JTextPane transcriptPane = new JTextPane() {
        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            String tip = getDocument() instanceof javax.swing.text.html.HTMLDocument html
                    ? TranscriptTips.titleAt(html, viewToModel2D(event.getPoint())) : null;
            return tip == null ? null : TranscriptTips.asTooltip(tip);
        }
    };
    /** S18b (UI tree U7): the transcript's scroll pane, kept so the view can follow its bottom. */
    private final JScrollPane transcriptScroll = new JScrollPane(transcriptPane);
    /**
     * S18b (UI tree U7): whether the transcript follows its newest line. Every refresh used to put
     * the caret at the end (audit C-28), which scrolled a player who was reading higher up back
     * down at each hint revision, counter or settings change — and, done before the layout, could
     * leave the newest line half cut off. Now, as in the comm log: a refresh follows the bottom
     * only while the view is at the bottom, the jump waits for the layout, and the player's own
     * scrolling decides whether the view follows (see {@link #onTranscriptBarAdjusted}).
     */
    private boolean followTail = true;
    /** U7: the scrollbar's extent and maximum at its last report — a change in either is a layout,
     * not the player scrolling. */
    private int lastBarExtent = -1;
    private int lastBarMaximum = -1;
    /** U7, test-only witness: whether the last refresh pinned the view to the bottom. */
    private boolean lastRefreshPinned;
    /**
     * S18b (UI tree U8): true from an Accept or Reject click until the parse of what it sent has
     * come back. Both buttons are disabled meanwhile: a double click used to send the move twice.
     */
    private boolean quickMoveInFlight;
    /**
     * S18b (UI tree U8; adversarial review): true from the moment the move an Accept or Reject
     * click sent reached the vessel side until that side answers this tab - a new round, a refused
     * move ({@link #answerArrived}) or the close. The parse of "deal" is back in milliseconds, well
     * inside a double click, so the buttons waiting for the parse alone let the second click send
     * the move again.
     */
    private boolean quickMoveAwaitingAnswer;
    /** S15: the offer line's two rows — theirs above, yours and the round below. */
    private final JLabel theirOfferLabel = new JLabel();
    private final JLabel yourOfferLabel = new JLabel();
    /** S14 (ADR-18): the real time this vessel will still wait, as {@code m:ss} — a number and a
     * colour, never a label and never an explanation (players discover why vessels differ). */
    private final JLabel counterLabel = new JLabel();
    private final PlaceholderTextField inputField = new PlaceholderTextField();
    private final JButton sendButton = new JButton(SEND_LABEL);
    private final JButton hintButton = new JButton(HINT_LABEL);
    private final JButton acceptButton = new JButton(ACCEPT_LABEL);
    private final JButton rejectButton = new JButton(REJECT_LABEL);
    /** S17a (ADR-21): the marker row — on screen exactly while the Assistant owns this open tab. */
    private final JPanel markerRow = new JPanel(new BorderLayout(4, 0));
    private final JLabel markerLabel = elasticLabel();
    private final JButton takeOverButton = new JButton(TAKE_OVER_LABEL);
    /** S17a: true from the click on {@link #TAKE_OVER_LABEL} until the tab is seen to be the
     * player's again. While it is set the request is not repeated and its transcript line is not
     * written a second time; the button is greyed, so the click is seen to have registered in
     * the moment before the Assistant answers. */
    private boolean takeOverRequested;
    /** S17a (ADR-21): the hint row — on screen while the player owns this open tab and the newest
     * Hint's card has not been used ({@link HintRow}). */
    private final JPanel hintRow = new JPanel(new BorderLayout(4, 0));
    private final JLabel suggestionLabel = elasticLabel();
    /** One button, two texts: {@link #USE_THIS_LABEL} or {@link #OUTDATED_LABEL}. */
    private final JButton useThisButton = new JButton(USE_THIS_LABEL);

    /** S15: the offer line as last rendered ({@code null} before the first render). */
    private OfferLine shownOfferLine;
    /** S15: what the counter shows right now, in milliseconds — empty while it is blank. The
     * "Waiting for you" list reads THIS (through {@code ChatPanel}) rather than the clock, so the
     * list and the tab can never show two different numbers for one vessel. */
    private OptionalLong shownRemainingMillis = OptionalLong.empty();
    /**
     * S15: told whenever a re-render of THIS tab changed what its counter shows. The one-second
     * timer is not the only thing that re-reads the conversation clock — every {@link #refresh()}
     * does (a send, a system line, an Assistant message, and since S17a a change of who is
     * negotiating in the tab — before that, a change of the autopilot setting), and those land
     * between two ticks. Without this the "Waiting for you" list would keep the previous tick's
     * number, and across 0:30 or 0:10 the previous colour, for the rest of that second.
     * ({@code ChatPanel} answers by having EVERY tab re-read, so the list sorts like with like.)
     */
    private final Runnable onCounterChanged;
    /** False while the constructor runs: the tab is not on the strip yet, nobody shows its counter. */
    private boolean built;

    /**
     * @param assistantOwnsDialogue S17a: whether the Assistant is negotiating in the tab of the
     *                      given dialogue right now ({@code AutopilotOwnershipEvent.assistantOwns}).
     *                      Asked on the EDT only: about this tab at every render and before every
     *                      send, and about the ADDRESSEE's tab before a move is dispatched.
     */
    DialogueTabView(String dialogueId, NegotiationTabRegistry registry, EventBus eventBus,
                    ChatInputProcessor processor, SimClock simClock,
                    java.util.function.Predicate<String> assistantOwnsDialogue, Runnable onCounterChanged) {
        super(new BorderLayout(4, 4));
        this.onCounterChanged = onCounterChanged;
        this.dialogueId = dialogueId;
        this.registry = registry;
        this.eventBus = eventBus;
        this.processor = processor;
        this.simClock = simClock;
        this.assistantOwnsDialogue = assistantOwnsDialogue;
        this.assistantOwns = () -> assistantOwnsDialogue.test(dialogueId);
        DialogueTabModel born = registry.tab(dialogueId);
        this.vesselId = born == null ? null : born.vesselId();

        transcriptPane.setContentType("text/html");
        transcriptPane.setEditable(false);
        // S18b (U7): only this view decides where the transcript is scrolled to, not the caret.
        transcriptPane.setCaret(new StillCaret());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(transcriptPane); // S19 (PT25)
        transcriptScroll.getVerticalScrollBar().addAdjustmentListener(e -> onTranscriptBarAdjusted());

        sendButton.addActionListener(e -> onSend());
        inputField.addActionListener(e -> onSend());
        hintButton.addActionListener(e -> onHint());
        // S15: the two buttons send what the player could have typed (see quickMove).
        acceptButton.addActionListener(e -> quickMove(InputExamples.ACCEPT_UTTERANCE, "accept"));
        rejectButton.addActionListener(e -> quickMove(InputExamples.REJECT_UTTERANCE, "reject"));
        takeOverButton.addActionListener(e -> onTakeOver());
        useThisButton.addActionListener(e -> onUseThis());

        // S15: two rows, not one. Four default-margin buttons beside the field left it about
        // 70 px wide at the column's 380 px minimum; on a row of their own the three action
        // buttons also sit away from Send, so a click aimed at Send cannot land on Reject.
        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.add(inputField, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);

        // A horizontal box: the glue pushes the buttons to the right edge, flush with Send above
        // (a right-aligned FlowLayout would stop its own gap short of that edge). A hidden Hint
        // takes no room — BoxLayout skips invisible components, where a GridLayout would leave a
        // hole — and the row keeps its height, so nothing jumps when Hint comes and goes.
        JPanel actionRow = new JPanel();
        actionRow.setLayout(new BoxLayout(actionRow, BoxLayout.X_AXIS));
        actionRow.add(Box.createHorizontalGlue());
        actionRow.add(hintButton);
        actionRow.add(Box.createRigidArea(new Dimension(4, 0)));
        actionRow.add(acceptButton);
        actionRow.add(Box.createRigidArea(new Dimension(4, 0)));
        actionRow.add(rejectButton); // last, and never a default button: it ends the negotiation

        JPanel inputBlock = new JPanel(new BorderLayout(0, 4));
        inputBlock.add(inputRow, BorderLayout.CENTER);
        inputBlock.add(actionRow, BorderLayout.SOUTH);

        // S17a (ADR-21): the marker row and the hint row, directly above the input row — marker
        // first. Each is a label that gives way (see elasticLabel) and ONE button, so neither can
        // widen the tab past the column's 380 px minimum the way a second button would; at that
        // width the label is cut with an ellipsis and the button keeps its size.
        //
        // Neither button can take the keyboard focus (like the comm log's toggles and the top
        // bar's speed toggles): a click on one must not pull the typing focus out of the input,
        // and a button that held the focus at the moment its row is hidden — which is exactly
        // what pressing it leads to — would have AWT hand the focus to whatever it picks next.
        // The labels and the two panels are told so too: nothing in these rows takes focus.
        markerLabel.setText(ASSISTANT_NEGOTIATING_TEXT);
        markerLabel.setToolTipText(ASSISTANT_NEGOTIATING_TEXT);
        takeOverButton.setFocusable(false);
        useThisButton.setFocusable(false);
        buildRow(markerRow, MARKER_ROW_NAME, markerLabel, takeOverButton);
        buildRow(hintRow, HINT_ROW_NAME, suggestionLabel, useThisButton);

        // BorderLayout leaves an invisible child out of both the layout and the sizes, so with
        // both rows hidden this panel is zero high and the input block sits exactly where it did
        // before S17a. The 4 px under a row are the row's own border: the gap comes and goes with it.
        JPanel aboveInput = new JPanel(new BorderLayout());
        aboveInput.add(markerRow, BorderLayout.NORTH);
        aboveInput.add(hintRow, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout());
        south.add(aboveInput, BorderLayout.NORTH);
        south.add(inputBlock, BorderLayout.CENTER);

        // The offer rows give way before anything else does: their box reports no minimum width,
        // so a long line is cut with an ellipsis (the tooltip has it whole) instead of widening
        // the tab — a JTabbedPane's minimum tracks the widest component of ANY of its tabs.
        JPanel offerBox = new JPanel(new GridLayout(2, 1)) {
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, getPreferredSize().height);
            }
        };
        offerBox.add(theirOfferLabel);
        offerBox.add(yourOfferLabel);

        counterLabel.setName(COUNTER_NAME);
        counterLabel.setFont(counterLabel.getFont().deriveFont(Font.BOLD));
        JPanel north = new JPanel(new BorderLayout(8, 0));
        north.add(offerBox, BorderLayout.CENTER);
        north.add(counterLabel, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);
        add(transcriptScroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        refresh();
        built = true;
    }

    /** S15: the dialogue this tab renders. */
    String dialogueId() {
        return dialogueId;
    }

    /** S15: the vessel this tab talks to — still answered after the tab closed (see the field). */
    String vesselId() {
        return vesselId;
    }

    /** Re-renders from the registry's current model for {@link #dialogueId}. Safe to call
     * whenever the model might have changed, for any reason (this tab's own send flow, or
     * an event {@code ChatPanel} routed here). */
    void refresh() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null) {
            return;
        }
        transcriptPane.setText(ChatMessageRenderer.renderTranscript(tab.transcript()));
        // Audit C-28 (2026-07-27): setText left the caret at offset 0 and the stock caret then
        // scrolled the view to the TOP at every message. S18b (UI tree U7): the caret no longer
        // scrolls at all (StillCaret); the view follows the newest line only while the player is
        // at the bottom, and the jump happens after the layout, so that line is shown whole.
        lastRefreshPinned = followTail;
        if (followTail) {
            transcriptPane.setCaretPosition(transcriptPane.getDocument().getLength());
            scrollTranscriptToBottom();
        }
        boolean closed = tab.isClosed();
        showOfferLineAndExample(tab);
        OptionalLong shownBefore = shownRemainingMillis;
        refreshCounter();
        if (built && !shownBefore.equals(shownRemainingMillis)) {
            onCounterChanged.run(); // S15: off the tick — tell whoever repeats this number
        }
        // #9 (checkpoint fix, 2026-07-18): a CLOSED tab keeps its input field ENABLED (hence
        // focusable) but NON-EDITABLE. A non-editable focused field silently swallows typed
        // characters (they no-op and never propagate); the old setEnabled(false) instead dropped
        // focus, and the keystrokes fell through to the comm-log Performative filter combo, whose
        // type-ahead then changed the filter. Send is still disabled and Enter is guarded in
        // sendText, so a closed tab is inert.
        //
        // S17a (ADR-21, decision A2): a tab the Assistant is negotiating in is locked the same
        // way, for the same reason — the field stays enabled and focusable and swallows what is
        // typed — and it shows no example either (PlaceholderTextField paints one only on an
        // editable field: a prompt would invite typing into a field that ignores it). Ownership
        // is asked only of an OPEN tab: once the negotiation is over nobody is negotiating in it,
        // and a closed tab looks the same whoever was.
        boolean locked = !closed && assistantOwns.getAsBoolean();
        boolean playerCanSend = !closed && !locked;
        inputField.setEditable(playerCanSend);
        sendButton.setEnabled(playerCanSend);
        // S18b (playtest 2, PT6): a closed tab greys all four — Send, Hint, Accept and Reject. S15
        // had kept Accept and Reject clickable there, to answer in the transcript that nothing was
        // left to act on; the playtest read clickable buttons as "something is still possible".
        // The offer line and the closing line say what happened. Hint is still hidden, not greyed,
        // while the Assistant owns the tab (S17a): the marker row says why.
        hintButton.setVisible(!locked);
        hintButton.setEnabled(playerCanSend);
        // S17a: Accept and Reject are disabled while the Assistant owns the tab, which explains
        // itself — the marker row right above them says who is negotiating and how to take the
        // tab back. S18b (UI tree U8): and while the move one of them sent is being read.
        acceptButton.setEnabled(playerCanSend && !quickMoveInFlight && !quickMoveAwaitingAnswer);
        rejectButton.setEnabled(playerCanSend && !quickMoveInFlight && !quickMoveAwaitingAnswer);
        if (!locked) {
            takeOverRequested = false; // the tab is the player's (or over): any request is settled
        }
        takeOverButton.setEnabled(!takeOverRequested);
        showRow(markerRow, locked);
        showHintRow(HintRow.of(tab, locked));
    }

    /**
     * S17a (ADR-21, decision A3): the hint row as the model describes it right now. Called from
     * every {@link #refresh()}, so the button turns to {@link #OUTDATED_LABEL} in the same render
     * that shows the vessel's counter in the transcript — the freshness is never a stored flag,
     * it is asked of the card against the tab's current snapshot each time.
     */
    private void showHintRow(HintRow row) {
        if (row.visible()) {
            suggestionLabel.setText(row.suggestion());
            // The whole suggestion, for a column narrow enough to cut the label short.
            suggestionLabel.setToolTipText(row.suggestion());
            useThisButton.setText(row.fresh() ? USE_THIS_LABEL : OUTDATED_LABEL);
            useThisButton.setEnabled(row.fresh());
        }
        showRow(hintRow, row.visible());
    }

    /** S17a: shows or hides one of the two rows above the input, and has the tab laid out again
     * when that changed. Swing asks for a new layout by itself when a component is shown, not
     * when it is hidden — and a hidden row that kept its place would leave a blank band above
     * the input until something else happened to lay the tab out. */
    private void showRow(JPanel row, boolean visible) {
        if (row.isVisible() != visible) {
            row.setVisible(visible);
            revalidate();
            repaint();
        }
    }

    /** S17a: gives one of the two rows above the input its name, its label and its one button.
     * Born hidden; {@link #refresh()} shows whichever applies. */
    private static void buildRow(JPanel row, String name, JLabel label, JButton button) {
        row.setName(name);
        row.setFocusable(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0)); // the gap to the input row below
        row.add(label, BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        row.setVisible(false);
    }

    /** S17a: a label for the rows above the input that gives way before anything else does. Like
     * the offer rows' box it reports no minimum width, so in a narrow column its text is cut with
     * an ellipsis (the tooltip has it whole) and the button beside it keeps its size. */
    private static JLabel elasticLabel() {
        JLabel label = new JLabel() {
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, getPreferredSize().height);
            }
        };
        label.setFocusable(false);
        return label;
    }

    /** Re-renders ONLY the offer line — a refused PROPOSE puts the previous offer back (S13, F-7)
     * without re-rendering the whole HTML transcript. S15: and the input's example with it — a
     * refused FIRST offer leaves the player with no offer standing again, which takes
     * "split the difference" back out of the rotation (the grammar could not parse it). */
    void refreshStrip() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab != null) {
            showOfferLineAndExample(tab);
        }
    }

    /** S15: the two things on the tab that are a pure function of the tab's model — the offer
     * line's two rows, and the example the empty input shows. */
    private void showOfferLineAndExample(DialogueTabModel tab) {
        OfferLine line = OfferLine.of(tab, Settings.load().roundLimit());
        shownOfferLine = line;
        theirOfferLabel.setText(line.theirs());
        yourOfferLabel.setText(line.yoursAndRound());
        // The whole sentence, for a column narrow enough to cut a row short. It says nothing
        // about time: the counter beside it stays a bare number, here as everywhere.
        theirOfferLabel.setToolTipText(line.text());
        yourOfferLabel.setToolTipText(line.text());
        inputField.setPlaceholder(InputExamples.placeholderFor(tab));
    }

    /**
     * S14 (ADR-18): re-reads this dialogue's conversation clock and shows what is left — the one
     * thing on the tab that moves on its own, so {@code ChatPanel}'s one-second Swing timer calls
     * only this. Blank when there is nothing to count: a closed tab, or a conversation the clock
     * does not know (never "0:00", which would read as a timeout that did not happen).
     */
    void refreshCounter() {
        DialogueTabModel tab = registry.tab(dialogueId);
        Optional<ConversationClock.View> view = tab == null || tab.isClosed()
                ? Optional.empty()
                : simClock.conversationClock().view(dialogueId);
        if (view.isEmpty()) {
            counterLabel.setText("");
            shownRemainingMillis = OptionalLong.empty();
            return;
        }
        long remaining = view.get().remainingMillis();
        counterLabel.setText(PatienceCounterModel.text(remaining));
        counterLabel.setForeground(CounterColours.of(PatienceCounterModel.level(remaining)));
        shownRemainingMillis = OptionalLong.of(remaining);
    }

    /** S15: the remaining time the counter last rendered, in milliseconds; empty while the counter
     * is blank. Never re-reads the clock — it is what is on screen, as of the last refresh. A
     * refresh that changes it outside the one-second tick reports that ({@link #onCounterChanged}). */
    OptionalLong shownRemainingMillis() {
        return shownRemainingMillis;
    }

    private void onSend() {
        // The field's TEXT only: the grey example an empty field shows is paint, not text, so
        // Send (or Enter) on a field that merely shows "e.g. 1900 for 6h" sends nothing.
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            sendText(text);
        }
    }

    /** Focuses this tab's input field — {@code ChatPanel} calls it on tab selection so keystrokes
     * always land in the tab the player is looking at (an open tab is ready to type; a CLOSED
     * tab's non-editable field swallows them — #9). No-op when the window is not showing. */
    void focusInput() {
        inputField.requestFocusInWindow();
    }

    /**
     * S15 (2026-10-12): Accept and Reject. The button sends the word the player could have typed
     * ({@code deal} / {@code no}) through {@link #sendText}, so a click gets everything a typed
     * move gets — the pipeline's reading, the world-identity guard, the "→ …" echo, the parse
     * note — and the bubble shows that literal word, because the note under it describes how
     * exactly that text was read. Irreversible, with no confirmation, as the owner asked.
     *
     * <p>On a CLOSED tab nothing is sent: the transcript says so, once (a second click does not
     * stack a second copy under the first). The same goes for an Accept with no offer on the
     * table — no tab is ever opened in that state, but "deal" answering nothing must not go out.
     * S18b (PT6): both buttons are disabled on a closed tab, so the closed branch is a guard now.
     *
     * <p>S18b (UI tree U8): both buttons are disabled from the click until the parse of what it
     * sent has come back ({@link #quickMoveInFlight}) — a double click used to send it twice.
     *
     * @param verb the button's verb, for the closed-tab line ("nothing to accept" / "to reject")
     */
    private void quickMove(String utterance, String verb) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null) {
            return;
        }
        if (tab.isClosed()) {
            appendSystemOnce("This negotiation is closed — there is nothing to " + verb + ".");
            return;
        }
        if (InputExamples.ACCEPT_UTTERANCE.equals(utterance) && !(tab.snapshot().lastVesselOffer() > 0)) {
            appendSystemOnce("There is no offer on the table to accept.");
            return;
        }
        if (quickMoveInFlight || quickMoveAwaitingAnswer) {
            return; // U8: the buttons are disabled; this guards a caller that holds the view
        }
        sendText(utterance, true);
    }

    /**
     * S17a (ADR-21, decision A2): {@code Take over}. Asks the Assistant to hand this negotiation
     * to the player ({@link TakeOverEvent}) and says so in the transcript — once: the request is
     * not repeated while it is pending, and the button is greyed until it is answered.
     *
     * <p>The tab stays locked. It unlocks in the render that follows the Assistant's next
     * {@code AutopilotOwnershipEvent}, if that names this dialogue as taken over; unlocking here
     * would open a window in which the Assistant — which may have a move a moment from being
     * sent — and the player both hold the tab, the very thing the lock exists to stop.
     */
    private void onTakeOver() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null || tab.isClosed() || takeOverRequested || !assistantOwns.getAsBoolean()) {
            return;
        }
        takeOverRequested = true;
        eventBus.publish(new TakeOverEvent(dialogueId));
        appendSystem(TAKING_OVER_LINE); // re-renders: the button greys with the line's arrival
    }

    /**
     * S17a (ADR-21, decision A3): {@code Use this}. Sends what the newest Hint's card recommends,
     * as the words the player could have typed ({@link HintRow#utteranceFor}), through
     * {@link #sendText} — so it gets the pipeline's reading, the world-identity guard, the "→ …"
     * echo and the parse note, exactly as Accept and Reject do.
     *
     * <p>The card is looked at again HERE, not trusted from the last render: a click that finds
     * it stale (or gone, or the tab no longer the player's) sends nothing and re-renders, which
     * is what turns the button to {@link #OUTDATED_LABEL}. And it is marked used BEFORE the send,
     * because the send re-renders — the row must already be gone in that render, or a second
     * click could send the same advice twice.
     */
    private void onUseThis() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null) {
            return;
        }
        HintRow row = HintRow.of(tab, assistantOwns.getAsBoolean());
        if (!row.fresh()) {
            refresh();
            return;
        }
        tab.useHintCard();
        sendText(row.utterance());
    }

    /** Package-private so a headless test can drive a send without simulating real
     * JTextField/ActionEvent plumbing. */
    void sendText(String text) {
        sendText(text, false);
    }

    /** @param fromButton S18b (U8): sent by Accept or Reject, which stay disabled until it is read */
    private void sendText(String text, boolean fromButton) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null || tab.isClosed()) {
            return; // #9: Enter on a closed tab's (non-editable) field must not send.
        }
        if (assistantOwns.getAsBoolean()) {
            // S17a (ADR-21, decision A2): the Assistant is negotiating here. The field is not
            // editable and the buttons are disabled, but Enter still reaches this method (as on a
            // closed tab), and so does any caller that holds the view. Nothing is sent, nothing
            // is echoed, and a draft the player had typed before the lock stays in the field.
            return;
        }
        inputField.setText("");
        quickMoveInFlight |= fromButton;
        // S15: the bubble's place in the transcript — the handle the parse note is written back
        // through once the result arrives (the bubble is on screen long before that).
        int messageIndex = tab.appendPlayerMessage(text);
        refresh(); // S15: also moves the input's example on — it advances on each send

        String typingVesselId = tab.vesselId();
        DialogueCtx ctx = registry.buildCtx(typingVesselId);
        // S8 (D-05 two-step): the registry doubles as the by-vessel context source, so the
        // pipeline can re-focus onto a vocative's ADDRESSEE and re-parse under ITS offer.
        // S10 (bug hunt, hunter C finding 1): the hook is invoked on the PIPELINE executor
        // thread (NLPPipeline's DCG-miss and vocative re-focus sites), but the registry's tab
        // map is EDT-confined — hand the raw method ref across and a concurrent tab open/close
        // on the EDT races the roster iteration (CME swallowed into a bogus "couldn't parse",
        // or a stale roster). buildCtxOnEdt hops the call onto the EDT instead.
        processor.process(text, ctx, this::buildCtxOnEdt).whenComplete((result, error) -> SwingUtilities.invokeLater(() ->
                handleResult(typingVesselId, tab, messageIndex, text, fromButton,
                        error != null ? new PipelineResult.Error(String.valueOf(error.getMessage())) : result)));
    }

    /**
     * S18b (UI tree U8; adversarial review): the vessel side has answered this tab - a new round,
     * or a refused move. Accept and Reject are back, if a move of theirs was waiting for it.
     */
    void answerArrived() {
        if (quickMoveAwaitingAnswer) {
            quickMoveAwaitingAnswer = false;
            refresh();
        }
    }

    /** S10 (hunter C finding 1): the registry is EDT-confined; the pipeline calls this from its
     * executor thread. An interrupted hop refuses context ({@code DialogueCtx.NONE} — the
     * pipeline treats an empty roster as "no re-focus available") rather than guessing. */
    private DialogueCtx buildCtxOnEdt(String vesselId) {
        if (SwingUtilities.isEventDispatchThread()) {
            return registry.buildCtx(vesselId);
        }
        DialogueCtx[] out = new DialogueCtx[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = registry.buildCtx(vesselId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DialogueCtx.NONE;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        return out[0];
    }

    /**
     * S10 (bug hunt, hunter C finding 4): {@code origin} is the tab model the player actually
     * typed into, captured at send time. The parse can take seconds (Rasa fallback budget), and
     * in that window the negotiation can close (timeout withdraw) or the WORLD can change
     * (load/scenario boot clears the registry — and the new world's deterministic WALKIN-n ids
     * can recreate the SAME dialogueId, so an id lookup alone would pass). An identity mismatch
     * means the result belongs to a world that no longer exists: drop it entirely — publishing
     * a {@code PlayerCommandEvent} across that boundary reaches a different vessel.
     *
     * <p>S15 (2026-10-12): {@code messageIndex} is where that send's bubble sits in
     * {@code origin}'s transcript. The parse note is written only AFTER the identity guard, and
     * onto {@code origin} itself — so a result that outlives its world tags nothing (the new
     * world's same-id tab has other messages at that index), and two parses that finish out of
     * order each land on their own message.
     */
    private void handleResult(String typingVesselId, DialogueTabModel origin, int messageIndex,
                              String typed, boolean fromButton, PipelineResult result) {
        if (fromButton) {
            // S18b (U8): the move is read; every branch below re-renders, which enables the
            // buttons again. The world-identity guard does not, so it re-renders itself.
            quickMoveInFlight = false;
        }
        if (registry.tab(dialogueId) != origin) {
            if (fromButton) {
                refresh();
            }
            return;
        }
        switch (result) {
            case PipelineResult.Routed routed -> {
                // Only a ROUTED turn says which stage read it, and only when the result names
                // one: a clarified or failed turn was acted on by neither stage (and the line
                // saying so sits right under the bubble), and a result with no stated source
                // gets no note rather than a guessed one. No re-render here — every branch of
                // dispatchRouted appends a system line, which renders the note with it.
                String note = ParseTag.text(routed.provenance());
                if (!note.isEmpty()) {
                    origin.tagPlayerMessage(messageIndex, note);
                }
                dispatchRouted(typingVesselId, origin, routed, fromButton);
            }
            case PipelineResult.NeedsClarification nc -> showClarification(nc.message(), nc.aboutVesselId(), typed);
            case PipelineResult.Error e -> appendSystem("I couldn't parse that.");
        }
    }

    /** {@code targetVesselId} may differ from the tab typed into (a 16-M2 vocative, e.g.
     * "tell the tanker: deal") — that's fine, it's just the published event's payload;
     * nothing about rendering THIS tab depends on which vessel ends up targeted. */
    private void dispatchRouted(String typingVesselId, DialogueTabModel origin, PipelineResult.Routed routed,
                                boolean fromButton) {
        String targetVesselId = routed.addressee() != null ? routed.addressee() : typingVesselId;
        PlayerCommandKind kind = kindFor(routed.msg().getPerformative());
        // Status is an OBSERVABLE, GUI-local concept — the tab already holds everything a
        // status reply could say, and no vessel-side behaviour answers a QUERY_REF (verified:
        // EvaluateCounterOfferBehaviour's template matches PROPOSE/ACCEPT/REJECT/CANCEL only),
        // so dispatching one was pure dead air in the play-test. Answer locally instead.
        // S10 (hunter C finding 2): a vocative ASK asks about the ADDRESSEE — answering with
        // the typing tab's own status printed the wrong vessel's numbers.
        // S11 (fix review, R3): the ASK arm sits ABOVE the closed-tab guard — an ASK sends
        // nothing, so the guard's "must never emit a move" rationale does not cover it, and
        // guarding it cost the player the final figures they were asking for. A closed tab's
        // statusLine() already speaks in the past tense.
        if (kind == PlayerCommandKind.ASK) {
            appendSystem(askReply(routed, targetVesselId, targetVesselId.equals(typingVesselId)));
            return;
        }
        if (origin.isClosed()) {
            // The negotiation ended while the parse was in flight (S10, finding 4's second
            // half): a closed dialogue must never emit a move, and silence here would leave
            // the player's bubble looking sent.
            appendSystem("This negotiation ended while I was reading that — nothing was sent.");
            return;
        }
        if (kind == null) {
            handleUnroutableRequest(routed);
            return;
        }
        // S17a (ADR-21, decision A2; adversarial review): the lock is on the VESSEL the move is
        // for, and it is asked HERE, where the move would leave - sendText's own check covers the
        // tab that was typed into at the moment of typing, which is not enough twice over. A line
        // typed in a tab the player owns can be addressed to another vessel by name, and reach a
        // negotiation the Assistant is in the middle of; and a parse that was still running when
        // the Assistant took this very tab arrives after the check. Either way two answers would
        // go to one offer - the thing the lock exists to stop. Nothing is sent, and the line says
        // why and what to do.
        String targetDialogueId = registry.dialogueIdForVessel(targetVesselId).orElse(null);
        if (targetDialogueId != null && assistantOwnsDialogue.test(targetDialogueId)) {
            appendSystem(assistantIsNegotiatingLine(displayNameFor(targetVesselId),
                    targetVesselId.equals(typingVesselId)));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frameElements =
                (Map<String, Object>) TerminalJson.readOrNull(routed.msg().getContent(), Map.class);
        Map<String, Object> content = toVesselProtocol(frameElements);
        long commandId = PlayerCommandEvent.NO_COMMAND_ID;
        if (kind == PlayerCommandKind.PROPOSE && content.get("price") instanceof Number price) {
            // The offer strip + DialogueCtx standing offer need the player's side, at once, so
            // record it at the send. Task 19b: the proposed HOURS ride along (null when the
            // utterance named none — the standing value holds). The task-19 "only the fee is
            // negotiable" note is gone: duration is a real §7.3 dimension now, and the bargaining
            // feedback is the vessel's own reply ("they need at least Nh" on a floor pushback —
            // see the registry's offerSummary). S13 (F-7): the HarbourMaster's Prolog gate has not
            // judged the send yet, so it carries an id; a refusal names it and the registry puts
            // the previous offer back on the strip. S17a (ADR-21): the id comes from the ONE
            // JVM-wide source the autopilot's moves draw from too (PlayerCommandEvent.nextCommandId)
            // — with a counter of its own here, a refusal of the autopilot's move number N would
            // have matched this tab's send number N and rolled its offer line back.
            Integer proposedHours = content.get("hours") instanceof Number n ? n.intValue() : null;
            long sendId = PlayerCommandEvent.nextCommandId();
            commandId = sendId;
            registry.dialogueIdForVessel(targetVesselId)
                    .ifPresent(id -> registry.recordPlayerOffer(id, price.doubleValue(), proposedHours, sendId));
        } else if (kind == PlayerCommandKind.PROPOSE || kind == PlayerCommandKind.ACCEPT) {
            // S19 (playtest 5, PT30): the Prolog gate judges every binding move, so each carries an
            // id its refusal - or its dispatch - names. (Only a priced PROPOSE changes the strip.)
            commandId = PlayerCommandEvent.nextCommandId();
        }
        // Echo how the NL was INTERPRETED (task 19 play-test: Rasa read a bare "so?" as an
        // acceptance and the deal closed with no visible cause — every routed action must be
        // legible). S15: the Accept and Reject buttons come through here too — they send
        // "deal" / "no" as text — so a click is echoed exactly like the typed word. (The old
        // quick-action buttons skipped this; their label bubble was the only trace they left.)
        // S19 (playtest 5, PT30): a move the gate judges is echoed once the HarbourMaster has SENT
        // it - "→ Demanding €2,000 for 5h" used to show for a move the gate refused. The tab holds
        // the line until then; a refusal drops it, and the refusal's own plain line says what
        // happened instead.
        String echo = interpretationEcho(kind, content, displayNameFor(targetVesselId),
                targetVesselId.equals(typingVesselId),
                targetDialogueId == null ? null : registry.tab(targetDialogueId));
        DialogueTabModel typing = registry.tab(dialogueId);
        boolean held = commandId != PlayerCommandEvent.NO_COMMAND_ID && typing != null;
        if (held) {
            typing.holdEcho(commandId, echo);
        }
        // S18b (UI tree U8, adversarial review): a move Accept or Reject sent waits for its answer.
        quickMoveAwaitingAnswer |= fromButton;
        eventBus.publish(new PlayerCommandEvent(kind, targetVesselId, content, commandId));
        if (held) {
            refresh(); // the strip shows the send at once, and the bubble its note; the echo follows the dispatch
        } else {
            appendSystem(echo);
        }
    }

    /**
     * S18a (ADR-23, decision N6): the reply to a question, by what it asks. Until S18a every
     * question got the status line, so "what berths are free" was answered with this
     * negotiation's round and price. The grammar names the topic of an {@code ask} frame; a
     * question about the PORT is pointed at the panel that holds the port's answer (the tab holds
     * none of that state and does not pretend to), "which berth do I get" is answered from the
     * vessel's own negotiation, and everything else - "what's your best price", Rasa's
     * topic-less {@code query_status} - keeps the status line, which is the right answer to it.
     */
    private String askReply(PipelineResult.Routed routed, String vesselId, boolean sameTab) {
        @SuppressWarnings("unchecked")
        Map<String, Object> content =
                (Map<String, Object>) TerminalJson.readOrNull(routed.msg().getContent(), Map.class);
        Object topic = content == null ? null : content.get("topic");
        Optional<String> aboutThePort = ChatReplies.portAnswer(topic);
        if (aboutThePort.isPresent()) {
            return aboutThePort.get();
        }
        if (ChatReplies.asksForTheBerth(topic)) {
            DialogueTabModel asked = registry.dialogueIdForVessel(vesselId).map(registry::tab).orElse(null);
            if (asked != null) {
                WalkInDialogueSnapshot s = asked.snapshot();
                return ChatReplies.berthAnswer(sameTab ? null : displayNameFor(vesselId),
                        s.berthId(), s.durationHours());
            }
        }
        return sameTab ? statusLine() : statusLineFor(vesselId, false);
    }

    /** S17a: what a move that was not sent says - the Assistant is negotiating with that vessel. */
    static String assistantIsNegotiatingLine(String vesselName, boolean thisTab) {
        return thisTab
                ? "The Assistant is negotiating here now \u2014 nothing was sent. Press Take over first."
                : "The Assistant is negotiating with " + vesselName
                        + " \u2014 nothing was sent. Press Take over in that tab first.";
    }

    /** S8 (GOAL 3): the echo names the ADDRESSEE by its display name where a dialogue knows one
     * ("(to Stella Maris)"), falling back to the raw id — same coalesce as the tab title. */
    private String displayNameFor(String vesselId) {
        return registry.dialogueIdForVessel(vesselId)
                .map(id -> registry.tab(id))
                .filter(java.util.Objects::nonNull)
                .map(t -> t.snapshot().vesselName())
                .orElse(vesselId);
    }

    /**
     * Everything the pipeline routes that is NOT a per-dialogue move (audit D-06, 2026-07-27).
     *
     * <p>Five of the ten canonical intents land here — {@code request_help}, {@code set_constraint},
     * {@code set_policy}, plus the DCG's {@code command} and {@code constrain} frames (the v1.1
     * negation and command phenomenon blocks, PROJECT_DEFINITION §6.2) — because all five become
     * an {@code ACLMessage.REQUEST} and {@code kindFor} has no {@code PlayerCommandKind} for it.
     * They all used to get the single line "Fleet-wide commands aren't wired to a dialogue tab
     * yet.", which was correct for two of them and <b>false</b> for the rest: a request for help is
     * not a fleet command, and a policy is not one either. The parse was right in every case and
     * the GUI then said something untrue about it.
     *
     * <p>This does not invent new dispatch — none of these has an in-game consumer in v1, which is
     * a scope fact and is stated as such. What it does is say something true about each. S18a
     * (ADR-23, decisions N5, N7, N8) rewrote all three replies, which now come from
     * {@link ChatReplies} and {@link InputSafety}: a typed {@code help} is answered with what the
     * tab understands (it used to press the Hint button), a policy is pointed at Settings, and a
     * limit or command says it was understood, is not carried out, and what to type instead.
     */
    /** S19 (playtest 5, PT27): what a typed request for the Hint is echoed as. */
    static final String HINT_ECHO = "→ Asking the Assistant for a hint.";

    private void handleUnroutableRequest(PipelineResult.Routed routed) {
        if (routed.msg().getPerformative() == ACLMessage.INFORM) {
            appendSystem("Noted.");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> content =
                (Map<String, Object>) TerminalJson.readOrNull(routed.msg().getContent(), Map.class);
        DialogueTabModel tab = registry.tab(dialogueId);
        if (content != null && Boolean.TRUE.equals(content.get(NLPPipeline.HINT_REQUEST_KEY))) {
            // S19 (playtest 5, PT27): a typed request for the Assistant's suggestion does what the
            // Hint button does. Rasa used to read "hint" as a refusal, and the vessel left.
            appendSystem(HINT_ECHO);
            onHint();
            return;
        }
        if (content != null && Boolean.TRUE.equals(content.get(NLPPipeline.HELP_REQUEST_KEY))) {
            // S18a (ADR-23, decision N8): "help" asks what can be typed here, and the tab answers
            // that itself, in its own numbers. Until S18a it pressed the Hint button for the
            // player - but the Assistant recommends a PRICE, which answers another question (and
            // costs a model call). The line names the Hint button for whoever wanted that.
            appendSystem(ChatReplies.help(tab == null ? List.of() : InputExamples.examplesFor(tab),
                    hintButton.isVisible()));
            return;
        }
        if (content != null && content.get("policy_text") != null) {
            // S18a (decision N5): the policy input exists (Settings, since 2026-07-28), so the
            // line can say where it is - the same sentence the pipeline answers with when the
            // policy DSL itself parses what was typed.
            appendSystem(InputSafety.POLICY_IN_CHAT);
            return;
        }
        // S18a (decision N7): the DCG's constrain and command frames and Rasa's set_constraint.
        // All three are read correctly and none is carried out in this version; the one old line
        // called every one of them "a fleet-wide instruction", which a limit is not.
        appendSystem(ChatReplies.notExecuted(content,
                tab == null ? "" : InputExamples.priceAndHoursExample(tab)));
    }

    /**
     * Task 19 play-test fix — the CORE key mapping between the two vocabularies this bridge
     * straddles. The DCG frame speaks Commerce_sell roles ({@code money}/{@code duration}/
     * {@code berth}, with money a {@code {amount, currency}} object — see {@code Frame});
     * the vessel's frozen task-07 negotiation protocol reads {@code price}/{@code hours}/
     * {@code berth_id}. Without this translation every player offer arrived priceless: the
     * vessel read {@code price} as 0.0, always answered with the same below-minimum counter
     * ("it only answers the same thing"), and withdrew "over_priced" even when the player
     * typed its exact asking price. {@code move} is NLP-internal routing metadata, not a wire
     * term. Unmapped keys pass through untouched, oldest-first (LinkedHashMap — determinism).
     */
    static Map<String, Object> toVesselProtocol(Map<String, Object> frameElements) {
        if (frameElements == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : frameElements.entrySet()) {
            switch (e.getKey()) {
                case "move" -> { }
                case "money" -> out.put("price", moneyAmount(e.getValue()));
                case "duration" -> out.put("hours", e.getValue());
                case "berth" -> out.put("berth_id", e.getValue());
                default -> out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** {@code money} arrives as {@code {amount: 5435.0, currency: "EUR"}} (Frame's decode of
     * the Prolog {@code price(N,eur)} term); older/looser callers may pass a bare number. */
    private static Object moneyAmount(Object money) {
        if (money instanceof Map<?, ?> m && m.get("amount") != null) {
            return m.get("amount");
        }
        return money;
    }

    /** DCG/Rasa performatives that map onto a per-dialogue negotiation move.
     * {@code null} for INFORM (a bare "cancel" with nothing on the table) and REQUEST
     * (fleet-wide {@code constrain}/{@code command} frames) — neither is a
     * per-dialogue move {@code PlayerCommandKind} can represent. ASK is intercepted
     * BEFORE dispatch and answered locally (see {@code dispatchRouted}). */
    private static PlayerCommandKind kindFor(int performative) {
        return switch (performative) {
            case ACLMessage.PROPOSE -> PlayerCommandKind.PROPOSE;
            case ACLMessage.ACCEPT_PROPOSAL -> PlayerCommandKind.ACCEPT;
            case ACLMessage.REJECT_PROPOSAL -> PlayerCommandKind.REJECT;
            case ACLMessage.QUERY_REF -> PlayerCommandKind.ASK;
            case ACLMessage.CANCEL -> PlayerCommandKind.WITHDRAW;
            default -> null;
        };
    }

    /** The local status reply — everything a (dead-air) QUERY_REF to the vessel could have said. */
    private String statusLine() {
        DialogueTabModel tab = registry.tab(dialogueId);
        return tab == null ? "No active negotiation." : statusLineFor(tab.vesselId(), true);
    }

    /** The status of ANY vessel with an open tab, by id — the vocative-ASK arm needs the
     * ADDRESSEE's numbers, not the typing tab's (S10, hunter C finding 2). {@code sameTab}
     * only shapes the wording: a cross-tab answer names whose status it is. */
    private String statusLineFor(String vesselId, boolean sameTab) {
        DialogueTabModel tab = registry.dialogueIdForVessel(vesselId)
                .map(registry::tab).orElse(null);
        if (tab == null) {
            return sameTab ? "No active negotiation."
                    : "No open negotiation with " + displayNameFor(vesselId) + ".";
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        String subject = sameTab ? "Status" : "Status of " + displayNameFor(vesselId);
        return String.format("%s — their offer: %s · your offer: %s · round %d of %d · %dh at %s.",
                subject, Euros.of(s.lastVesselOffer()), Euros.of(s.lastPlayerOffer()), s.roundsUsed(),
                Settings.load().roundLimit(), s.durationHours(), s.berthId());
    }

    /** One line saying what the pipeline UNDERSTOOD, appended after every routed dispatch.
     * {@code targetDisplay} is the addressee's display name (S8: human name where known).
     * S18b (UI tree U6): an accept names what it accepts — the addressee's standing offer,
     * {@code addressee} being its tab ({@code null} when it has none) — and amounts are written
     * as everywhere in the chat ({@link Euros}). S19 (playtest 5, PT32): it says who moves -
     * "→ You ask €2,200 for 6h." ("→ Demanding …" read like someone else's demand). */
    static String interpretationEcho(PlayerCommandKind kind, Map<String, Object> content,
                                     String targetDisplay, boolean sameTab, DialogueTabModel addressee) {
        String target = sameTab ? "" : " (to " + targetDisplay + ")";
        return switch (kind) {
            case PROPOSE -> content.get("price") instanceof Number price
                    ? (content.get("hours") instanceof Number hours
                            ? "→ You ask " + Euros.of(price.doubleValue()) + " for " + hours.intValue() + "h" + target + "."
                            : "→ You ask " + Euros.of(price.doubleValue()) + target + ".")
                    : "→ You make an offer" + target + ".";
            case ACCEPT -> addressee != null && addressee.snapshot().lastVesselOffer() > 0
                    ? "→ You accept " + Euros.of(addressee.snapshot().lastVesselOffer()) + " for "
                            + addressee.snapshot().durationHours() + "h" + target + "."
                    : "→ You accept their offer" + target + ".";
            case REJECT -> "→ You turn " + targetDisplay + " away.";
            case WITHDRAW -> "→ You withdraw from this negotiation" + target + ".";
            case ASK -> "→ You ask for the status" + target + "."; // unreachable (ASK answered locally)
        };
    }

    /**
     * What a low-confidence parse adds is FEEDBACK — the play-test's "I wrote something and it
     * doesn't reply" was this branch silently doing nothing visible.
     *
     * <p>A SPECIFIC message wins over the generic line (2026-07-28, D-05's cross-tab guard):
     * there, the pipeline understood the sentence perfectly and declined it on purpose, so
     * "I didn't get that" would be an outright lie about what happened.
     *
     * <p>S15 (2026-10-12): the pipeline's result still carries the five fallback options
     * ({@code ClarificationButtons}, untouched), but nothing draws them any more — the bar they
     * fed is gone, so they are not passed in. The generic line points at what the tab does have:
     * a worked example and the two buttons.
     */
    private void showClarification(String message, String aboutVesselId, String typed) {
        if (message == null || message.isBlank()) {
            appendSystem(clarificationLine(typed));
            return;
        }
        // S18a (ADR-23): a message that wants a worked example leaves a slot for it, because only
        // a tab knows a number that is safely above ITS vessel's offer (see InputSafety). When
        // the refused move was for another tab's vessel (a vocative), the example is that tab's
        // - written with the vessel's name in front, so that copying it sends it to the vessel it
        // is safe for. This tab's own example could be far below the other vessel's offer.
        DialogueTabModel tab = aboutVesselId == null ? registry.tab(dialogueId)
                : registry.dialogueIdForVessel(aboutVesselId).map(registry::tab).orElse(null);
        String example = tab == null ? "" : InputExamples.priceAndHoursExample(tab);
        if (aboutVesselId != null && !example.isEmpty()) {
            example = displayNameFor(aboutVesselId) + ": " + example;
        }
        appendSystem(InputSafety.fillExample(message, example));
    }

    /** S15: the generic "I didn't get that" line. Its example is THIS vessel's — a fixed price
     * copied onto a vessel already offering more would close the deal far too cheaply (the vessel
     * accepts any demand at or below its standing offer), so the number comes from
     * {@link InputExamples}, above the offer on the table. With nothing to build an example from
     * (the tab closed while the parse was in flight) the line makes no suggestion it cannot back.
     * S18b (playtest 4, PT22): a line with one price in it, above the vessel's offer, is answered
     * with that price as a move to copy ({@link ChatReplies#didYouMean}). */
    private String clarificationLine(String typed) {
        DialogueTabModel tab = registry.tab(dialogueId);
        Optional<String> guess = ChatReplies.didYouMean(typed, tab);
        if (guess.isPresent()) {
            return guess.get();
        }
        String example = tab == null ? "" : InputExamples.priceAndHoursExample(tab);
        if (!example.isEmpty()) {
            return "I didn't get that — try something like '" + example + "', or press Accept or Reject.";
        }
        return tab != null && tab.isClosed()
                ? "I didn't get that — and this negotiation is closed, so nothing was sent."
                : "I didn't get that — type your price and hours, or press Accept or Reject.";
    }

    /**
     * S14: the snapshot a Hint reasons from carries the counter AS IT READS NOW. The stored
     * snapshot's time figures date from the vessel's last message; the Assistant's urgency term
     * must see what the player sees at the moment they ask (INVARIANTS: one visible counter, two
     * consumers). No conversation on the clock: the stored figures stand.
     */
    private void onHint() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab != null) {
            WalkInDialogueSnapshot snapshot = simClock.conversationClock().view(dialogueId)
                    .map(v -> tab.snapshot().withTime(v.elapsedMillis() / 1000.0, v.totalMillis() / 1000.0))
                    .orElse(tab.snapshot());
            eventBus.publish(new HintButtonEvent(dialogueId, snapshot));
        }
    }

    private void appendSystem(String text) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab != null) {
            tab.appendSystemMessage(text);
            refresh();
        }
    }

    /** S15: as {@link #appendSystem}, unless {@code text} already is the transcript's last line —
     * a button pressed twice on a closed tab says its piece once. */
    private void appendSystemOnce(String text) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null) {
            return;
        }
        List<DialogueTabModel.ChatEntry> transcript = tab.transcript();
        if (!transcript.isEmpty()) {
            DialogueTabModel.ChatEntry last = transcript.get(transcript.size() - 1);
            if (last.speaker() == DialogueTabModel.Speaker.SYSTEM && last.text().equals(text)) {
                return;
            }
        }
        appendSystem(text);
    }

    /** Every button under this tab, in reading order (top row first, left to right). The scroll
     * pane's own arrow buttons are buttons too; they carry no text and are left out. */
    private List<JButton> labelledButtons() {
        List<JButton> found = new ArrayList<>();
        collectLabelledButtons(this, found);
        return found;
    }

    private static void collectLabelledButtons(Container container, List<JButton> into) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button) {
                String text = button.getText();
                if (text != null && !text.isEmpty()) {
                    into.add(button);
                }
            } else if (child instanceof Container nested) {
                collectLabelledButtons(nested, into);
            }
        }
    }

    /**
     * S18b (UI tree U7): the one way the transcript goes to its newest line — the scrollbar is
     * set one EDT turn later, after the scroll pane has been laid out for the new document, so the
     * maximum is the real one and the newest line is shown whole.
     */
    private void scrollTranscriptToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (followTail) {
                JScrollBar bar = transcriptScroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum()); // the model clamps this to maximum - extent
            }
        });
    }

    /**
     * S18b (UI tree U7), the comm log's rule: a report in which the bar's extent and maximum did
     * not change is the player scrolling, and decides whether the view follows the bottom; a
     * report that changed them is a layout — a new line, a narrower window, the tab coming to the
     * front — and a view that follows the bottom goes back to it.
     */
    private void onTranscriptBarAdjusted() {
        JScrollBar bar = transcriptScroll.getVerticalScrollBar();
        boolean layoutChanged = bar.getVisibleAmount() != lastBarExtent || bar.getMaximum() != lastBarMaximum;
        lastBarExtent = bar.getVisibleAmount();
        lastBarMaximum = bar.getMaximum();
        if (!layoutChanged) {
            followTail = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
        } else if (followTail) {
            scrollTranscriptToBottom();
        }
    }

    /**
     * A caret that never moves the view (the comm log's, S15). The stock one scrolls its position
     * into view after a document change and after a mouse press; here only
     * {@link #scrollTranscriptToBottom()} may scroll. Selecting and copying work as before.
     */
    private static final class StillCaret extends DefaultCaret {
        private static final long serialVersionUID = 1L;

        StillCaret() {
            setUpdatePolicy(NEVER_UPDATE);
        }

        @Override
        protected void adjustVisibility(Rectangle newLocation) {
            // never
        }
    }

    /** Test-only accessors. */
    JTextField inputFieldForTest() {
        return inputField;
    }

    /** Test-only (S18b, U7): whether the transcript follows its newest line. */
    boolean followingTailForTest() {
        return followTail;
    }

    /** Test-only (S18b, U7): the player scrolled away from the bottom, or back to it. */
    void setFollowingTailForTest(boolean follow) {
        followTail = follow;
    }

    /** Test-only (S18b, U7): whether the last refresh pinned the view to the newest line. */
    boolean lastRefreshPinnedForTest() {
        return lastRefreshPinned;
    }

    /** Test-only (S18b, U7): the transcript itself, to find the scroll pane around it. */
    JTextPane transcriptPaneForTest() {
        return transcriptPane;
    }

    /** Test-only: where the transcript caret sits (audit C-28 — it must be at the end). */
    int caretPositionForTest() {
        return transcriptPane.getCaretPosition();
    }

    /** Test-only: the transcript document's length, the expected caret position. */
    int transcriptLengthForTest() {
        return transcriptPane.getDocument().getLength();
    }

    /** Test-only: the rendered transcript as plain text (S12 — a Hint revision must show in place). */
    String transcriptTextForTest() {
        try {
            return transcriptPane.getDocument().getText(0, transcriptPane.getDocument().getLength());
        } catch (javax.swing.text.BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test-only: the offer line as one sentence ({@link OfferLine#text()}), as last rendered. */
    String offerStripTextForTest() {
        return shownOfferLine == null ? "" : shownOfferLine.text();
    }

    /** Test-only (S15): what the two offer rows actually show, top row first. */
    List<String> offerRowsForTest() {
        return List.of(theirOfferLabel.getText(), yourOfferLabel.getText());
    }

    /** Test-only: the live counter's text ({@code m:ss}, or blank). */
    String counterTextForTest() {
        return counterLabel.getText();
    }

    /** Test-only: the live counter's colour. */
    Color counterColourForTest() {
        return counterLabel.getForeground();
    }

    boolean hintButtonVisibleForTest() {
        return hintButton.isVisible();
    }

    /** Test-only (S15): the text on every button the tab is offering right now — hidden buttons
     * left out, disabled ones included — in reading order. S17a: that is top to bottom, so
     * {@code Take over} or {@code Use this} comes first while its row is on screen; a button
     * whose ROW is hidden is hidden too, whatever its own flag says. */
    List<String> buttonLabelsForTest() {
        List<String> labels = new ArrayList<>();
        for (JButton button : labelledButtons()) {
            if (visibleInTab(button)) {
                labels.add(button.getText());
            }
        }
        return List.copyOf(labels);
    }

    /** Whether {@code component} would be on screen if this tab were: it and every container
     * between it and the tab are visible. ({@code isShowing()} cannot answer that in a headless
     * test — it also wants a window.) */
    private boolean visibleInTab(Component component) {
        for (Component c = component; c != null && c != this; c = c.getParent()) {
            if (!c.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Test-only (S15): the button with that text, hidden or not.
     * @throws IllegalArgumentException if the tab has no such button */
    JButton buttonForTest(String label) {
        List<String> labels = new ArrayList<>();
        for (JButton button : labelledButtons()) {
            if (button.getText().equals(label)) {
                return button;
            }
            labels.add(button.getText());
        }
        throw new IllegalArgumentException("no button labelled \"" + label + "\" — the tab has " + labels);
    }

    /** Test-only (S15): a player's click on the button with that text. Like a real click it does
     * nothing on a disabled button; a HIDDEN button cannot be clicked at all, so asking for one is
     * a mistake in the test and fails loudly. */
    void clickForTest(String label) {
        JButton button = buttonForTest(label);
        if (!visibleInTab(button)) {
            throw new IllegalStateException("the \"" + label + "\" button is hidden — a player could not click it");
        }
        button.doClick(0);
    }

    /** Test-only (S17a): whether the "Assistant is negotiating…" marker row is on screen. */
    boolean markerRowVisibleForTest() {
        return visibleInTab(markerRow);
    }

    /** Test-only (S17a): the marker row's label as shown, or the empty string while the row is hidden. */
    String markerTextForTest() {
        return visibleInTab(markerRow) ? markerLabel.getText() : "";
    }

    /** Test-only (S17a): whether the hint row ("Assistant suggests: …") is on screen. */
    boolean hintRowVisibleForTest() {
        return visibleInTab(hintRow);
    }

    /** Test-only (S17a): the hint row's label as shown, or the empty string while the row is hidden. */
    String suggestionTextForTest() {
        return visibleInTab(hintRow) ? suggestionLabel.getText() : "";
    }

    /** Test-only (S15): the example the input field is showing right now, or the empty string
     * when it shows none (a closed tab, or a field with text in it). */
    String placeholderForTest() {
        return inputField.showsPlaceholder() ? inputField.placeholder() : "";
    }
}
