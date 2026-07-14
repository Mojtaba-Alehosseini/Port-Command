package it.unige.portcommand.nlp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Hermetic: every test talks to an in-memory {@link HttpServer} mock on a loopback
 * ephemeral port, never the real Rasa process — the pre-commit hook's {@code gradlew
 * check} must pass whether or not {@code start_rasa.bat} is running.
 */
class RasaBridgeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RasaBridge start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/model/parse", handler);
        server.setExecutor(daemonExecutor());
        server.start();
        URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/model/parse");
        return new RasaBridge(uri, HttpClient.newHttpClient(), new ObjectMapper());
    }

    /** Daemon threads so a mock handler mid-sleep never keeps the test JVM alive. */
    private static ExecutorService daemonExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void parseSuccessMapsIntentEntitiesAndRanking() throws IOException {
        RasaBridge bridge = start(ex -> respond(ex, 200, """
                {"intent": {"name": "propose_offer", "confidence": 0.92},
                 "entities": [{"entity": "price_expression", "value": "2000", "start": 14, "end": 18}],
                 "intent_ranking": [{"name": "propose_offer", "confidence": 0.92}, \
                {"name": "counter_offer", "confidence": 0.05}]}"""));

        RasaParseResult result = bridge.parse("I'll give you 2000 for 5 hours at berth 3");

        assertEquals("propose_offer", result.intentName());
        assertEquals(0.92, result.confidence(), 1e-9);
        assertEquals("2000", result.entities().get("price_expression").value());
        assertEquals(2, result.intentRanking().size());
    }

    @Test
    void duplicateEntityNameLastWriteWins() throws IOException {
        RasaBridge bridge = start(ex -> respond(ex, 200, """
                {"intent": {"name": "propose_offer", "confidence": 0.9},
                 "entities": [{"entity": "berth_id", "value": "berth_1", "start": 0, "end": 1}, \
                {"entity": "berth_id", "value": "berth_2", "start": 5, "end": 6}]}"""));

        RasaParseResult result = bridge.parse("some text");

        assertEquals("berth_2", result.entities().get("berth_id").value());
        assertEquals(1, result.entities().size());
    }

    @Test
    void malformedJsonThrowsNlpServiceException() throws IOException {
        RasaBridge bridge = start(ex -> respond(ex, 200, "{not json"));

        assertThrows(NlpServiceException.class, () -> bridge.parse("hello"));
    }

    @Test
    void serverErrorRetriesOnceThenThrows() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RasaBridge bridge = start(ex -> {
            calls.incrementAndGet();
            respond(ex, 503, "{\"error\":\"loading\"}");
        });

        assertThrows(NlpServiceException.class, () -> bridge.parse("hello"));
        assertEquals(2, calls.get(), "must retry exactly once on 5xx");
    }

    @Test
    void clientErrorDoesNotRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RasaBridge bridge = start(ex -> {
            calls.incrementAndGet();
            respond(ex, 400, "{\"error\":\"bad request\"}");
        });

        assertThrows(NlpServiceException.class, () -> bridge.parse("hello"));
        assertEquals(1, calls.get(), "must not retry on 4xx");
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void requestTimeoutThrowsRasaTimeoutExceptionWithNoRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RasaBridge bridge = start(ex -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{}");
        });

        assertThrows(RasaTimeoutException.class, () -> bridge.parse("hello"));
        assertEquals(1, calls.get(), "must not retry on a genuine timeout");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void connectionFailureEventuallyThrowsNlpServiceException() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort(); // freed on close; nothing listens on it below
        }
        RasaBridge bridge = new RasaBridge(
                URI.create("http://localhost:" + deadPort + "/model/parse"),
                HttpClient.newHttpClient(), new ObjectMapper());

        assertThrows(NlpServiceException.class, () -> bridge.parse("hello"));
    }

    @Test
    void blankTextRejectedWithoutNetworkCall() {
        RasaBridge bridge = new RasaBridge(URI.create("http://localhost:1/model/parse"),
                HttpClient.newHttpClient(), new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> bridge.parse("   "));
    }

    @Test
    void tooLongTextRejectedWithoutNetworkCall() {
        RasaBridge bridge = new RasaBridge(URI.create("http://localhost:1/model/parse"),
                HttpClient.newHttpClient(), new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> bridge.parse("x".repeat(1001)));
    }

    @Test
    void parseAsyncCompletesOffTheCallingThread() throws IOException {
        RasaBridge bridge = start(ex -> respond(ex, 200,
                "{\"intent\": {\"name\": \"query_status\", \"confidence\": 0.99}}"));

        RasaParseResult result = bridge.parseAsync("status?").join();

        assertEquals("query_status", result.intentName());
    }
}
