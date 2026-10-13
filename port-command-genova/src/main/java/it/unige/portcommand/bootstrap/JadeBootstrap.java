package it.unige.portcommand.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.nlp.ConfidenceGate;
import it.unige.portcommand.nlp.DcgTokenizer;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.NLPPipeline;
import it.unige.portcommand.nlp.OntologyValidator;
import it.unige.portcommand.nlp.PreprocessRegex;
import it.unige.portcommand.nlp.PrologDcgParser;
import it.unige.portcommand.nlp.RasaBridge;
import it.unige.portcommand.ontology.OntologyValidation;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.EventBus;
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
 *
 * <p><b>A world is rebuilt in this JVM</b> - a load, a new game, free play replacing a scenario
 * are all {@link #shutdown()} followed by {@link #start}. Three things keep that safe
 * (2026-10-13, after the integration lane hung twice in a rebuilt world's teardown; ADR-15
 * addendum):
 * <ul>
 *   <li>{@link JadeTableGuard} is installed on every new Main Container before an agent of ours
 *       exists. JADE 4.6.0's agent tables can leave a row locked for good when they grow while a
 *       message is being delivered - which is exactly what spawning the fleet does. That was the
 *       cause of "the DF is not answering".</li>
 *   <li>{@link #shutdown()} returns only when the old platform is really gone. JADE removes a
 *       dead platform's entries from its JVM-wide command dispatcher - and closes its server
 *       socket - in background threads that sleep a full second first; a platform started inside
 *       that second shares the dying dispatcher and cannot bind its own socket ("Cannot bind
 *       server socket to localhost port ..."). The wait is bounded, and {@link #start} now refuses
 *       a port that is still held, on a restart as on a cold boot.</li>
 *   <li>the kill itself is bounded: a teardown that does not finish is logged with what it is
 *       stuck in and interrupted - JADE's waits give way to an interrupt - and, should even that
 *       not end it, abandoned. It never wedges the caller.</li>
 * </ul>
 */
public final class JadeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(JadeBootstrap.class);
    private static final long READY_TIMEOUT_SECONDS = 5L;
    private static final int PORT_PROBE_TIMEOUT_MS = 300;
    /** How long {@code mainContainer.kill()} may take before it is abandoned. JADE's own kill takes
     * about 2.5 s on an idle platform, and one second more per agent whose DF deregistration
     * times out (see {@code PortCommandAgent.takeDown}). */
    private static volatile long killTimeoutMs = 30_000L;
    /** How long the old platform may take to let go of JADE's shared dispatcher and its port once
     * the kill has returned. Normally a few milliseconds (its sleeping threads are woken). */
    private static volatile long platformGoneTimeoutMs = 5_000L;
    /** How long a kill that overran its bound is given to finish once it is being interrupted. */
    private static final long KILL_INTERRUPT_GRACE_MS = 10_000L;
    /** JADE's delayed "remove this dead platform's entry from the dispatcher" thread: an anonymous
     * class of {@code jade.imtp.leap.CommandDispatcher} (JADE 4.6.0) that sleeps 1000 ms first. */
    private static final String JADE_DISPATCHER_REMOVAL_THREAD = "jade.imtp.leap.CommandDispatcher$1";
    // Fixed master seed for reproducible runs; task 22/23 override it via RandomSource.setSeed on load/scenario.
    private static final long DEFAULT_RANDOM_SEED = 20260622L;

    /** The port of the platform that is up (or was last up): {@link #shutdown()} waits for it. */
    private volatile int mainPort;

    private final ReentrantLock lock = new ReentrantLock();
    // Written under the lock in start()/shutdown(); volatile so the lock-free
    // getters publish their values safely to other threads.
    private volatile boolean started;
    private volatile AgentContainer mainContainer;
    private volatile SimClock simClock;
    private volatile PortStateArtifact portStateArtifact;
    private volatile RandomSource randomSource;
    private volatile MarketHistoryArtifact marketHistory;
    private volatile PolicyRegistryArtifact policyRegistry;
    private volatile JadeAgentSpawner spawner;
    private volatile List<AID> bootDfSelfCheck = List.of();
    private volatile RasaBridge rasaBridge;
    private volatile LLMBridge llmBridge;
    private volatile EventBus eventBus;
    private volatile NLPPipeline nlpPipeline;
    private volatile ExecutorService nlpExecutor;

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
            // On a restart too, since 2026-10-13: shutdown() now waits until the previous
            // platform has closed its socket, so a port still held here is either a foreign
            // process or a teardown that did not finish - and starting on top of either one
            // is what must not happen. (Until then a restart skipped this check, because the
            // previous platform of this very JVM routinely still held the port.)
            assertPortAvailable(cfg.mainPort());

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
            mainPort = cfg.mainPort();
            // Before ANY agent of ours exists (the probe below is the first): JADE's agent
            // tables must not be written and read at the same time once the fleet is spawned.
            try {
                JadeTableGuard.install(mainContainer);
            } catch (IllegalStateException e) {
                killBounded(mainContainer);
                awaitOldPlatformGone(mainPort);
                mainContainer = null;
                throw e;
            }

            // Task 22: the shared services are CREATE-IF-ABSENT so a save/load restart cycle
            // (shutdown() then start()) keeps the SAME instances — the GUI holds references to
            // the bus/clock/artifacts from the first boot, and the loader restores their state
            // in place. Only the container-bound spawner is rebuilt each start.
            if (simClock == null) {
                simClock = new SimClock(cfg.realSecondsPerGameDay());
            }
            if (portStateArtifact == null) {
                portStateArtifact = new PortStateArtifact();
            }
            if (randomSource == null) {
                randomSource = new RandomSource(DEFAULT_RANDOM_SEED);
            }
            if (marketHistory == null) {
                marketHistory = new MarketHistoryArtifact();
            }
            if (policyRegistry == null) {
                // Promoted from AgentRoster's per-boot construction (task 22): the save file
                // persists the registry's triggers, so the instance must survive the reload
                // (the loader clears + re-parses into it on a cross-JVM load).
                policyRegistry = new PolicyRegistryArtifact();
            }
            // Single shared bus for every agent + the (future, task 17) GUI — one instance so a
            // publish from one agent is visible to a subscriber on another (task 10 needs this
            // for the Assistant; the real dispatcher arrives in task 17, this is still the stub).
            if (eventBus == null) {
                eventBus = new EventBus();
            }
            spawner = new JadeAgentSpawner(mainContainer);

            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<List<AID>> dfCheck = new CompletableFuture<>();
            spawner.spawn("bootstrap_probe", BootstrapProbeAgent.class,
                    new Object[] {mainContainer, ready, dfCheck});
            awaitReady(ready, cfg.mainPort());
            bootDfSelfCheck = dfCheck.getNow(List.of());

            // One shared HttpClient for both NLP bridges (thread-safe; planning/14's own
            // note). Single Rasa pipeline on port 5005 — there is no second instance.
            // Create-if-absent since task 22 (restart cycle keeps the GUI's captured refs).
            if (rasaBridge == null) {
                HttpClient sharedHttpClient = RasaBridge.newClientBuilder().build();
                ObjectMapper sharedJson = new ObjectMapper();
                rasaBridge = new RasaBridge(RasaBridge.resolveParseUri(), sharedHttpClient, sharedJson);
                llmBridge = new LLMBridge(LLMBridge.resolveExplainUri(), LLMBridge.resolveHealthUri(),
                        sharedHttpClient, sharedJson);
            }

            // Prolog kernel up before any agent makes a binding decision. Independent
            // of JADE (pure JPL); idempotent, so a restart cycle re-enters as a no-op.
            PrologEngine.getInstance().init();

            // The NL chat pipeline (task 16). Constructed AFTER PrologEngine.init() because the
            // DCG parser runs goals against the grammar that init() consults. This is the first
            // production construction site: task 14 shipped NLPPipeline with a NoOp DCG stub and
            // no caller at all, leaving the real wiring to this task. The DCG slot now holds the
            // real grammar and the frame-validator seam holds the real ontology check, so a clean
            // negotiation move routes WITHOUT Rasa (PROJECT_DEFINITION §6.1's DCG-first order).
            // The GUI's ChatPanel (task 19) is the intended consumer.
            // java.lang.Runtime spelled out: the bare name `Runtime` in this file is
            // jade.core.Runtime (imported for the container lifecycle).
            // Create-if-absent since task 22: MainWindow captured a method reference to THIS
            // pipeline at first boot; recreating it on a load-restart would leave chat wired
            // to a dead executor.
            if (nlpPipeline == null) {
                nlpExecutor = Executors.newFixedThreadPool(
                        java.lang.Runtime.getRuntime().availableProcessors(),
                        r -> {
                            Thread t = new Thread(r, "nlp-pipeline");
                            t.setDaemon(true); // must never hold the JVM up at shutdown
                            return t;
                        });
                nlpPipeline = new NLPPipeline(
                        new PreprocessRegex(),
                        rasaBridge,
                        new ConfidenceGate(),
                        new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer()),
                        new OntologyValidator(),
                        nlpExecutor);
            }

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
                    "JADE main port " + port + " already in use — kill the stale java process holding it "
                            + "(or, right after a load or a new game: the previous world's platform did not "
                            + "shut down - see the ERROR line of its teardown above)");
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            if (!started) {
                log.warn("shutdown() called while not started — ignoring");
                return;
            }
            boolean killed = killBounded(mainContainer);
            // Task 22: the NLP executor is NOT shut down here any more — shutdown() is also the
            // save/load restart teardown, and the pipeline (with MainWindow's captured method
            // reference into it) must survive the cycle. Its threads are daemons; JVM exit
            // reaps them.
            if (killed) {
                Runtime.instance().shutDown();
            }
            boolean gone = awaitOldPlatformGone(mainPort);
            started = false;
            if (killed && gone) {
                log.info("JADE Main Container down");
            } else {
                log.error("JADE Main Container teardown did NOT complete (kill finished: {}, platform gone: {}) "
                        + "- this world is abandoned; a new one cannot be started on port {} in this process",
                        killed, gone, mainPort);
            }
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

    /** Session-stable autopilot policy registry (task 22 promotion); {@code null} before start. */
    public PolicyRegistryArtifact getPolicyRegistryArtifact() {
        return policyRegistry;
    }

    /** Single shared publish/subscribe bus for every agent + the GUI; {@code null} before start. */
    public EventBus getEventBus() {
        return eventBus;
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

    /**
     * The DCG-first NL chat pipeline (PROJECT_DEFINITION.md §6.1); {@code null} before start.
     * Wired with the real {@code dcg_negotiation.pl} grammar and ontology validation (task 16),
     * so a clean negotiation move routes to an ACL message without Rasa being up at all.
     */
    public NLPPipeline getNlpPipeline() {
        return nlpPipeline;
    }

    public boolean isStarted() {
        return started;
    }

    // =============================================================================================
    // A teardown that always ends (2026-10-13)
    // =============================================================================================

    /**
     * Kills {@code container} on a helper thread and waits for it, but not for ever. JADE's
     * {@code kill()} has no timeout of its own: it joins every agent, and it walks the agent
     * table taking each row's lock - a row that is never released, or an agent that never
     * returns from its takedown, used to hold the calling thread (a test's teardown, the
     * player's Load) until the process was killed from outside.
     *
     * <p>A kill that overruns its bound is first reported - an ERROR line with the frames it is
     * stuck in - and then interrupted, repeatedly, for {@link #KILL_INTERRUPT_GRACE_MS}. That is
     * usually enough: JADE's row lock waits in {@code Object.wait()}, catches the interrupt and
     * carries on without the lock, which is harmless in a platform that is being destroyed, and
     * its agent joins give up after two interrupts. So a stuck kill normally still completes, the
     * port is released and the next world can start.
     *
     * @return false only if the kill could not be ended at all; it is then abandoned (its thread
     *         is a daemon) and {@link #start} will refuse the port it still holds
     */
    private static boolean killBounded(AgentContainer container) {
        Thread killer = new Thread(() -> {
            try {
                container.kill();
            } catch (StaleProxyException e) {
                log.warn("main container kill raised", e);
            } catch (RuntimeException e) {
                log.error("main container kill failed", e);
            }
        }, "jade-container-kill");
        killer.setDaemon(true);
        long bound = killTimeoutMs;
        killer.start();
        try {
            killer.join(bound);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!killer.isAlive()) {
            return true;
        }
        StringBuilder where = new StringBuilder();
        for (StackTraceElement frame : killer.getStackTrace()) {
            where.append(System.lineSeparator()).append("    at ").append(frame);
        }
        log.error("JADE container kill did not finish within {} ms - interrupting it. It is stuck in:{}",
                bound, where);
        long giveUpAt = System.currentTimeMillis() + KILL_INTERRUPT_GRACE_MS;
        try {
            while (killer.isAlive() && System.currentTimeMillis() < giveUpAt) {
                killer.interrupt(); // one stuck wait at a time: there may be several rows, several agents
                killer.join(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!killer.isAlive()) {
            log.warn("JADE container kill finished once it was interrupted");
            return true;
        }
        log.error("JADE container kill could not be ended even by interrupting it for {} ms - abandoning it",
                KILL_INTERRUPT_GRACE_MS);
        return false;
    }

    /**
     * Waits until the platform that was just killed has let go of everything a new one would
     * share with it: JADE's delayed dispatcher-removal threads have run, and nothing accepts a
     * connection on {@code port} any more.
     *
     * <p>Those threads ({@value #JADE_DISPATCHER_REMOVAL_THREAD}) sleep 1000 ms before they act,
     * "to give the reply of the exit command time to be sent back" to a remote container. There
     * is no remote container here, so they are interrupted - JADE catches the interrupt and does
     * the removal at once - and joined; the last removal shuts the dispatcher down and closes
     * its server socket. A rebuild therefore costs milliseconds here, not a second.
     *
     * @return false if the port is still held after {@code platformGoneTimeoutMs}
     */
    private static boolean awaitOldPlatformGone(int port) {
        long bound = platformGoneTimeoutMs;
        long deadline = System.currentTimeMillis() + bound;
        boolean interrupted = false;
        try {
            while (true) {
                List<Thread> removals = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.isAlive() && JADE_DISPATCHER_REMOVAL_THREAD.equals(t.getClass().getName()))
                        .toList();
                for (Thread removal : removals) {
                    removal.interrupt();
                }
                for (Thread removal : removals) {
                    try {
                        removal.join(Math.max(1L, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                // The port is asked only once the removal threads are gone and JADE's server thread
                // for it has ended: a connection to a JICP server that is still alive makes JADE
                // print an EOFException for it, every time.
                boolean serverThreadAlive = Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> t.isAlive() && ("JICPServer-" + port).equals(t.getName()));
                if (removals.isEmpty() && !serverThreadAlive && !portAccepts(port)) {
                    return true;
                }
                if (System.currentTimeMillis() >= deadline) {
                    log.error("the previous JADE platform still holds port {} {} ms after its kill - "
                            + "its dispatcher was not shut down", port, bound);
                    return false;
                }
                if (removals.isEmpty()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Test seam: shorter bounds, so a test that stages a teardown that cannot finish does not
     * sit through the production ones. Returns nothing; a test restores {@code 30_000, 5_000}. */
    static void teardownBoundsForTest(long killMs, long goneMs) {
        killTimeoutMs = killMs;
        platformGoneTimeoutMs = goneMs;
    }

    private static boolean portAccepts(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PORT_PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException refusedOrUnreachable) {
            return false;
        }
    }
}
