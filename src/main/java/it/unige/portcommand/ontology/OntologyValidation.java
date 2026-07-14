package it.unige.portcommand.ontology;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only validation surface over the generated ontology, used by the NLP
 * pipeline (task 12) and as a drift cross-check for {@link VesselSpec}.
 *
 * <p>Populated once at class-load time by scanning {@code port_ontology.pl} on the
 * classpath. Only {@code vessel_type/1} atoms count as vessel types (never the
 * generic {@code class/1}, so {@code cargo}/{@code berth}/{@code sunny} are never
 * accepted), and only {@code instance_of(berth_N, _)} atoms count as berths.
 */
public final class OntologyValidation {

    private static final String ONTOLOGY_RESOURCE = "/prolog/port_ontology.pl";
    private static final Pattern VESSEL_TYPE =
            Pattern.compile("^\\s*vessel_type\\(\\s*([a-z][a-z0-9_]*)\\s*\\)\\s*\\.");
    private static final Pattern BERTH_INSTANCE =
            Pattern.compile("^\\s*instance_of\\(\\s*(berth_[1-4])\\s*,");

    private static final Set<String> VESSEL_TYPES;
    private static final Set<String> BERTHS;

    static {
        Set<String> vesselTypes = new LinkedHashSet<>();
        Set<String> berths = new LinkedHashSet<>();
        try (InputStream in = OntologyValidation.class.getResourceAsStream(ONTOLOGY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        ONTOLOGY_RESOURCE + " not on classpath — run ontology_to_assets.py");
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher vt = VESSEL_TYPE.matcher(line);
                    if (vt.find()) {
                        vesselTypes.add(vt.group(1));
                        continue;
                    }
                    Matcher b = BERTH_INSTANCE.matcher(line);
                    if (b.find()) {
                        berths.add(b.group(1));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + ONTOLOGY_RESOURCE, e);
        }
        VESSEL_TYPES = Collections.unmodifiableSet(vesselTypes);
        BERTHS = Collections.unmodifiableSet(berths);
    }

    private OntologyValidation() {
    }

    public static boolean isKnownVesselType(String vesselType) {
        return VESSEL_TYPES.contains(vesselType);
    }

    public static boolean isKnownBerth(String berthId) {
        return BERTHS.contains(berthId);
    }

    /** Unmodifiable set of the {@code vessel_type/1} atoms in the ontology. */
    public static Set<String> getVesselTypes() {
        return VESSEL_TYPES;
    }

    /** Unmodifiable set of the {@code berth_N} ids in the ontology. */
    public static Set<String> getBerths() {
        return BERTHS;
    }
}
