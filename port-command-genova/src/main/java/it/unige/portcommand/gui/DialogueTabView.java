package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import it.unige.portcommand.nlp.ButtonOption;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.nlp.ClarificationButtons;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.util.EventBus;
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

    private final String dialogueId;
    private final NegotiationTabRegistry registry;
    private final EventBus eventBus;
    private final ChatInputProcessor processor;

    private final JTextPane transcriptPane = new JTextPane();
    private final JLabel offerStripLabel = new JLabel();
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton hintButton = new JButton("Hint");
    private final ClarificationButtonsPanel clarificationPanel;

    DialogueTabView(String dialogueId, NegotiationTabRegistry registry, EventBus eventBus,
                    ChatInputProcessor processor) {
        super(new BorderLayout(4, 4));
        this.dialogueId = dialogueId;
        this.registry = registry;
        this.eventBus = eventBus;
        this.processor = processor;

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

        add(offerStripLabel, BorderLayout.NORTH);
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
        boolean closed = tab.isClosed();
        offerStripLabel.setText(offerStripText(tab, closed));
        inputField.setEnabled(!closed);
        sendButton.setEnabled(!closed);
        hintButton.setVisible(!closed && !Settings.load().autopilotEnabled());
        // Task 19 play-test fix: the buttons are a PERSISTENT quick-action bar on every open
        // tab, not a fallback that only appears after a failed parse — players expected them
        // to always be there ("the buttons are not always appear!").
        clarificationPanel.setVisible(!closed);
    }

    /** Task 19b: both §7.3 dimensions on the strip — their €X for Yh / your €A for Bh. The
     * player's hours fall back to the hours under discussion until they name their own. */
    private static String offerStripText(DialogueTabModel tab, boolean closed) {
        if (closed) {
            return "Negotiation closed.";
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        int playerHours = tab.lastPlayerHours() != null ? tab.lastPlayerHours() : s.durationHours();
        int roundLimit = Settings.load().roundLimit();
        long remaining = Math.max(0L, Settings.load().timeLimitSeconds() - Math.round(s.timeUsedSec()));
        return String.format(
                "Their offer: €%.0f for %dh  |  Your offer: €%.0f for %dh  |  Round %d of %d  |  ~%ds remaining",
                s.lastVesselOffer(), s.durationHours(), s.lastPlayerOffer(), playerHours,
                s.roundsUsed(), roundLimit, remaining);
    }

    private void onSend() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            sendText(text);
        }
    }

    /** Package-private so a headless test can drive a send without simulating real
     * JTextField/ActionEvent plumbing. */
    void sendText(String text) {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab == null) {
            return;
        }
        inputField.setText("");
        clarificationPanel.setVisible(false);
        tab.appendPlayerMessage(text);
        refresh();

        String vesselId = tab.vesselId();
        DialogueCtx ctx = registry.buildCtx(vesselId);
        processor.process(text, ctx).whenComplete((result, error) -> SwingUtilities.invokeLater(() ->
                handleResult(vesselId, error != null ? new PipelineResult.Error(String.valueOf(error.getMessage())) : result)));
    }

    private void handleResult(String typingVesselId, PipelineResult result) {
        switch (result) {
            case PipelineResult.Routed routed -> dispatchRouted(typingVesselId, routed);
            case PipelineResult.NeedsClarification nc -> showClarification(nc.buttons());
            case PipelineResult.Error e -> appendSystem("I couldn't parse that.");
        }
    }

    /** {@code targetVesselId} may differ from the tab typed into (a 16-M2 vocative, e.g.
     * "tell the tanker: deal") — that's fine, it's just the published event's payload;
     * nothing about rendering THIS tab depends on which vessel ends up targeted. */
    private void dispatchRouted(String typingVesselId, PipelineResult.Routed routed) {
        String targetVesselId = routed.addressee() != null ? routed.addressee() : typingVesselId;
        PlayerCommandKind kind = kindFor(routed.msg().getPerformative());
        if (kind == null) {
            appendSystem(routed.msg().getPerformative() == ACLMessage.INFORM
                    ? "Noted." : "Fleet-wide commands aren't wired to a dialogue tab yet.");
            return;
        }
        // Status is an OBSERVABLE, GUI-local concept — the tab already holds everything a
        // status reply could say, and no vessel-side behaviour answers a QUERY_REF (verified:
        // EvaluateCounterOfferBehaviour's template matches PROPOSE/ACCEPT/REJECT/CANCEL only),
        // so dispatching one was pure dead air in the play-test. Answer locally instead.
        if (kind == PlayerCommandKind.ASK) {
            appendSystem(statusLine());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frameElements =
                (Map<String, Object>) TerminalJson.readOrNull(routed.msg().getContent(), Map.class);
        Map<String, Object> content = toVesselProtocol(frameElements);
        if (kind == PlayerCommandKind.PROPOSE && content.get("price") instanceof Number price) {
            // The offer strip + DialogueCtx standing offer need the player's side; no event
            // carries it back (the HM doesn't track it), so record it at the send. Task 19b:
            // the proposed HOURS ride along (null when the utterance named none — the standing
            // value holds). The task-19 "only the fee is negotiable" note is gone: duration is
            // a real §7.3 dimension now, and the bargaining feedback is the vessel's own reply
            // ("they need at least Nh" on a floor pushback — see the registry's offerSummary).
            Integer proposedHours = content.get("hours") instanceof Number n ? n.intValue() : null;
            registry.dialogueIdForVessel(targetVesselId)
                    .ifPresent(id -> registry.recordPlayerOffer(id, price.doubleValue(), proposedHours));
        }
        eventBus.publish(new PlayerCommandEvent(kind, targetVesselId, content));
        // Echo how the NL was INTERPRETED (task 19 play-test: Rasa read a bare "so?" as an
        // acceptance and the deal closed with no visible cause — every routed action must be
        // legible). Button clicks skip this: their own label bubble already is the echo.
        appendSystem(interpretationEcho(kind, content, targetVesselId, typingVesselId));
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
        if (tab == null) {
            return "No active negotiation.";
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        return String.format("Status — their offer: €%.0f · your offer: €%.0f · round %d of %d · %dh at %s.",
                s.lastVesselOffer(), s.lastPlayerOffer(), s.roundsUsed(), Settings.load().roundLimit(),
                s.durationHours(), s.berthId());
    }

    /** One line saying what the pipeline UNDERSTOOD, appended after every routed dispatch. */
    private static String interpretationEcho(PlayerCommandKind kind, Map<String, Object> content,
                                             String targetVesselId, String typingVesselId) {
        String target = targetVesselId.equals(typingVesselId) ? "" : " (to " + targetVesselId + ")";
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

    private void showClarification(List<ButtonOption> options) {
        // The buttons are always visible on an open tab (see refresh()); what a low-confidence
        // parse adds is FEEDBACK — the play-test's "I wrote something and it doesn't reply"
        // was this branch silently doing nothing visible. The options parameter is kept as the
        // seam for a future context-aware button set (flagged in ClarificationButtons' javadoc).
        appendSystem("I didn't get that — use the buttons below, or try 'how about 6500 for 10 hours'.");
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

    private void onHint() {
        DialogueTabModel tab = registry.tab(dialogueId);
        if (tab != null) {
            eventBus.publish(new HintButtonEvent(dialogueId, tab.snapshot()));
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
