package it.unige.portcommand.negotiation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads {@code data/vessel_templates.json} from the classpath once (the data is
 * static config, not live shared state, so a cached read is fine — no DI needed).
 * Keyed by vessel type ({@code tanker}, {@code container_vessel}, …).
 */
public final class VesselTemplates {

    private static final Map<String, VesselTemplate> TEMPLATES = load();

    private VesselTemplates() {
    }

    public static VesselTemplate forType(String vesselType) {
        VesselTemplate template = TEMPLATES.get(vesselType);
        if (template == null) {
            throw new IllegalArgumentException("no vessel template for type: " + vesselType);
        }
        return template;
    }

    public static Set<String> types() {
        return TEMPLATES.keySet();
    }

    private static Map<String, VesselTemplate> load() {
        try (InputStream in = VesselTemplates.class.getResourceAsStream("/data/vessel_templates.json")) {
            if (in == null) {
                throw new IllegalStateException("vessel_templates.json not found on classpath (/data/)");
            }
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, VesselTemplate>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("failed to load vessel_templates.json", e);
        }
    }
}
