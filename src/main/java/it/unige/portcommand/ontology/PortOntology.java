package it.unige.portcommand.ontology;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import it.unige.portcommand.prolog.PrologEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton entry point to the generated ontology facts.
 *
 * <p>Guarantees that {@code port_ontology.pl} is present on the classpath (so
 * downstream code can rely on it) and resolves its on-disk path. The actual JPL
 * consult of the ontology is owned by {@link PrologEngine#init()} (which loads it
 * first of the six files); {@link #init(PrologEngine)} here is a thin presence
 * check, kept null-tolerant for callers that have no engine yet.
 */
public final class PortOntology {

    private static final Logger log = LoggerFactory.getLogger(PortOntology.class);
    private static final String ONTOLOGY_RESOURCE = "/prolog/port_ontology.pl";

    private static final class Holder {
        private static final PortOntology INSTANCE = new PortOntology();
    }

    PortOntology() {
        validateClasspath();
    }

    public static PortOntology getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Asserts the generated ontology is on the classpath. Downstream tasks (04+)
     * rely on this guarantee; it fails fast if the converter was never run.
     */
    private void validateClasspath() {
        if (PortOntology.class.getResource(ONTOLOGY_RESOURCE) == null) {
            throw new IllegalStateException(
                    ONTOLOGY_RESOURCE + " not on classpath — run ontology_to_assets.py to generate it");
        }
    }

    /**
     * Resolves the on-disk path of {@code port_ontology.pl}, forward-slashed so it
     * is safe as a Prolog {@code consult/1} atom on Windows. Task 04 replaces the
     * caller's stub with a real JPL consult; here it only verifies the file exists.
     *
     * @return absolute, forward-slash path to the ontology file
     */
    public String consultOntologyFile() {
        URL url = PortOntology.class.getResource(ONTOLOGY_RESOURCE);
        if (url == null) {
            throw new IllegalStateException(ONTOLOGY_RESOURCE + " not on classpath");
        }
        try {
            Path path = Paths.get(url.toURI());
            if (!Files.exists(path)) {
                throw new IllegalStateException("ontology file missing on disk: " + path);
            }
            return path.toString().replace('\\', '/');
        } catch (URISyntaxException e) {
            throw new IllegalStateException("bad URI for " + ONTOLOGY_RESOURCE + ": " + url, e);
        }
    }

    /**
     * Thin presence check: verifies the ontology resolves on disk and notes
     * whether the supplied engine has already consulted it. The consult itself is
     * {@link PrologEngine#init()}'s responsibility — this does not consult.
     *
     * @param engine the Prolog engine, or {@code null} if none is wired yet
     */
    public void init(PrologEngine engine) {
        String path = consultOntologyFile();
        if (engine != null && !engine.isInitialised()) {
            log.warn("PortOntology.init: engine not initialised — call PrologEngine.init() to consult {}",
                    path);
        } else {
            log.info("PortOntology present at {} (consult owned by PrologEngine.init)", path);
        }
    }
}
