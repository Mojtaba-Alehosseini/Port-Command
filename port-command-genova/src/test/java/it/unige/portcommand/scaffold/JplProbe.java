package it.unige.portcommand.scaffold;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;

import org.jpl7.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Isolated JPL native-binding probe. Proves SWI-Prolog 10 + JPL 7 load and
 * answer a query, independently of JADE.
 *
 * <p>JPL initialises the embedded SWI-Prolog engine lazily on the first
 * {@link Query} — there is intentionally no explicit init call.
 */
public final class JplProbe {

    private static final Logger log = LoggerFactory.getLogger(JplProbe.class);

    private JplProbe() {
    }

    /**
     * Consults the smoke Prolog file and evaluates {@code member(2, [1,2,3])}.
     *
     * @return {@code true} if both queries succeed
     * @throws IllegalStateException if a query fails or the engine cannot bind
     */
    public static boolean check() {
        String smokePath = resolveSmokePl();

        boolean consultOk = Query.hasSolution("consult('" + smokePath + "')");
        log.info("JPL consult('{}') -> {}", smokePath, consultOk);
        if (!consultOk) {
            throw new IllegalStateException("Prolog consult failed for " + smokePath);
        }

        boolean memberOk = Query.hasSolution("member(2, [1,2,3])");
        log.info("JPL member(2, [1,2,3]) -> {}", memberOk);
        if (!memberOk) {
            throw new IllegalStateException("Prolog query member(2,[1,2,3]) failed");
        }

        return true;
    }

    /**
     * Resolves {@code /prolog/smoke.pl} from the classpath to an absolute,
     * forward-slash path suitable for a Prolog {@code consult/1} atom. Backslashes
     * would otherwise break the goal on Windows.
     */
    private static String resolveSmokePl() {
        URL url = JplProbe.class.getResource("/prolog/smoke.pl");
        if (url == null) {
            throw new IllegalStateException("/prolog/smoke.pl not found on the classpath");
        }
        try {
            URI uri = url.toURI();
            return Paths.get(uri).toString().replace('\\', '/');
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Bad URI for smoke.pl: " + url, e);
        }
    }
}
