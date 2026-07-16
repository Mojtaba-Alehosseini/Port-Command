package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import it.unige.portcommand.prolog.PrologEngine;

import org.jpl7.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link DCGParser}: runs {@code dcg_negotiation.pl}'s {@code parse_move/2} over the
 * player's utterance via JPL and decodes the bound frame. Replaces {@link NoOpDCGParser} in
 * production wiring (task 16; PROJECT_DEFINITION.md §6.1's DCG-first branch).
 *
 * <p><b>Why this class lives in {@code nlp} and not {@code prolog}</b> (ADR-10): task 14 already
 * shipped {@code nlp.DCGParser} as the interface {@link NLPPipeline} is constructed against, and
 * the {@code prolog} package is generic JPL infrastructure ({@code PrologEngine},
 * {@code PrologQueries}, {@code PrologTerms}) that carries no domain grammar. planning/16's file
 * table names a second {@code prolog.DCGParser}; two classes with the same simple name would be
 * pure confusion. The Prolog artifact the reports point at is {@code dcg_negotiation.pl} itself,
 * which lives under {@code resources/prolog/} either way.
 *
 * <p><b>Never throws on bad input.</b> A parse miss, a malformed frame, or a JPL error all resolve
 * to {@link Optional#empty()} — the pipeline then falls through to Rasa, which is the correct
 * behaviour for anything this deliberately-narrow grammar does not model.
 */
public final class PrologDcgParser implements DCGParser {

    private static final Logger log = LoggerFactory.getLogger(PrologDcgParser.class);

    private final PrologEngine engine;
    private final DcgTokenizer tokenizer;

    public PrologDcgParser(PrologEngine engine, DcgTokenizer tokenizer) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    /**
     * @param ctx the v1.1 dialogue context, rendered to a Prolog {@code ctx(…)} term
     *            ({@link DialogueCtxTerm}) and threaded into the grammar via {@code parse_move/3}
     *            (16-M2). {@link DialogueCtx#NONE} renders as the empty context, under which the
     *            phenomenon blocks are inert and the parse is identical to 16-M1's {@code parse_move/2}.
     */
    @Override
    public Optional<Frame> parse(String text, DialogueCtx ctx) {
        List<String> tokens = tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        String goal = "parse_move(" + tokenizer.toPrologList(tokens) + ", "
                + DialogueCtxTerm.toPrologTerm(ctx) + ", Frame)";
        try {
            Optional<Map<String, Term>> solution = engine.oneSolution(goal);
            if (solution.isEmpty()) {
                log.debug("DCG miss (-> Rasa fallback) for tokens {}", tokens);
                return Optional.empty();
            }
            Frame frame = Frame.fromProlog(solution.get().get("Frame"));
            log.debug("DCG hit: {} -> {}", tokens, frame);
            return Optional.of(frame);
        } catch (RuntimeException e) {
            // Covers PrologException (a RuntimeException subclass) and any JPL fault. A
            // grammar/marshalling problem must degrade to the Rasa fallback, never kill the
            // caller's turn: this runs on a JADE agent thread via NLPPipeline's executor.
            log.warn("DCG parse failed for goal {} — falling through to Rasa", goal, e);
            return Optional.empty();
        }
    }
}
