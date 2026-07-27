package it.unige.portcommand.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loader for the task-25 NLP regression goldens ({@code src/test/resources/nlp/golden/*.jsonl}).
 *
 * <p>One JSON object per line, so a golden diff in review is one line per changed case — the
 * whole point of the {@code .jsonl} choice (planning/25 "Hard constraints"). Blank lines and
 * {@code #} comment lines are skipped so a corpus can carry a header.
 *
 * <p><b>Numeric normalisation.</b> {@link #toJava} maps every integral JSON number to
 * {@link Long}, because that is what {@code Frame.fromProlog} produces for a Prolog integer
 * ({@code Term.longValue()}). Without it every golden comparison would fail on
 * {@code Integer(2000) != Long(2000)} — a difference with no semantic content.
 */
public final class Goldens {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Goldens() {
    }

    /** Reads a {@code .jsonl} golden file off the test classpath, one parsed object per line. */
    public static List<JsonNode> load(String classpathResource) {
        List<JsonNode> out = new ArrayList<>();
        try (InputStream in = Goldens.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException(classpathResource + " not on the test classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    try {
                        out.add(MAPPER.readTree(trimmed));
                    } catch (IOException e) {
                        throw new IllegalStateException(classpathResource + ":" + lineNo + " is not valid JSON", e);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    /**
     * Decodes a JSON value to the Java shape {@code Frame.elements()} uses: integral number
     * &rarr; {@link Long}, object &rarr; insertion-ordered {@link LinkedHashMap}, array &rarr;
     * {@link List}, text &rarr; {@link String}.
     */
    public static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toJava(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(toJava(child)));
            return List.copyOf(list);
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    /** Same as {@link #toJava} but typed for an object node's element map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toElementMap(JsonNode node) {
        Object decoded = toJava(node);
        return decoded == null ? Map.of() : (Map<String, Object>) decoded;
    }

    /**
     * Writes a report file under {@code build/reports/<dir>/}. Used by the regression ITs to emit
     * the per-block / per-intent tables the two course reports cite; the build directory is the
     * right home because these are generated evidence, not committed artefacts.
     *
     * @return the path written, for the log line the test prints
     */
    public static Path writeReport(String dir, String fileName, String content) {
        try {
            Path out = Path.of("build", "reports", dir);
            Files.createDirectories(out);
            Path file = out.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
