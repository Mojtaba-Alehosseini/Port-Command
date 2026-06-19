package it.unige.portcommand.prolog;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jpl7.Query;
import org.jpl7.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide gateway to the embedded SWI-Prolog engine via JPL.
 *
 * <p><b>Thread-safety.</b> JPL is not thread-safe; every goal is serialised
 * through a single {@link ReentrantLock}. The lock is reentrant so a caller may
 * bracket several goals as one critical section via {@link #inLock(Supplier)}
 * (used by {@link PrologQueries} to make assert→query→retract atomic) without
 * self-deadlock.
 *
 * <p><b>Module context.</b> All goals run in JPL's default {@code user} module,
 * which is where {@code consult/1} loads the ontology and rule files (none carry
 * a {@code :- module} directive). Transient facts asserted by {@link PrologQueries}
 * are explicitly {@code user:}-qualified so the rules — also in {@code user} —
 * resolve them. A module mismatch here makes rules silently see no facts.
 *
 * <p><b>Caching.</b> Only <i>pure</i> goals — those that read the static ontology
 * fact base and assert no transient state — may use {@link #hasSolutionCached}/
 * {@link #oneSolutionCached}. Goals that assert ephemeral per-vessel facts, or
 * read the dynamic {@code blacklisted_combo/2} / {@code vessel_priority_override/2},
 * must use the uncached methods, since a cached answer would reflect a stale
 * fact base.
 */
public final class PrologEngine {

    private static final Logger log = LoggerFactory.getLogger(PrologEngine.class);

    /**
     * The exact six files consulted at startup, in load order: the ontology
     * facts FIRST, then the five rule modules alphabetically. Resolved by name
     * (not a directory scan): the test classpath overlays a second {@code /prolog}
     * dir holding {@code smoke.pl} + the PLUnit suites, so a wildcard scan is
     * ambiguous. A fixed manifest also pins the 5-module / 30-rule invariant.
     */
    private static final List<String> RESOURCE_MANIFEST = List.of(
            "/prolog/port_ontology.pl",
            "/prolog/rules_compatibility.pl",
            "/prolog/rules_customs.pl",
            "/prolog/rules_escort.pl",
            "/prolog/rules_priority.pl",
            "/prolog/rules_weather.pl");

    private static final class Holder {
        private static final PrologEngine INSTANCE = new PrologEngine();
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean initialised;

    // Pure-query caches (goal string -> result). Never holds a goal that touches
    // transient/dynamic facts. Concurrent maps; a benign double-compute on a race
    // is harmless because pure goals are deterministic.
    private final Map<String, Boolean> hasSolutionCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Map<String, Term>>> oneSolutionCache = new ConcurrentHashMap<>();

    // Counts goals actually dispatched to JPL (cache hits excluded). Lets tests
    // observe that a pure query's second call is served from cache.
    private final AtomicLong queryCount = new AtomicLong();

    private PrologEngine() {
    }

    public static PrologEngine getInstance() {
        return Holder.INSTANCE;
    }

    /** The six resources consulted by {@link #init()}, in load order. */
    public static List<String> resourceManifest() {
        return RESOURCE_MANIFEST;
    }

    /**
     * Idempotently consults the ontology and the five rule modules. The first
     * call loads all six files (ontology first); later calls are no-ops.
     *
     * @throws PrologException if any consult fails
     */
    public void init() {
        lock.lock();
        try {
            if (initialised) {
                return;
            }
            for (String resource : RESOURCE_MANIFEST) {
                String path = resolveResource(resource);
                boolean ok = Query.hasSolution("consult('" + path + "')");
                if (!ok) {
                    throw new PrologException("consult failed for " + resource + " (" + path + ")");
                }
                log.debug("consulted {}", resource);
            }
            initialised = true;
            log.info("Prolog engine initialised: {} rule modules, {} ontology facts, 30 rules (R1–R30)",
                    RESOURCE_MANIFEST.size() - 1, ontologyFactCount());
        } finally {
            lock.unlock();
        }
    }

    public boolean isInitialised() {
        return initialised;
    }

    /** Goals dispatched to JPL so far (excludes cache hits); for cache assertions. */
    public long queryCount() {
        return queryCount.get();
    }

    // --- Uncached goal execution -------------------------------------------

    /** Runs {@code goal} for a yes/no answer. */
    public boolean hasSolution(String goal) {
        lock.lock();
        try {
            queryCount.incrementAndGet();
            log.trace("hasSolution: {}", goal);
            return Query.hasSolution(goal);
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code goal} for its first solution (variable bindings), if any. */
    public Optional<Map<String, Term>> oneSolution(String goal) {
        lock.lock();
        try {
            queryCount.incrementAndGet();
            log.trace("oneSolution: {}", goal);
            return Optional.ofNullable(Query.oneSolution(goal));
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code goal} for all solutions. */
    public List<Map<String, Term>> allSolutions(String goal) {
        lock.lock();
        try {
            queryCount.incrementAndGet();
            log.trace("allSolutions: {}", goal);
            return List.of(Query.allSolutions(goal));
        } finally {
            lock.unlock();
        }
    }

    // --- Cached goal execution (pure goals ONLY) ---------------------------

    /**
     * Cached yes/no for a PURE goal. The caller asserts the goal touches only the
     * static fact base; the engine does not verify this.
     */
    public boolean hasSolutionCached(String goal) {
        Boolean cached = hasSolutionCache.get(goal);
        if (cached != null) {
            log.trace("hasSolution (cache hit): {}", goal);
            return cached;
        }
        boolean result = hasSolution(goal);
        hasSolutionCache.put(goal, result);
        return result;
    }

    /**
     * Cached first-solution for a PURE goal. The caller asserts purity.
     */
    public Optional<Map<String, Term>> oneSolutionCached(String goal) {
        Optional<Map<String, Term>> cached = oneSolutionCache.get(goal);
        if (cached != null) {
            log.trace("oneSolution (cache hit): {}", goal);
            return cached;
        }
        Optional<Map<String, Term>> result = oneSolution(goal);
        oneSolutionCache.put(goal, result);
        return result;
    }

    public void clearCache() {
        hasSolutionCache.clear();
        oneSolutionCache.clear();
    }

    // --- Atomic critical section + transient fact helpers ------------------

    /**
     * Runs {@code body} holding the engine lock, so a sequence of goals (e.g.
     * assert → query → retract) executes as one atomic critical section.
     */
    public <T> T inLock(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Asserts a transient clause into {@code user:} (where the rules resolve it).
     * For per-query vessel state only; the caller must retract it afterwards.
     */
    public void assertFact(String fact) {
        lock.lock();
        try {
            log.trace("assertz user:{}", fact);
            if (!Query.hasSolution("assertz(user:" + fact + ")")) {
                throw new PrologException("assertz failed: user:" + fact);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Retracts every {@code user:} clause matching {@code pattern} (never fails). */
    public void retractAllMatching(String pattern) {
        lock.lock();
        try {
            log.trace("retractall user:{}", pattern);
            Query.hasSolution("retractall(user:" + pattern + ")");
        } finally {
            lock.unlock();
        }
    }

    // --- Internals ---------------------------------------------------------

    private long ontologyFactCount() {
        return countClauses("default(_, _, _)") + countClauses("instance_of(_, _)");
    }

    private long countClauses(String pattern) {
        Map<String, Term> sol = Query.oneSolution("aggregate_all(count, (" + pattern + "), N)");
        return sol == null ? 0L : sol.get("N").longValue();
    }

    /**
     * Resolves a classpath {@code /prolog/*.pl} resource to an absolute,
     * forward-slash path safe as a {@code consult/1} atom on Windows.
     */
    private static String resolveResource(String resource) {
        URL url = PrologEngine.class.getResource(resource);
        if (url == null) {
            throw new PrologException("resource not on classpath: " + resource);
        }
        try {
            Path path = Paths.get(url.toURI());
            if (!Files.exists(path)) {
                throw new PrologException("resource missing on disk: " + path);
            }
            return path.toString().replace('\\', '/');
        } catch (URISyntaxException e) {
            throw new PrologException("bad URI for " + resource + ": " + url, e);
        }
    }
}
