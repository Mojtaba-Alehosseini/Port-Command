package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.nlp.DialogueCtx;

/**
 * Pure rendering model for the whole per-dialogue tab set — the tab-lifecycle
 * counterpart to {@link CommLogModel}. No Swing/AWT import; {@code ChatPanel} is the
 * thin Swing view that reacts to {@link #onNegotiationOpened}/{@link
 * #onNegotiationClosed}/{@link #onAssistantChat}'s return values to decide which tab
 * to add/refresh/grey.
 *
 * <p><b>No separate "focused tab" concept.</b> Each {@code DialogueTabView} owns its
 * own input row, so "which dialogue is this utterance for" is answered by
 * construction — whichever tab's Send button fired. {@link #buildCtx} takes that
 * tab's vessel id directly as the focus.
 *
 * <p><b>P-04 (privacy).</b> This class and {@link DialogueTabModel} read ONLY
 * {@link WalkInDialogueSnapshot}'s observable fields — never {@code WalkInState}'s
 * hidden beliefs. {@code NegotiationTabRegistryTest} greps both files to keep it
 * that way (mirrors {@code DialogueCtxTest}'s own gate).
 */
public final class NegotiationTabRegistry {

    /** @param dialogueId the (possibly newly-created) tab's id
     *  @param isNewTab   true iff this call created the tab (view must add a JTabbedPane
     *                    page); false iff an existing tab's round/snapshot was updated
     *                    (view just refreshes that page). */
    public record OpenResult(String dialogueId, boolean isNewTab) {
    }

    /** The orientation line every new tab opens with. S15 (2026-10-12): it no longer quotes an
     * example price — a literal one copied onto a vessel already offering more would close the
     * deal far too cheaply, so the worked example lives in the input field's placeholder, computed
     * from THIS vessel's standing offer ({@link InputExamples}) — and it names the two buttons
     * that replaced the typed 'deal' / 'reject' advice. Hint is not mentioned: the model cannot
     * know the button is hidden while the autopilot is on. */
    static final String ORIENTATION_LINE = "You are the Harbour Master — this vessel pays YOU for berth time. "
            + "Type your price and hours, or press Accept or Reject.";

    private final LinkedHashMap<String, DialogueTabModel> tabs = new LinkedHashMap<>();
    private final Map<String, String> dialogueIdByVesselId = new LinkedHashMap<>();
    /** S15 (2026-10-12): the dialogue ids in the order their tabs were FIRST opened — the order
     * the tab strip shows them in. {@link #tabs} cannot answer that: a later round removes and
     * re-puts its entry on purpose (the recency {@link #rosterSnapshots} needs), so its order
     * changes every time a vessel counters. Kept in step with {@code tabs} at the places a tab is
     * created or dropped (open, a tab leaving the strip, reset). */
    private final List<String> tabOrder = new ArrayList<>();
    /**
     * S18b (playtest 2, PT5): every dialogue that has ended in this world, in the order they
     * ended — the "Finished" list, and the transcripts it brings back. A model stays here after
     * its tab has left the strip ({@link #closeTab}, {@link #trimClosedTabs}); {@link #reopenFinished}
     * puts it back on. Its order is the order of the closes - the order {@link #trimClosedTabs} uses.
     * Session-only: nothing here is saved, and {@link #reset()} (a load, a new game) empties it.
     * Bounded by the game: one entry per walk-in that ever hailed.
     */
    private final LinkedHashMap<String, DialogueTabModel> finished = new LinkedHashMap<>();
    private Runnable onChange = () -> { };

    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /** Creates a tab on round 1, or updates + bumps recency on a later round. */
    public OpenResult onNegotiationOpened(NegotiationOpenedEvent event) {
        String dialogueId = event.dialogueId();
        WalkInDialogueSnapshot snapshot = event.snapshot();
        boolean isNew = !tabs.containsKey(dialogueId);

        DialogueTabModel tab = tabs.remove(dialogueId); // no-op if absent; removing-then-re-putting bumps recency
        if (tab == null) {
            tab = new DialogueTabModel(dialogueId, snapshot);
            dialogueIdByVesselId.put(snapshot.vesselId(), dialogueId);
            tabOrder.add(dialogueId);
            // Task 19 play-test feedback: orient the player — who pays whom, and what to type.
            // (Frame.java's commerce_sell contract: the player SELLS berth space and RECEIVES
            // the money; the vessel is the buyer.) ONE entry, always first in the transcript.
            tab.appendSystemMessage(ORIENTATION_LINE);
        } else {
            // Fallback since 2026-08-10 (audit P1-9): the HM now DOES track the player's standing
            // offer and stamps it into the snapshot, so on the ordinary priced-counter path this
            // branch no longer fires. It still guards the cases where the HM cannot know and the
            // GUI can — chiefly a dialogue whose HM-side entry did not survive (a reload, or a
            // command that reached the tab without passing DispatchPlayerCommandBehaviour) —
            // where dropping to "no offer" would blank the offer strip and the DialogueCtx
            // standing offer on the next vessel counter.
            if (snapshot.lastPlayerOffer() == 0.0 && tab.snapshot().lastPlayerOffer() > 0.0) {
                snapshot = withPlayerOffer(snapshot, tab.snapshot().lastPlayerOffer());
            }
            tab.updateSnapshot(snapshot);
        }
        // S18b (PT3): plain lines with the price and the hours ("They counter: €1,460 for 5h.").
        tab.appendVesselMessage(snapshot.roundsUsed() <= 1 ? OutcomeLines.opening(snapshot)
                : OutcomeLines.counter(snapshot, tab.lastPlayerHours()));
        tabs.put(dialogueId, tab);

        onChange.run();
        return new OpenResult(dialogueId, isNew);
    }

    /**
     * Records the player's outgoing PROPOSE terms on the dialogue it targeted (task 19
     * play-test fix: the offer strip showed "Your offer: €0" forever, and the DialogueCtx
     * standing offer never carried the player side — no event carries it, because the HM's
     * snapshots don't track the player's offers; the GUI is that price's only witness).
     * {@code hours} is the player's proposed stay, or {@code null} when the utterance named
     * none (task 19b). A null resolves to the hours currently under discussion (the vessel's
     * last announced term, {@code snapshot.durationHours()}) — EXACTLY what the vessel side
     * resolves an absent {@code hours} key to ({@code lastOwnHours}), so the strip and the
     * floor-pushback note always reflect what the engine actually heard: a price-only reply
     * to a "we need at least Nh" counter implicitly concedes the Nh.
     */
    public void recordPlayerOffer(String dialogueId, double price, Integer hours) {
        recordPlayerOffer(dialogueId, price, hours, PlayerCommandEvent.NO_COMMAND_ID);
    }

    /**
     * As {@link #recordPlayerOffer(String, double, Integer)}, for a send the Prolog gate may still
     * refuse (S13, live-playtest F-7): the strip shows the terms at once, and {@code commandId} —
     * the id the send's {@link PlayerCommandEvent} carries — remembers what it showed before, so
     * {@link #onPlayerCommandRefused} can put that back.
     */
    public void recordPlayerOffer(String dialogueId, double price, Integer hours, long commandId) {
        DialogueTabModel tab = tabs.get(dialogueId);
        if (tab == null || tab.isClosed()) {
            return;
        }
        if (commandId != PlayerCommandEvent.NO_COMMAND_ID) {
            tab.notePendingOffer(commandId, tab.lastPlayerHours());
        } else {
            tab.clearPendingOffers();
        }
        tab.updateSnapshot(withPlayerOffer(tab.snapshot(), price));
        tab.recordPlayerHours(hours != null ? hours : tab.snapshot().durationHours());
        onChange.run();
    }

    /**
     * S13 (live-playtest F-7): the Prolog gate refused a command, so nothing reached the vessel.
     * If it was a PROPOSE this tab recorded at send time and no later send has replaced it, the
     * strip — and the {@link DialogueCtx} standing offer built from it — goes back to the last
     * standing offer: the price the HarbourMaster says the vessel last heard (it records the
     * player's price only on dispatch, so never a refused one; {@code €0} when there was none),
     * and the hours the strip showed before that send (the HM does not track hours).
     * @return the dialogue whose strip changed, or empty if nothing did (an autopilot or button
     *         command, an unknown or superseded send, another world's id)
     */
    public Optional<String> onPlayerCommandRefused(PlayerCommandRefusedEvent event) {
        // S19 (playtest 5, PT30): the move was never sent, so it is never echoed - whichever tab
        // held its line (a move typed in one tab may be addressed to another vessel by name). S19
        // review: a tab that typed it for ANOTHER ship says where the reason is - the refusal line
        // goes to that ship's tab.
        for (DialogueTabModel held : tabs.values()) {
            if (held.takeHeldEcho(event.commandId()).isPresent() && !held.dialogueId().equals(event.dialogueId())) {
                DialogueTabModel target = tabs.get(event.dialogueId());
                held.appendSystemMessage("Not sent to " + (target == null ? "that ship" : target.snapshot().vesselName())
                        + " — the reason is in its tab.");
            }
        }
        DialogueTabModel tab = tabs.get(event.dialogueId());
        if (tab == null || event.commandId() == PlayerCommandEvent.NO_COMMAND_ID) {
            return Optional.empty();
        }
        if (!tab.refusePendingOffer(event.commandId())) {
            return Optional.empty();
        }
        // The HM's price, not this tab's memory of the strip: an autopilot counter dispatched
        // between the send and this refusal is in the HM's record but was never in this tab's
        // (review finding, S13; since S17a the autopilot's moves carry ids of their own, which
        // never match a send of this tab). The model has already put the hours back.
        tab.updateSnapshot(withPlayerOffer(tab.snapshot(), event.standingPlayerPrice()));
        onChange.run();
        return Optional.of(event.dialogueId());
    }

    private static WalkInDialogueSnapshot withPlayerOffer(WalkInDialogueSnapshot s, double playerOffer) {
        return s.withLastPlayerOffer(playerOffer); // S14: every other field rides along, the counter too
    }

    /** @param nowMillis the close instant ({@code ChatPanel}: sim millis)
     *  @return the closed tab's dialogue id, or empty if the event named an unknown dialogue. */
    public Optional<String> onNegotiationClosed(NegotiationClosedEvent event, long nowMillis) {
        DialogueTabModel tab = tabs.get(event.dialogueId());
        if (tab == null) {
            return Optional.empty();
        }
        tab.close(event.outcome(), nowMillis, event.price(), event.hours());
        // S18b (PT3/U3): the line says how it ended, with the agreed terms of a deal. S19 (playtest
        // 5, PT32): and who - the ship by its name.
        tab.appendSystemMessage(OutcomeLines.closing(tab.snapshot().vesselName(), event.outcome(), event.price(),
                event.hours(), event.vesselAccepted()));
        finished.putIfAbsent(event.dialogueId(), tab); // a repeated close (C-11) keeps its place
        onChange.run();
        return Optional.of(event.dialogueId());
    }

    /**
     * Routes an Assistant message by exact {@code dialogueId} match. Ordinary messages append,
     * closed tab or not — the berth-queue note is ALWAYS published after the deal closed the tab.
     * S12: a Hint reply goes through {@link DialogueTabModel#appendHint}/{@link
     * DialogueTabModel#reviseHint}, which refuse closed tabs and stale revisions; the check and
     * the change happen here in one EDT step, on the state that gets rendered.
     *
     * <p>S17a (ADR-21, decision A3): a Hint's template may carry its card
     * ({@link AssistantChatEvent#card()}) — the recommendation as data. It is stored on the tab
     * with that Hint's id: its details line shows under the hint's text, and the tab's hint row
     * offers it as "Use this" ({@link HintRow}). A revision carries no card and needs none — it
     * adds the model's wording to the hint entry as its Why (S19, PT25: it used to replace the
     * entry's text), so the line, the card and its details stay. The next Hint
     * replaces the card, whether or not it brings one of its own.
     * @return the routed tab's dialogue id, or empty if nothing was shown or changed. */
    public Optional<String> onAssistantChat(AssistantChatEvent event) {
        DialogueTabModel tab = tabs.get(event.dialogueId());
        if (tab == null) {
            // S18b (PT5): a finished dialogue whose tab has left the strip still keeps what is
            // said about it, for when the player opens it again from the Finished list.
            tab = finished.get(event.dialogueId());
        }
        if (tab == null) {
            return Optional.empty();
        }
        boolean shown;
        if (!event.isHint()) {
            tab.appendAssistantMessage(event.text());
            shown = true;
        } else if (event.revision()) {
            shown = tab.reviseHint(event.hintId(), event.text());
        } else {
            shown = tab.appendHint(event.hintId(), event.text(), event.card());
        }
        if (!shown) {
            return Optional.empty();
        }
        onChange.run();
        return Optional.of(event.dialogueId());
    }

    /** S19 (playtest 5, PT26): at most this many CLOSED tabs stay on the strip ({@link #trimClosedTabs}). */
    public static final int MAX_CLOSED_TABS = 5;

    /**
     * S19 (playtest 5, PT26): the player's x on a closed tab. The tab leaves the strip - its model
     * and its vessel mapping go, as they did when audit C-07's 30-second sweep took a tab - and it
     * stays in the Finished list, which brings it back on a click. An open negotiation has no x:
     * with its tab gone, a vessel still waiting for an answer would be out of sight.
     *
     * @return {@code true} when the tab left the strip; {@code false} for an open tab or one not on it
     */
    public boolean closeTab(String dialogueId) {
        DialogueTabModel tab = dialogueId == null ? null : tabs.get(dialogueId);
        if (tab == null || !tab.isClosed()) {
            return false;
        }
        leaveStrip(dialogueId);
        onChange.run();
        return true;
    }

    /**
     * S19 (playtest 5, PT26): while more than {@link #MAX_CLOSED_TABS} closed tabs are on the strip,
     * the one that closed first leaves it for the Finished list - never {@code reading}, the tab the
     * player has selected. Until S19 every closed tab left by itself 30 seconds after it closed
     * (audit C-07, F-9's pile-up of closed tabs); playtest 5 found tabs leaving that the player still
     * wanted. The player closes them now; this only keeps the pile-up bounded.
     *
     * @param reading the selected dialogue's id, or {@code null} when none is selected
     * @return the ids that left, in the order they closed
     */
    public List<String> trimClosedTabs(String reading) {
        List<String> closedOnStrip = new ArrayList<>();
        for (String id : finished.keySet()) { // the order they closed in
            DialogueTabModel tab = tabs.get(id);
            if (tab != null && tab.isClosed()) {
                closedOnStrip.add(id);
            }
        }
        int excess = closedOnStrip.size() - MAX_CLOSED_TABS;
        List<String> left = new ArrayList<>();
        for (String id : closedOnStrip) {
            if (left.size() >= excess) {
                break;
            }
            if (!id.equals(reading)) {
                leaveStrip(id);
                left.add(id);
            }
        }
        if (!left.isEmpty()) {
            onChange.run();
        }
        return List.copyOf(left);
    }

    /** A tab leaves the strip: its model, its vessel mapping and its place in the tab order go; the
     * model stays in {@link #finished}. */
    private void leaveStrip(String dialogueId) {
        DialogueTabModel gone = tabs.remove(dialogueId);
        dialogueIdByVesselId.remove(gone.vesselId(), dialogueId); // two-arg: never drop a newer mapping
        tabOrder.remove(dialogueId); // S15: the id leaves the strip order with its tab
    }

    /** S19 review (PT30): the tab that holds the line of {@code commandId}, if any. */
    public Optional<String> tabHoldingEcho(long commandId) {
        for (DialogueTabModel tab : tabs.values()) {
            if (tab.holdsEcho(commandId)) {
                return Optional.of(tab.dialogueId());
            }
        }
        return Optional.empty();
    }

    /**
     * S19 (playtest 5, PT30): a move the HarbourMaster has sent. The tab it was typed in held its
     * "→ ..." line until now ({@link DialogueTabModel#holdEcho}); it is written here, once. A move
     * the Prolog gate refused is never written - {@link #onPlayerCommandRefused} drops its line.
     *
     * @return the tab the line was written in, or empty (an autopilot move, a line already written)
     */
    public Optional<String> onPlayerCommandDispatched(PlayerCommandDispatchedEvent event) {
        for (DialogueTabModel tab : tabs.values()) {
            Optional<String> echo = tab.takeHeldEcho(event.commandId());
            if (echo.isPresent()) {
                tab.appendSystemMessage(echo.get());
                onChange.run();
                return Optional.of(tab.dialogueId());
            }
        }
        return Optional.empty();
    }

    /** Task 22: a loaded game has no open dialogues — drop every tab and mapping. S18b (PT5): and
     * no finished ones either; the Finished list belongs to the world that is gone. */
    public void reset() {
        tabs.clear();
        dialogueIdByVesselId.clear();
        tabOrder.clear();
        finished.clear();
        onChange.run();
    }

    /**
     * S18b (playtest 2, PT5): every negotiation that has ended in this world, newest first — the
     * left column's "Finished" list. P-04: the vessel's display name and the close's own terms.
     */
    public List<FinishedDialogue> finishedDialogues() {
        List<FinishedDialogue> newestFirst = new ArrayList<>();
        for (DialogueTabModel tab : finished.values()) {
            newestFirst.add(new FinishedDialogue(tab.dialogueId(), tab.snapshot().vesselName(), tab.outcome(),
                    tab.agreedPrice(), tab.agreedHours()));
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    /**
     * S18b (playtest 2, PT5): puts a finished dialogue's tab back on the strip, read-only as every
     * closed tab is, at the end of the tab order. S19 (PT26): it leaves again by its x, or as the
     * oldest closed tab once more than {@link #MAX_CLOSED_TABS} are on the strip.
     *
     * @return {@code true} when the dialogue's tab is on the strip now (put back, or still there);
     *         {@code false} for an id this world never finished
     */
    public boolean reopenFinished(String dialogueId) {
        if (tabs.containsKey(dialogueId)) {
            return true;
        }
        DialogueTabModel tab = finished.get(dialogueId);
        if (tab == null) {
            return false;
        }
        tabs.put(dialogueId, tab);
        tabOrder.add(dialogueId);
        onChange.run();
        return true;
    }

    public DialogueTabModel tab(String dialogueId) {
        return tabs.get(dialogueId);
    }

    /** Every tab's id, closed tabs included while they are on the strip, least-recently-updated first.
     * That is the order the dialogues opened in only until one of them goes a second round — a
     * round update re-inserts its entry in {@link #tabs}. S15 (2026-10-12): this comment used to
     * call it the tab display order; {@link #openDialogues} is the view that keeps that order. */
    public List<String> dialogueIds() {
        return List.copyOf(tabs.keySet());
    }

    public Optional<String> dialogueIdForVessel(String vesselId) {
        return Optional.ofNullable(dialogueIdByVesselId.get(vesselId));
    }

    /**
     * S15 (2026-10-12): the negotiations still open, in the order their tabs sit on the strip —
     * what the rest of the window lists ("Waiting for you"), selects and follows. A closed tab is
     * left out from the moment it closes, although its tab stays on the strip until its x: no
     * one is waiting on a finished negotiation. A vessel countering does not move its entry
     * (see {@link #tabOrder}), so the list cannot reshuffle under the player's cursor.
     *
     * <p>P-04: built from the snapshot's vessel id and display name only — both observable (the
     * name is the tab's own title, and it is the id when the vessel has none).
     */
    public List<OpenDialogue> openDialogues() {
        List<OpenDialogue> open = new ArrayList<>();
        for (String dialogueId : tabOrder) {
            DialogueTabModel tab = tabs.get(dialogueId);
            if (tab == null || tab.isClosed()) {
                continue;
            }
            WalkInDialogueSnapshot snapshot = tab.snapshot();
            open.add(new OpenDialogue(dialogueId, snapshot.vesselId(), snapshot.vesselName()));
        }
        return List.copyOf(open);
    }

    /** Every OPEN dialogue's current snapshot, most-recently-updated first (a closed
     * dialogue's vessel is no longer in play, so it drops out of the anaphora/vocative
     * roster). */
    public List<WalkInDialogueSnapshot> rosterSnapshots() {
        List<WalkInDialogueSnapshot> mostRecentLast = new ArrayList<>();
        for (DialogueTabModel tab : tabs.values()) {
            if (!tab.isClosed()) {
                mostRecentLast.add(tab.snapshot());
            }
        }
        Collections.reverse(mostRecentLast);
        return List.copyOf(mostRecentLast);
    }

    /** Builds a {@link DialogueCtx} focused on {@code focusVesselId} (the tab the player
     * is typing into) from every open dialogue's observable snapshot. Reuses the
     * existing, already P-04-gated {@link DialogueCtx#from} — no new privacy surface. */
    public DialogueCtx buildCtx(String focusVesselId) {
        return DialogueCtx.from(rosterSnapshots(), focusVesselId);
    }
}
