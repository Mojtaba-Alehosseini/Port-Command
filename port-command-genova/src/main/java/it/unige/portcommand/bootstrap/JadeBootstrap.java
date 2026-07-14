package it.unige.portcommand.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.RasaBridge;
import it.unige.portcommand.ontology.OntologyValidation;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the JADE Main Container lifecycle: build a {@link Profile}, boot a single
 * Main Container, spawn a {@link BootstrapProbeAgent} for liveness + a DF
 * self-check, expose the shared {@link SimClock}/{@link JadeAgentSpawner}/
 * {@link ServiceLocator}, and tear down cleanly.
 *
 * <p>Not a {@link jade.core.Agent}: it uses only the {@code jade.wrapper} API and
 * never calls the agent-side DF directly. Instance methods are guarded by a
 * {@link ReentrantLock}; one instance per JVM.
 */
public final class JadeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(JadeBootstrap.class);
    private static final long READY_TIMEOUT_SECONDS = 5L;
    private static final int PORT_PROBE_TIMEOUT_MS = 300;
    // Fixed master seed for reproducible runs; task 22/23 override it via RandomSource.setSeed on load/scenario.
    private static final long DEFAULT_RANDOM_SEED = 20260622L;

    // JVM-global, mirroring JADE's own Runtime singleton: once a Main Container has
    // booted in this JVM, its RMI registry persists across shutdown and is reused on
    // restart, so the held-port probe must run only on the FIRST (cold) boot — a
    // foreign orphan can only wedge that one. Not DI/registry state; pure boot latch.
    private static volatile boolean jadeBootedInThisJvm;

    private final ReentrantLock lock = new ReentrantLock();
    // Written under the lock in start()/shutdown(); volatile so the lock-free
    // getters publish their values safely to other threads.
    private volatile boolean started;
    private volatile AgentContainer mainContainer;
    private volatile SimClock simClock;
    private volatile PortStateArtifact portStateArtifact;
    private volatile RandomSource randomSource;
    private volatile MarketHistoryArtifact marketHistory;
    private volatile JadeAgentSpawner spawner;
    private volatile List<AID> bootDfSelfCheck = List.of();
    private volatile RasaBridge rasaBridge;
    private volatile LLMBridge llmBridge;

    public void start(BootstrapConfig cfg) {
        lock.lock();
        try {
            if (started) {
                log.warn("start() called while already started — ignoring");
                return;
            }
            // Task 02 guarantee: the ontology must have loaded before any agent runs.
            if (OntologyValidation.getVesselTypes().isEmpty()) {
                throw new IllegalStateException("ontology not loaded (no vessel types) — aborting bootstrap");
            }

            // Fail fast on a held port: createMainContainer() blocks indefinitely on a
            // contended RMI bind (a stale JADE container once wedged the build ~1h25m),
            // and our awaitReady timeout only covers the probe AFTER the container exists.
            // Cold boot only — a restart reuses this JVM's own persistent registry.
            if (!jadeBootedInThisJvm) {
                assertPortAvailable(cfg.mainPort());
            }

            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, "localhost");
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(cfg.mainPort()));
            profile.setParameter(Profile.GUI, String.valueOf(cfg.enableRMA()));

            Runtime rt = Runtime.instance();
            rt.setCloseVM(false); // we own JVM shutdown, not JADE
            mainContainer = rt.createMainContainer(profile);
            if (mainContainer == null) {
                throw new IllegalStateException("createMainContainer returned null — JADE failed to boot");
            }
            jadeBootedInThisJvm = true; // registry now persists in this JVM; skip the probe on restart

            simClock = new SimClock(cfg.realSecondsPerGameDay());
            portStateArtifact = new PortStateArtifact();
            randomSource = new RandomSource(DEFAULT_RANDOM_SEED);
            marketHistory = new MarketHistoryArtifact();
            spawner = new JadeAgentSpawner(mainContainer);

            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<List<AID>> dfCheck = new CompletableFuture<>();
            spawner.spawn("bootstrap_probe", BootstrapProbeAgent.class,
                    new Object[] {mainContainer, ready, dfCheck});
            awaitReady(ready, cfg.mainPort());
            bootDfSelfCheck = dfCheck.getNow(List.of());

            // One shared HttpClient for both NLP bridges (thread-safe; planning/14's own
            // note). Single Rasa pipeline on port 5005 — there is no second instance.
            HttpClient sharedHttpClient = RasaBridge.newClientBuilder().build();
            ObjectMapper sharedJson = new ObjectMapper();
            rasaBridge = new RasaBridge(RasaBridge.resolveParseUri(), sharedHttpClient, sharedJson);
            llmBridge = new LLMBridge(LLMBridge.resolveExplainUri(), LLMBridge.resolveHealthUri(),
                    sharedHttpClient, sharedJson);

            // Prolog kernel up before any agent makes a binding decision. Independent
            // of JADE (pure JPL); idempotent, so a restart cycle re-enters as a no-op.
            PrologEngine.getInstance().init();

            started = true;
            log.info("JADE Main Container up on port {}", cfg.mainPort());
        } finally {
            lock.unlock();
        }
    }

    private void awaitReady(CountDownLatch ready, int port) {
        try {
            if (!ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "bootstrap probe did not signal readiness within " + READY_TIMEOUT_SECONDS
                                + "s on port " + port);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for bootstrap readiness", e);
        }
    }

    /**
     * Throws fast if {@code port} has an <em>active listener</em> (typically a stale
     * JADE container), rather than letting {@code createMainContainer} block on the
     * contended RMI bind. Probes by <em>connecting</em> to the loopback port: a
     * successful TCP handshake means something is listening → fail fast; a refused
     * connection means the port is free to bind.
     *
     * <p>A connect probe — not a bind probe — is used deliberately: a clean
     * {@link #shutdown()} leaves the port re-bindable but may leave {@code TIME_WAIT}
     * remnants from the RMI connections, which a {@code ServerSocket} bind (without
     * {@code SO_REUSEADDR}) would reject as a false positive. Connecting only detects
     * a live listener, so the start→shutdown→start restart cycle is unaffected.
     */
    private static void assertPortAvailable(int port) {
        boolean listenerPresent;
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PORT_PROBE_TIMEOUT_MS);
            listenerPresent = true; // handshake completed → an active listener holds the port
        } catch (IOException refusedOrUnreachable) {
            listenerPresent = false; // connection refused / no listener → free to bind
        }
        if (listenerPresent) {
            throw new IllegalStateException(
                    "JADE main port " + port + " already in use — kill the stale java process holding it");
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            if (!started) {
                log.warn("shutdown() called while not started — ignoring");
                return;
            }
            try {
                mainContainer.kill();
            } catch (StaleProxyException e) {
                log.warn("main container kill raised", e);
            }
            Runtime.instance().shutDown();
            started = false;
            log.info("JADE Main Container down");
        } finally {
            lock.unlock();
        }
    }

    /** The Main Container handle; {@code null} before {@link #start(BootstrapConfig)}. */
    public AgentContainer getMainContainer() {
        return mainContainer;
    }

    public SimClock getSimClock() {
        return simClock;
    }

    /** Shared port-state store; {@code null} before {@link #start(BootstrapConfig)}. */
    public PortStateArtifact getPortStateArtifact() {
        return portStateArtifact;
    }

    /** Master seeded RNG; agents derive named sub-streams via {@code forStream}. */
    public RandomSource getRandomSource() {
        return randomSource;
    }

    /** Shared market-history store (deal outcomes); {@code null} before start. */
    public MarketHistoryArtifact getMarketHistoryArtifact() {
        return marketHistory;
    }

    public JadeAgentSpawner getSpawner() {
        return spawner;
    }

    /** AIDs found by the boot-time {@code smoke-test} DF self-check (expected empty). */
    public List<AID> bootDfSelfCheck() {
        return bootDfSelfCheck;
    }

    /** The single Rasa NLU pipeline client (port 5005); {@code null} before start. */
    public RasaBridge getRasaBridge() {
        return rasaBridge;
    }

    /** The Flask LLM sidecar client (port 5006); {@code null} before start. */
    public LLMBridge getLLMBridge() {
        return llmBridge;
    }

    public boolean isStarted() {
        return started;
    }

    /** Test seam: clears the JVM cold-boot latch so a unit test can exercise the held-port probe. */
    static void resetBootLatchForTest() {
        jadeBootedInThisJvm = false;
    }
}
