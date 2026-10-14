package it.unige.portcommand.nlp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jade.lang.acl.ACLMessage;

import it.unige.portcommand.ontology.Offer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic end-to-end coverage of {@link NLPPipeline}'s DCG-first orchestration. Rasa is a
 * real {@link RasaBridge} pointed at an in-memory {@link HttpServer} mock, never the real
 * Python process — the pre-commit hook's {@code gradlew check} must pass with both servers
 * down.
 */
class NLPPipelineTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private RasaBridge rasaAgainst(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/model/parse", handler);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/model/parse");
        return new RasaBridge(uri, HttpClient.newHttpClient(), new ObjectMapper());
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private NLPPipeline pipeline(RasaBridge rasa, DCGParser dcgParser, BiPredicate<Frame, String> frameValidator) {
        executor = Executors.newFixedThreadPool(2);
        return new NLPPipeline(new PreprocessRegex(), rasa, new ConfidenceGate(), dcgParser, frameValidator, executor);
    }

    private NLPPipeline pipeline(RasaBridge rasa) {
        return pipeline(rasa, new NoOpDCGParser(), (f, t) -> true);
    }

    /** Every wait has a deadline (design rule 5): a pipeline that never answers fails here
     * in fifteen seconds with a sentence, instead of hanging the lane. */
    private static PipelineResult await(CompletableFuture<PipelineResult> future) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the pipeline", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the pipeline did not answer within 15 s", e);
        }
    }

    // --- DCG-first: a frame short-circuits Rasa entirely --------------------------------------

    @Test
    void dcgHitRoutesWithoutEverCallingRasa() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, "{\"intent\": {\"name\": \"reject_deal\", \"confidence\": 0.99}}");
        });
        // Task 16 reshaped the frame: the name is the FrameNet frame (commerce_sell) for all five
        // negotiation moves, and the move type lives in the `move` element. See ADR-10.
        DCGParser dcg = (text, ctx) ->
                Optional.of(new Frame("commerce_sell", Map.of(Frame.MOVE, "propose", "price", 2000)));

        PipelineResult result = await(pipeline(rasa, dcg, (f, t) -> true).processChatInput(
                "I will give you 2000 for 5 hours at berth 3", DialogueCtx.NONE));

        PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class, result);
        assertEquals(ACLMessage.PROPOSE, routed.msg().getPerformative());
        assertEquals(0, rasaCalls.get(), "a Rasa outage must never block a clean DCG parse");
    }

    @Test
    void dcgHitFailingValidationFallsBackToClarificationWithoutCallingRasa() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.9}}");
        });
        DCGParser dcg = (text, ctx) -> Optional.of(new Frame("commerce_sell", Map.of(Frame.MOVE, "propose")));

        PipelineResult result = await(pipeline(rasa, dcg, (f, t) -> false).processChatInput("anything", DialogueCtx.NONE));

        assertInstanceOf(PipelineResult.NeedsClarification.class, result);
        assertEquals(0, rasaCalls.get());
    }

    // --- DCG miss -> Rasa fallback -> confidence gate ------------------------------------------

    @Test
    void highConfidenceAcceptDealRoutesToAcceptProposal() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.95}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("deal", DialogueCtx.NONE));

        assertEquals(ACLMessage.ACCEPT_PROPOSAL, assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
    }

    @Test
    void aBindingAcceptAtPointNinetyFiveOrAboveStillRoutes() throws IOException {
        // The guard is a floor, not a ban: a genuinely confident accept still closes.
        // S6 raised it 0.80 -> 0.95 after the holdout measured "goodbye" -> accept_deal@0.91
        // (audit P2-6's money-action wound) — a farewell must never close a deal.
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.95}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("yes I accept", DialogueCtx.NONE));

        assertEquals(ACLMessage.ACCEPT_PROPOSAL,
                assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
    }

    @Test
    void anAcceptInTheGoodbyeBandFallsToClarification() throws IOException {
        // 0.91 is exactly where the measured misclassified farewell sat — below the S6
        // accept floor (0.95), above the general wire floor (0.85). This is also the task-19
        // "so?" exploit's modern pin: a sub-floor accept must clarify, never auto-close (the
        // old 0.70-case test was deleted as vacuous once ROUTE_MIN subsumed it — adversarial
        // finding 11; sub-0.85 accepts are covered by anyRouteBelowTheWireFloor...).
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.91}, \"entities\": []}"));

        assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline(rasa).processChatInput("goodbye", DialogueCtx.NONE)));
    }

    @Test
    void anyRouteBelowTheWireFloorFallsToClarification() throws IOException {
        // S6 (audit P2-6): ROUTE_MIN_CONFIDENCE = 0.85 for EVERY wire-emitting intent — the
        // measured false-bind band at 0.80-0.85 ("what would you do in my position" ->
        // request_help@0.817) clarifies instead of emitting a REQUEST.
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"request_help\", \"confidence\": 0.82}, \"entities\": []}"));

        assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline(rasa).processChatInput("what would you do in my position", DialogueCtx.NONE)));
    }

    @Test
    void highConfidenceRejectDealRoutesToRejectProposal() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"reject_deal\", \"confidence\": 0.95}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("no deal", DialogueCtx.NONE));

        assertEquals(ACLMessage.REJECT_PROPOSAL, assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
    }

    @Test
    void highConfidenceQueryStatusRoutesToQueryRef() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"query_status\", \"confidence\": 0.9}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("what's the status?", DialogueCtx.NONE));

        assertEquals(ACLMessage.QUERY_REF, assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
    }

    @Test
    void highConfidenceSetPolicyCarriesRawTextIgnoringEntities() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, """
                {"intent": {"name": "set_policy", "confidence": 0.9},
                 "entities": [{"entity": "price_expression", "value": "2200", "start": 0, "end": 4}]}"""));

        // S18a (N5): a sentence the policy DSL itself parses never reaches Rasa any more (see
        // policyTextTypedInChatIsAnsweredNotClassified), so this route is what is left for the
        // free-text policies only Rasa recognises.
        String rawText = "always take tankers that pay more than 2200";
        PipelineResult result = await(pipeline(rasa).processChatInput(rawText, DialogueCtx.NONE));

        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, result).msg();
        assertEquals(ACLMessage.REQUEST, msg.getPerformative());
        assertTrue(msg.getContent().contains(rawText), "content must carry the raw text verbatim: " + msg.getContent());
        assertFalse(msg.getContent().contains("price_expression"), "entities must be ignored on the set_policy path");
    }

    // --- S6 (audit P2-3): the regex pre-pass FILLS missing Rasa entities, never overrides ------

    /**
     * The rescue case: Rasa gets the intent right and the entities WRONG (misses them
     * entirely) — before S6 the PreprocessRegex extraction was computed at the top of the
     * pipeline and thrown away, so this REQUEST went out with empty content. Now the pre-pass
     * backfills the price and the berth from the utterance's own surface.
     */
    @Test
    void regexPrePassRescuesEntitiesRasaMissed() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"set_constraint\", \"confidence\": 0.9}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput(
                "no tankers over 2500 at berth 2", DialogueCtx.NONE));

        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, result).msg();
        assertEquals(ACLMessage.REQUEST, msg.getPerformative());
        assertTrue(msg.getContent().contains("\"price_expression\":\"2500\""),
                "the regex pre-pass must supply the price Rasa missed: " + msg.getContent());
        assertTrue(msg.getContent().contains("\"berth_id\":\"berth_2\""),
                "the regex pre-pass must supply the berth (already canonical): " + msg.getContent());
    }

    /** The other half of the contract: a value Rasa DID extract is never overridden. */
    @Test
    void regexPrePassNeverOverridesARasaValue() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, """
                {"intent": {"name": "set_constraint", "confidence": 0.9},
                 "entities": [{"entity": "price_expression", "value": "1800", "start": 0, "end": 4}]}"""));

        PipelineResult result = await(pipeline(rasa).processChatInput(
                "cap it at 1800 not 2500 at berth 2", DialogueCtx.NONE));

        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, result).msg();
        assertTrue(msg.getContent().contains("\"price_expression\":\"1800\""),
                "Rasa's own extraction must survive untouched: " + msg.getContent());
        assertFalse(msg.getContent().contains("2500"),
                "the regex hit must not replace a parsed value: " + msg.getContent());
    }

    @Test
    void structuralIntentTheDcgAlreadyMissedNeedsClarification() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"propose_offer\", \"confidence\": 0.99}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("2000 for 5 hours", DialogueCtx.NONE));

        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class, result);
        // S18a (N10) - born red: this turn got the same "I didn't get that" as a keyboard mash,
        // although the classifier had seen exactly what it was.
        assertEquals("I see an offer but couldn't read the terms — write it like '{example}'.",
                asked.message());
    }

    @Test
    void aCounterOfferTheGrammarMissedSaysTheSameThing() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, rasaReply("counter_offer", 0.97)));

        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline(rasa).processChatInput("could you stretch to nineteen hundred", DialogueCtx.NONE)));

        assertEquals("I see an offer but couldn't read the terms — write it like '{example}'.",
                asked.message());
    }

    @Test
    void mediumConfidenceNeedsClarification() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.55}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("hmm maybe", DialogueCtx.NONE));

        assertInstanceOf(PipelineResult.NeedsClarification.class, result);
    }

    @Test
    void lowConfidenceNeedsClarification() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.1}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("???", DialogueCtx.NONE));

        assertInstanceOf(PipelineResult.NeedsClarification.class, result);
    }

    // --- cancel_action: both v1.1 routing branches, plus the no-active-negotiation case --------

    @Test
    void cancelActionWithNoActiveNegotiationMapsToCancel() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"cancel_action\", \"confidence\": 0.9}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("cancel the tug", DialogueCtx.NONE));

        assertEquals(ACLMessage.CANCEL, assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
    }

    /**
     * S18a (ADR-23, decision N3) - born red. This used to be {@code
     * cancelActionWithStandingOfferMapsToRejectProposal}: "never mind", "cancel" or "nvm" typed
     * in a negotiation tab became a REJECT_PROPOSAL and the vessel left. A player who types
     * "cancel" is far more often taking back their own last line than ending the negotiation, and
     * a departure cannot be undone - so the tab asks, and the Reject button stays the one way out.
     */
    @Test
    void cancelWithAnOfferOnTheTableAsksInsteadOfRejecting() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"cancel_action\", \"confidence\": 0.99}, \"entities\": []}"));
        Offer standingOffer = new Offer(2000, 5, "berth_3", "vessel_1", "harbour_master", 0L);
        DialogueCtx ctx = new DialogueCtx("conv-1", standingOffer, null);

        NLPPipeline pipeline = pipeline(rasa);

        for (String typed : List.of("never mind", "cancel", "nvm")) {
            PipelineResult result = await(pipeline.processChatInput(typed, ctx));

            PipelineResult.NeedsClarification asked =
                    assertInstanceOf(PipelineResult.NeedsClarification.class, result, typed);
            assertEquals("That would end the negotiation — press Reject to confirm.", asked.message(), typed);
        }
    }

    @Test
    void cancelActionWithActiveNegotiationButNoStandingOfferMapsToInform() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"cancel_action\", \"confidence\": 0.9}, \"entities\": []}"));
        DialogueCtx ctx = new DialogueCtx("conv-1", null, null);

        PipelineResult result = await(pipeline(rasa).processChatInput("never mind", ctx));

        assertEquals(ACLMessage.INFORM, assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
    }

    // --- failure handling -----------------------------------------------------------------------

    @Test
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void rasaTimeoutResolvesToClarificationNotAnException() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.9}}");
        });

        PipelineResult result = await(pipeline(rasa).processChatInput("slow reply", DialogueCtx.NONE));

        assertInstanceOf(PipelineResult.NeedsClarification.class, result);
    }

    @Test
    void nonTimeoutRasaFailureResolvesToError() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 400, "{\"error\": \"bad request\"}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("hello", DialogueCtx.NONE));

        assertInstanceOf(PipelineResult.Error.class, result);
    }

    @Test
    void nullTextResolvesToErrorNotAnExceptionalFuture() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, "{}"));

        CompletableFuture<PipelineResult> future = pipeline(rasa).processChatInput(null, DialogueCtx.NONE);

        assertInstanceOf(PipelineResult.Error.class, future.join());
        assertFalse(future.isCompletedExceptionally());
    }

    // --- non-blocking contract -------------------------------------------------------------------

    @Test
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void processChatInputReturnsBeforeRasaResponds() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"intent\": {\"name\": \"accept_deal\", \"confidence\": 0.9}}");
        });

        CompletableFuture<PipelineResult> future = pipeline(rasa).processChatInput("deal", DialogueCtx.NONE);

        assertFalse(future.isDone(), "processChatInput must return before the slow Rasa call finishes");
        future.join();
    }

    // --- S15 (2026-10-12): a routed result says which stage produced it -------------------------
    // Reporting only — the chat shows it as "(DCG)" / "(Rasa 0.97)" under the player's message.
    // Every routing assertion above is unchanged; these add what the result now carries.

    private static String rasaReply(String intent, double confidence) {
        return "{\"intent\": {\"name\": \"" + intent + "\", \"confidence\": " + confidence + "}, \"entities\": []}";
    }

    @Test
    void aDcgRouteCarriesDcgProvenance() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, rasaReply("reject_deal", 0.99));
        });
        DCGParser dcg = (text, ctx) -> Optional.of(new Frame("commerce_sell", Map.of(Frame.MOVE, "accept")));

        PipelineResult result = await(pipeline(rasa, dcg, (f, t) -> true).processChatInput("deal", DialogueCtx.NONE));

        PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class, result);
        assertEquals(ACLMessage.ACCEPT_PROPOSAL, routed.msg().getPerformative());
        assertEquals(ParseProvenance.DCG, routed.provenance());
        assertEquals(ParseProvenance.Stage.DCG, routed.provenance().stage());
        assertTrue(Double.isNaN(routed.provenance().confidence()),
                "a grammar parses or it does not — there is no confidence to report");
        assertEquals(0, rasaCalls.get(), "and Rasa was never asked, so no Rasa number can leak in");
    }

    @Test
    void aDcgRouteKeepsItsAddresseeAlongsideItsProvenance() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, rasaReply("reject_deal", 0.99)));
        // A slot-less vocative ("Carthago: deal"): routes across tabs, addressee surfaced.
        DCGParser dcg = (text, ctx) -> Optional.of(
                new Frame("commerce_sell", Map.of(Frame.MOVE, "accept", "addressee", "T001")));

        PipelineResult result = await(pipeline(rasa, dcg, (f, t) -> true)
                .processChatInput("Carthago: deal", DialogueCtx.NONE));

        PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class, result);
        assertEquals("T001", routed.addressee(), "the third component must not displace the second");
        assertEquals(ParseProvenance.DCG, routed.provenance());
    }

    @Test
    void aRasaRouteCarriesItsRealConfidence() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, rasaReply("reject_deal", 0.97)));

        PipelineResult result = await(pipeline(rasa).processChatInput("not for me", DialogueCtx.NONE));

        PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class, result);
        assertEquals(ACLMessage.REJECT_PROPOSAL, routed.msg().getPerformative());
        assertEquals(ParseProvenance.Stage.RASA, routed.provenance().stage());
        assertEquals(0.97, routed.provenance().confidence(),
                "the number Rasa reported for THIS parse, exactly — not a floor, not a constant");
        assertEquals(ParseProvenance.rasa(0.97), routed.provenance());
    }

    @Test
    void everyIntentRasaCanRouteCarriesTheConfidenceOfItsOwnParse() throws IOException {
        // One server, one pipeline; the reply changes per turn so each route must report the
        // confidence of its own parse (a value remembered from an earlier turn would show here).
        AtomicReference<String> reply = new AtomicReference<>(rasaReply("out_of_scope", 0.99));
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, reply.get()));
        NLPPipeline pipeline = pipeline(rasa);
        Map<String, Double> turns = new LinkedHashMap<>();
        turns.put("accept_deal", 0.9997);    // the measured "so?" — above the 0.95 accept floor
        turns.put("reject_deal", 0.86);      // just above the 0.85 wire floor
        turns.put("query_status", 0.9);
        turns.put("set_constraint", 0.93);
        turns.put("request_help", 0.88);
        turns.put("set_policy", 0.91);
        turns.put("cancel_action", 0.96);

        for (Map.Entry<String, Double> turn : turns.entrySet()) {
            reply.set(rasaReply(turn.getKey(), turn.getValue()));

            PipelineResult result = await(pipeline.processChatInput("anything the grammar misses", DialogueCtx.NONE));

            PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class, result, turn.getKey());
            assertEquals(ParseProvenance.rasa(turn.getValue()), routed.provenance(), turn.getKey());
            assertEquals(null, routed.addressee(), "a Rasa route is never addressed: " + turn.getKey());
        }
    }

    @Test
    void aRasaCancelRouteCarriesItsConfidenceToo() throws IOException {
        // cancel_action takes its own three-way branch on the dialogue context; each branch must
        // hand the parse's confidence on, not just the first.
        AtomicReference<String> reply = new AtomicReference<>(rasaReply("cancel_action", 0.9));
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, reply.get()));
        NLPPipeline pipeline = pipeline(rasa);

        reply.set(rasaReply("cancel_action", 0.91));
        PipelineResult.Routed cancel = assertInstanceOf(PipelineResult.Routed.class,
                await(pipeline.processChatInput("cancel the tug", DialogueCtx.NONE)));
        assertEquals(ACLMessage.CANCEL, cancel.msg().getPerformative());
        assertEquals(ParseProvenance.rasa(0.91), cancel.provenance());

        // S18a (N3): with an offer on the table a cancel is no longer routed at all - it is asked
        // about - so there is no route here to carry a confidence.
        reply.set(rasaReply("cancel_action", 0.89));
        Offer standingOffer = new Offer(2000, 5, "berth_3", "vessel_1", "harbour_master", 0L);
        assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("never mind", new DialogueCtx("conv-1", standingOffer, null))));

        reply.set(rasaReply("cancel_action", 0.87));
        PipelineResult.Routed plainWithdrawal = assertInstanceOf(PipelineResult.Routed.class,
                await(pipeline.processChatInput("never mind", new DialogueCtx("conv-1", null, null))));
        assertEquals(ACLMessage.INFORM, plainWithdrawal.msg().getPerformative());
        assertEquals(ParseProvenance.rasa(0.87), plainWithdrawal.provenance());
    }

    @Test
    void carryingTheConfidenceDoesNotMoveEitherFloor() throws IOException {
        // The provenance is built from the same number the floors read; it must not become a
        // second way through them. 0.949 accept and 0.849 reject still clarify, and a clarified
        // turn is not a routed one — it has nothing to tag.
        AtomicReference<String> reply = new AtomicReference<>(rasaReply("accept_deal", 0.949));
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, reply.get()));
        NLPPipeline pipeline = pipeline(rasa);

        assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("so?", DialogueCtx.NONE)));

        reply.set(rasaReply("reject_deal", 0.849));
        assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("hmm", DialogueCtx.NONE)));

        reply.set(rasaReply("propose_offer", 0.99));
        assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("2000 for 5 hours", DialogueCtx.NONE)),
                "Rasa never routes an offer, however sure it is");
    }

    // --- S18a (ADR-23): input safety - what the chat refuses to send, and what it says instead ---
    // The vessel accepts any demand at or below its own offer. So a slip of the keyboard that the
    // pipeline reads as a low price, or as an acceptance, is a binding money action - these are
    // the checks that stand between the two. Each test below was born red.

    /** A cargo vessel offering EUR 1436 for 6 h at berth 3; the player has not answered yet. */
    private static final DialogueCtx TABLE = new DialogueCtx("V1",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(1436.0, 6, "berth_3"),
                    DialogueCtx.OfferView.NONE),
            List.of(new DialogueCtx.RosterEntry("V1", "cargo_vessel", "Genoa Star", "berth_3", 6),
                    new DialogueCtx.RosterEntry("T1", "tanker", "Carthago", "berth_2", 8)),
            "berth_3");

    private static Frame demand(String move, Number amount) {
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put(Frame.MOVE, move);
        elements.put("money", Map.of("amount", amount, "currency", "EUR"));
        elements.put("duration", 6L);
        elements.put("berth", "berth_3");
        return new Frame("commerce_sell", elements);
    }

    /** A pipeline whose grammar always answers {@code frame}, and a Rasa that must not be asked. */
    private NLPPipeline grammarAnswering(Frame frame, AtomicInteger rasaCalls) throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, rasaReply("accept_deal", 0.99));
        });
        return pipeline(rasa, (text, ctx) -> Optional.of(frame), (f, t) -> true);
    }

    /** Each helper call below starts a server and a pool of its own; stop them before the next. */
    private void stopServices() {
        cleanup();
        server = null;
        executor = null;
    }

    private String askedAbout(Frame frame, DialogueCtx ctx) throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        PipelineResult result = await(grammarAnswering(frame, rasaCalls).processChatInput("typed", ctx));
        stopServices();
        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                result, () -> "expected a question, not a route, for " + frame.elements());
        assertEquals(0, rasaCalls.get(), "a refused price is not handed to Rasa for a second opinion");
        return asked.message();
    }

    private ACLMessage sent(Frame frame, DialogueCtx ctx) throws IOException {
        PipelineResult result = await(grammarAnswering(frame, new AtomicInteger()).processChatInput("typed", ctx));
        stopServices();
        return assertInstanceOf(PipelineResult.Routed.class, result,
                () -> "expected a route for " + frame.elements()).msg();
    }

    /** Decision N1: typed "8" (or "make it 8") was a counter of EUR 8, which the vessel took. */
    @Test
    void aNumberNoLargerThanTwentyFourIsNeverSentAsAPrice() throws IOException {
        assertEquals("Did you mean €8? A number that small is never sent as a price — hours are "
                        + "written with an h, beside a price: '{example}'.",
                askedAbout(demand("counter", 8L), TABLE));
        assertTrue(askedAbout(demand("counter", 24L), TABLE).startsWith("Did you mean €24?"), "24 is still hours");
        assertTrue(askedAbout(demand("propose", 2L), DialogueCtx.NONE).startsWith("Did you mean €2?"),
                "with no offer on the table too: the rule is about the number, not the context");
        assertTrue(askedAbout(demand("counter", 0L), TABLE).startsWith("Did you mean €0?"));

        assertEquals(ACLMessage.PROPOSE, sent(demand("propose", 25L), DialogueCtx.NONE).getPerformative(),
                "25 is past every hour, berth and tug count - with nothing to compare it to, it is a price");
    }

    /** Decision N1: any demand below half of the vessel's own offer is asked about. Moji's playtest
     * closed a EUR 3,692 cruise deal at EUR 400 this way. */
    @Test
    void aPriceBelowHalfOfTheirOfferIsAskedAbout() throws IOException {
        assertEquals("Did you mean €700? That is less than half of their offer (€1436), so nothing "
                        + "was sent — a price looks like '{example}'.",
                askedAbout(demand("counter", 700L), TABLE));
        assertTrue(askedAbout(demand("propose", 717L), TABLE).startsWith("Did you mean €717?"),
                "717 is below half of 1436");

        assertEquals(ACLMessage.PROPOSE, sent(demand("counter", 718L), TABLE).getPerformative(),
                "exactly half is not below half");
        assertEquals(ACLMessage.PROPOSE, sent(demand("counter", 1900L), TABLE).getPerformative(),
                "the demo's own line: asking MORE than they offer is the ordinary move");
        assertEquals(ACLMessage.PROPOSE, sent(demand("counter", 700L), DialogueCtx.NONE).getPerformative(),
                "with no offer of theirs there is nothing to be half of");
    }

    /** The comparison is with the offer of the vessel the move is FOR. Typed in Genoa Star's tab
     * but addressed to Carthago, with no way to look Carthago's offer up, only the absolute rule
     * applies - Genoa Star's EUR 1436 says nothing about a tanker. */
    @Test
    void theHalfOfferCheckNeverUsesAnotherVesselsOffer() throws IOException {
        Frame toCarthago = demand("counter", 700L);
        Map<String, Object> elements = new LinkedHashMap<>(toCarthago.elements());
        elements.put("addressee", "T1");
        Frame addressed = new Frame("commerce_sell", elements);

        assertEquals(ACLMessage.PROPOSE, sent(addressed, TABLE).getPerformative());

        elements.put("money", Map.of("amount", 8L, "currency", "EUR"));
        assertTrue(askedAbout(new Frame("commerce_sell", elements), TABLE).startsWith("Did you mean €8?"));
    }

    /** ...and when the addressee's own context CAN be looked up (the two-step vocative), it is the
     * addressee's offer the price is held against. */
    @Test
    void theHalfOfferCheckUsesTheAddresseesOwnOffer() throws IOException {
        Map<String, Object> elements = new LinkedHashMap<>(demand("counter", 1000L).elements());
        elements.put("addressee", "T1");
        Frame addressed = new Frame("commerce_sell", elements);
        DialogueCtx carthago = new DialogueCtx("T1",
                new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(4500.0, 8, "berth_2"),
                        DialogueCtx.OfferView.NONE),
                TABLE.roster(), "berth_2");

        PipelineResult result = await(grammarAnswering(addressed, new AtomicInteger())
                .processChatInput("Carthago: 1000", TABLE, id -> "T1".equals(id) ? carthago : null));

        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class, result);
        // Review finding: the refusal names the vessel it is about and says so on the result, so
        // the chat takes the worked example from Carthago's tab. Genoa Star's example (above
        // EUR 1436) would pass this very check for Carthago and close EUR 1,250 under its offer.
        assertTrue(asked.message().contains("less than half of Carthago's offer (€4500)"), asked.message());
        assertEquals("T1", asked.aboutVesselId());
    }

    /** A refusal about the vessel of the tab it was typed in is about nobody else. */
    @Test
    void aRefusalInTheVesselsOwnTabNamesNoOtherVessel() throws IOException {
        PipelineResult result = await(grammarAnswering(demand("counter", 700L), new AtomicInteger())
                .processChatInput("typed", TABLE));

        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class, result);
        assertEquals(null, asked.aboutVesselId());
        assertTrue(asked.message().contains("their offer"), asked.message());
    }

    /** ...and an explicit move to a vessel that cannot be looked up is still ABOUT that vessel. */
    @Test
    void aRefusalOfAMoveToAnotherVesselSaysWhoItIsAbout() throws IOException {
        Map<String, Object> elements = new LinkedHashMap<>(demand("counter", 8L).elements());
        elements.put("addressee", "T1");

        PipelineResult result = await(grammarAnswering(new Frame("commerce_sell", elements), new AtomicInteger())
                .processChatInput("typed", TABLE));

        assertEquals("T1", assertInstanceOf(PipelineResult.NeedsClarification.class, result).aboutVesselId());
    }

    /** Decision N4: "ok, 2000" is not "ok". Rasa reads the first and binds an ACCEPT of the
     * vessel's price; the sentence named another one. */
    @Test
    void anAcceptThatAlsoNamesTermsIsAskedAbout() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, rasaReply("accept_deal", 0.999)));
        NLPPipeline pipeline = pipeline(rasa);

        PipelineResult.NeedsClarification price = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("ok, 2000", TABLE)));
        assertEquals("That reads as accepting their offer, but you also wrote €2000 — accepting takes "
                + "their terms as they stand, so nothing was sent. For other terms write '{example}'; "
                + "to take theirs press Accept.", price.message());

        PipelineResult.NeedsClarification hours = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("fine by me for 8 hours", TABLE)));
        assertTrue(hours.message().contains("you also wrote 8h"), hours.message());

        PipelineResult.NeedsClarification both = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("yes ok 2000 euros for 8 hours then", TABLE)));
        assertTrue(both.message().contains("you also wrote €2000 and 8h"), both.message());

        PipelineResult.NeedsClarification berth = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("ok but at berth 2", TABLE)));
        assertTrue(berth.message().contains("you also wrote berth 2"), berth.message());

        assertEquals(ACLMessage.ACCEPT_PROPOSAL, assertInstanceOf(PipelineResult.Routed.class,
                        await(pipeline.processChatInput("that'll do nicely", TABLE))).msg().getPerformative(),
                "an acceptance with no terms in it is still an acceptance");
    }

    /**
     * Review finding - born red. The first version asked only the regex pre-pass, which needs
     * three digits for a bare price and a unit for hours: "ok 8", "yes for 12" and "ok two
     * thousand" named terms it could not see, and an ACCEPT went out. Any number in the sentence
     * - digits, a number word, or a term Rasa itself extracted - is a term beside the decision.
     */
    @Test
    void anAcceptIsAskedAboutWhateverFormItsNumberTakes() throws IOException {
        AtomicReference<String> reply = new AtomicReference<>(rasaReply("accept_deal", 0.999));
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, reply.get()));
        NLPPipeline pipeline = pipeline(rasa);

        for (String typed : List.of("ok 8", "yes for 12", "fine, 50 then")) {
            PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                    await(pipeline.processChatInput(typed, TABLE)), typed);
            assertTrue(asked.message().startsWith("That reads as accepting their offer, but you also wrote "),
                    typed + " -> " + asked.message());
        }
        assertTrue(assertInstanceOf(PipelineResult.NeedsClarification.class,
                        await(pipeline.processChatInput("ok 8", TABLE))).message().contains("you also wrote 8 —"));

        PipelineResult.NeedsClarification words = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("ok two thousand then", TABLE)));
        assertTrue(words.message().contains("you also wrote \"two thousand\""), words.message());

        // A term only Rasa saw: nothing for the pre-pass, no digit, no number word.
        reply.set("{\"intent\": {\"name\": \"reject_deal\", \"confidence\": 0.99}, \"entities\": "
                + "[{\"entity\": \"time_expression\", \"value\": \"a fortnight\", \"start\": 13, \"end\": 24}]}");
        PipelineResult.NeedsClarification entity = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("not for under a fortnight", TABLE)));
        assertTrue(entity.message().startsWith("That reads as turning them away, but you also wrote "
                + "\"a fortnight\""), entity.message());

        // "one" is a pronoun far more often than a number: it must not turn an acceptance into a question.
        reply.set(rasaReply("accept_deal", 0.999));
        assertInstanceOf(PipelineResult.Routed.class, await(pipeline.processChatInput("go with that one", TABLE)));
    }

    @Test
    void aRejectThatAlsoNamesTermsIsAskedAbout() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200, rasaReply("reject_deal", 0.97)));
        NLPPipeline pipeline = pipeline(rasa);

        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                await(pipeline.processChatInput("nope, 2000 is nowhere near enough", TABLE)));
        assertEquals("That reads as turning them away, but you also wrote €2000, so nothing was sent. "
                + "For other terms write '{example}'; to end the negotiation press Reject.", asked.message());

        assertEquals(ACLMessage.REJECT_PROPOSAL, assertInstanceOf(PipelineResult.Routed.class,
                await(pipeline.processChatInput("not a chance", TABLE))).msg().getPerformative());
    }

    /** Decision N5: a policy typed into a chat tab was handed to Rasa, which may call it an
     * acceptance. A sentence the policy DSL parses is answered before anything classifies it. */
    @Test
    void policyTextTypedInChatIsAnsweredNotClassified() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        AtomicInteger grammarCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, rasaReply("accept_deal", 0.999));
        });
        NLPPipeline pipeline = pipeline(rasa, (text, ctx) -> {
            grammarCalls.incrementAndGet();
            return Optional.empty();
        }, (f, t) -> true);

        for (String policy : List.of("auto accept if price > 6000 for tankers",
                "AUTO reject if price < 1000", "auto counter +10% if rounds_used < 3 for ferries")) {
            PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                    await(pipeline.processChatInput(policy, TABLE)), policy);
            assertEquals("Policies are set in Settings → Autopilot policy — nothing was sent to the vessel.",
                    asked.message(), policy);
        }
        assertEquals(0, rasaCalls.get(), "a policy is never classified");
        assertEquals(0, grammarCalls.get(), "nor parsed as a move");
    }

    /** Playtest decision PT7: "berth 5" got the generic "I didn't get that". */
    @Test
    void aBerthThatDoesNotExistIsAnsweredByNumber() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, rasaReply("set_constraint", 0.99));
        });
        NLPPipeline pipeline = pipeline(rasa);

        for (String typed : List.of("berth 5", "berth-5", "berth5", "Berth 9 then", "2000 for 6h at berth 7",
                "is berth 0 free", "berth 12")) {
            PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                    await(pipeline.processChatInput(typed, TABLE)), typed);
            assertTrue(asked.message().matches("There is no berth \\d+ — berths are 1 to 4 "
                    + "\\(ferries fit only berth 4\\)\\."), typed + " -> " + asked.message());
        }
        assertEquals("There is no berth 5 — berths are 1 to 4 (ferries fit only berth 4).",
                assertInstanceOf(PipelineResult.NeedsClarification.class,
                        await(pipeline.processChatInput("berth 5", TABLE))).message());
        assertEquals(0, rasaCalls.get(), "the answer is known before Rasa is asked");

        assertInstanceOf(PipelineResult.Routed.class, await(pipeline.processChatInput("is berth 2 taken", TABLE)),
                "a berth that exists goes on to Rasa as before");
        assertEquals(1, rasaCalls.get());
    }

    /** Review finding - born red: "same berth 2200" was answered "There is no berth 2200". A number
     * after the word is a berth only if it could be one - one or two digits, and not hours. */
    @Test
    void aPriceOrHoursAfterTheWordBerthIsNotABerthNumber() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, rasaReply("out_of_scope", 0.99));
        });
        NLPPipeline pipeline = pipeline(rasa);

        for (String typed : List.of("same berth 2200", "that berth 2500 please", "free berth 5 hours",
                "any berth 6h")) {
            PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                    await(pipeline.processChatInput(typed, TABLE)), typed);
            assertEquals(null, asked.message(), typed + " must not be told about a berth that does not exist");
        }
        assertEquals(4, rasaCalls.get(), "each of them went on to Rasa");
    }

    /** The help line tells the player to type "status" (decision N8). That word must work without
     * Rasa - born red: with Rasa down it was "I couldn't parse that". */
    @Test
    void theWordStatusIsAnsweredWithoutRasa() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 500, "{}");
        });
        NLPPipeline pipeline = pipeline(rasa);

        for (String typed : List.of("status", "Status?", "  status  ")) {
            PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class,
                    await(pipeline.processChatInput(typed, TABLE)), typed);
            assertEquals(ACLMessage.QUERY_REF, routed.msg().getPerformative(), typed);
        }
        assertEquals(0, rasaCalls.get());
    }
}
