package it.unige.portcommand.ontology;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton entry point to the generated ontology facts.
 *
 * <p>For task 02 this only guarantees that {@code port_ontology.pl} is present on
 * the classpath (so downstream tasks can rely on it) and can resolve its on-disk
 * path. The real JPL consult is wired in task 04, where {@link #init(Object)}'s
 * parameter tightens from {@code Object} to the concrete {@code PrologEngine}.
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
     * Task-02 stub. The real JPL consult arrives in task 04, where {@code engine}
     * tightens from {@code Object} to {@code PrologEngine}.
     *
     * @param engine the future Prolog engine (ignored in task 02)
     */
    public void init(Object engine) {
        // TODO(task-04): replace with engine.consult(consultOntologyFile()).
        log.info("PortOntology stub init: ontology present at {} (JPL consult deferred to task 04)",
                consultOntologyFile());
    }
}
