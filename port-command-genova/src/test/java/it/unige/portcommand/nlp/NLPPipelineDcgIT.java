package it.unige.portcommand.nlp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import it.unige.portcommand.prolog.PrologEngine;
import jade.lang.acl.ACLMessage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The 16-M1 keystone proof: the DCG-first order is real, not decorative.</b>
 *
 * <p>PROJECT_DEFINITION.md §6.1 puts the DCG ahead of Rasa, which is only meaningful if a clean
 * negotiation move parses with <b>Rasa not running at all</b>. Every test here points the
 * {@link RasaBridge} at a dead port, so any dependency on Rasa shows up as a failure rather than
 * as a silently-degraded result.
 *
 * <p>Tagged {@code integration} because it drives the real embedded SWI-Prolog engine over JPL
 * (the whole point — a mocked parser would prove nothing about the grammar).
 */
@Tag("integration")
class NLPPipelineDcgIT {

    private static URI deadRasaUri;
    private static ExecutorService executor;
    private static NLPPipeline pipelineWithRasaDown;

    @BeforeAll
    static void setUp() throws IOException {
        PrologEngine.getInstance().init();
        deadRasaUri = URI.create("http://localhost:" + closedPort() + "/model/parse");
        executor = Executors.newFixedThreadPool(2);
        pipelineWithRasaDown = pipelineAgainst(deadRasaUri);
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Binds an ephemeral port and immediately releases it: nothing is listening there, so a
     * connect attempt is refused fast — a real Rasa outage, not a mock of one. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static NLPPipeline pipelineAgainst(URI rasaUri) {
        RasaBridge rasa = new RasaBridge(rasaUri, HttpClient.newHttpClient(), new ObjectMapper());
        return new NLPPipeline(
                new PreprocessRegex(),
                rasa,
                new ConfidenceGate(),
                new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer()),
                new OntologyValidator(),
                executor);
    }

    private static PipelineResult route(String text) {
        return pipelineWithRasaDown.processChatInput(text, DialogueCtx.NONE).join();
    }

    // --- the acceptance criterion --------------------------------------------------------------

    /** The session brief's literal M1 gate. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cleanNegotiationMoveRoutesWithRasaDown() {
        PipelineResult result = route("I will give you 2000 for 5 hours at berth 3");

        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, result,
                "a clean DCG move must route even though Rasa is unreachable").msg();
        assertEquals(ACLMessage.PROPOSE, msg.getPerformative());
        assertEquals("port_command_v1", msg.getOntology());
        assertEquals("json", msg.getLanguage());
        assertTrue(msg.getContent().contains("berth_3"), msg.getContent());
        assertTrue(msg.getContent().contains("2000"), msg.getContent());
    }

    /**
     * NON-VACUITY CONTROL. Without this, the test above could pass for the wrong reason (e.g. if
     * the URI were secretly reachable, or the Rasa branch were never attempted at all). An
     * utterance the DCG cannot parse MUST reach the dead Rasa and degrade — proving the outage is
     * real and that the DCG hit above genuinely short-circuited it.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aDcgMissActuallyReachesTheDeadRasaAndDegrades() {
        PipelineResult result = route("the weather is rather nice today isn't it");

        assertTrue(result instanceof PipelineResult.NeedsClarification
                        || result instanceof PipelineResult.Error,
                "a DCG miss must fall through to Rasa; with Rasa down that is a clarification or "
                        + "an error, never a Routed message. Got: " + result);
    }

    @ParameterizedTest(name = "[{0}] routes with Rasa down")
    @ValueSource(strings = {
            "I will give you 2000 for 5 hours at berth 3",
            "I'll pay €1,800 for berth 3",
            "How about 1800?",
            "Deal",
            "Too low",
            "What berths are free?",
    })
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void everyMoveTypeRoutesWithoutRasa(String utterance) {
        assertInstanceOf(PipelineResult.Routed.class, route(utterance),
                "the DCG covers all five move types; none may depend on Rasa being up");
    }

    @ParameterizedTest(name = "[{0}] -> {1}")
    @ValueSource(strings = {"Deal", "Too low", "What berths are free?"})
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void movesMapToTheirFipaPerformativeWithoutRasa(String utterance) {
        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, route(utterance)).msg();
        int expected = switch (utterance) {
            case "Deal" -> ACLMessage.ACCEPT_PROPOSAL;
            case "Too low" -> ACLMessage.REJECT_PROPOSAL;
            default -> ACLMessage.QUERY_REF;
        };
        assertEquals(expected, msg.getPerformative(),
                () -> "got " + ACLMessage.getPerformative(msg.getPerformative()));
    }

    // --- the validator seam is wired, not just constructed --------------------------------------

    /**
     * A frame naming a berth outside the ontology must become a clarification. Note the grammar's
     * own mention-guard already rejects "berth 9" at parse time, so this drives the validator
     * directly to prove the seam is connected — the two layers are belt and braces by design.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aFrameFailingOntologyValidationBecomesClarificationNotAnAcl() {
        NLPPipeline pipeline = new NLPPipeline(
                new PreprocessRegex(),
                new RasaBridge(deadRasaUri, HttpClient.newHttpClient(), new ObjectMapper()),
                new ConfidenceGate(),
                // A parser that hands the validator a frame naming a berth the ontology lacks.
                (text, ctx) -> java.util.Optional.of(
                        new Frame("commerce_sell", java.util.Map.of(Frame.MOVE, "propose", "berth", "berth_9"))),
                new OntologyValidator(),
                executor);

        PipelineResult result = pipeline.processChatInput("anything", DialogueCtx.NONE).join();

        assertInstanceOf(PipelineResult.NeedsClarification.class, result,
                "an out-of-ontology frame must never be dispatched as an ACL message");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aValidFrameStillRoutesThroughTheSameValidatorSeam() {
        // Control for the test above: same wiring, an in-ontology berth -> routed.
        NLPPipeline pipeline = new NLPPipeline(
                new PreprocessRegex(),
                new RasaBridge(deadRasaUri, HttpClient.newHttpClient(), new ObjectMapper()),
                new ConfidenceGate(),
                (text, ctx) -> java.util.Optional.of(
                        new Frame("commerce_sell", java.util.Map.of(Frame.MOVE, "propose", "berth", "berth_3"))),
                new OntologyValidator(),
                executor);

        assertInstanceOf(PipelineResult.Routed.class,
                pipeline.processChatInput("anything", DialogueCtx.NONE).join());
    }

    // --- the DCG is not merely FIRST, it is EXCLUSIVE on a hit -----------------------------------

    /**
     * With a live Rasa that would answer confidently and WRONGLY (reject_deal for an offer), a DCG
     * hit must still win and Rasa must not be called at all. Proves the short-circuit by counting
     * requests against a real server, rather than inferring it from an outage.
     *
     * <p><b>Carries its own positive control.</b> Asserting {@code rasaCalls == 0} is worthless on
     * its own — it would also hold if the handler were never wired up (wrong path, wrong port), and
     * the test would pass for entirely the wrong reason. So the same pipeline, against the same
     * server, then sends a DCG MISS and asserts the counter DOES climb. Only the pair is evidence.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aDcgHitNeverCallsRasaEvenWhenRasaIsUp() throws IOException {
        AtomicInteger rasaCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/model/parse", exchange -> {
            rasaCalls.incrementAndGet();
            byte[] body = "{\"intent\": {\"name\": \"reject_deal\", \"confidence\": 0.99}, \"entities\": []}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        try {
            URI liveRasa = URI.create("http://localhost:" + server.getAddress().getPort() + "/model/parse");
            NLPPipeline pipeline = pipelineAgainst(liveRasa);

            PipelineResult result = pipeline
                    .processChatInput("I will give you 2000 for 5 hours at berth 3", DialogueCtx.NONE)
                    .join();

            assertEquals(ACLMessage.PROPOSE,
                    assertInstanceOf(PipelineResult.Routed.class, result).msg().getPerformative(),
                    "the DCG's PROPOSE must win over Rasa's confident reject_deal");
            assertEquals(0, rasaCalls.get(), "a DCG hit must not call Rasa at all");

            // POSITIVE CONTROL: the counter must be able to move, against THIS server.
            pipeline.processChatInput("the weather is rather nice today isn't it", DialogueCtx.NONE).join();
            assertTrue(rasaCalls.get() > 0,
                    "a DCG miss must reach this very server — otherwise the 0 asserted above only "
                            + "proves the handler was never wired up, and this test is vacuous");
        } finally {
            server.stop(0);
        }
    }
}
