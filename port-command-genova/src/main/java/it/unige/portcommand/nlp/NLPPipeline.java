package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * that the DCG parses never calls Rasa at all, so a Rasa outage never blocks it; today the DCG
 * is {@link NoOpDCGParser} (task 16 replaces it), so every turn currently falls through to Rasa.
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
     *                       Task 16 wires ontology validation here; until then a permissive
     *                       {@code frame -> true} is fine since {@link NoOpDCGParser} never
     *                       actually produces a frame for it to see.
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

    private PipelineResult routeFrame(Frame frame, DialogueCtx ctx) {
        if (!frameValidator.test(frame)) {
            return clarification();
        }
        ACLMessage msg = FrameToAcl.build(frame, null, ctx.activeNegotiationId());
        return new PipelineResult.Routed(msg);
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
            case "accept_deal" -> routed(ACLMessage.ACCEPT_PROPOSAL, entitiesContent(r), ctx);
            case "reject_deal" -> routed(ACLMessage.REJECT_PROPOSAL, entitiesContent(r), ctx);
            // An 11th performative beyond the committed 10 (report note, per session brief).
            case "query_status" -> routed(ACLMessage.QUERY_REF, entitiesContent(r), ctx);
            case "set_constraint" -> routed(ACLMessage.REQUEST, entitiesContent(r), ctx);
            case "request_help" -> routed(ACLMessage.REQUEST, entitiesContent(r), ctx);
            // Raw text handed onward to PolicyParser (task 10); Rasa's entities are ignored
            // on this path — PolicyParser owns its own regex-DSL parse of the original text.
            case "set_policy" -> routed(ACLMessage.REQUEST, policyContent(text), ctx);
            case "cancel_action" -> routeCancelAction(ctx);
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
        if (ctx.standingOffer() != null) {
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

    private static PipelineResult clarification() {
        return new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions());
    }
}
