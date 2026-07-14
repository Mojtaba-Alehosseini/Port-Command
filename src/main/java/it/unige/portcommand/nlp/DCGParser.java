package it.unige.portcommand.nlp;

import java.util.Optional;

/**
 * Stub interface for the SWI-Prolog DCG negotiation parser (PROJECT_DEFINITION.md §6.2).
 * The real JPL-backed implementation — consulting {@code dcg_negotiation.pl} via {@code
 * negotiation_move(Frame, DialogueCtx)} — is task 16's. This task only declares the
 * interface so {@link NLPPipeline} can be wired end-to-end ahead of the real grammar;
 * {@link NoOpDCGParser} is the production placeholder until task 16 lands.
 */
public interface DCGParser {

    /**
     * @param text the raw chat line
     * @param ctx  the v1.1 dialogue context (standing offer, active negotiation, last
     *             mentioned) the context-carrying grammar resolves ellipsis/anaphora against
     * @return the parsed frame, or {@link Optional#empty()} on any parse miss — including
     *         every call today, since no implementation is wired in yet
     */
    Optional<Frame> parse(String text, DialogueCtx ctx);
}
