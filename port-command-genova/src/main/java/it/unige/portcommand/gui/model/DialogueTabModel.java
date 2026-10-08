package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.ontology.Deal;

/**
 * Pure rendering model for one walk-in dialogue's chat tab. No Swing/AWT import — the
 * transcript, current offer/round state, and closed/outcome flag live here;
 * {@code DialogueTabView} is the thin Swing rendering of it. Reads ONLY
 * {@link WalkInDialogueSnapshot}'s observable fields (P-04 — see
 * {@code NegotiationTabRegistryTest}'s grep gate, which covers this file too).
 */
public final class DialogueTabModel {

    public enum Speaker { PLAYER, VESSEL, ASSISTANT, SYSTEM }

    public record ChatEntry(Speaker speaker, String text) {
        public ChatEntry {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
        }
    }

    /** S13 (F-7): a PROPOSE whose terms the strip shows before the Prolog gate has judged it,
     * and the hours the strip showed before it ({@code null} = none named yet) — restored if the
     * gate refuses it. The price needs no memory here: the refusal carries the HarbourMaster's. */
    private record PendingOffer(long commandId, Integer hoursBefore) {
    }

    private final String dialogueId;
    private final List<ChatEntry> transcript = new ArrayList<>();
    private WalkInDialogueSnapshot snapshot;
    private Integer lastPlayerHours; // task 19b: the player's last PROPOSED stay; null until they name one
    private boolean closed;
    private Deal.Outcome outcome;
    private long closedAtMillis;
    /** S12: the newest Hint shown in this tab, and where its entry sits — the only one a revision
     * may replace. The transcript never removes or reorders entries, so the index stays valid. */
    private long latestHintId = AssistantChatEvent.NO_HINT;
    private int latestHintIndex = -1;
    /** S13 (F-7): chat PROPOSEs not yet known to have passed the gate, in send order. Bounded by
     * the negotiation's own length (one entry per send); the gate judges commands in send order,
     * so a refusal also settles every older entry — see {@link #refusePendingOffer}. */
    private final List<PendingOffer> pendingOffers = new ArrayList<>();

    public DialogueTabModel(String dialogueId, WalkInDialogueSnapshot snapshot) {
        this.dialogueId = Objects.requireNonNull(dialogueId, "dialogueId");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public String dialogueId() {
        return dialogueId;
    }

    public String vesselId() {
        return snapshot.vesselId();
    }

    public WalkInDialogueSnapshot snapshot() {
        return snapshot;
    }

    public void updateSnapshot(WalkInDialogueSnapshot newSnapshot) {
        this.snapshot = Objects.requireNonNull(newSnapshot, "newSnapshot");
    }

    /**
     * The stay length the player's last PROPOSE effectively carried, or {@code null} before
     * their first offer (task 19b — §7.3's second dimension). GUI-local for the same reason as
     * the registry's player-offer carry-over: the HM's snapshots never track the player's side,
     * so this model is the value's only witness. The registry resolves a duration-less
     * utterance to the hours under discussion before recording — mirroring how the vessel
     * resolves an absent {@code hours} key — so this is always what the engine actually heard.
     */
    public Integer lastPlayerHours() {
        return lastPlayerHours;
    }

    /** Records the player's effective proposed stay ({@code null} guard kept for safety). */
    public void recordPlayerHours(Integer hours) {
        if (hours != null) {
            this.lastPlayerHours = hours;
        }
    }

    /** S13 (F-7): call BEFORE the strip shows send {@code commandId}'s terms; {@code hoursBefore}
     * is the {@link #lastPlayerHours()} it showed until then. */
    public void notePendingOffer(long commandId, Integer hoursBefore) {
        pendingOffers.add(new PendingOffer(commandId, hoursBefore));
    }

    /** S13 (F-7): an offer written WITHOUT an id cannot be taken back and replaces every pending
     * one on the strip, so no later refusal may roll the strip back past it. */
    public void clearPendingOffers() {
        pendingOffers.clear();
    }

    /**
     * S13 (F-7): the Prolog gate refused send {@code commandId}. When that send is still the
     * NEWEST one, puts back the hours it overwrote and returns true — the caller then restores the
     * price. False when it is unknown here (the autopilot's, an old world's, already settled) or a
     * later send already replaced it on the strip — then that later send inherits the refused
     * one's {@code hoursBefore}, because a refused offer never stood. Every send OLDER than the
     * refused one is settled too: the gate judges commands in send order, and their own refusals
     * would have arrived first, so they passed.
     */
    public boolean refusePendingOffer(long commandId) {
        int refused = -1;
        for (int i = 0; i < pendingOffers.size(); i++) {
            if (pendingOffers.get(i).commandId() == commandId) {
                refused = i;
                break;
            }
        }
        if (refused < 0) {
            return false;
        }
        Integer hoursBefore = pendingOffers.get(refused).hoursBefore();
        boolean newest = refused == pendingOffers.size() - 1;
        if (newest) {
            lastPlayerHours = hoursBefore; // null included: "no hours named yet"
        } else {
            PendingOffer next = pendingOffers.get(refused + 1);
            pendingOffers.set(refused + 1, new PendingOffer(next.commandId(), hoursBefore));
        }
        pendingOffers.subList(0, refused + 1).clear();
        return newest;
    }

    public List<ChatEntry> transcript() {
        return List.copyOf(transcript);
    }

    public void appendVesselMessage(String text) {
        transcript.add(new ChatEntry(Speaker.VESSEL, text));
    }

    public void appendPlayerMessage(String text) {
        transcript.add(new ChatEntry(Speaker.PLAYER, text));
    }

    public void appendAssistantMessage(String text) {
        transcript.add(new ChatEntry(Speaker.ASSISTANT, text));
    }

    public void appendSystemMessage(String text) {
        transcript.add(new ChatEntry(Speaker.SYSTEM, text));
    }

    /**
     * S12: shows Hint press {@code hintId}'s template and makes it the one revision may replace.
     * @return false (nothing shown) once the tab is closed — advice for a finished negotiation
     */
    public boolean appendHint(long hintId, String text) {
        if (closed) {
            return false;
        }
        transcript.add(new ChatEntry(Speaker.ASSISTANT, text));
        latestHintId = hintId;
        latestHintIndex = transcript.size() - 1;
        return true;
    }

    /**
     * S12: replaces Hint {@code hintId}'s template with the model's validated wording, in place.
     * Refused (false, nothing changed) when the tab is closed, or when {@code hintId} is not the
     * newest Hint this tab showed: a late answer never overwrites a newer hint, and an id from
     * another world (a reload recreates the same dialogue id) matches nothing.
     */
    public boolean reviseHint(long hintId, String text) {
        if (closed || hintId == AssistantChatEvent.NO_HINT || hintId != latestHintId) {
            return false;
        }
        transcript.set(latestHintIndex, new ChatEntry(Speaker.ASSISTANT, text));
        return true;
    }

    public boolean isClosed() {
        return closed;
    }

    /** {@code null} until {@link #close} is called. */
    public Deal.Outcome outcome() {
        return outcome;
    }

    /** When the tab first closed, in the caller's clock ({@code ChatPanel}: sim millis); 0 while open. */
    public long closedAtMillis() {
        return closedAtMillis;
    }

    /** The first close fixes {@link #closedAtMillis}: a repeated close (audit C-11) must not
     * restart the dwell before the tab leaves the strip. */
    public void close(Deal.Outcome outcome, long atMillis) {
        if (!closed) {
            closedAtMillis = atMillis;
        }
        this.closed = true;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }
}
