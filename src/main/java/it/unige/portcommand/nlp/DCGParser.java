package it.unige.portcommand.nlp;

import java.util.Optional;

/**
 * The SWI-Prolog DCG negotiation parser (PROJECT_DEFINITION.md §6.2). Declared by task 14 so
 * {@link NLPPipeline} could be wired ahead of the grammar; implemented by task 16 as
 * {@link PrologDcgParser}, which consults {@code dcg_negotiation.pl} and runs {@code parse_move}
 * over the utterance via JPL. {@link NoOpDCGParser} remains as the always-miss null object.
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
