package it.unige.portcommand.bootstrap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import jade.core.AID;
import jade.core.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defect {@link JadeTableGuard} closes, and the guard, on JADE 4.6.0's own table classes with
 * no platform running (2026-10-13; ADR-15 addendum).
 *
 * <p>What is staged is what a new world does. Some agents exist; messages for them are being
 * delivered - on a Main Container every delivery locks the receiver's row in the platform table
 * and releases it again; and more agents are born, which adds rows until the table's
 * {@code HashMap} grows. The tables are driven through their real methods by reflection (the
 * classes are package-private to JADE), one table per round, a few hundred rounds.
 *
 * <p>Every wait has a deadline; nothing sleeps.
 */
class JadeTableGuardTest {

    private static final int ROUNDS = 400;
    private static final int EXISTING = 8;
    private static final int BORN = 10; // 8 -> 18 rows: the 13th makes a default HashMap grow
    private static final int READERS = 3;

    /** One of JADE's two tables behind the four methods both of them have. */
    private record Table(Class<?> type, Constructor<?> constructor, Object[] constructorArgs, Method put,
                         Method acquire, Method release, Field agents, Field rowOwner, Class<?> valueType) {

        static Table platform() throws Exception {
            Class<?> type = Class.forName("jade.core.GADT");
            Class<?> value = Class.forName("jade.core.AgentDescriptor");
            return of(type, type.getDeclaredConstructor(), new Object[0], value);
        }

        static Table local() throws Exception {
            Class<?> type = Class.forName("jade.core.LADT");
            return of(type, type.getDeclaredConstructor(int.class), new Object[] {16}, Agent.class);
        }

        private static Table of(Class<?> type, Constructor<?> constructor, Object[] args, Class<?> value)
                throws Exception {
            constructor.setAccessible(true);
            Method put = type.getDeclaredMethod("put", AID.class, value);
            Method acquire = type.getDeclaredMethod("acquire", AID.class);
            Method release = type.getDeclaredMethod("release", AID.class);
            Field agents = type.getDeclaredField("agents");
            Field owner = Class.forName(type.getName() + "$Row").getDeclaredField("owner");
            for (java.lang.reflect.AccessibleObject o : new java.lang.reflect.AccessibleObject[] {
                    put, acquire, release, agents, owner}) {
                o.setAccessible(true);
            }
            return new Table(type, constructor, args, put, acquire, release, agents, owner, value);
        }

        Object newValue() throws Exception {
            if (valueType == Agent.class) {
                return new Agent();
            }
            Constructor<?> c = valueType.getDeclaredConstructor(boolean.class);
            c.setAccessible(true);
            return c.newInstance(true);
        }
    }

    /** What the rounds found. */
    private record Outcome(int tablesWithALeakedLock, long lookupsThatMissedAnExistingAgent, String example) {
    }

    /**
     * Runs the staging {@code rounds} times, each on a fresh table whose map went through
     * {@code mapOf} (identity = the table as JADE ships it), stopping at the first leak when
     * {@code stopAtFirstLeak}.
     */
    private static Outcome race(Table table, UnaryOperator<jade.util.leap.Map> mapOf, int rounds,
                                boolean stopAtFirstLeak) throws Exception {
        int leakedTables = 0;
        long missed = 0;
        String example = "";
        for (int round = 0; round < rounds; round++) {
            Object instance = table.constructor().newInstance(table.constructorArgs());
            table.agents().set(instance, mapOf.apply((jade.util.leap.Map) table.agents().get(instance)));
            AID[] existing = new AID[EXISTING];
            for (int i = 0; i < EXISTING; i++) {
                existing[i] = new AID("existing" + i + "@platform", AID.ISGUID);
                table.put().invoke(instance, existing[i], table.newValue());
            }
            AtomicBoolean run = new AtomicBoolean(true);
            AtomicLong misses = new AtomicLong();
            CountDownLatch delivering = new CountDownLatch(READERS);
            Thread[] deliverers = new Thread[READERS];
            for (int t = 0; t < READERS; t++) {
                AID receiver = existing[t];
                deliverers[t] = new Thread(() -> {
                    try {
                        boolean announced = false;
                        while (run.get()) {
                            // what jade.core.MainContainerImpl.getContainerID does for every delivery
                            if (table.acquire().invoke(instance, receiver) == null) {
                                misses.incrementAndGet(); // "no such agent" - although she is there
                            } else {
                                table.release().invoke(instance, receiver);
                            }
                            if (!announced) {
                                announced = true;
                                delivering.countDown();
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }, "deliverer-" + t);
                deliverers[t].setDaemon(true);
                deliverers[t].start();
            }
            assertTrue(delivering.await(10, TimeUnit.SECONDS), "fixture: the deliverers are running");
            for (int i = 0; i < BORN; i++) { // the fleet is spawned: the table grows
                table.put().invoke(instance, new AID("born" + i + "@platform", AID.ISGUID), table.newValue());
            }
            run.set(false);
            for (Thread deliverer : deliverers) {
                deliverer.join(10_000);
                assertFalse(deliverer.isAlive(), "fixture: a deliverer did not stop");
            }
            // Everybody has stopped. A row that still has an owner will never be released.
            jade.util.leap.Map agents = (jade.util.leap.Map) table.agents().get(instance);
            boolean leaked = false;
            for (AID aid : existing) {
                Object row = agents.get(aid);
                Thread owner = row == null ? null : (Thread) table.rowOwner().get(row);
                if (owner != null) {
                    leaked = true;
                    example = "the row of " + aid.getLocalName() + " is still locked by " + owner.getName()
                            + ", a thread that has ended";
                }
            }
            missed += misses.get();
            if (leaked) {
                leakedTables++;
                if (stopAtFirstLeak) {
                    break;
                }
            }
        }
        return new Outcome(leakedTables, missed, example);
    }

    // ---- the defect -----------------------------------------------------------------------------

    /**
     * JADE's platform table, as shipped. This is the bug that hung the integration lane: after
     * such a round the next thread that needs the row - a delivery, the agent's own end, the
     * container's shutdown - waits for ever. If this test ever fails, JADE has been fixed (or
     * replaced) and {@link JadeTableGuard} can go.
     */
    @Test
    @Timeout(120)
    void jadesPlatformTableLeavesARowLockedWhenItGrowsWhileRowsAreLookedUp() throws Exception {
        Outcome outcome = race(Table.platform(), map -> map, 2_000, true);

        assertTrue(outcome.tablesWithALeakedLock() > 0, "expected the JADE 4.6.0 defect to show within 2000 "
                + "rounds (measured: about one round in five): a release() that cannot find the row it locked");
    }

    // ---- the guard ------------------------------------------------------------------------------

    @Test
    @Timeout(120)
    void theGuardedPlatformTableNeverLeavesARowLockedAndNeverLosesAnAgent() throws Exception {
        Outcome outcome = race(Table.platform(), JadeTableGuard::guarded, ROUNDS, false);

        assertEquals(0, outcome.tablesWithALeakedLock(), outcome.example());
        assertEquals(0, outcome.lookupsThatMissedAnExistingAgent(),
                "a lookup must never come back empty for an agent that exists");
    }

    @Test
    @Timeout(120)
    void theGuardedLocalTableNeverLeavesARowLockedAndNeverLosesAnAgent() throws Exception {
        Outcome outcome = race(Table.local(), JadeTableGuard::guarded, ROUNDS, false);

        assertEquals(0, outcome.tablesWithALeakedLock(), outcome.example());
        assertEquals(0, outcome.lookupsThatMissedAnExistingAgent());
    }

    @Test
    void theGuardIsAViewOfTheSameMapNotACopy() {
        jade.util.leap.Map original = new jade.util.leap.HashMap();
        original.put("a", 1);
        jade.util.leap.Map guarded = JadeTableGuard.guarded(original);

        assertEquals(1, guarded.get("a"), "what was there is there");
        guarded.put("b", 2);
        assertEquals(2, original.get("b"), "a write through the guard lands in JADE's own map");
        original.put("c", 3);
        assertEquals(3, guarded.get("c"), "and a write that went past the guard is not lost to it");
        assertEquals(3, guarded.size());
        assertTrue(guarded.containsKey("c"));
        assertEquals(3, guarded.remove("c"));
        assertFalse(original.containsKey("c"));
        assertEquals(2, guarded.keySet().size());
        assertEquals(2, guarded.values().size());
        assertFalse(guarded.isEmpty());
        guarded.clear();
        assertTrue(original.isEmpty());
        assertSame(JadeTableGuard.SynchronizedLeapMap.class, guarded.getClass());
    }
}
