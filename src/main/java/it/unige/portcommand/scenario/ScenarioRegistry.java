package it.unige.portcommand.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 3 packaged scenarios (PROJECT_DEFINITION §7.2 — an explicitly fixed set, so the
 * keys are enumerated rather than the classpath directory scanned, which is unreliable
 * inside a jar). All are loaded and validated eagerly at construction: a malformed
 * packaged scenario is a build error ({@code ScenarioLoaderTest}), never a runtime
 * surprise in the NewGameDialog.
 */
public final class ScenarioRegistry {

    /** Locked v1 keys, in NewGameDialog display order. No freeform, no 4th or 5th. */
    public static final List<String> KEYS = List.of("tutorial", "busy_day", "storm");

    private final Map<String, Scenario> byKey = new LinkedHashMap<>();

    public ScenarioRegistry() {
        ScenarioLoader loader = new ScenarioLoader();
        for (String key : KEYS) {
            String resource = "/data/scenarios/" + key + ".json";
            try (InputStream in = ScenarioRegistry.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(resource + " not found on classpath");
                }
                Scenario scenario = loader.load(in, resource);
                if (!key.equals(scenario.key())) {
                    throw new IllegalStateException(resource + " declares key '" + scenario.key()
                            + "' — must equal its file stem '" + key + "'");
                }
                byKey.put(key, scenario);
            } catch (IOException | ScenarioValidationException e) {
                throw new IllegalStateException("packaged scenario " + resource + " is invalid", e);
            }
        }
    }

    public Optional<Scenario> findByKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** All 3, in display order. */
    public List<Scenario> all() {
        return List.copyOf(byKey.values());
    }
}
