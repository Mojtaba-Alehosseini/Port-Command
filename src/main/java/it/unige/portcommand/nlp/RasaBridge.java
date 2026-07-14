package it.unige.portcommand.nlp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client to the single Rasa NLU pipeline's {@code /model/parse} endpoint (task 12,
 * port 5005). Constructed once in {@code JadeBootstrap}; there is no second Rasa pipeline —
 * the policy pipeline was cut (PROJECT_DEFINITION.md §9 item 6), {@code set_policy} is
 * handled in-process by {@code PolicyParser} (task 10).
 *
 * <p><b>Timeouts.</b> Connect 1&nbsp;s, request 3&nbsp;s (planning/14 hard constraints).
 * <b>Retries.</b> One retry on a connection failure or a 5xx response; no retry on 4xx, and
 * no retry on a genuine request timeout (retrying an already-slow call would just double the
 * worst-case latency the caller is budgeting for).
 *
 * <p>{@link #parse} blocks the calling thread for up to ~4&nbsp;s (worst case, with the one
 * retry). Never call it from a JADE agent thread or the Swing EDT — {@link NLPPipeline} runs
 * it on its own bounded {@code ExecutorService}; a caller that only needs a single one-off
 * call off-thread can use {@link #parseAsync}.
 */
public final class RasaBridge {

    private static final Logger log = LoggerFactory.getLogger(RasaBridge.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final int DEFAULT_PORT = 5005;
    // Security-audit boundary check: a chat line has no legitimate reason to be this long;
    // caps the payload sent upstream and logged.
    private static final int MAX_TEXT_LENGTH = 1000;

    private final URI parseUri;
    private final HttpClient client;
    private final ObjectMapper json;

    public RasaBridge(URI parseUri, HttpClient client, ObjectMapper json) {
        this.parseUri = Objects.requireNonNull(parseUri, "parseUri");
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** {@code connectTimeout} is set on the shared {@link HttpClient}, not per-request. */
    public static HttpClient.Builder newClientBuilder() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
    }

    /** Reads {@code -Drasa.port} (default 5005): {@code http://localhost:<port>/model/parse}. */
    public static URI resolveParseUri() {
        int port = Integer.getInteger("rasa.port", DEFAULT_PORT);
        return URI.create("http://localhost:" + port + "/model/parse");
    }

    /**
     * Synchronous parse of one chat line.
     *
     * @throws IllegalArgumentException {@code text} is null, blank, or exceeds {@value #MAX_TEXT_LENGTH} chars
     * @throws RasaTimeoutException     the request exceeded its 3&nbsp;s timeout (no retry)
     * @throws NlpServiceException      any other HTTP/parse failure (4xx, 5xx after one retry,
     *                                  connection failure after one retry, malformed JSON)
     */
    public RasaParseResult parse(String text) {
        validate(text);
        return attempt(text, true);
    }

    public CompletableFuture<RasaParseResult> parseAsync(String text) {
        return CompletableFuture.supplyAsync(() -> parse(text));
    }

    private static void validate(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds " + MAX_TEXT_LENGTH + " chars");
        }
    }

    private RasaParseResult attempt(String text, boolean allowRetry) {
        HttpRequest request = HttpRequest.newBuilder(parseUri)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(writeBody(text)))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log.debug("rasa parse timed out ({} chars)", text.length());
            throw new RasaTimeoutException("rasa timeout", e);
        } catch (ConnectException e) {
            if (allowRetry) {
                log.debug("rasa connection failure, retrying once");
                return attempt(text, false);
            }
            throw new NlpServiceException("rasa connection failure: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new NlpServiceException("rasa I/O failure: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NlpServiceException("interrupted while calling rasa", e);
        }

        int status = response.statusCode();
        if (status >= 500 && allowRetry) {
            log.debug("rasa returned HTTP {}, retrying once", status);
            return attempt(text, false);
        }
        if (status >= 400) {
            throw new NlpServiceException("rasa returned HTTP " + status + ": " + response.body());
        }
        return toResult(parseBody(response.body()));
    }

    private String writeBody(String text) {
        try {
            return json.writeValueAsString(Map.of("text", text));
        } catch (JsonProcessingException e) {
            throw new NlpServiceException("failed to serialize rasa request", e);
        }
    }

    private RawParseResponse parseBody(String body) {
        try {
            return json.readValue(body, RawParseResponse.class);
        } catch (JsonProcessingException e) {
            throw new NlpServiceException("malformed rasa response: " + e.getMessage(), e);
        }
    }

    private static RasaParseResult toResult(RawParseResponse raw) {
        String intentName = raw.intent != null ? raw.intent.name : null;
        double confidence = raw.intent != null ? raw.intent.confidence : 0.0;

        Map<String, EntityHit> entities = new LinkedHashMap<>();
        if (raw.entities != null) {
            for (RawEntity e : raw.entities) {
                entities.put(e.entity, new EntityHit(e.entity, e.value, e.start, e.end));
            }
        }

        List<RankedIntent> ranking = raw.intentRanking == null
                ? List.of()
                : raw.intentRanking.stream().map(r -> new RankedIntent(r.name, r.confidence)).toList();

        return new RasaParseResult(intentName, confidence, entities, ranking);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawParseResponse {
        @JsonProperty("intent")
        RawIntent intent;
        @JsonProperty("entities")
        List<RawEntity> entities;
        @JsonProperty("intent_ranking")
        List<RawIntent> intentRanking;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawIntent {
        @JsonProperty("name")
        String name;
        @JsonProperty("confidence")
        double confidence;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawEntity {
        @JsonProperty("entity")
        String entity;
        @JsonProperty("value")
        String value;
        @JsonProperty("start")
        int start;
        @JsonProperty("end")
        int end;
    }
}
