package it.unige.portcommand.nlp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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

    private NLPPipeline pipeline(RasaBridge rasa, DCGParser dcgParser, Predicate<Frame> frameValidator) {
        executor = Executors.newFixedThreadPool(2);
        return new NLPPipeline(new PreprocessRegex(), rasa, new ConfidenceGate(), dcgParser, frameValidator, executor);
    }

    private NLPPipeline pipeline(RasaBridge rasa) {
        return pipeline(rasa, new NoOpDCGParser(), f -> true);
    }

    private static PipelineResult await(CompletableFuture<PipelineResult> future) {
        return future.join();
    }

    // --- DCG-first: a frame short-circuits Rasa entirely --------------------------------------

    @Test
    void dcgHitRoutesWithoutEverCallingRasa() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        RasaBridge rasa = rasaAgainst(ex -> {
            rasaCalls.incrementAndGet();
            respond(ex, 200, "{\"intent\": {\"name\": \"reject_deal\", \"confidence\": 0.99}}");
        });
        DCGParser dcg = (text, ctx) -> Optional.of(new Frame("propose", Map.of("price", 2000)));

        PipelineResult result = await(pipeline(rasa, dcg, f -> true).processChatInput(
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
        DCGParser dcg = (text, ctx) -> Optional.of(new Frame("propose", Map.of()));

        PipelineResult result = await(pipeline(rasa, dcg, f -> false).processChatInput("anything", DialogueCtx.NONE));

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

        String rawText = "auto accept if price > 2200 for tankers";
        PipelineResult result = await(pipeline(rasa).processChatInput(rawText, DialogueCtx.NONE));

        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, result).msg();
        assertEquals(ACLMessage.REQUEST, msg.getPerformative());
        assertTrue(msg.getContent().contains(rawText), "content must carry the raw text verbatim: " + msg.getContent());
        assertFalse(msg.getContent().contains("price_expression"), "entities must be ignored on the set_policy path");
    }

    @Test
    void structuralIntentTheDcgAlreadyMissedNeedsClarification() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"propose_offer\", \"confidence\": 0.99}, \"entities\": []}"));

        PipelineResult result = await(pipeline(rasa).processChatInput("2000 for 5 hours", DialogueCtx.NONE));

        assertInstanceOf(PipelineResult.NeedsClarification.class, result);
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

    @Test
    void cancelActionWithStandingOfferMapsToRejectProposal() throws IOException {
        RasaBridge rasa = rasaAgainst(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"cancel_action\", \"confidence\": 0.9}, \"entities\": []}"));
        Offer standingOffer = new Offer(2000, 5, "berth_3", "vessel_1", "harbour_master", 0L);
        DialogueCtx ctx = new DialogueCtx("conv-1", standingOffer, null);

        PipelineResult result = await(pipeline(rasa).processChatInput("never mind", ctx));

        assertEquals(ACLMessage.REJECT_PROPOSAL, assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative());
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
}
