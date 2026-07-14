package it.unige.portcommand.nlp;

import java.util.Optional;

/**
 * Always misses — see {@link DCGParser}. The production wiring until task 16 replaces it:
 * every chat turn falls through to the Rasa fallback in {@link NLPPipeline}, which is the
 * currently-correct behaviour (no DCG grammar exists yet to parse against).
 */
public final class NoOpDCGParser implements DCGParser {

    @Override
    public Optional<Frame> parse(String text, DialogueCtx ctx) {
        return Optional.empty();
    }
}
