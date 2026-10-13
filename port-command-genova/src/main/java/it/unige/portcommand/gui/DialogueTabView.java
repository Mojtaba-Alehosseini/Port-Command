package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.model.DialogueTabModel;
import it.unige.portcommand.gui.model.InputExamples;
import it.unige.portcommand.gui.model.NegotiationTabRegistry;
import it.unige.portcommand.gui.model.OfferLine;
import it.unige.portcommand.gui.model.ParseTag;
import it.unige.portcommand.gui.model.PatienceCounterModel;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.DialogueCtx;
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
 */
final class DialogueTabView extends JPanel {

    /** S13 (F-7): ids for the PROPOSEs whose terms the strip shows before the Prolog gate's verdict.
     * JVM-wide, like the Assistant's hint ids: a refusal still in flight across a load must not
     * match the next world's same-id dialogue. */
    private static final AtomicLong COMMAND_IDS = new AtomicLong();

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
    /** Live Channel-B autopilot state (task 21): the parent {@code ChatPanel} owns the flag and
     * updates it on {@code SettingsChangedEvent}; the Hint button hides while autopilot is on.
     * Read live rather than off the static {@code Settings.load()} so the toggle takes effect at
     * once instead of only reflecting the {@code defaults.json} value. */
    private final BooleanSupplier autopilotEnabled;
    private final JTextPane transcriptPane = new JTextPane();
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

    /** S15: the offer line as last rendered ({@code null} before the first render). */
    private OfferLine shownOfferLine;
    /** S15: what the counter shows right now, in milliseconds — empty while it is blank. The
     * "Waiting for you" list reads THIS (through {@code ChatPanel}) rather than the clock, so the
     * list and the tab can never show two different numbers for one vessel. */
    private OptionalLong shownRemainingMillis = OptionalLong.empty();
    /**
     * S15: told whenever a re-render of THIS tab changed what its counter shows. The one-second
     * timer is not the only thing that re-reads the conversation clock — every {@link #refresh()}
     * does (a send, a system line, an Assistant message, a settings change), and those land
     * between two ticks. Without this the "Waiting for you" list would keep the previous tick's
     * number, and across 0:30 or 0:10 the previous colour, for the rest of that second.
     * ({@code ChatPanel} answers by having EVERY tab re-read, so the list sorts like with like.)
     */
    private final Runnable onCounterChanged;
    /** False while the constructor runs: the tab is not on the strip yet, nobody shows its counter. */
    private boolean built;

    DialogueTabView(String dialogueId, NegotiationTabRegistry registry, EventBus eventBus,
                    ChatInputProcessor processor, SimClock simClock, BooleanSupplier autopilotEnabled,
                    Runnable onCounterChanged) {
        super(new BorderLayout(4, 4));
        this.onCounterChanged = onCounterChanged;
        this.dialogueId = dialogueId;
        this.registry = registry;
        this.eventBus = eventBus;
        this.processor = processor;
        this.simClock = simClock;
        this.autopilotEnabled = autopilotEnabled;
        DialogueTabModel born = registry.tab(dialogueId);
        this.vesselId = born == null ? null : born.vesselId();

        transcriptPane.setContentType("text/html");
        transcriptPane.setEditable(false);

        sendButton.addActionListener(e -> onSend());
        inputField.addActionListener(e -> onSend());
        hintButton.addActionListener(e -> onHint());
        // S15: the two buttons send what the player could have typed (see quickMove).
        acceptButton.addActionListener(e -> quickMove(InputExamples.ACCEPT_UTTERANCE, "accept"));
        rejectButton.addActionListener(e -> quickMove(InputExamples.REJECT_UTTERANCE, "reject"));

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

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(inputRow, BorderLayout.CENTER);
        south.add(actionRow, BorderLayout.SOUTH);

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
        add(new JScrollPane(transcriptPane), BorderLayout.CENTER);
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
        // Audit C-28 (2026-07-27): JEditorPane.setText replaces the document and leaves the caret
        // at offset 0, and the default DefaultCaret update policy then scrolls the viewport TO the
        // caret — i.e. to the TOP. Every new message therefore jumped a scrolling transcript back
        // to the orientation line, putting the vessel's newest counter (the thing the player is
        // supposed to answer) off-screen, every turn, in the panel the demo spends the most time
        // in. CommLogPanel already handles the same hazard with its wasAtBottom/setCaretPosition
        // logic; the chat tab had no equivalent. Pinning the caret at the end is unconditional
        // here rather than stick-to-bottom-only, because a transcript grows at the end and the
        // player has no reason to be parked mid-history when a new line lands. The one in-place
        // edit (S12: a Hint's template replaced by the model's validated wording) re-renders the
        // same way; the newest lines are what the player should be looking at then too.
        transcriptPane.setCaretPosition(transcriptPane.getDocument().getLength());
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
        inputField.setEditable(!closed);
        sendButton.setEnabled(!closed);
        hintButton.setVisible(!closed && !autopilotEnabled.getAsBoolean());
        // S15: Accept and Reject are never hidden or disabled. On a closed tab they answer in
        // the transcript that there is nothing left to act on (quickMove) — a button that goes
        // grey the instant a vessel walks away, under the pointer, explains nothing.
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
        sendText(utterance);
    }

    /** Package-private so a headless test can drive a send without simulating real
     * JTextField/ActionEvent plumbing. */
    void sendText(String text) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null || tab.isClosed()) {
            return; // #9: Enter on a closed tab's (non-editable) field must not send.
        }
        inputField.setText("");
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
                handleResult(typingVesselId, tab, messageIndex,
                        error != null ? new PipelineResult.Error(String.valueOf(error.getMessage())) : result)));
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
                              PipelineResult result) {
        if (registry.tab(dialogueId) != origin) {
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
                dispatchRouted(typingVesselId, origin, routed);
            }
            case PipelineResult.NeedsClarification nc -> showClarification(nc.message());
            case PipelineResult.Error e -> appendSystem("I couldn't parse that.");
        }
    }

    /** {@code targetVesselId} may differ from the tab typed into (a 16-M2 vocative, e.g.
     * "tell the tanker: deal") — that's fine, it's just the published event's payload;
     * nothing about rendering THIS tab depends on which vessel ends up targeted. */
    private void dispatchRouted(String typingVesselId, DialogueTabModel origin, PipelineResult.Routed routed) {
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
            appendSystem(targetVesselId.equals(typingVesselId)
                    ? statusLine()
                    : statusLineFor(targetVesselId, false));
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
            // the previous offer back on the strip.
            Integer proposedHours = content.get("hours") instanceof Number n ? n.intValue() : null;
            long sendId = COMMAND_IDS.incrementAndGet();
            commandId = sendId;
            registry.dialogueIdForVessel(targetVesselId)
                    .ifPresent(id -> registry.recordPlayerOffer(id, price.doubleValue(), proposedHours, sendId));
        }
        eventBus.publish(new PlayerCommandEvent(kind, targetVesselId, content, commandId));
        // Echo how the NL was INTERPRETED (task 19 play-test: Rasa read a bare "so?" as an
        // acceptance and the deal closed with no visible cause — every routed action must be
        // legible). S15: the Accept and Reject buttons come through here too — they send
        // "deal" / "no" as text — so a click is echoed exactly like the typed word. (The old
        // quick-action buttons skipped this; their label bubble was the only trace they left.)
        appendSystem(interpretationEcho(kind, content, displayNameFor(targetVesselId),
                targetVesselId.equals(typingVesselId)));
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
     * a scope fact and is stated as such. What it does is (a) send the typed {@code help} to the
     * Assistant, exactly as the Hint button does (before S15 a "Talk to the assistant" quick-action
     * button did the same; only the typed form was dead); and (b) say something true about the
     * other four.
     */
    private void handleUnroutableRequest(PipelineResult.Routed routed) {
        if (routed.msg().getPerformative() == ACLMessage.INFORM) {
            appendSystem("Noted.");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> content =
                (Map<String, Object>) TerminalJson.readOrNull(routed.msg().getContent(), Map.class);
        if (content != null && Boolean.TRUE.equals(content.get(NLPPipeline.HELP_REQUEST_KEY))) {
            appendSystem("→ Asking the Assistant.");
            onHint();
            return;
        }
        if (content != null && content.get("policy_text") != null) {
            // Deliberately says what happened HERE, not what the game does or does not support:
            // whether a policy input surface exists is an open decision (audit C-01/D-02), and a
            // message asserting "v1 has none" would silently become false the moment that decision
            // goes the other way. Found by the adversarial review, 2026-07-27.
            appendSystem("I read that as a standing policy. A chat tab dispatches per-dialogue moves "
                    + "only, so nothing was set from here — handle this negotiation directly.");
            return;
        }
        appendSystem("I understood a fleet-wide instruction, but v1 only dispatches per-dialogue "
                + "moves — nothing fleet-wide was sent.");
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
        return String.format("%s — their offer: €%.0f · your offer: €%.0f · round %d of %d · %dh at %s.",
                subject, s.lastVesselOffer(), s.lastPlayerOffer(), s.roundsUsed(), Settings.load().roundLimit(),
                s.durationHours(), s.berthId());
    }

    /** One line saying what the pipeline UNDERSTOOD, appended after every routed dispatch.
     * {@code targetDisplay} is the addressee's display name (S8: human name where known). */
    private static String interpretationEcho(PlayerCommandKind kind, Map<String, Object> content,
                                             String targetDisplay, boolean sameTab) {
        String target = sameTab ? "" : " (to " + targetDisplay + ")";
        return switch (kind) {
            case PROPOSE -> content.get("price") instanceof Number price
                    ? (content.get("hours") instanceof Number hours
                            ? String.format("→ Demanding €%.0f for %dh%s.", price.doubleValue(), hours.intValue(), target)
                            : String.format("→ Demanding €%.0f%s.", price.doubleValue(), target))
                    : "→ Making an offer" + target + ".";
            case ACCEPT -> "→ Accepting their offer" + target + ".";
            case REJECT -> "→ Turning them away" + target + ".";
            case WITHDRAW -> "→ Withdrawing from this negotiation" + target + ".";
            case ASK -> "→ Checking status" + target + "."; // unreachable (ASK answered locally)
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
    private void showClarification(String message) {
        appendSystem(message != null && !message.isBlank() ? message : clarificationLine());
    }

    /** S15: the generic "I didn't get that" line. Its example is THIS vessel's — a fixed price
     * copied onto a vessel already offering more would close the deal far too cheaply (the vessel
     * accepts any demand at or below its standing offer), so the number comes from
     * {@link InputExamples}, above the offer on the table. With nothing to build an example from
     * (the tab closed while the parse was in flight) the line makes no suggestion it cannot back. */
    private String clarificationLine() {
        DialogueTabModel tab = registry.tab(dialogueId);
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

    /** Test-only accessors. */
    JTextField inputFieldForTest() {
        return inputField;
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
     * left out, disabled ones included — in reading order. */
    List<String> buttonLabelsForTest() {
        List<String> labels = new ArrayList<>();
        for (JButton button : labelledButtons()) {
            if (button.isVisible()) {
                labels.add(button.getText());
            }
        }
        return List.copyOf(labels);
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
        if (!button.isVisible()) {
            throw new IllegalStateException("the \"" + label + "\" button is hidden — a player could not click it");
        }
        button.doClick(0);
    }

    /** Test-only (S15): the example the input field is showing right now, or the empty string
     * when it shows none (a closed tab, or a field with text in it). */
    String placeholderForTest() {
        return inputField.showsPlaceholder() ? inputField.placeholder() : "";
    }
}
