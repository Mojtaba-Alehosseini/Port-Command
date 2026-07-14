package it.unige.portcommand.nlp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client to the Flask LLM sidecar (task 13, port 5006): {@code GET /health} and
 * {@code POST /explain}.
 *
 * <p><b>Timeout is config-driven (task 13b correction).</b> A fixed 3&nbsp;s timeout would
 * mean fp16 CPU inference (~9&nbsp;min/explanation, task 13's measurement) never completes,
 * making the sidecar dead code in-game. {@link #resolveTimeoutMs()} reads {@code llm
 * .timeout_ms} from {@code /data/defaults.json} on the classpath (task 15 owns that file —
 * this class only reads it, never writes it) if present, else defaults to {@value
 * #DEFAULT_TIMEOUT_MS}&nbsp;ms — sized from task 13b's ONNX-INT4 measurement (~17&nbsp;s/
 * explanation, max ~19&nbsp;s cold) with ~1.5&times; margin, covering the 120-token worst
 * case (~26&nbsp;s).
 *
 * <p>{@link #explain} is fully async (never blocks the calling thread) and never retries —
 * a retry after a ~20&nbsp;s generation would double an already-long wait. On timeout the
 * returned future completes exceptionally with {@link LLMTimeoutException}; {@code
 * LLMBridge} has no access to the recommendation trace, so it cannot build the template-text
 * fallback itself — see {@link LLMTimeoutException}'s Javadoc for who does.
 */
public final class LLMBridge {

    private static final Logger log = LoggerFactory.getLogger(LLMBridge.class);

    private static final int DEFAULT_PORT = 5006;
    static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    private static final long HEALTH_CACHE_MS = 5_000L;
    private static final String DEFAULTS_JSON_RESOURCE = "/data/defaults.json";

    private final URI explainUri;
    private final URI healthUri;
    private final HttpClient client;
    private final ObjectMapper json;
    private final Duration timeout;

    private volatile boolean cachedReady;
    private volatile long cachedReadyAtMillis = -HEALTH_CACHE_MS;

    public LLMBridge(URI explainUri, URI healthUri, HttpClient client, ObjectMapper json) {
        this(explainUri, healthUri, client, json, Duration.ofMillis(resolveTimeoutMs()));
    }

    /** Test/ops seam: pin an explicit timeout instead of the resolved config-driven one. */
    public LLMBridge(URI explainUri, URI healthUri, HttpClient client, ObjectMapper json, Duration timeout) {
        this.explainUri = Objects.requireNonNull(explainUri, "explainUri");
        this.healthUri = Objects.requireNonNull(healthUri, "healthUri");
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** Reads {@code -Dllm.port} (default 5006): {@code http://localhost:<port>/explain}. */
    public static URI resolveExplainUri() {
        return URI.create("http://localhost:" + resolvePort() + "/explain");
    }

    /** Reads {@code -Dllm.port} (default 5006): {@code http://localhost:<port>/health}. */
    public static URI resolveHealthUri() {
        return URI.create("http://localhost:" + resolvePort() + "/health");
    }

    private static int resolvePort() {
        return Integer.getInteger("llm.port", DEFAULT_PORT);
    }

    /**
     * {@code -Dllm.timeout_ms} system property, else {@code llm.timeout_ms} from {@code
     * /data/defaults.json} on the classpath, else {@value #DEFAULT_TIMEOUT_MS}. Never throws —
     * an absent/malformed config file silently falls back to the code default (task 15 owns
     * creating the file; its absence today is expected).
     */
    static long resolveTimeoutMs() {
        Long override = Long.getLong("llm.timeout_ms");
        if (override != null) {
            return override;
        }
        Long fromFile = readTimeoutMsFromDefaultsJson();
        return fromFile != null ? fromFile : DEFAULT_TIMEOUT_MS;
    }

    private static Long readTimeoutMsFromDefaultsJson() {
        try (InputStream in = LLMBridge.class.getResourceAsStream(DEFAULTS_JSON_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return timeoutMsFromJson(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            log.debug("could not read {}: {}", DEFAULTS_JSON_RESOURCE, e.getMessage());
            return null;
        }
    }

    /** Pure extraction of the nested {@code {"llm": {"timeout_ms": N}}} shape; unit-testable
     * without touching the classpath. {@code null} when the key is missing or not numeric. */
    static Long timeoutMsFromJson(JsonNode root) {
        JsonNode timeoutMs = root.path("llm").path("timeout_ms");
        return timeoutMs.canConvertToLong() ? timeoutMs.asLong() : null;
    }

    /**
     * {@code true} once the sidecar has finished loading its model. Result is cached for
     * 5&nbsp;s (wall-clock) so a UI "thinking..." poll loop does not hammer the sidecar;
     * blocks the calling thread for at most 3&nbsp;s on a cache miss.
     */
    public boolean isReady() {
        long now = System.currentTimeMillis();
        if (now - cachedReadyAtMillis < HEALTH_CACHE_MS) {
            return cachedReady;
        }
        boolean ready = probeHealth();
        cachedReady = ready;
        cachedReadyAtMillis = System.currentTimeMillis();
        return ready;
    }

    private boolean probeHealth() {
        HttpRequest request = HttpRequest.newBuilder(healthUri).GET().timeout(HEALTH_TIMEOUT).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Async explanation call — never blocks the calling thread (safe to call from a JADE
     * agent thread or the Swing EDT). On timeout the returned future completes exceptionally
     * with {@link LLMTimeoutException}; on any other HTTP/parse failure, with {@link
     * NlpServiceException}.
     */
    public CompletableFuture<LLMResponse> explain(LLMRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(explainUri)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(toWire(request))))
                    .build();
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(new NlpServiceException("failed to serialize llm request", e));
        }

        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::toResponse)
                .exceptionallyCompose(ex -> CompletableFuture.failedFuture(mapFailure(ex)));
    }

    private RuntimeException mapFailure(Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        if (cause instanceof HttpTimeoutException) {
            log.debug("llm explain timed out after {}", timeout);
            return new LLMTimeoutException("llm explain timed out after " + timeout, cause);
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new NlpServiceException("llm explain failed: " + cause.getMessage(), cause);
    }

    private LLMResponse toResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status != 200) {
            throw new NlpServiceException("llm sidecar returned HTTP " + status + ": " + response.body());
        }
        try {
            RawExplainResponse raw = json.readValue(response.body(), RawExplainResponse.class);
            return new LLMResponse(raw.text, Boolean.TRUE.equals(raw.validated));
        } catch (JsonProcessingException e) {
            throw new NlpServiceException("malformed llm response: " + e.getMessage(), e);
        }
    }

    private static RawExplainRequest toWire(LLMRequest request) {
        RawExplainRequest raw = new RawExplainRequest();
        raw.prompt = request.prompt();
        raw.system = request.system();
        raw.requiredNumbers = request.requiredNumbers();
        raw.requiredEntities = request.requiredEntities();
        raw.validate = request.validate();
        return raw;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawExplainRequest {
        @JsonProperty("prompt")
        String prompt;
        @JsonProperty("system")
        String system;
        @JsonProperty("required_numbers")
        List<String> requiredNumbers;
        @JsonProperty("required_entities")
        List<String> requiredEntities;
        @JsonProperty("validate")
        boolean validate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawExplainResponse {
        @JsonProperty("text")
        String text;
        @JsonProperty("validated")
        Boolean validated;
        @JsonProperty("gen_seconds")
        double genSeconds;
    }
}
