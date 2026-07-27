package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.lang.acl.ACLMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates one chat turn through the canonical DCG-first pipeline (PROJECT_DEFINITION.md
 * §6.1): preprocess &rarr; DCG &rarr; (on a frame: validate &rarr; {@link FrameToAcl}) &rarr;
 * Rasa fallback &rarr; {@link ConfidenceGate} &rarr; clarification. A clean negotiation move
 * that the DCG parses never calls Rasa at all, so a Rasa outage never blocks it — since task 16
 * the DCG slot holds the real {@link PrologDcgParser} over {@code dcg_negotiation.pl}, so that
 * short-circuit is live rather than theoretical ({@link NoOpDCGParser} survives for tests that
 * want to force the Rasa branch).
 *
 * <p><b>Never blocks the calling thread and never throws.</b> {@link #processChatInput} runs
 * entirely on the injected {@code executor} (typically {@code
 * Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())}) — safe to call
 * from a JADE agent thread or the Swing EDT. A Rasa request timeout (3&nbsp;s, fixed) resolves
 * to {@link PipelineResult.NeedsClarification}, never an exception; any other unhandled
 * service failure resolves to {@link PipelineResult.Error} rather than an exceptionally-
 * completed future, so callers never need their own retry/catch scaffolding.
 */
public final class NLPPipeline {

    private static final Logger log = LoggerFactory.getLogger(NLPPipeline.class);

    private final PreprocessRegex preprocess;
    private final RasaBridge rasa;
    private final ConfidenceGate gate;
    private final DCGParser dcgParser;
    private final Predicate<Frame> frameValidator;
    private final ExecutorService executor;

    /**
     * @param frameValidator the frame-path "WordNet/validate" seam (planning/14): whatever the
     *                       DCG returns, this predicate gates whether {@link FrameToAcl} runs.
     *                       Production wires {@link OntologyValidator} here (task 16), so a frame
     *                       naming an entity outside the ontology becomes a clarification rather
     *                       than a malformed ACL.
     */
    public NLPPipeline(PreprocessRegex preprocess, RasaBridge rasa, ConfidenceGate gate,
                        DCGParser dcgParser, Predicate<Frame> frameValidator, ExecutorService executor) {
        this.preprocess = Objects.requireNonNull(preprocess, "preprocess");
        this.rasa = Objects.requireNonNull(rasa, "rasa");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.dcgParser = Objects.requireNonNull(dcgParser, "dcgParser");
        this.frameValidator = Objects.requireNonNull(frameValidator, "frameValidator");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * @param text the raw chat line
     * @param ctx  the v1.1 dialogue context (see {@link DialogueCtx}); pass {@link
     *             DialogueCtx#NONE} when no negotiation is active yet
     */
    public CompletableFuture<PipelineResult> processChatInput(String text, DialogueCtx ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return CompletableFuture.supplyAsync(() -> processSync(text, ctx), executor);
    }

    private PipelineResult processSync(String text, DialogueCtx ctx) {
        try {
            Objects.requireNonNull(text, "text");
            PreprocessRegex.Extracted extracted = preprocess.extract(text);
            log.debug("preprocess extracted: {}", extracted);

            Optional<Frame> frame = dcgParser.parse(text, ctx);
            if (frame.isPresent()) {
                return routeFrame(frame.get(), ctx);
            }

            Optional<RasaParseResult> parsed = callRasaWithTimeout(text);
            if (parsed.isEmpty()) {
                return clarification();
            }
            return routeRasaResult(text, parsed.get(), ctx);
        } catch (RuntimeException e) {
            log.warn("pipeline failed to route chat input", e);
            return new PipelineResult.Error(String.valueOf(e.getMessage()));
        }
    }

    /** Per-dialogue negotiation moves — the ones that are ambiguous when two walk-ins are active and
     * neither is addressed or focused. A command / constraint / query is not tied to one dialogue. */
    private static final Set<String> PER_DIALOGUE_MOVES = Set.of("propose", "counter", "accept", "reject");

    private PipelineResult routeFrame(Frame frame, DialogueCtx ctx) {
        if (!frameValidator.test(frame)) {
            return clarification();
        }
        // 16-M2 vocative: a "Genoa Star: …" binds an addressee; surface it so the caller can route
        // between concurrent walk-ins. Absent (null) for an unaddressed turn.
        Object addressee = frame.element("addressee");
        // Routing question as clarification: an UNaddressed per-dialogue move, with two+ active
        // dialogues and no focused one, is ambiguous — which walk-in is it for? Ask rather than guess.
        // A bound addressee, a single dialogue, or an explicit focus all disambiguate (and the empty
        // 16-M1 context has an empty roster, so this never fires there).
        if (addressee == null && ctx.activeNegotiationId() == null && ctx.roster().size() >= 2
                && PER_DIALOGUE_MOVES.contains(frame.move())) {
            return clarification();
        }
        ACLMessage msg = FrameToAcl.build(frame, null, ctx.activeNegotiationId());
        return new PipelineResult.Routed(msg, addressee == null ? null : String.valueOf(addressee));
    }

    /** Timeout -&gt; {@link Optional#empty()}, no exception propagated (planning/14 §Step 3).
     * Any non-timeout Rasa failure (4xx/5xx after retry, malformed JSON) propagates to the
     * outer catch in {@link #processSync} and becomes a {@link PipelineResult.Error}. */
    private Optional<RasaParseResult> callRasaWithTimeout(String text) {
        try {
            return Optional.of(rasa.parse(text));
        } catch (RasaTimeoutException e) {
            log.debug("rasa fallback timed out; routing to clarification");
            return Optional.empty();
        }
    }

    private PipelineResult routeRasaResult(String text, RasaParseResult r, DialogueCtx ctx) {
        ConfidenceGate.Branch branch = gate.route(r.confidence());
        if (branch != ConfidenceGate.Branch.A_HIGH_CONFIDENCE) {
            return clarification();
        }
        return routeHighConfidence(text, r, ctx);
    }

    /**
     * A Rasa-classified {@code accept_deal} BINDS a negotiation, so it demands near-certainty:
     * a throwaway {@code "so?"} that the DCG misses and Rasa reads as {@code accept_deal} must
     * never silently close a deal (task 19 play-test — a bare "so?" classified above the 0.60
     * gate closed a €6263 deal with no visible cause). Below this the turn falls to
     * clarification — the player confirms via the explicit "Accept the current offer" button.
     * The DCG accept path ({@code routeFrame}) and the Accept button (a direct
     * {@code PlayerCommandEvent}) both bypass Rasa entirely and are unaffected.
     */
    private static final double BINDING_ACCEPT_MIN_CONFIDENCE = 0.80;

    /** Branch A (&ge;0.60): map a non-structural intent straight to an action/ACL. A
     * structural intent ({@code propose_offer}/{@code counter_offer}) that the DCG already
     * failed to parse still needs clarification — Rasa's confidence alone is not enough
     * structure to build a valid offer/counter frame from. */
    private PipelineResult routeHighConfidence(String text, RasaParseResult r, DialogueCtx ctx) {
        String intent = r.intentName();
        if (intent == null) {
            return clarification();
        }
        return switch (intent) {
            case "propose_offer", "counter_offer" -> clarification();
            case "accept_deal" -> r.confidence() >= BINDING_ACCEPT_MIN_CONFIDENCE
                    ? routed(ACLMessage.ACCEPT_PROPOSAL, entitiesContent(r), ctx)
                    : clarification();
            case "reject_deal" -> routed(ACLMessage.REJECT_PROPOSAL, entitiesContent(r), ctx);
            // An 11th performative beyond the committed 10 (report note, per session brief).
            case "query_status" -> routed(ACLMessage.QUERY_REF, entitiesContent(r), ctx);
            case "set_constraint" -> routed(ACLMessage.REQUEST, entitiesContent(r), ctx);
            // Audit D-06 (2026-07-27): carries an explicit marker instead of Rasa's entities (which
            // for a bare "help" is an empty object, indistinguishable from set_constraint's). The
            // GUI needs to tell a HELP request apart from a fleet-wide instruction so it can route
            // the typed form to the Assistant, exactly as the "Talk to the assistant" clarification
            // BUTTON already does — before this, `help` was classified correctly at F1 1.000 and
            // then answered with "Fleet-wide commands aren't wired to a dialogue tab yet.", which
            // is not merely unhelpful but false: a help request is not a fleet command. Nothing
            // consumed the entities on this path (there was no consumer at all), so dropping them
            // costs nothing. Same shape as set_policy's dedicated content below.
            case "request_help" -> routed(ACLMessage.REQUEST, helpContent(), ctx);
            // Raw text handed onward to PolicyParser (task 10); Rasa's entities are ignored
            // on this path — PolicyParser owns its own regex-DSL parse of the original text.
            case "set_policy" -> routed(ACLMessage.REQUEST, policyContent(text), ctx);
            case "cancel_action" -> routeCancelAction(ctx);
            // 2026-07-27 (task 26, decision b): the 10th canonical intent. A ROUTING LABEL, like
            // set_policy — it never becomes an ACL, it is the classifier saying "none of the nine
            // port moves", which is exactly the clarification path. Written explicitly rather than
            // left to `default` so the intent's contract is visible where it is enforced: the
            // whole reason it was added is that without a "none of these" class DIET had to spread
            // a keyboard mash or a greeting over the nine in-domain intents, and one of them won
            // (measured: 73.7% false-bind rate on the wild set's should-ask turns).
            case "out_of_scope" -> clarification();
            default -> clarification();
        };
    }

    /**
     * v1.1 routing (2026-05-31 audit rule: FIPA CANCEL is reserved for revoking an
     * already-issued commitment, not for withdrawing from an open negotiation):
     * <ul>
     *   <li>no active negotiation -&gt; the target is an issued assignment (tug dispatch,
     *       berth booking) -&gt; CANCEL</li>
     *   <li>active negotiation with a standing offer on the table -&gt; withdrawal of that
     *       proposal -&gt; REJECT_PROPOSAL</li>
     *   <li>active negotiation, nothing currently on the table -&gt; plain withdrawal -&gt;
     *       INFORM</li>
     * </ul>
     */
    private PipelineResult routeCancelAction(DialogueCtx ctx) {
        if (ctx.activeNegotiationId() == null) {
            return routed(ACLMessage.CANCEL, "{}", ctx);
        }
        if (ctx.hasStandingOffer()) {
            return routed(ACLMessage.REJECT_PROPOSAL, "{}", ctx);
        }
        return routed(ACLMessage.INFORM, "{}", ctx);
    }

    private static PipelineResult routed(int performative, String content, DialogueCtx ctx) {
        ACLMessage msg = MessageFactory.create(performative);
        if (ctx.activeNegotiationId() != null) {
            msg.setConversationId(ctx.activeNegotiationId());
        }
        msg.setContent(content);
        return new PipelineResult.Routed(msg);
    }

    private static String entitiesContent(RasaParseResult r) {
        Map<String, String> values = new LinkedHashMap<>();
        r.entities().forEach((name, hit) -> values.put(name, hit.value()));
        return TerminalJson.write(values);
    }

    private static String policyContent(String text) {
        return TerminalJson.write(Map.of("policy_text", text));
    }

    /** The {@code request_help} marker the GUI keys on — see {@link #HELP_REQUEST_KEY}. */
    private static String helpContent() {
        return TerminalJson.write(Map.of(HELP_REQUEST_KEY, true));
    }

    /** Content key marking a REQUEST as the player asking for help rather than issuing a
     * fleet-wide instruction (audit D-06). Read by {@code DialogueTabView.dispatchRouted}. */
    public static final String HELP_REQUEST_KEY = "help_request";

    private static PipelineResult clarification() {
        return new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions());
    }
}
