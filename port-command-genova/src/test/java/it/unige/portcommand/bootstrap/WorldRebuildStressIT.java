package it.unige.portcommand.bootstrap;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.FIPAManagementVocabulary;
import jade.domain.FIPAAgentManagement.SearchConstraints;
import jade.domain.FIPAService;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world is rebuilt in one JVM again and again - what Load, New Game and Free play do - and every
 * rebuilt world must be a working platform (2026-10-13; ADR-15 addendum).
 *
 * <p>Born red. On 2026-10-13 the integration lane hung twice, for hours, in the teardown of a
 * rebuilt world whose DF had stopped answering. Two things were wrong, and this class holds a
 * check for each that failed on the code of that day:
 * <ul>
 *   <li><b>the cause</b> - JADE 4.6.0's agent tables can leave a row locked for good when they
 *       grow while a message is being delivered, which is what spawning the fleet does (see
 *       {@link JadeTableGuard}; {@code JadeTableGuardTest} shows the defect on JADE's own classes).
 *       Here: each rebuilt world's two tables hold the guarded map, no row stays locked, the
 *       fleet is up and the DF answers;</li>
 *   <li><b>the untidy hand-over</b> - {@code shutdown()} used to return while the dead platform
 *       still held JADE's JVM-wide dispatcher and its server socket (JADE lets go a full second
 *       later), so every other rebuilt platform came up unable to bind its own socket
 *       ("Cannot bind server socket to localhost port ..."). Here: the port is free the moment
 *       {@code shutdown()} returns, and no rebuild logs that warning.</li>
 * </ul>
 *
 * <p>This class uses nothing the fix added, on purpose: it compiles against the code from before
 * the fix, which is how it was shown red. JADE's internals are read by reflection, by name.
 * Every wait has a deadline.
 */
@Tag("integration")
class WorldRebuildStressIT {

    private static final int TEST_PORT = 18099;
    private static final int REBUILDS = 10;
    private static final List<String> FLEET = List.of("harbour_master", "weather_agent", "customs_agent",
            "assistant_agent", "terminal_container", "terminal_general", "tug_1", "tug_2", "tug_3", "tug_4");

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    /** Asks the DF once, with a deadline, says whether it answered, and goes away. */
    public static final class DfProbeAgent extends Agent {
        @Override
        protected void setup() {
            @SuppressWarnings("unchecked")
            CompletableFuture<Boolean> answer = (CompletableFuture<Boolean>) getArguments()[0];
            try {
                ACLMessage request = DFService.createRequestMessage(this, getDefaultDF(),
                        FIPAManagementVocabulary.SEARCH, new DFAgentDescription(), new SearchConstraints());
                answer.complete(FIPAService.doFipaRequestClient(this, request, 2_000) != null);
            } catch (Exception e) {
                answer.complete(false);
            }
            doDelete();
        }
    }

    // =============================================================================================
    // Ten rebuilds through the session: the path Load takes
    // =============================================================================================

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void tenRebuiltWorldsEachHaveAnAnsweringDfAGuardedTableAndNoLockedRow() throws Exception {
        AtomicInteger bindFailures = countBindFailures();
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot, new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        boot.getSimClock().pause(); // no traffic: a save is allowed at once, and every world is the same
        assertTrue(waitUntil(() -> fleetIsUp(session), 15_000), "fixture: the first world's fleet is up");
        Path saved = session.saveNow();

        for (int rebuild = 1; rebuild <= REBUILDS; rebuild++) {
            String where = "rebuild " + rebuild + " of " + REBUILDS;
            long started = System.currentTimeMillis();

            session.loadGame(saved);

            assertTrue(System.currentTimeMillis() - started < 45_000, where + ": the rebuild itself must not drag");
            assertTrue(waitUntil(() -> fleetIsUp(session), 15_000),
                    where + ": every fixed agent must come up (an agent whose DF registration is not "
                            + "answered dies in setup); missing: " + missingFromFleet(session));
            assertEquals(List.of("guarded", "guarded"), tableKinds(boot),
                    where + ": JADE's platform table and local table must hold the guarded map "
                            + "(their own lets a write run under a read)");
            assertTrue(dfAnswers(boot, "df_probe_" + rebuild + "a"), where + ": the DF must answer");
            assertEquals(List.of(), rowsThatStayLocked(boot), where + ": no row of the platform table may stay locked");
            // ... and still once the dead platform's delayed clean-up is over: before the fix it
            // ran up to a second AFTER the rebuild, inside the dispatcher the new platform shared.
            assertTrue(waitUntil(() -> jadeDispatcherRemovalThreads().isEmpty(), 5_000),
                    where + ": JADE's clean-up threads for the previous platform must end");
            assertTrue(dfAnswers(boot, "df_probe_" + rebuild + "b"),
                    where + ": the DF must still answer after the previous platform's clean-up");
        }

        assertEquals(0, bindFailures.get(), "no rebuilt platform may fail to bind its own server socket: "
                + "shutdown() must not return while the previous platform still holds the port");
    }

    // =============================================================================================
    // The hand-over itself, at the bootstrap
    // =============================================================================================

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aPlatformThatIsShutDownHasLetGoOfItsPortWhenShutdownReturns() throws Exception {
        AtomicInteger bindFailures = countBindFailures();
        boot = new JadeBootstrap();
        BootstrapConfig config = new BootstrapConfig(TEST_PORT, false, "realtime", 300);
        boot.start(config);

        for (int restart = 1; restart <= 3; restart++) {
            String where = "restart " + restart;

            boot.shutdown();

            assertEquals(List.of(), jadeDispatcherRemovalThreads(),
                    where + ": JADE's delayed dispatcher clean-up for the dead platform must be over");
            assertFalse(portAccepts(TEST_PORT), where + ": nothing may accept a connection on the platform "
                    + "port once shutdown() has returned (before the fix the dead platform held it for a second more)");

            boot.start(config);
            assertTrue(dfAnswers(boot, "df_probe_restart_" + restart), where + ": the new platform's DF must answer");
        }

        assertEquals(0, bindFailures.get(), "every restarted platform must bind its own server socket");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static boolean fleetIsUp(GameSession session) {
        return missingFromFleet(session).isEmpty()
                && session.directory().byType(TugAgent.class).stream().allMatch(t -> t.snapshotDto().tugId() != null);
    }

    private static List<String> missingFromFleet(GameSession session) {
        return FLEET.stream().filter(name -> session.directory().byName(name).isEmpty()).toList();
    }

    private static boolean dfAnswers(JadeBootstrap boot, String probeName) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        boot.getSpawner().spawn(probeName, DfProbeAgent.class, new Object[] {answer});
        try {
            return answer.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /** Counts JADE's "Cannot bind server socket" warnings from here on (JADE logs through java.util.logging). */
    private static AtomicInteger countBindFailures() {
        AtomicInteger count = new AtomicInteger();
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (String.valueOf(record.getMessage()).contains("Cannot bind server socket")) {
                    count.incrementAndGet();
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return count;
    }

    private static boolean portAccepts(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (Exception refused) {
            return false;
        }
    }

    private static List<String> jadeDispatcherRemovalThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && "jade.imtp.leap.CommandDispatcher$1".equals(t.getClass().getName()))
                .map(Thread::getName).toList();
    }

    // ---- JADE's two agent tables, by reflection -------------------------------------------------

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** The maps behind the platform table and the local table of the running Main Container. */
    private static List<jade.util.leap.Map> tableMaps(JadeBootstrap boot) throws Exception {
        Object container = field(jade.wrapper.ContainerController.class, "myImpl").get(boot.getMainContainer());
        Class<?> containerClass = Class.forName("jade.core.AgentContainerImpl");
        Object platformTable = field(Class.forName("jade.core.MainContainerImpl"), "platformAgents")
                .get(field(containerClass, "myMainContainer").get(container));
        Object localTable = field(containerClass, "localAgents").get(container);
        return List.of((jade.util.leap.Map) field(Class.forName("jade.core.GADT"), "agents").get(platformTable),
                (jade.util.leap.Map) field(Class.forName("jade.core.LADT"), "agents").get(localTable));
    }

    /** "guarded" for a table whose map is the guard's, else the class JADE put there. */
    private static List<String> tableKinds(JadeBootstrap boot) throws Exception {
        return tableMaps(boot).stream()
                .map(map -> map.getClass().getName().contains("JadeTableGuard") ? "guarded" : map.getClass().getName())
                .toList();
    }

    /** Rows of the platform table that have an owner now and still have it 150 ms later. A row is
     * locked for microseconds at a time; one that stays locked is never coming back. */
    private static List<String> rowsThatStayLocked(JadeBootstrap boot) throws Exception {
        List<String> first = lockedRows(boot);
        if (first.isEmpty()) {
            return first;
        }
        // Give a lock that is merely in use 150 ms to be released; what is left then is stuck.
        List<String> stillLocked = new ArrayList<>(first);
        waitUntil(() -> {
            try {
                stillLocked.retainAll(lockedRows(boot));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return stillLocked.isEmpty();
        }, 150);
        return stillLocked;
    }

    private static List<String> lockedRows(JadeBootstrap boot) throws Exception {
        jade.util.leap.Map agents = tableMaps(boot).get(0);
        Field owner = field(Class.forName("jade.core.GADT$Row"), "owner");
        List<String> locked = new ArrayList<>();
        Object[] keys;
        synchronized (agents) {
            keys = agents.keySet().toArray();
        }
        for (Object key : keys) {
            Object row;
            synchronized (agents) {
                row = agents.get(key);
            }
            if (row != null && owner.get(row) instanceof Thread thread) {
                locked.add(((jade.core.AID) key).getLocalName() + " (held by " + thread.getName() + ")");
            }
        }
        return locked;
    }

    /** Bounded poll; {@code false} when the deadline passes. */
    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
