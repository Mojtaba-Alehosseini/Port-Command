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

    private final LinkedHashMap<String, DialogueTabModel> tabs = new LinkedHashMap<>();
    private final Map<String, String> dialogueIdByVesselId = new LinkedHashMap<>();
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
            // Task 19 play-test feedback: orient the player — who pays whom, and what to type.
            // (Frame.java's commerce_sell contract: the player SELLS berth space and RECEIVES
            // the money; the vessel is the buyer.)
            tab.appendSystemMessage("You are the Harbour Master — this vessel pays YOU for berth time. "
                    + "Counter with your fee (e.g. 'how about 6500 for 10 hours'), type 'deal' to accept "
                    + "their offer, 'reject' to turn them away, or press Hint for advice.");
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
        tab.appendVesselMessage(offerSummary(snapshot, tab.lastPlayerHours()));
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
        DialogueTabModel tab = tabs.get(dialogueId);
        if (tab == null || tab.isClosed()) {
            return;
        }
        tab.updateSnapshot(withPlayerOffer(tab.snapshot(), price));
        tab.recordPlayerHours(hours != null ? hours : tab.snapshot().durationHours());
        onChange.run();
    }

    private static WalkInDialogueSnapshot withPlayerOffer(WalkInDialogueSnapshot s, double playerOffer) {
        return new WalkInDialogueSnapshot(s.vesselId(), s.vesselType(), s.draft(), s.length(), s.tonnage(),
                s.cargoClass(), s.berthId(), s.durationHours(), s.lastVesselOffer(), playerOffer,
                s.roundsUsed(), s.timeUsedSec(), s.threatenedWithdrawal(), s.vesselName());
    }

    /** @param nowMillis the close instant, in the same clock {@link #sweepClosed} is driven by
     *  @return the closed tab's dialogue id, or empty if the event named an unknown dialogue. */
    public Optional<String> onNegotiationClosed(NegotiationClosedEvent event, long nowMillis) {
        DialogueTabModel tab = tabs.get(event.dialogueId());
        if (tab == null) {
            return Optional.empty();
        }
        tab.close(event.outcome(), nowMillis);
        String summary = switch (event.outcome()) {
            case DEAL -> "Deal closed.";
            case WITHDRAW_PRICE -> "Vessel withdrew (price never met).";
            case WITHDRAW_DURATION -> "Vessel withdrew (you never granted the hours it needs).";
            case TIMEOUT -> "Vessel withdrew (negotiation timed out).";
            case PLAYER_REFUSED -> "You turned them away.";
        };
        tab.appendSystemMessage(summary);
        onChange.run();
        return Optional.of(event.dialogueId());
    }

    /**
     * Routes an Assistant message by exact {@code dialogueId} match. Ordinary messages append,
     * closed tab or not — the berth-queue note is ALWAYS published after the deal closed the tab.
     * S12: a Hint reply goes through {@link DialogueTabModel#appendHint}/{@link
     * DialogueTabModel#reviseHint}, which refuse closed tabs and stale revisions; the check and
     * the change happen here in one EDT step, on the state that gets rendered.
     * @return the routed tab's dialogue id, or empty if nothing was shown or changed. */
    public Optional<String> onAssistantChat(AssistantChatEvent event) {
        DialogueTabModel tab = tabs.get(event.dialogueId());
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
            shown = tab.appendHint(event.hintId(), event.text());
        }
        if (!shown) {
            return Optional.empty();
        }
        onChange.run();
        return Optional.of(event.dialogueId());
    }

    /**
     * Audit C-07: removes every CLOSED tab whose dwell has run out — its model and its vessel
     * mapping — and returns the removed ids in tab order. Open tabs never expire. Pure: the
     * caller supplies both instants in the clock {@link #onNegotiationClosed} was stamped with.
     * The stamp lives on the model, so {@link #reset()} discards it along with the model and a
     * same-id tab in the next world can never inherit an old expiry.
     */
    public List<String> sweepClosed(long nowMillis, long dwellMillis) {
        List<String> expired = new ArrayList<>();
        for (DialogueTabModel tab : tabs.values()) {
            if (tab.isClosed() && nowMillis - tab.closedAtMillis() >= dwellMillis) {
                expired.add(tab.dialogueId());
            }
        }
        for (String id : expired) {
            DialogueTabModel gone = tabs.remove(id);
            dialogueIdByVesselId.remove(gone.vesselId(), id); // two-arg: never drop a newer mapping
        }
        if (!expired.isEmpty()) {
            onChange.run();
        }
        return List.copyOf(expired);
    }

    /** Task 22: a loaded game has no open dialogues — drop every tab and mapping. */
    public void reset() {
        tabs.clear();
        dialogueIdByVesselId.clear();
        onChange.run();
    }

    public DialogueTabModel tab(String dialogueId) {
        return tabs.get(dialogueId);
    }

    /** Tab display order (insertion order — the order dialogues first opened in). */
    public List<String> dialogueIds() {
        return List.copyOf(tabs.keySet());
    }

    public Optional<String> dialogueIdForVessel(String vesselId) {
        return Optional.ofNullable(dialogueIdByVesselId.get(vesselId));
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

    /**
     * The vessel's transcript line for an opening/counter. Task 19b: when the vessel's hours
     * term came back ABOVE the player's own proposed stay, this counter is the duration floor
     * pushing back — say so ("they need at least Nh"), the §7.3 bargaining reply. Derived
     * entirely from observable terms on the wire (the two hours values); no hidden belief is
     * read — the floor's VALUE is public exactly when, and because, the vessel counters at it.
     */
    private static String offerSummary(WalkInDialogueSnapshot snapshot, Integer playerHours) {
        String verb = snapshot.roundsUsed() <= 1 ? "Opens at" : "Counters at";
        String line = verb + " €" + Math.round(snapshot.lastVesselOffer()) + " for "
                + snapshot.durationHours() + "h at " + snapshot.berthId();
        if (playerHours != null && snapshot.durationHours() > playerHours) {
            line += " — they need at least " + snapshot.durationHours() + "h";
        }
        return line;
    }
}
