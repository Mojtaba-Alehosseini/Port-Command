package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Objects;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.ontology.Offer;

/**
 * The v1.1 context-carrying grammar's dialogue state (PROJECT_DEFINITION.md §6.2,
 * {@code negotiation_move(Frame, DialogueCtx)}). Widened for 16-M2 from M1's single-offer stub so
 * the six phenomenon blocks can resolve against real context:
 *
 * <ul>
 *   <li><b>ellipsis / delta</b> complete or transform a bare fragment against {@link #standingOffer}
 *       — which now carries BOTH sides' last offers, because "split the difference" needs the
 *       midpoint of the vessel's ask and the player's bid;</li>
 *   <li><b>anaphora / vocative</b> resolve "the tanker" / "Genoa Star" against {@link #roster}, a
 *       type-tagged, recency-ordered list of the active dialogues;</li>
 *   <li>"that berth" / "same berth as before" resolve against {@link #lastMentioned}.</li>
 * </ul>
 *
 * <p><b>P-04 (privacy).</b> {@link #from} maps ONLY {@link WalkInDialogueSnapshot}'s observable
 * fields — vessel id/type/name, the berth and duration under discussion, and the two last offers.
 * It never reads a walk-in's hidden beliefs (min-acceptable/target price, max-wait, personality);
 * the snapshot type does not even expose them ({@code WalkInDialogueSnapshotPrivacyTest}), and
 * {@code DialogueCtxPrivacyTest} greps this file to keep it that way.
 *
 * @param activeNegotiationId the conversation id of the focused dialogue (Java-side ACL routing),
 *                            or {@code null} if no negotiation is active
 * @param standingOffer       both sides' last offers in the focused dialogue (never {@code null};
 *                            {@link StandingOffer#NONE} when nothing is on the table)
 * @param roster              every active dialogue, most-recent first (never {@code null})
 * @param lastMentioned       the most-recently-named berth (anaphora "that berth"), or {@code null}
 */
public record DialogueCtx(String activeNegotiationId, StandingOffer standingOffer,
                          List<RosterEntry> roster, String lastMentioned) {

    public DialogueCtx {
        standingOffer = standingOffer == null ? StandingOffer.NONE : standingOffer;
        roster = roster == null ? List.of() : List.copyOf(roster);
    }

    /** The empty context: no active dialogue, nothing on the table, no roster, nothing mentioned. */
    public static final DialogueCtx NONE = new DialogueCtx(null, StandingOffer.NONE, List.of(), null);

    /**
     * Backward-compatible 3-arg form (pre-M2 callers, e.g. cancel-routing tests): the single legacy
     * {@link Offer} is the offer on the table, mapped to the player's side of a {@link StandingOffer}.
     * Kept so M1 construction sites compile and pass unchanged through the widening.
     */
    public DialogueCtx(String activeNegotiationId, Offer legacyStandingOffer, String lastMentioned) {
        this(activeNegotiationId,
                legacyStandingOffer == null ? StandingOffer.NONE : StandingOffer.ofPlayer(legacyStandingOffer),
                List.of(), lastMentioned);
    }

    /**
     * Builds a context from the active walk-in dialogues (most-recent first) and the id of the one
     * this chat turn targets. The focused dialogue supplies the standing offer and last-mentioned
     * berth; every dialogue becomes a roster entry. P-04: reads observable snapshot fields ONLY.
     *
     * @param dialoguesRecentFirst active dialogues ordered most-recent first (recency = roster order)
     * @param focusId              the vessel id of the focused dialogue, or {@code null}
     */
    public static DialogueCtx from(List<WalkInDialogueSnapshot> dialoguesRecentFirst, String focusId) {
        Objects.requireNonNull(dialoguesRecentFirst, "dialoguesRecentFirst");
        List<RosterEntry> roster = dialoguesRecentFirst.stream()
                .map(s -> new RosterEntry(s.vesselId(), s.vesselType(), s.vesselName(), s.berthId(), s.durationHours()))
                .toList();
        WalkInDialogueSnapshot focus = focusId == null ? null
                : dialoguesRecentFirst.stream().filter(s -> focusId.equals(s.vesselId())).findFirst().orElse(null);
        StandingOffer standing = focus == null ? StandingOffer.NONE : StandingOffer.from(focus);
        String lastMentioned = focus == null ? null : focus.berthId();
        return new DialogueCtx(focusId, standing, roster, lastMentioned);
    }

    /** @return true iff either side has a live offer on the table (cancel-routing predicate). */
    public boolean hasStandingOffer() {
        return standingOffer.hasAnyOffer();
    }

    /** One side's last offer over price (&euro;/hour), duration, and berth. A {@code null} price
     * means "no offer yet" — the sentinel the standing-offer resolution predicates fail on. */
    public record OfferView(Double price, Integer durationHours, String berthId) {

        /** No offer on this side. */
        public static final OfferView NONE = new OfferView(null, null, null);

        static OfferView of(Offer o) {
            return new OfferView(o.price(), o.durationHours(), o.berthId());
        }

        /** @return true iff this side has actually made a priced offer. */
        public boolean isPresent() {
            return price != null;
        }
    }

    /** Both sides' last offers in a dialogue: the vessel's ask and the player's bid. The midpoint
     * of the two is what "split the difference" needs, which is why M2 carries both. */
    public record StandingOffer(OfferView vesselOffer, OfferView playerOffer) {

        public static final StandingOffer NONE = new StandingOffer(OfferView.NONE, OfferView.NONE);

        public StandingOffer {
            vesselOffer = vesselOffer == null ? OfferView.NONE : vesselOffer;
            playerOffer = playerOffer == null ? OfferView.NONE : playerOffer;
        }

        /** From a snapshot: both offers share the berth/duration under discussion and differ only in
         * price; a 0.0 price is the snapshot's "no offer yet" sentinel and maps to {@link OfferView#NONE}. */
        static StandingOffer from(WalkInDialogueSnapshot s) {
            OfferView vessel = s.lastVesselOffer() > 0
                    ? new OfferView(s.lastVesselOffer(), s.durationHours(), s.berthId()) : OfferView.NONE;
            OfferView player = s.lastPlayerOffer() > 0
                    ? new OfferView(s.lastPlayerOffer(), s.durationHours(), s.berthId()) : OfferView.NONE;
            return new StandingOffer(vessel, player);
        }

        static StandingOffer ofPlayer(Offer o) {
            return new StandingOffer(OfferView.NONE, OfferView.of(o));
        }

        public boolean hasAnyOffer() {
            return vesselOffer.isPresent() || playerOffer.isPresent();
        }
    }

    /** A type-tagged, named entry for one active dialogue — the unit the anaphora and vocative
     * blocks match "the tanker" / "Genoa Star" against. All fields are observable (P-04). */
    public record RosterEntry(String id, String vesselType, String vesselName, String lastBerth,
                              Integer lastDuration) {
    }
}
