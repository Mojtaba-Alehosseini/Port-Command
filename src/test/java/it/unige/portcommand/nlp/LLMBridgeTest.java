package it.unige.portcommand.nlp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic: every test talks to an in-memory {@link HttpServer} mock, never the real Flask
 * sidecar — the pre-commit hook's {@code gradlew check} must pass whether or not {@code
 * start_llm.bat} is running.
 */
class LLMBridgeTest {

    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("llm.timeout_ms");
        System.clearProperty("llm.port");
    }

    private LLMBridge start(String path, HttpHandler handler, Duration timeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, handler);
        server.setExecutor(daemonExecutor());
        server.start();
        int port = server.getAddress().getPort();
        URI explainUri = URI.create("http://localhost:" + port + "/explain");
        URI healthUri = URI.create("http://localhost:" + port + "/health");
        return new LLMBridge(explainUri, healthUri, HttpClient.newHttpClient(), new ObjectMapper(), timeout);
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

    // --- /explain --------------------------------------------------------------------------

    @Test
    void explainSuccessReturnsTextAndValidated() throws Exception {
        LLMBridge bridge = start("/explain", ex -> respond(ex, 200,
                "{\"text\": \"Recommended accept.\", \"validated\": true, \"gen_seconds\": 16.3}"),
                Duration.ofSeconds(5));

        LLMResponse response = bridge.explain(LLMRequest.of("explain this decision")).get(5, TimeUnit.SECONDS);

        assertEquals("Recommended accept.", response.text());
        assertTrue(response.validated());
    }

    @Test
    void explainValidatedNullMapsToFalse() throws Exception {
        LLMBridge bridge = start("/explain", ex -> respond(ex, 200,
                "{\"text\": \"Recommended accept.\", \"validated\": null, \"gen_seconds\": 1.0}"),
                Duration.ofSeconds(5));

        LLMResponse response = bridge.explain(LLMRequest.of("explain this decision")).get(5, TimeUnit.SECONDS);

        assertFalse(response.validated());
    }

    @Test
    void explainMalformedJsonCompletesExceptionallyWithNlpServiceException() throws IOException {
        LLMBridge bridge = start("/explain", ex -> respond(ex, 200, "{not json"), Duration.ofSeconds(5));

        var future = bridge.explain(LLMRequest.of("explain this"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof NlpServiceException, "expected NlpServiceException, got " + e.getCause());
    }

    @Test
    void explainNon200StatusCompletesExceptionallyWithNlpServiceException() throws Exception {
        LLMBridge bridge = start("/explain", ex -> respond(ex, 503, "{\"error\":\"model not ready\"}"),
                Duration.ofSeconds(5));

        var future = bridge.explain(LLMRequest.of("explain this"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof NlpServiceException, "expected NlpServiceException, got " + e.getCause());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void explainTimeoutCompletesExceptionallyWithLlmTimeoutException() throws Exception {
        LLMBridge bridge = start("/explain", ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"text\": \"too slow\", \"validated\": null}");
        }, Duration.ofMillis(200));

        var future = bridge.explain(LLMRequest.of("explain this"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof LLMTimeoutException, "expected LLMTimeoutException, got " + e.getCause());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void explainDoesNotBlockTheCallingThread() throws Exception {
        LLMBridge bridge = start("/explain", ex -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"text\": \"ok\", \"validated\": null}");
        }, Duration.ofSeconds(5));

        CompletableFuture<LLMResponse> future = bridge.explain(LLMRequest.of("explain this"));

        assertFalse(future.isDone(), "explain() must return before the (1s-delayed) sidecar responds");
        future.get(4, TimeUnit.SECONDS); // drain it so no background work outlives this test
    }

    // --- /health -----------------------------------------------------------------------------

    @Test
    void isReadyFalseWhenSidecarReports503() throws IOException {
        LLMBridge bridge = start("/health", ex -> respond(ex, 503, "{\"ready\": false}"), Duration.ofSeconds(5));

        assertFalse(bridge.isReady());
    }

    @Test
    void isReadyTrueWhenSidecarReports200() throws IOException {
        LLMBridge bridge = start("/health", ex -> respond(ex, 200, "{\"ready\": true}"), Duration.ofSeconds(5));

        assertTrue(bridge.isReady());
    }

    @Test
    void isReadyCachesForFiveSecondsSoPollingDoesNotHammerTheSidecar() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        LLMBridge bridge = start("/health", ex -> {
            calls.incrementAndGet();
            try {
                respond(ex, 200, "{\"ready\": true}");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, Duration.ofSeconds(5));

        assertTrue(bridge.isReady());
        assertTrue(bridge.isReady());
        assertTrue(bridge.isReady());

        assertEquals(1, calls.get(), "repeated isReady() within the 5s cache window must hit the network once");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void isReadyReturnsFalseRatherThanBlockingWhenSidecarIsUnreachable() {
        LLMBridge bridge = new LLMBridge(
                URI.create("http://localhost:1/explain"), URI.create("http://localhost:1/health"),
                HttpClient.newHttpClient(), new ObjectMapper(), Duration.ofSeconds(5));

        assertFalse(bridge.isReady());
    }

    // --- timeout config resolution -----------------------------------------------------------

    @Test
    void resolveTimeoutMsDefaultsTo30000WhenNoConfigFilePresent() {
        // /data/defaults.json is task 15's file; it does not exist yet in this repo state.
        assertEquals(30_000L, LLMBridge.resolveTimeoutMs());
        assertEquals(LLMBridge.DEFAULT_TIMEOUT_MS, LLMBridge.resolveTimeoutMs());
    }

    @Test
    void resolveTimeoutMsHonoursSystemPropertyOverride() {
        System.setProperty("llm.timeout_ms", "45000");
        assertEquals(45_000L, LLMBridge.resolveTimeoutMs());
    }

    @Test
    void timeoutMsFromJsonExtractsNestedKey() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"llm\": {\"timeout_ms\": 25000}}");
        assertEquals(25_000L, LLMBridge.timeoutMsFromJson(node));
    }

    @Test
    void timeoutMsFromJsonReturnsNullWhenKeyMissing() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"llm\": {\"model\": \"phi-4-mini\"}}");
        assertNull(LLMBridge.timeoutMsFromJson(node));
    }

    @Test
    void timeoutMsFromJsonReturnsNullWhenNotNumeric() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"llm\": {\"timeout_ms\": \"soon\"}}");
        assertNull(LLMBridge.timeoutMsFromJson(node));
    }

    // --- URI resolution ------------------------------------------------------------------------

    @Test
    void resolveUrisUseDefaultPortWhenNoSystemProperty() {
        assertEquals(URI.create("http://localhost:5006/explain"), LLMBridge.resolveExplainUri());
        assertEquals(URI.create("http://localhost:5006/health"), LLMBridge.resolveHealthUri());
    }

    @Test
    void resolveUrisHonourPortSystemProperty() {
        System.setProperty("llm.port", "7777");
        assertEquals(URI.create("http://localhost:7777/explain"), LLMBridge.resolveExplainUri());
        assertEquals(URI.create("http://localhost:7777/health"), LLMBridge.resolveHealthUri());
    }
}
