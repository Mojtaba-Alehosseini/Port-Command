package it.unige.portcommand.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.unige.portcommand.agents.PortCommandAgent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import jade.core.AID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety net under the rebuild (2026-10-13; backlog A-14, ADR-15 addendum): a teardown that
 * cannot complete must END, with a line that says why - it must never hold the thread that asked
 * for it. Before this, a platform whose DF had stopped answering held a test's {@code @AfterEach}
 * (and would have held the player's Load) for as long as the process lived: the integration lane
 * was stopped by hand after two hours, twice.
 *
 * <p>Two stagings of "cannot complete", from the outside in:
 * <ol>
 *   <li>the DF does not answer - each agent's deregistration is given a second and then left;</li>
 *   <li>JADE's own {@code kill()} does not finish (a row of its agent table is locked for good, as
 *       the defect {@link JadeTableGuard} closes used to leave it) - the kill is reported with the
 *       frame it is stuck in and interrupted, which lets JADE carry on; the world is torn down
 *       after all, and the next one starts.</li>
 * </ol>
 * The second one tampers with JADE's tables, so it runs last and on a port of its own. Every wait
 * has a deadline.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JadeTeardownIT {

    private static final int TEST_PORT = 18099;
    private static final int WRECK_PORT = 18097;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        JadeBootstrap.teardownBoundsForTest(30_000L, 5_000L);
        if (boot != null && boot.isStarted()) {
            // Bounded here as well: if a test above has just shown that shutdown() can hang, this
            // must not be the place that hangs next.
            assertTimeoutPreemptively(Duration.ofSeconds(90), () -> boot.shutdown());
        }
    }

    @Test
    @Order(1)
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void aWorldWhoseDfDoesNotAnswerIsTornDownAnywayAndTheNextWorldStarts() throws Exception {
        // Every agent logs under its own class; they share the package of PortCommandAgent.
        ListAppender<ILoggingEvent> agentLog = capture(PortCommandAgent.class.getPackageName());
        boot = new JadeBootstrap();
        BootstrapConfig config = new BootstrapConfig(TEST_PORT, false, "realtime", 300);
        GameSession session = new GameSession(boot, config,
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        boot.getSimClock().pause();
        assertTrue(waitUntil(() -> session.directory().byName("tug_4").isPresent()
                && session.directory().byName("harbour_master").isPresent(), 15_000), "fixture: the fleet is up");

        // The DF stops answering: suspended, it reads no message. (What the table defect did to
        // it from the outside: requests go in, nothing comes back.)
        boot.getMainContainer().getAgent("df").suspend();

        long started = System.currentTimeMillis();
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> boot.shutdown(),
                "before the fix this never returned: every agent waited in DFService.deregister for an "
                        + "answer that could not come, and JADE's kill() waited for the agents");
        long tookMs = System.currentTimeMillis() - started;

        assertFalse(boot.isStarted());
        assertTrue(tookMs < 40_000, "about a second per agent, then JADE's own kill: took " + tookMs + " ms");
        List<String> warnings = agentLog.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("DF deregister timed out")).toList();
        assertTrue(warnings.size() >= 10, "each of the ten fixed agents must say that its deregistration was "
                + "not answered, got " + warnings.size() + ": " + warnings);
        assertTrue(warnings.get(0).contains("the platform DF is not answering"), warnings.get(0));

        // And the wreck is gone: the port is free and a new world is a working platform.
        boot.start(config);
        assertTrue(dfAnswers(boot, "df_probe_after_dead_df"), "the next platform's DF must answer");
    }

    @Test
    @Order(2)
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aKillThatDoesNotFinishIsReportedInterruptedAndTheNextWorldStillStarts() throws Exception {
        ListAppender<ILoggingEvent> bootLog = capture(JadeBootstrap.class.getName());
        JadeBootstrap.teardownBoundsForTest(4_000L, 1_500L);
        boot = new JadeBootstrap();
        BootstrapConfig config = new BootstrapConfig(WRECK_PORT, false, "realtime", 300);
        boot.start(config);
        lockTheDfRowForGood(boot);

        long started = System.currentTimeMillis();
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> boot.shutdown(),
                "before the fix a kill that met a row locked for good never returned");
        long tookMs = System.currentTimeMillis() - started;

        assertTrue(tookMs < 20_000, "the bound (4 s here) and a moment more, not for ever: took " + tookMs + " ms");
        assertTrue(tookMs >= 4_000, "fixture: the kill really was stuck until its bound, took " + tookMs + " ms");
        assertFalse(boot.isStarted());
        List<String> errors = bootLog.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(errors.stream().anyMatch(m -> m.contains("did not finish within 4000 ms")
                        && m.contains("jade.core.GADT")),
                "the ERROR line must say that the kill overran and where it was stuck: " + errors);
        assertTrue(errors.stream().noneMatch(m -> m.contains("teardown did NOT complete")),
                "interrupted, JADE's kill carries on and the world IS torn down: " + errors);

        // The wreck is gone after all: the port was released and a new world is a working platform.
        boot.start(config);
        assertTrue(dfAnswers(boot, "df_probe_after_stuck_kill"), "the next platform's DF must answer");
    }

    // ---- staging --------------------------------------------------------------------------------

    /** Locks the DF's row in JADE's platform table from a thread that then ends: nobody can release it. */
    private static void lockTheDfRowForGood(JadeBootstrap boot) throws Exception {
        Object container = field(jade.wrapper.ContainerController.class, "myImpl").get(boot.getMainContainer());
        Class<?> containerClass = Class.forName("jade.core.AgentContainerImpl");
        Object table = field(Class.forName("jade.core.MainContainerImpl"), "platformAgents")
                .get(field(containerClass, "myMainContainer").get(container));
        Class<?> tableClass = Class.forName("jade.core.GADT");
        Method keys = tableClass.getDeclaredMethod("keys");
        Method acquire = tableClass.getDeclaredMethod("acquire", AID.class);
        keys.setAccessible(true);
        acquire.setAccessible(true);
        AID df = null;
        for (AID aid : (AID[]) keys.invoke(table)) {
            if ("df".equals(aid.getLocalName())) {
                df = aid;
            }
        }
        assertTrue(df != null, "fixture: the DF is in the platform table");
        AID target = df;
        CompletableFuture<Object> locked = new CompletableFuture<>();
        Thread thief = new Thread(() -> {
            try {
                locked.complete(acquire.invoke(table, target));
            } catch (ReflectiveOperationException e) {
                locked.completeExceptionally(e);
            }
        }, "row-lock-thief");
        thief.start();
        assertTrue(locked.get(10, TimeUnit.SECONDS) != null, "fixture: the row was found and locked");
        thief.join(10_000);
        assertFalse(thief.isAlive(), "fixture: the thread that holds the lock has ended");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static ListAppender<ILoggingEvent> capture(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).addAppender(appender);
        return appender;
    }

    private static boolean dfAnswers(JadeBootstrap boot, String probeName) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        boot.getSpawner().spawn(probeName, WorldRebuildStressIT.DfProbeAgent.class, new Object[] {answer});
        try {
            return answer.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
