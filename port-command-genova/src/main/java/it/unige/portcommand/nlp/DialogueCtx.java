package it.unige.portcommand.nlp;

import it.unige.portcommand.ontology.Offer;

/**
 * Stub of the v1.1 context-carrying grammar's dialogue state (PROJECT_DEFINITION.md §6.2,
 * {@code negotiation_move(Frame, DialogueCtx)}). Created here as a placeholder so task 14's
 * signatures don't need to change again in task 16; the fields' <em>resolution semantics</em>
 * (ellipsis fills missing slots from {@code standingOffer}, anaphora resolves against
 * {@code lastMentioned}, vocatives route via {@code activeNegotiationId}, …) are task 16's.
 *
 * @param activeNegotiationId the conversation id of the dialogue this chat turn targets, or
 *                            {@code null} if no negotiation is currently active
 * @param standingOffer       the offer currently on the table in that dialogue, or {@code null}
 * @param lastMentioned       the most recently mentioned berth/vessel id, or {@code null}
 */
public record DialogueCtx(String activeNegotiationId, Offer standingOffer, String lastMentioned) {

    /** The empty context: no active dialogue, nothing on the table, nothing mentioned yet. */
    public static final DialogueCtx NONE = new DialogueCtx(null, null, null);
}
