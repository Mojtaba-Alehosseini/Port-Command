package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

import it.unige.portcommand.prolog.PrologQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gate between a DCG-parsed {@link Frame} and an outgoing ACL message (planning/16 Step 16.7):
 * a structured frame must name only entities that actually exist in the port ontology before it
 * becomes a binding message. On failure {@link NLPPipeline} routes to
 * {@link PipelineResult.NeedsClarification} rather than dispatching a malformed ACL.
 *
 * <p>Reads the ontology facts already consulted by {@code PrologEngine.init()} through the
 * {@link PrologQueries} facade — it does NOT open a second copy of the OWL file, and it never
 * touches raw JPL (INVARIANTS.md "Prolog").
 *
 * <p><b>Scope note.</b> The grammar's own bounds already reject an out-of-range berth/duration at
 * parse time (a named-but-invalid slot fails the parse instead of being silently dropped — see
 * {@code dcg_negotiation.pl}'s mention-guards). This validator is the second, semantic line:
 * it answers "does {@code berth_3} exist in THIS ontology", which the grammar cannot know, and
 * it keeps guarding the frame surface even as 16-M2 adds rules. Belt and braces, deliberately.
 *
 * <p><b>S6 (audit P2-2).</b> Now a {@link BiPredicate} over (frame, utterance): the pipeline
 * passes the raw text so the WordNet stage can run LESK over the surrounding words instead of
 * trusting the table blind — the {@code resolveToOntology} call below was the production site
 * that always passed an empty context, which left the Lesk branch dead. The old
 * {@code Predicate<Frame>} face survives as a plain {@link #test(Frame)} method (pinned
 * equivalent to an empty context) — it cannot stay an {@code implements}: {@code
 * Predicate.negate()} and {@code BiPredicate.negate()} share an erased signature with
 * incompatible return types, so Java forbids implementing both.
 */
public final class OntologyValidator implements BiPredicate<Frame, String> {

    private static final Logger log = LoggerFactory.getLogger(OntologyValidator.class);

    /** PROJECT_DEFINITION.md §6.2 / the ontology's own bound: an offer runs 1..24 hours. */
    static final long MIN_DURATION_HOURS = 1;
    static final long MAX_DURATION_HOURS = 24;

    private final WordNetResolver wordNet;

    /** Uses the shipped WordNet resolver (its dictionary loads lazily, on the first vessel-class
     * element that is not already an ontology term — so the common paths pay nothing). */
    public OntologyValidator() {
        this(new WordNetResolver());
    }

    public OntologyValidator(WordNetResolver wordNet) {
        this.wordNet = Objects.requireNonNull(wordNet, "wordNet");
    }

    /** The pre-S6 single-arg face — context-free, for callers with no utterance at hand. */
    public boolean test(Frame frame) {
        return validate(frame, "");
    }

    /** The S6 face: same checks, with the utterance as Lesk context for the WordNet stage. */
    @Override
    public boolean test(Frame frame, String utterance) {
        return validate(frame, utterance);
    }

    /** @return true iff every ontology-bound element of {@code frame} names a real entity. */
    public boolean validate(Frame frame) {
        return validate(frame, "");
    }

    /** @return true iff every ontology-bound element of {@code frame} names a real entity,
     * with {@code utterance}'s words as the Lesk disambiguation context (blank = context-free,
     * byte-identical to the pre-S6 behaviour). */
    public boolean validate(Frame frame, String utterance) {
        if (frame == null) {
            return false;
        }
        return validBerth(frame)
                && validVesselClass(frame, contextTokens(utterance))
                && validMoney(frame)
                && validDuration(frame);
    }

    /** A plain lowercase word split — Lesk wants gloss-overlap words, not grammar tokens. */
    private static List<String> contextTokens(String utterance) {
        if (utterance == null || utterance.isBlank()) {
            return List.of();
        }
        return List.of(utterance.toLowerCase(Locale.ROOT).split("\\W+"));
    }

    private static boolean validBerth(Frame frame) {
        Object berth = frame.element("berth");
        if (berth == null) {
            return true; // absent is fine — the HarbourMaster picks a compatible berth (R8)
        }
        boolean exists = PrologQueries.berthExists(String.valueOf(berth));
        if (!exists) {
            log.debug("frame rejected: no such berth in the ontology: {}", berth);
        }
        return exists;
    }

    /**
     * planning/16 Step 16.7: "any vessel-class element resolves to one of the five vessel_type
     * classes (AFTER WordNet/Lesk normalisation)". So the player's word is normalised first —
     * "freighter" is not an ontology class, but {@code cargo_vessel} is — and only then is the
     * ontology asked. Skipping the normalisation would reject perfectly good English.
     *
     * <p>The ontology is tried FIRST as a short-circuit: a class the grammar already produced in
     * canonical form ({@code tanker}) needs no lexical work, which is what keeps the WordNet
     * dictionary from loading on the common path.
     */
    private boolean validVesselClass(Frame frame, List<String> context) {
        Object vesselClass = frame.element("vessel_class");
        if (vesselClass == null) {
            return true;
        }
        String raw = String.valueOf(vesselClass);
        if (PrologQueries.vesselTypeExists(raw)) {
            return true;
        }
        // S6 (audit P2-2): the 3-arg call — this used to be the 2-arg overload, which pinned
        // the context empty and left Lesk unreachable from production. With the utterance's
        // words along, an ambiguous surface ("liner") is disambiguated by sense, not table-blind.
        String resolved = wordNet.resolveToOntology(raw, "noun", context);
        boolean exists = PrologQueries.vesselTypeExists(resolved);
        if (!exists) {
            log.debug("frame rejected: '{}' (WordNet -> '{}') is not one of the five vessel types",
                    raw, resolved);
        }
        return exists;
    }

    /** {@code money} decodes to {@code {amount=N, currency=EUR}} (see {@link Frame#fromProlog}). */
    private static boolean validMoney(Frame frame) {
        Object money = frame.element("money");
        if (money == null) {
            return true;
        }
        if (!(money instanceof Map<?, ?> map)) {
            log.debug("frame rejected: money element is not a decoded price: {}", money);
            return false;
        }
        Object amount = map.get("amount");
        if (!(amount instanceof Number number)) {
            log.debug("frame rejected: money.amount is not a number: {}", amount);
            return false;
        }
        boolean nonNegative = number.doubleValue() >= 0;
        if (!nonNegative) {
            log.debug("frame rejected: negative money amount: {}", amount);
        }
        return nonNegative;
    }

    private static boolean validDuration(Frame frame) {
        Object duration = frame.element("duration");
        if (duration == null) {
            return true;
        }
        if (!(duration instanceof Number number)) {
            log.debug("frame rejected: duration is not a number: {}", duration);
            return false;
        }
        // Integral hours only: a fractional duration is a grammar bug, not a roundable value.
        double hours = number.doubleValue();
        boolean valid = hours == Math.floor(hours)
                && hours >= MIN_DURATION_HOURS
                && hours <= MAX_DURATION_HOURS;
        if (!valid) {
            log.debug("frame rejected: duration outside 1..24 whole hours: {}", duration);
        }
        return valid;
    }
}
