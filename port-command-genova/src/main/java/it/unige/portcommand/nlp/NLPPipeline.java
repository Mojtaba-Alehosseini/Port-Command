package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiPredicate;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.prolog.PrologQueries;
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
    private final BiPredicate<Frame, String> frameValidator;
    private final ExecutorService executor;
    /** S18a (ADR-23, decision N5): recognises a policy typed into a chat tab. Stateless; only its
     * quiet parse is used here, so nothing is published and nothing is registered. */
    private final PolicyParser policyParser = new PolicyParser();

    /**
     * @param frameValidator the frame-path "WordNet/validate" seam (planning/14): whatever the
     *                       DCG returns, this predicate gates whether {@link FrameToAcl} runs.
     *                       Production wires {@link OntologyValidator} here (task 16), so a frame
     *                       naming an entity outside the ontology becomes a clarification rather
     *                       than a malformed ACL. S6 (audit P2-2): a {@code BiPredicate} over
     *                       (frame, utterance) so the validator's WordNet stage can run Lesk over
     *                       the player's actual words — the old {@code Predicate<Frame>} shape is
     *                       exactly what kept the Lesk branch dead in production.
     */
    public NLPPipeline(PreprocessRegex preprocess, RasaBridge rasa, ConfidenceGate gate,
                        DCGParser dcgParser, BiPredicate<Frame, String> frameValidator,
                        ExecutorService executor) {
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
        return processChatInput(text, ctx, null);
    }

    /**
     * S8 (audit D-05, done right): the TWO-STEP vocative form. {@code ctxByVesselId} resolves a
     * vessel id to THAT dialogue's full context (production: {@code NegotiationTabRegistry::
     * buildCtx}); when a parsed frame is addressed to a vessel OTHER than the focused tab's, the
     * pipeline re-fetches the addressee's context and RE-PARSES the whole utterance under it —
     * so a context-dependent inner move ("make it 2200") completes from the ADDRESSEE's standing
     * offer, never the focused tab's (the transplant {@code crossTabContextGuard} used to have
     * to decline). Null keeps the pre-S8 behaviour exactly: the guard stays the last line.
     */
    public CompletableFuture<PipelineResult> processChatInput(String text, DialogueCtx ctx,
            java.util.function.Function<String, DialogueCtx> ctxByVesselId) {
        Objects.requireNonNull(ctx, "ctx");
        return CompletableFuture.supplyAsync(() -> processSync(text, ctx, ctxByVesselId), executor);
    }

    private PipelineResult processSync(String text, DialogueCtx ctx,
            java.util.function.Function<String, DialogueCtx> ctxByVesselId) {
        try {
            Objects.requireNonNull(text, "text");
            // S18a (ADR-23, decision N5): a sentence the policy DSL parses IS a policy, whatever a
            // classifier would make of it (Rasa has called one an acceptance). It is answered
            // here, before the grammar or Rasa see it, with where policies are set.
            if (policyParser.parseQuietly(text).isPresent()) {
                return asked(InputSafety.POLICY_IN_CHAT);
            }
            // S6 (audit P2-3): the pre-pass is a PRE-ANNOTATION LAYER, no longer computed and
            // discarded. Its hits ride to the Rasa path, where they FILL entities the classifier
            // missed (entitiesContent) — never overriding a parsed value. The DCG path takes
            // nothing from it on purpose: a frame's absent slot is MEANINGFUL (ellipsis resolves
            // from the dialogue ctx; an accept deliberately carries no terms), so backfilling
            // there would change what the player said.
            PreprocessRegex.Extracted extracted = preprocess.extract(text);
            log.debug("preprocess extracted: {}", extracted);

            Optional<Frame> frame = dcgParser.parse(text, ctx);
            if (frame.isPresent()) {
                return routeFrame(frame.get(), ctx, text, ctxByVesselId, null);
            }

            // S18a (playtest decision PT7): the grammar refuses a berth that does not exist, and
            // then that refusal is the whole reason for the miss - under any context. Say so by
            // number instead of handing the sentence to Rasa to guess at.
            Optional<String> noSuchBerth = InputSafety.noSuchBerth(PREFIX_TOKENIZER.tokenize(text));
            if (noSuchBerth.isPresent()) {
                return asked(noSuchBerth.get());
            }
            // S18a (decision N8): the chat's help line tells the player to type "status". That
            // one word must not depend on a classifier being up, so it is read here; the chat
            // answers a QUERY_REF itself (the status line). UNKNOWN: neither stage read it.
            if (InputSafety.isStatusWord(text)) {
                return routed(ACLMessage.QUERY_REF, "{}", ctx, ParseProvenance.UNKNOWN);
            }

            // S8 (D-05 two-step, hook 2): a vocative can MISS under the focused ctx yet succeed
            // under the addressee's (focused tab has nothing on the table, the addressee does —
            // the inner ellipsis fails against the wrong standing offer and the whole vocative
            // parse dies before routeFrame ever runs). If the utterance opens with a resolvable
            // "Name :" prefix, re-focus and re-parse BEFORE surrendering to Rasa. The name has
            // already been through the MED rewrite inside the resolver helper, so a typo'd name
            // gets the same one-chance-refuse-on-tie treatment as everywhere else.
            if (ctxByVesselId != null) {
                Optional<String> prefixId = vocativePrefixAddressee(text, ctx);
                if (prefixId.isPresent() && !prefixId.get().equals(ctx.activeNegotiationId())) {
                    DialogueCtx addresseeCtx = ctxByVesselId.apply(prefixId.get());
                    if (addresseeCtx != null) {
                        Optional<Frame> refocused = dcgParser.parse(text, addresseeCtx);
                        if (refocused.isPresent()) {
                            return routeFrame(refocused.get(), addresseeCtx, text, null, prefixId.get());
                        }
                    }
                }
            }

            Optional<RasaParseResult> parsed = callRasaWithTimeout(text);
            if (parsed.isEmpty()) {
                return clarification();
            }
            return routeRasaResult(text, parsed.get(), ctx, extracted);
        } catch (RuntimeException e) {
            log.warn("pipeline failed to route chat input", e);
            return new PipelineResult.Error(String.valueOf(e.getMessage()));
        }
    }

    /** Per-dialogue negotiation moves — the ones that are ambiguous when two walk-ins are active and
     * neither is addressed or focused. A command / constraint / query is not tied to one dialogue. */
    private static final Set<String> PER_DIALOGUE_MOVES = Set.of("propose", "counter", "accept", "reject");

    /**
     * @param otherTabsVessel S18a: {@code null} for a move typed in the tab of the vessel it is
     *                        for. On the two-step re-parse it is the ADDRESSEE's id: {@code ctx}
     *                        is then that vessel's context, but the line was typed in another
     *                        tab, and a refusal has to say whose offer it means and take its
     *                        worked example from that vessel, not from the tab on screen
     */
    private PipelineResult routeFrame(Frame frame, DialogueCtx ctx, String text,
            java.util.function.Function<String, DialogueCtx> ctxByVesselId, String otherTabsVessel) {
        if (!frameValidator.test(frame, text)) {
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
        // S8 (D-05 two-step, hook 1): a per-dialogue move addressed AWAY from the focused tab is
        // re-parsed under the ADDRESSEE's own context — the grammar re-resolves the vocative and
        // completes the inner move from the RIGHT standing offer. The recursion runs with a null
        // resolver (no loops) and its guard passes trivially (addressee == the re-fetched focus).
        // A re-parse miss means the addressee's context cannot support the move (e.g. nothing on
        // its table to complete from): honest clarification, never the transplanted frame.
        // A SLOT-LESS cross-tab move (accept / reject / "no it") skips the two-step entirely
        // (S8 adversarial finding 1): it carries nothing to transplant, so re-parsing under the
        // addressee can only LOSE it (an anaphoric "accept it" needs a standing offer to parse,
        // and the addressee may have none while the move is still perfectly meaningful) — those
        // routed before the guard existed and must keep routing, which the guard's own
        // nothing-to-transplant early return below guarantees.
        boolean carriesTerms = frame.element("money") != null || frame.element("duration") != null
                || frame.element("berth") != null;
        if (addressee != null && ctxByVesselId != null && carriesTerms
                && PER_DIALOGUE_MOVES.contains(frame.move())
                && !String.valueOf(addressee).equals(ctx.activeNegotiationId())) {
            DialogueCtx addresseeCtx = ctxByVesselId.apply(String.valueOf(addressee));
            if (addresseeCtx != null) {
                Optional<Frame> refocused = dcgParser.parse(text, addresseeCtx);
                if (refocused.isPresent()) {
                    return routeFrame(refocused.get(), addresseeCtx, text, null, String.valueOf(addressee));
                }
                String to = displayName(ctx, String.valueOf(addressee));
                log.info("two-step vocative: {}'s context cannot complete the move — clarifying", to);
                return new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(),
                        "That is addressed to " + to + ", but " + to + " has nothing on the table "
                                + "to complete it from — say it in full (price, hours and berth), "
                                + "or switch to " + to + "'s tab first.");
            }
        }
        PipelineResult crossTalk = crossTabContextGuard(frame, ctx, text, addressee);
        if (crossTalk != null) {
            return crossTalk;
        }
        // S18a (ADR-23, decision N1): the last check before the wire, and the only place every
        // grammar route passes (the first parse and both two-step re-parses return through here).
        // The vessel accepts any demand at or below its own offer, so a price that cannot be meant
        // is asked about, not sent. "Their offer" is the offer of the vessel this move is FOR:
        // `ctx` is that vessel's own context when the move is unaddressed, addressed to the tab
        // it was typed in, or was re-parsed under its addressee (the two-step); a fully explicit
        // move addressed elsewhere with no way to look that vessel up is checked against the
        // absolute rule only - this tab's offer says nothing about another vessel.
        boolean forThisDialogue = addressee == null
                || String.valueOf(addressee).equals(ctx.activeNegotiationId());
        // Whose move this is, when it is not the vessel of the tab on screen: the two-step's
        // addressee, or an explicit move to a vessel that could not be looked up. A refusal then
        // names that vessel, and the chat takes the worked example from ITS tab - a number that
        // is safe for the tab on screen can be far below the other vessel's offer (review).
        String aboutVessel = otherTabsVessel != null ? otherTabsVessel
                : forThisDialogue ? null : String.valueOf(addressee);
        String whose = aboutVessel == null ? "their" : displayName(ctx, aboutVessel) + "'s";
        Double theirOffer = forThisDialogue ? ctx.standingOffer().vesselOffer().price() : null;
        Optional<String> implausible = InputSafety.implausiblePrice(frame, theirOffer, whose);
        if (implausible.isPresent()) {
            return asked(implausible.get(), aboutVessel);
        }
        // S18a (review): hours typed with no price, on a tab where the player has not bid yet.
        // The grammar completes the move with the VESSEL's own price - a demand of exactly what
        // it offers, which it accepts on the spot. "8h" or "what about 6 hours" would close the
        // deal at the opening price; that is an acceptance nobody typed, so it is asked about.
        Optional<String> herPrice = InputSafety.closesAtTheirPrice(frame, theirOffer,
                ctx.standingOffer().playerOffer().isPresent(), PREFIX_TOKENIZER.tokenize(text), whose);
        if (herPrice.isPresent()) {
            return asked(herPrice.get(), aboutVessel);
        }
        ACLMessage msg = FrameToAcl.build(frame, null, ctx.activeNegotiationId());
        // S15 (2026-10-12): every frame that reaches this line came out of the grammar — the first
        // parse and both two-step re-parses return through here — so this one site stamps them all
        // (DCG). The stamp is written after routing is decided and nothing reads it to decide.
        return new PipelineResult.Routed(msg, addressee == null ? null : String.valueOf(addressee),
                ParseProvenance.DCG);
    }

    /** Tokeniser for the hook-2 prefix probe only (stateless; the parse path owns its own). */
    private static final DcgTokenizer PREFIX_TOKENIZER = new DcgTokenizer();

    /**
     * Hook 2's detector: tokenize, run the SAME MED rewrite the parse path runs, and resolve a
     * "Name :" prefix exactly against the roster. Empty when there is no colon, no unique match,
     * or the prefix is not name-shaped — falling through to Rasa exactly as before S8.
     */
    private static Optional<String> vocativePrefixAddressee(String text, DialogueCtx ctx) {
        if (text == null || ctx.roster().isEmpty() || text.indexOf(':') < 0) {
            return Optional.empty();
        }
        java.util.List<String> tokens = RosterNameResolver.resolve(
                PREFIX_TOKENIZER.tokenize(text), ctx);
        int colon = tokens.indexOf(":");
        if (colon < 1) {
            return Optional.empty();
        }
        return RosterNameResolver.resolveExact(tokens.subList(0, colon), ctx);
    }

    /**
     * Audit D-05 — a vocative must not carry ANOTHER dialogue's terms (2026-07-28).
     *
     * <p>{@code NegotiationTabRegistry.buildCtx} focuses the context on the tab whose Send button
     * fired, so {@code ctx.standingOffer} belongs to THAT vessel. {@code vocative_move} then parses
     * its inner move under that same context and re-tags the frame with a DIFFERENT vessel via
     * {@code add_addressee}. For context-free inner moves (accept / reject / an explicit propose)
     * that is harmless — they carry no completed slots. For the context-DEPENDENT blocks it
     * silently transplants terms: typing {@code Carthago: make it 2200} in Genoa Star's tab sent
     * Carthago a binding €2,200-for-5h-at-berth_3 proposal it had never discussed, and
     * {@code Carthago: 200 more} computed the delta from Genoa Star's number. No golden and no
     * PLUnit case combines a vocative with ellipsis or delta, which is why 16-M2's review could not
     * see it.
     *
     * <p><b>Since S8 this guard is the FALLBACK, not the primary defence.</b> The audit's proposed
     * two-step re-parse now exists (the {@code ctxByVesselId} hooks above): with a resolver wired,
     * a cross-tab move is re-completed against the ADDRESSEE's own context and routes correctly —
     * this guard is reached only when NO resolver was supplied (the 2-arg entry: tests, headless
     * callers) and keeps exactly its pre-S8 decline behaviour there. The detector is a second
     * parse of the SAME text against a context that is identical except it has nothing to
     * complete FROM: same roster (so the vocative still resolves), but no standing offer and no
     * last-mentioned berth. If the utterance still parses to the same move and the same terms,
     * every term was explicit in the sentence and the move is safe to route across tabs. If it
     * does not, the original frame borrowed at least one term from the focused dialogue — the
     * D-05 shape.
     *
     * <p>Cost is one extra DCG goal, and only on a cross-tab vocative — a rare turn. Returns
     * {@code null} when the turn is fine.
     */
    private PipelineResult crossTabContextGuard(Frame frame, DialogueCtx ctx, String text, Object addressee) {
        if (addressee == null || frame.move() == null || !PER_DIALOGUE_MOVES.contains(frame.move())) {
            return null; // not a binding per-dialogue move, or not addressed elsewhere
        }
        String target = String.valueOf(addressee);
        if (target.equals(ctx.activeNegotiationId())) {
            return null; // addressed to the tab it was typed into — the ordinary case
        }
        if (frame.element("money") == null && frame.element("duration") == null
                && frame.element("berth") == null) {
            // Nothing to transplant, so nothing to guard. This early return is load-bearing, not an
            // optimisation (adversarial review, 2026-07-28): the probe below DECLINES whenever the
            // second parse fails, and a cross-tab anaphoric accept/reject — "Carthago: accept it" —
            // parses only WITH a standing offer, because anaphora_move's pronoun clauses require
            // `S \= standing(none,none)`. Without this check the guard declined a move that carries
            // no price, hours or berth at all, and told the player to "say it in full … (price,
            // hours and berth)" for a sentence that has none. Those routed before the guard existed
            // and must keep routing.
            return null;
        }
        DialogueCtx nothingToBorrow = new DialogueCtx(
                ctx.activeNegotiationId(), DialogueCtx.StandingOffer.NONE, ctx.roster(), null);
        Optional<Frame> explicit = dcgParser.parse(text, nothingToBorrow);
        if (explicit.isPresent() && sameBindingTerms(frame, explicit.get())) {
            return null; // fully specified in the sentence itself — allowed across tabs
        }
        String from = displayName(ctx, ctx.activeNegotiationId());
        String to = displayName(ctx, target);
        log.info("cross-tab vocative declined: terms would come from {} but the move is addressed to {}",
                from, to);
        return new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(),
                "That was addressed to " + to + ", but it leaves out terms I would have to take from "
                        + from + "'s offer — and those are different negotiations. Say it in full to "
                        + to + " (price, hours and berth), or switch to " + to + "'s tab first.");
    }

    /** Same move and same binding slots — i.e. the second parse borrowed nothing. */
    private static boolean sameBindingTerms(Frame withContext, Frame withoutContext) {
        return Objects.equals(withContext.move(), withoutContext.move())
                && Objects.equals(withContext.element("money"), withoutContext.element("money"))
                && Objects.equals(withContext.element("duration"), withoutContext.element("duration"))
                && Objects.equals(withContext.element("berth"), withoutContext.element("berth"));
    }

    /** The roster's display name for a vessel id, falling back to the id itself. */
    private static String displayName(DialogueCtx ctx, String vesselId) {
        if (vesselId == null) {
            return "the current tab";
        }
        return ctx.roster().stream()
                .filter(r -> vesselId.equals(r.id()))
                .map(DialogueCtx.RosterEntry::vesselName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(vesselId);
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

    private PipelineResult routeRasaResult(String text, RasaParseResult r, DialogueCtx ctx,
                                           PreprocessRegex.Extracted extracted) {
        ConfidenceGate.Branch branch = gate.route(r.confidence());
        if (branch != ConfidenceGate.Branch.A_HIGH_CONFIDENCE) {
            return clarification();
        }
        return routeHighConfidence(text, r, ctx, extracted);
    }

    /**
     * S6 (audit P2-6 "consider confidence floor for binding intents", calibrated by
     * measurement): NOTHING goes on the wire below this. The wild-set probe on the retrained
     * model showed the false-bind confidence distribution is bimodal — the incorrigible binds
     * sit at &ge;0.98 (a floor cannot touch them without destroying coverage) while a second
     * band sits at 0.80–0.85 ("what would you do in my position" &rarr; request_help@0.817).
     * 0.85 cuts under the healthy mass (correct routes measured overwhelmingly at &ge;0.9)
     * and above that band. ConfidenceGate's 0.60 tri-branch is UNCHANGED — it still names the
     * display semantics (high/medium/low); this floor is the wire's own stricter demand.
     */
    private static final double ROUTE_MIN_CONFIDENCE = 0.85;

    /**
     * A Rasa-classified {@code accept_deal} BINDS a negotiation, so it demands near-certainty:
     * a throwaway {@code "so?"} that the DCG misses and Rasa reads as {@code accept_deal} must
     * never silently close a deal (task 19 play-test — a bare "so?" classified above the 0.60
     * gate closed a €6263 deal with no visible cause; it was 0.80 from task 19 to S6).
     * S6 raised it to 0.95 after the holdout measured {@code "goodbye"} &rarr;
     * {@code accept_deal} at 0.91 — the audit P2-6 money-action wound — so THAT farewell can
     * never close a deal even when the classifier is confidently wrong. Honesty note
     * (adversarial finding 12): "so?" itself still measures at 0.9997 and remains a documented
     * live false-bind in the wild set — the floor caps the 0.85–0.95 band, it cannot touch the
     * incorrigible &ge;0.98 cluster; the explicit Accept button and the DCG path are the safe
     * routes for those. Below this the turn
     * falls to clarification — the player confirms with the Accept button, one click for the
     * game's only irreversible move. S15 (2026-10-12): that button no longer publishes a
     * {@code PlayerCommandEvent} of its own — it sends the literal {@code deal} through this
     * pipeline, which the grammar parses ({@code accept_phrase --> [deal]}), so it takes the DCG
     * accept path ({@code routeFrame}). That path never reaches Rasa and is unaffected by this
     * floor.
     */
    private static final double BINDING_ACCEPT_MIN_CONFIDENCE = 0.95;

    /** Branch A (&ge;0.60) hands intents here, but since S6 the WIRE demands more: everything
     * below {@link #ROUTE_MIN_CONFIDENCE} clarifies before the switch. A structural intent
     * ({@code propose_offer}/{@code counter_offer}) that the DCG already failed to parse still
     * needs clarification — Rasa's confidence alone is not enough structure to build a valid
     * offer/counter frame from. */
    private PipelineResult routeHighConfidence(String text, RasaParseResult r, DialogueCtx ctx,
                                               PreprocessRegex.Extracted extracted) {
        String intent = r.intentName();
        if (intent == null) {
            return clarification();
        }
        if (r.confidence() < ROUTE_MIN_CONFIDENCE) {
            return clarification();
        }
        // S15 (2026-10-12): the confidence Rasa reported for THIS parse, carried on every result
        // routed below so the chat can show "(Rasa 0.97)" under the player's message. It is handed
        // to nothing but the result records: the wire floor above and the accept floor below still
        // read `r.confidence()` themselves, and the switch still decides on `intent` alone.
        ParseProvenance source = ParseProvenance.rasa(r.confidence());
        // S18a (ADR-23, decision N4): Rasa labels the whole sentence and ignores what else is in
        // it - "ok, 2000" is accept_deal at 0.99, and routing it accepts the VESSEL's price. An
        // acceptance or refusal that also names a price, hours or a berth (the regex pre-pass
        // found one) is asked about. Checked before the accept floor below, so the specific
        // sentence wins over the generic one whatever the confidence.
        if (intent.equals("accept_deal") || intent.equals("reject_deal")) {
            // The pre-pass alone is not enough (review): it needs three digits for a bare price
            // and a unit for hours, so "ok 8" and "ok two thousand" named terms it cannot see.
            // The sentence itself and Rasa's own term entities are asked as well.
            java.util.List<String> rasaTerms = new java.util.ArrayList<>();
            for (String entity : java.util.List.of("price_expression", "time_expression", "berth_id")) {
                EntityHit hit = r.entities().get(entity);
                if (hit != null && hit.value() != null && !hit.value().isBlank()) {
                    rasaTerms.add(hit.value());
                }
            }
            Optional<String> terms = InputSafety.termsBesideADecision(
                    intent.equals("accept_deal"), extracted, text, rasaTerms);
            if (terms.isPresent()) {
                return asked(terms.get());
            }
        }
        return switch (intent) {
            // S18a (decision N10): the classifier is sure this is an offer, the grammar could not
            // read its terms - a different situation from "I didn't get that", and said as such.
            case "propose_offer", "counter_offer" -> asked(InputSafety.UNREADABLE_OFFER);
            case "accept_deal" -> r.confidence() >= BINDING_ACCEPT_MIN_CONFIDENCE
                    ? routed(ACLMessage.ACCEPT_PROPOSAL, entitiesContent(text, r, extracted), ctx, source)
                    : clarification();
            case "reject_deal" ->
                    routed(ACLMessage.REJECT_PROPOSAL, entitiesContent(text, r, extracted), ctx, source);
            // An 11th performative beyond the committed 10 (report note, per session brief).
            case "query_status" -> routed(ACLMessage.QUERY_REF, entitiesContent(text, r, extracted), ctx, source);
            case "set_constraint" -> routed(ACLMessage.REQUEST, entitiesContent(text, r, extracted), ctx, source);
            // Audit D-06 (2026-07-27): carries an explicit marker instead of Rasa's entities (which
            // for a bare "help" is an empty object, indistinguishable from set_constraint's). The
            // GUI needs to tell a HELP request apart from a fleet-wide instruction so it can route
            // the typed form to the Assistant, exactly as the "Talk to the assistant" clarification
            // BUTTON already does — before this, `help` was classified correctly at F1 1.000 and
            // then answered with "Fleet-wide commands aren't wired to a dialogue tab yet.", which
            // is not merely unhelpful but false: a help request is not a fleet command. Nothing
            // consumed the entities on this path (there was no consumer at all), so dropping them
            // costs nothing. Same shape as set_policy's dedicated content below.
            case "request_help" -> routed(ACLMessage.REQUEST, helpContent(), ctx, source);
            // Raw text handed onward to PolicyParser (task 10); Rasa's entities are ignored
            // on this path — PolicyParser owns its own regex-DSL parse of the original text.
            case "set_policy" -> routed(ACLMessage.REQUEST, policyContent(text), ctx, source);
            case "cancel_action" -> routeCancelAction(ctx, source);
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
     *   <li>active negotiation with a standing offer on the table -&gt; <b>asked about, not
     *       sent</b> (S18a, ADR-23, decision N3). Until S18a this was a REJECT_PROPOSAL: the
     *       vessel left on a typed "cancel", "never mind" or "nvm", which a player types far more
     *       often to take back their own last line than to end the negotiation. A departure
     *       cannot be undone, so the Reject button is the one way to it.</li>
     *   <li>active negotiation, nothing currently on the table -&gt; plain withdrawal -&gt;
     *       INFORM</li>
     * </ul>
     *
     * @param source S15 (2026-10-12): the Rasa parse this cancel came from — reported on the
     *               two routed results, never consulted here (the three branches read only
     *               {@code ctx})
     */
    private PipelineResult routeCancelAction(DialogueCtx ctx, ParseProvenance source) {
        if (ctx.activeNegotiationId() == null) {
            return routed(ACLMessage.CANCEL, "{}", ctx, source);
        }
        if (ctx.hasStandingOffer()) {
            return asked(InputSafety.CANCEL_IN_A_NEGOTIATION);
        }
        return routed(ACLMessage.INFORM, "{}", ctx, source);
    }

    /** The Rasa path's one construction site. S15 (2026-10-12): {@code source} is required here
     * on purpose — a Rasa route cannot be built without saying how confident Rasa was, so a new
     * intent added to the switch above cannot silently come out untagged. */
    private static PipelineResult routed(int performative, String content, DialogueCtx ctx,
                                         ParseProvenance source) {
        ACLMessage msg = MessageFactory.create(performative);
        if (ctx.activeNegotiationId() != null) {
            msg.setConversationId(ctx.activeNegotiationId());
        }
        msg.setContent(content);
        return new PipelineResult.Routed(msg, null, source);
    }

    /**
     * S6: the resolver behind the Rasa path's vessel-class normalisation — the half of audit
     * P2-2 that makes WordNet/Lesk REACHABLE at runtime. The DCG grammar never emits a
     * {@code vessel_class} element (it only parses canonical type words), so the validator's
     * WordNet stage alone would still never see a raw surface; a Rasa entity is the one place
     * one actually arrives. Lazy dictionary: never loaded until a non-canonical surface shows up.
     */
    private final WordNetResolver wordNet = new WordNetResolver();

    private String entitiesContent(String text, RasaParseResult r, PreprocessRegex.Extracted extracted) {
        Map<String, String> values = new LinkedHashMap<>();
        r.entities().forEach((name, hit) -> values.put(name, hit.value()));
        mergeRegexBackfill(values, extracted);
        normaliseVesselClass(values, text);
        return TerminalJson.write(values);
    }

    /**
     * S6 (audit P2-3): regex hits FILL entities Rasa missed — {@code putIfAbsent} only, so a
     * classifier-extracted value always wins and the pre-pass can add but never change.
     * Applies to the four entity-carrying intents (this method's callers); set_policy, help
     * and cancel keep their dedicated content shapes by design. The duration is rendered back
     * to the corpus's own surface convention ("6 hours"); a deadline or clock time fills
     * time_expression only when no duration did (a duration is the negotiation-relevant one).
     */
    private static void mergeRegexBackfill(Map<String, String> values, PreprocessRegex.Extracted extracted) {
        // Value-space note, honest (adversarial finding 9): a backfilled key does not carry the
        // corpus's raw-surface convention — price is the normalised bare number, a clock time is
        // HH:MM — and consumers of this content read values loosely by design. Non-finite
        // prices are dropped outright rather than serialised as NaN/overflow artifacts.
        extracted.price().ifPresent(p -> {
            if (!p.isNaN() && !p.isInfinite()) {
                values.putIfAbsent("price_expression",
                        p == Math.floor(p) ? String.valueOf(p.longValue()) : String.valueOf(p));
            }
        });
        extracted.berthId().ifPresent(b -> values.putIfAbsent("berth_id", b));
        extracted.vesselName().ifPresent(v -> values.putIfAbsent("vessel_name", v));
        extracted.duration().ifPresent(d -> values.putIfAbsent("time_expression",
                d + (d == 1 ? " hour" : " hours")));
        if (!values.containsKey("time_expression")) {
            extracted.deadline().ifPresent(d -> values.put("time_expression", d.toString()));
        }
        if (!values.containsKey("time_expression")) {
            extracted.time().ifPresent(t -> values.put("time_expression", t.toString()));
        }
    }

    /**
     * Rasa's implicit synonym table canonicalises every surface the training corpus annotates
     * ("cargo ship" → {@code cargo_vessel}), and S6's explicit {@code nlu_tables.yml} blocks
     * cover freighter/merchantman/liner too — so on the happy path Rasa hands this method an
     * already-canonical value and the short-circuit below fires (adversarial finding 8: the
     * two example surfaces are Rasa-handled FIRST). This stage is the FALLBACK for surfaces
     * outside both tables ("merchant vessel", "boxship", a mapper miss), where the handcrafted
     * table + WordNet run with the utterance's own words as the Lesk context — a voyage
     * "liner" resolves to a cruise ship, an eyeliner does not. Unresolvable surfaces are left
     * as-is (downstream validation owns them).
     */
    private void normaliseVesselClass(Map<String, String> values, String text) {
        String surface = values.get("vessel_class");
        if (surface == null || PrologQueries.vesselTypeExists(surface)) {
            return;
        }
        java.util.List<String> context = text == null || text.isBlank()
                ? java.util.List.of()
                : java.util.List.of(text.toLowerCase(java.util.Locale.ROOT).split("\\W+"));
        String resolved = wordNet.resolveToOntology(surface, "noun", context);
        if (PrologQueries.vesselTypeExists(resolved)) {
            log.debug("Rasa vessel_class '{}' normalised to '{}' (WordNet/Lesk)", surface, resolved);
            values.put("vessel_class", resolved);
        } else {
            log.debug("Rasa vessel_class '{}' resolves to no vessel type ('{}') — left as-is",
                    surface, resolved);
        }
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

    /** S18a (ADR-23): a turn that was read well enough to be refused on purpose - the sentence
     * says what was read and what to do instead (see {@link InputSafety}). Nothing is sent. */
    private static PipelineResult asked(String message) {
        return asked(message, null);
    }

    /** @param aboutVesselId the vessel the refused move was for when that is not the vessel of the
     *                       tab it was typed in; {@code null} otherwise */
    private static PipelineResult asked(String message, String aboutVesselId) {
        return new PipelineResult.NeedsClarification(ClarificationButtons.defaultOptions(), message,
                aboutVesselId);
    }
}
