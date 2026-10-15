package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import it.unige.portcommand.assistant.HintCard;
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

    /**
     * One line of the transcript.
     *
     * @param tag S15 (2026-10-12): the small grey note shown after a PLAYER message saying how it
     *            was read — {@code (DCG)} / {@code (Rasa 0.97)}, see {@link ParseTag}. Empty for
     *            a player message nothing has been said about yet (the parse is still running, or
     *            the turn was not routed). S17a (ADR-21): on the ASSISTANT entry of a Hint that
     *            came with a card it is the card's details line ({@link HintCard#details()}),
     *            drawn under the hint's text in the same small grey — the one other entry that
     *            carries a note. Empty for every other entry. Never {@code null}.
     */
    /**
     * @param why S19 (playtest 5, PT25): under a Hint, the model's explanation - added below the
     *            short line, which never changes; empty for every other entry
     * @param tip S19 (PT25 / PT33 / PT42): the tooltip of a Hint's details line ({@code tag}) - what
     *            its parts mean and what the cost is made of; empty for every other entry
     */
    public record ChatEntry(Speaker speaker, String text, String tag, String why, String tip) {
        public ChatEntry {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
            tag = tag == null ? "" : tag;
            why = why == null ? "" : why;
            tip = tip == null ? "" : tip;
        }

        /** An untagged line — every entry is born this way. Kept as its own constructor so the
         * pre-S15 two-argument form (and record equality against it) means exactly what it did. */
        public ChatEntry(Speaker speaker, String text) {
            this(speaker, text, "");
        }

        /** A tagged line (S15: a player's parse note; S17a: a Hint's details). */
        public ChatEntry(Speaker speaker, String text, String tag) {
            this(speaker, text, tag, "", "");
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
    /** S19 (playtest 5, PT30): the "→ ..." lines of sent moves the HarbourMaster has not answered
     * for yet, by the move's command id - see {@link #holdEcho}. */
    private final Map<Long, String> heldEchoes = new LinkedHashMap<>();
    /** S18b (UI tree U3): what a deal was agreed at, from the close itself; 0 until then, and for
     * a negotiation that ended without one. */
    private double agreedPrice;
    private int agreedHours;
    /** S12: the newest Hint shown in this tab, and where its entry sits — the only one a revision
     * may replace. The transcript never removes or reorders entries, so the index stays valid. */
    private long latestHintId = AssistantChatEvent.NO_HINT;
    private int latestHintIndex = -1;
    /** S17a (ADR-21): the newest Hint's recommendation as data — what the tab's hint row offers
     * as "Use this" — or {@code null} when that Hint came without one (or no Hint was shown yet).
     * It belongs to {@link #latestHintId}: a newer Hint replaces it, a revision of the text leaves
     * it alone. Whether it still answers the offer on the table is NOT stored here; it is asked of
     * the card each time ({@link HintCard#describes}), against the snapshot as it is then. */
    private HintCard hintCard;
    /** S17a: true once the player has sent what {@link #hintCard} recommends. One card, one send. */
    private boolean hintCardUsed;
    /** S13 (F-7): chat PROPOSEs not yet known to have passed the gate, in send order. Bounded by
     * the negotiation's own length (one entry per send); the gate judges commands in send order,
     * so a refusal also settles every older entry — see {@link #refusePendingOffer}. */
    private final List<PendingOffer> pendingOffers = new ArrayList<>();
    /** S15 (2026-10-12): how many messages the player has sent in this tab — the index the input
     * field's rotating example advances on ({@link InputExamples}). Counted here, at the append,
     * because the transcript is the only witness: a send the pipeline later fails to understand
     * is still a send. */
    private int playerMessageCount;

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

    /**
     * Appends what the player sent.
     *
     * @return S15 (2026-10-12): the new entry's index in {@link #transcript()} — the handle
     *         {@link #tagPlayerMessage} takes once the parse has come back. It stays valid for the
     *         tab's whole life: the transcript never removes or reorders entries.
     */
    public int appendPlayerMessage(String text) {
        transcript.add(new ChatEntry(Speaker.PLAYER, text));
        playerMessageCount++;
        return transcript.size() - 1;
    }

    /**
     * S15 (2026-10-12): says how the player's message at {@code index} was read, in place — the
     * bubble is on screen before the parse finishes, so the note can only arrive afterwards
     * ({@link #reviseHint} is the precedent for replacing an entry where it stands). The text of
     * the message is untouched. A closed tab still takes a tag: a parse that finishes just after
     * the negotiation ended was still read by one stage or the other.
     *
     * @param index the value {@link #appendPlayerMessage} returned for that message
     * @param tag   the note to show ({@code null} or empty clears it)
     * @return false (nothing changed) when {@code index} does not name a PLAYER entry — out of
     *         range, or another speaker's line. A caller holding an index from another tab or
     *         another world therefore cannot write a tag onto a vessel's or the Assistant's words.
     */
    public boolean tagPlayerMessage(int index, String tag) {
        if (index < 0 || index >= transcript.size()) {
            return false;
        }
        ChatEntry entry = transcript.get(index);
        if (entry.speaker() != Speaker.PLAYER) {
            return false;
        }
        transcript.set(index, new ChatEntry(Speaker.PLAYER, entry.text(), tag));
        return true;
    }

    /** S15 (2026-10-12): how many messages the player has sent in this tab (never decreases). */
    public int playerMessageCount() {
        return playerMessageCount;
    }

    /**
     * S15 (2026-10-12): whether the player has an offer standing — the same sentinel
     * {@code DialogueCtx.StandingOffer.from} reads ({@code lastPlayerOffer > 0}), so the offer
     * line, the input examples and the grammar's context always agree on it. It goes back to
     * false when the Prolog gate refuses the player's first offer (the registry restores €0).
     */
    public boolean playerHasOffered() {
        return snapshot.lastPlayerOffer() > 0;
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
        return appendHint(hintId, text, null);
    }

    /**
     * S17a (ADR-21): as {@link #appendHint(long, String)}, with the Hint's card. The card's
     * details line goes onto the hint's own entry (its {@link ChatEntry#tag}), and the card
     * becomes the one the hint row offers — unused, whatever happened to the card before it. A
     * Hint without a card ({@code null}) takes the previous card away all the same: the row
     * always speaks for the newest Hint, never for one the transcript has moved past.
     * @return false (nothing shown, the previous card kept) once the tab is closed
     */
    public boolean appendHint(long hintId, String text, HintCard card) {
        if (closed) {
            return false;
        }
        transcript.add(new ChatEntry(Speaker.ASSISTANT, text, card == null ? "" : card.details(), "",
                card == null ? "" : card.detailsTip()));
        latestHintId = hintId;
        latestHintIndex = transcript.size() - 1;
        hintCard = card;
        hintCardUsed = false;
        return true;
    }

    /**
     * S12: Hint {@code hintId}'s template gets the model's validated wording, in place - since S19
     * (playtest 5, PT25) as the entry's Why, under the unchanged line; until S19 it replaced the line.
     * Refused (false, nothing changed) when the tab is closed, or when {@code hintId} is not the
     * newest Hint this tab showed: a late answer never overwrites a newer hint, and an id from
     * another world (a reload recreates the same dialogue id) matches nothing.
     *
     * <p>S17a (ADR-21): the details line and the card behind the hint row are the recommendation
     * itself — the model explains the sentence, it does not recompute anything — so both stay
     * exactly as the template brought them.
     */
    public boolean reviseHint(long hintId, String text) {
        if (closed || hintId == AssistantChatEvent.NO_HINT || hintId != latestHintId) {
            return false;
        }
        // S19 (playtest 5, PT25): the short line never changes - it used to be replaced a few seconds
        // after it appeared. The model's wording is added under it, as the Why.
        ChatEntry hint = transcript.get(latestHintIndex);
        transcript.set(latestHintIndex, new ChatEntry(Speaker.ASSISTANT, hint.text(), hint.tag(), text, hint.tip()));
        return true;
    }

    /**
     * S17a (ADR-21): the newest Hint's card while the player can still act on it — empty when no
     * Hint brought one, or once it has been used ({@link #useHintCard}). A closed tab still
     * answers with the card it had; whether a row is drawn for it is {@link HintRow}'s decision.
     */
    public Optional<HintCard> hintCard() {
        return hintCardUsed ? Optional.empty() : Optional.ofNullable(hintCard);
    }

    /** S17a (ADR-21): the player sent what the card recommends — it is used up, and
     * {@link #hintCard()} is empty until the next Hint brings a new one. */
    public void useHintCard() {
        hintCardUsed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * S19 (playtest 5, PT30): keeps a move's "→ You ask ..." line until the HarbourMaster has sent
     * the move. A move the Prolog gate refuses is never echoed - "→ Demanding €2,000 for 5h" used to
     * show for a move the gate refused. {@link NegotiationTabRegistry} writes the line, or drops it.
     */
    public void holdEcho(long commandId, String line) {
        heldEchoes.put(commandId, Objects.requireNonNull(line, "line"));
    }

    /** S19 (PT30): the line held for {@code commandId}, and no longer held - empty when there is none. */
    public Optional<String> takeHeldEcho(long commandId) {
        return Optional.ofNullable(heldEchoes.remove(commandId));
    }

    /** {@code null} until {@link #close} is called. */
    public Deal.Outcome outcome() {
        return outcome;
    }

    /** When the tab first closed, in the caller's clock ({@code ChatPanel}: sim millis); 0 while open. */
    public long closedAtMillis() {
        return closedAtMillis;
    }

    /** The first close fixes {@link #closedAtMillis}: a repeated close (audit C-11) does not move it. */
    public void close(Deal.Outcome outcome, long atMillis) {
        if (!closed) {
            closedAtMillis = atMillis;
        }
        this.closed = true;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * S18b (UI tree U3): as {@link #close(Deal.Outcome, long)}, with the terms a {@code DEAL} was
     * agreed at. Terms are kept only for a deal that names a price; any other close keeps none.
     */
    public void close(Deal.Outcome outcome, long atMillis, double price, int hours) {
        close(outcome, atMillis);
        if (outcome == Deal.Outcome.DEAL && price > 0) {
            agreedPrice = price;
            agreedHours = hours;
        }
    }

    /** S18b (U3): the price a deal was agreed at; 0 for an open tab and for any other outcome. */
    public double agreedPrice() {
        return agreedPrice;
    }

    /** S18b (U3): the stay a deal was agreed at; 0 for an open tab and for any other outcome. */
    public int agreedHours() {
        return agreedHours;
    }
}
