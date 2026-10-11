package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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
import it.unige.portcommand.gui.model.NegotiationTabRegistry;
import it.unige.portcommand.gui.model.PatienceCounterModel;
import it.unige.portcommand.nlp.ButtonOption;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.ClarificationButtons;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.NLPPipeline;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.util.ConversationClock;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;

/**
 * One walk-in dialogue's tab: transcript + offer-state strip + input row + Hint
 * button + clarification fallback. Entirely self-contained — it never needs to reach
 * into a SIBLING tab's view, because every rendering effect of a send (the player's
 * own bubble, a clarification prompt, an error/system notice) is local to the tab the
 * player typed into; only the resulting {@link PlayerCommandEvent}'s {@code
 * targetVesselId} may point at a different vessel (the 16-M2 vocative case), and
 * that's just data on the published event; {@code ChatPanel} needs no direct
 * involvement in ANY tab's send flow, only in creating tabs and forwarding
 * server-originated events (opened/closed/assistant-chat) to {@link #refresh}.
 */
final class DialogueTabView extends JPanel {

    /** S13 (F-7): ids for the PROPOSEs whose terms the strip shows before the Prolog gate's verdict.
     * JVM-wide, like the Assistant's hint ids: a refusal still in flight across a load must not
     * match the next world's same-id dialogue. */
    private static final AtomicLong COMMAND_IDS = new AtomicLong();

    /** Component name of the live counter, so a test (and the coming redesign) can find it. */
    static final String COUNTER_NAME = "dialogue-counter";
    /** S14: the counter's three colours — normal, under 30 s, under 10 s. */
    static final Color COUNTER_NORMAL = new Color(0x33, 0x33, 0x33);
    static final Color COUNTER_WARNING = new Color(0xE0, 0x7B, 0x00);
    static final Color COUNTER_CRITICAL = new Color(0xC6, 0x28, 0x28);

    private final String dialogueId;
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
    private final JLabel offerStripLabel = new JLabel();
    /** S14 (ADR-18): the real time this vessel will still wait, as {@code m:ss} — a number and a
     * colour, never a label and never an explanation (players discover why vessels differ). */
    private final JLabel counterLabel = new JLabel();
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton hintButton = new JButton("Hint");
    private final ClarificationButtonsPanel clarificationPanel;

    DialogueTabView(String dialogueId, NegotiationTabRegistry registry, EventBus eventBus,
                    ChatInputProcessor processor, SimClock simClock, BooleanSupplier autopilotEnabled) {
        super(new BorderLayout(4, 4));
        this.dialogueId = dialogueId;
        this.registry = registry;
        this.eventBus = eventBus;
        this.processor = processor;
        this.simClock = simClock;
        this.autopilotEnabled = autopilotEnabled;

        transcriptPane.setContentType("text/html");
        transcriptPane.setEditable(false);

        clarificationPanel = new ClarificationButtonsPanel(ClarificationButtons.defaultOptions(), this::onClarificationClick);

        sendButton.addActionListener(e -> onSend());
        inputField.addActionListener(e -> onSend());
        hintButton.addActionListener(e -> onHint());

        JPanel buttonsBox = new JPanel();
        buttonsBox.add(sendButton);
        buttonsBox.add(hintButton);
        JPanel inputRow = new JPanel(new BorderLayout(4, 4));
        inputRow.add(inputField, BorderLayout.CENTER);
        inputRow.add(buttonsBox, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.add(clarificationPanel, BorderLayout.NORTH);
        south.add(inputRow, BorderLayout.SOUTH);

        counterLabel.setName(COUNTER_NAME);
        counterLabel.setFont(counterLabel.getFont().deriveFont(Font.BOLD));
        JPanel north = new JPanel(new BorderLayout(8, 0));
        north.add(offerStripLabel, BorderLayout.CENTER);
        north.add(counterLabel, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);
        add(new JScrollPane(transcriptPane), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        refresh();
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
        offerStripLabel.setText(offerStripText(tab, closed));
        refreshCounter();
        // #9 (checkpoint fix, 2026-07-18): a CLOSED tab keeps its input field ENABLED (hence
        // focusable) but NON-EDITABLE. A non-editable focused field silently swallows typed
        // characters (they no-op and never propagate); the old setEnabled(false) instead dropped
        // focus, and the keystrokes fell through to the comm-log Performative filter combo, whose
        // type-ahead then changed the filter. Send is still disabled and Enter is guarded in
        // sendText, so a closed tab is inert.
        inputField.setEditable(!closed);
        sendButton.setEnabled(!closed);
        hintButton.setVisible(!closed && !autopilotEnabled.getAsBoolean());
        // Task 19 play-test fix: the buttons are a PERSISTENT quick-action bar on every open
        // tab, not a fallback that only appears after a failed parse — players expected them
        // to always be there ("the buttons are not always appear!").
        clarificationPanel.setVisible(!closed);
    }

    /** Re-renders ONLY the offer strip — a refused PROPOSE puts the previous offer back (S13, F-7)
     * without re-rendering the whole HTML transcript. */
    void refreshStrip() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab != null) {
            offerStripLabel.setText(offerStripText(tab, tab.isClosed()));
        }
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
            return;
        }
        long remaining = view.get().remainingMillis();
        counterLabel.setText(PatienceCounterModel.text(remaining));
        counterLabel.setForeground(switch (PatienceCounterModel.level(remaining)) {
            case NORMAL -> COUNTER_NORMAL;
            case WARNING -> COUNTER_WARNING;
            case CRITICAL -> COUNTER_CRITICAL;
        });
    }

    /** Task 19b: both §7.3 dimensions on the strip — their €X for Yh / your €A for Bh. The
     * player's hours fall back to the hours under discussion until they name their own. */
    private String offerStripText(DialogueTabModel tab, boolean closed) {
        if (closed) {
            return "Negotiation closed.";
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        int playerHours = tab.lastPlayerHours() != null ? tab.lastPlayerHours() : s.durationHours();
        int roundLimit = Settings.load().roundLimit();
        return String.format(
                "Their offer: €%.0f for %dh  |  Your offer: €%.0f for %dh  |  Round %d of %d",
                s.lastVesselOffer(), s.durationHours(), s.lastPlayerOffer(), playerHours,
                s.roundsUsed(), roundLimit);
    }

    private void onSend() {
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

    /** Package-private so a headless test can drive a send without simulating real
     * JTextField/ActionEvent plumbing. */
    void sendText(String text) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null || tab.isClosed()) {
            return; // #9: Enter on a closed tab's (non-editable) field must not send.
        }
        inputField.setText("");
        clarificationPanel.setVisible(false);
        tab.appendPlayerMessage(text);
        refresh();

        String vesselId = tab.vesselId();
        DialogueCtx ctx = registry.buildCtx(vesselId);
        // S8 (D-05 two-step): the registry doubles as the by-vessel context source, so the
        // pipeline can re-focus onto a vocative's ADDRESSEE and re-parse under ITS offer.
        // S10 (bug hunt, hunter C finding 1): the hook is invoked on the PIPELINE executor
        // thread (NLPPipeline's DCG-miss and vocative re-focus sites), but the registry's tab
        // map is EDT-confined — hand the raw method ref across and a concurrent tab open/close
        // on the EDT races the roster iteration (CME swallowed into a bogus "couldn't parse",
        // or a stale roster). buildCtxOnEdt hops the call onto the EDT instead.
        processor.process(text, ctx, this::buildCtxOnEdt).whenComplete((result, error) -> SwingUtilities.invokeLater(() ->
                handleResult(vesselId, tab, error != null ? new PipelineResult.Error(String.valueOf(error.getMessage())) : result)));
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
     */
    private void handleResult(String typingVesselId, DialogueTabModel origin, PipelineResult result) {
        if (registry.tab(dialogueId) != origin) {
            return;
        }
        switch (result) {
            case PipelineResult.Routed routed -> dispatchRouted(typingVesselId, origin, routed);
            case PipelineResult.NeedsClarification nc -> showClarification(nc.buttons(), nc.message());
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
        // legible). Button clicks skip this: their own label bubble already is the echo.
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
     * Assistant, which the "Talk to the assistant" clarification BUTTON already did, so only the
     * typed form was dead; and (b) say something true about the other four.
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

    private void showClarification(List<ButtonOption> options, String message) {
        // The buttons are always visible on an open tab (see refresh()); what a low-confidence
        // parse adds is FEEDBACK — the play-test's "I wrote something and it doesn't reply"
        // was this branch silently doing nothing visible. The options parameter is kept as the
        // seam for a future context-aware button set (flagged in ClarificationButtons' javadoc).
        //
        // A SPECIFIC message wins over the generic line (2026-07-28, D-05's cross-tab guard):
        // there, the pipeline understood the sentence perfectly and declined it on purpose, so
        // "I didn't get that" would be an outright lie about what happened.
        appendSystem(message != null && !message.isBlank() ? message
                : "I didn't get that — use the buttons below, or try 'how about 6500 for 10 hours'.");
    }

    private void onClarificationClick(ButtonOption option) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null || tab.isClosed()) {
            return;
        }
        // A button click must leave the same visible trace a typed command would (task 19
        // play-test: an un-echoed click made the resulting deal-close look causeless).
        if (!"propose_offer".equals(option.mappedIntent())) {
            tab.appendPlayerMessage(option.label());
            refresh();
        }
        switch (option.mappedIntent()) {
            case "accept_deal" -> eventBus.publish(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, tab.vesselId(), Map.of()));
            case "reject_deal" -> eventBus.publish(new PlayerCommandEvent(PlayerCommandKind.REJECT, tab.vesselId(), Map.of()));
            // Answered locally — no vessel-side behaviour ever replied to a QUERY_REF (dead air).
            case "query_status" -> appendSystem(statusLine());
            case "request_help" -> onHint();
            case "propose_offer" -> {
                inputField.setText("how about ");
                inputField.requestFocusInWindow();
            }
            default -> { }
        }
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

    /** Test-only: the offer strip's current text. */
    String offerStripTextForTest() {
        return offerStripLabel.getText();
    }

    /** Test-only: the live counter's text ({@code m:ss}, or blank). */
    String counterTextForTest() {
        return counterLabel.getText();
    }

    /** Test-only: the live counter's colour. */
    Color counterColourForTest() {
        return counterLabel.getForeground();
    }

    boolean clarificationVisibleForTest() {
        return clarificationPanel.isVisible();
    }

    boolean hintButtonVisibleForTest() {
        return hintButton.isVisible();
    }

    /** Test-only: drives a clarification button click by its {@code mappedIntent} without
     * needing to dig into {@code ClarificationButtonsPanel}'s Swing child components. */
    void clickClarificationForTest(String mappedIntent) {
        ClarificationButtons.defaultOptions().stream()
                .filter(o -> o.mappedIntent().equals(mappedIntent))
                .findFirst()
                .ifPresent(this::onClarificationClick);
    }
}
