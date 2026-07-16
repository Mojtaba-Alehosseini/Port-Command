package it.unige.portcommand.nlp;

import java.util.Optional;

/**
 * Always misses — see {@link DCGParser}. Was the production wiring until task 16; production now
 * uses {@link PrologDcgParser} over the real grammar. Kept as the null object for tests and
 * callers that need to force {@link NLPPipeline} down its Rasa fallback branch deterministically,
 * without standing up a Prolog engine.
 */
public final class NoOpDCGParser implements DCGParser {

    @Override
    public Optional<Frame> parse(String text, DialogueCtx ctx) {
        return Optional.empty();
    }
}
