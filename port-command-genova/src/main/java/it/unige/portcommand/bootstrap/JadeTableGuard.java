package it.unige.portcommand.bootstrap;

import java.lang.reflect.Field;

import jade.wrapper.ContainerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes a thread-safety hole in JADE 4.6.0's two agent tables, at the one moment a platform is
 * built (2026-10-13, the "DF is not answering" gate hang; ADR-15 addendum).
 *
 * <p><b>The hole.</b> {@code jade.core.GADT} (the platform's agent table, one per Main Container)
 * and {@code jade.core.LADT} (the container's own) each keep their rows in a
 * {@code jade.util.leap.HashMap} — a plain, unsynchronized {@code java.util.HashMap} underneath.
 * Every read of the map is wrapped in {@code synchronized (agents)}; the writes are not:
 * {@code agents.put(...)} when an agent is born and {@code agents.remove(...)} when one dies run
 * with no lock at all. So a lookup can run while the map is being written — and when the write
 * makes the map grow, the lookup can come back empty for an agent that is there.
 *
 * <p>The lookup that matters is the one in {@code release}. JADE's message delivery on a Main
 * Container locks the receiver's row ({@code acquire}), reads which container she lives in, and
 * unlocks it ({@code release}) — and {@code release} finds the row by looking it up again. If
 * that second lookup misses, nothing is unlocked: the row stays locked by a delivery thread that
 * believes it let go. The next thread that needs the row — another delivery thread with the next
 * message for that agent, the agent's own termination, the container's shutdown — waits on it
 * for ever.
 *
 * <p><b>Why it showed up here, and as "the DF is not answering".</b> A Java {@code HashMap} grows
 * when its thirteenth entry is added. A new world is the AMS, the DF, the bootstrap probe and ten
 * fixed agents — thirteen — spawned in one burst while the first of them are already registering
 * with the DF. The thirteenth, {@code tug_4}, makes the platform table grow; a delivery to the DF
 * in that instant can leave the DF's row locked. From then on every message to the DF queues
 * behind a delivery thread that never returns: registrations time out, lookups time out, every
 * agent's deregistration at teardown blocks, and JADE's own {@code kill()} blocks on the same row.
 * It was rare (about one world in a hundred), it needed a warm JVM (on a cold one the births are
 * spaced out by class loading), and so it looked like a slow DF "under load" for three months
 * (the 2026-07-18 note in INVARIANTS) before it hung the integration lane twice in one day.
 * Measured on JADE's own classes, with nothing of this project in the way: with lookups running
 * while ten rows are added to a table of eight, 587 of 3000 tables ended with a row locked for
 * good; with the guard below, 0 of 3000 ({@code JadeTableGuardTest} keeps both halves).
 *
 * <p><b>The guard.</b> The reads already take the map's own monitor. So the map each table holds
 * is replaced by a wrapper around that same map whose every method takes that monitor too: a
 * write can no longer run under a read. Nothing is copied — the wrapper delegates to the map JADE
 * made — and nothing else in JADE holds the map. It is installed by {@link JadeBootstrap#start}
 * right after the Main Container exists and before a single agent of ours is spawned.
 *
 * <p>This reaches into two private fields of a third-party library, which is not something to
 * do lightly. The alternatives were worse: the stack is locked at JADE 4.6.0 and its jar is not
 * ours to patch, and spawning the agents "more carefully" cannot make a race impossible, only
 * rarer. If a future JADE no longer has these fields, {@link #install} says so and the boot
 * fails at once — loudly, at start-up, in every test — instead of running unguarded.
 */
public final class JadeTableGuard {

    private static final Logger log = LoggerFactory.getLogger(JadeTableGuard.class);

    private JadeTableGuard() {
    }

    /**
     * Guards the platform table and the local table of {@code mainContainer}. Safe to call twice.
     *
     * @throws IllegalStateException if JADE's internals are not the ones this was written for
     */
    public static void install(ContainerController mainContainer) {
        try {
            Object container = field(ContainerController.class, "myImpl").get(mainContainer);
            Class<?> containerClass = Class.forName("jade.core.AgentContainerImpl");
            Object platformTable = field(Class.forName("jade.core.MainContainerImpl"), "platformAgents")
                    .get(field(containerClass, "myMainContainer").get(container));
            Object localTable = field(containerClass, "localAgents").get(container);
            guard(platformTable, "jade.core.GADT");
            guard(localTable, "jade.core.LADT");
            log.debug("JADE agent tables guarded (platform + local)");
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("JADE's agent tables could not be guarded against their "
                    + "unsynchronized writes (expected JADE 4.6.0 internals: jade.core.GADT.agents and "
                    + "jade.core.LADT.agents) - refusing to run a platform that can lock itself up", e);
        }
    }

    /** Whether both tables of {@code mainContainer} hold the guarded map. Test seam. */
    public static boolean isInstalled(ContainerController mainContainer) {
        try {
            Object container = field(ContainerController.class, "myImpl").get(mainContainer);
            Class<?> containerClass = Class.forName("jade.core.AgentContainerImpl");
            Object platformTable = field(Class.forName("jade.core.MainContainerImpl"), "platformAgents")
                    .get(field(containerClass, "myMainContainer").get(container));
            Object localTable = field(containerClass, "localAgents").get(container);
            return field(Class.forName("jade.core.GADT"), "agents").get(platformTable) instanceof SynchronizedLeapMap
                    && field(Class.forName("jade.core.LADT"), "agents").get(localTable) instanceof SynchronizedLeapMap;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void guard(Object table, String tableClassName) throws ReflectiveOperationException {
        if (table == null) {
            throw new IllegalStateException(tableClassName + " table is missing");
        }
        Field agents = field(Class.forName(tableClassName), "agents");
        jade.util.leap.Map original = (jade.util.leap.Map) agents.get(table);
        if (original instanceof SynchronizedLeapMap) {
            return;
        }
        // Under the monitor the table's readers take: no lookup is in flight while the field
        // changes hands. (A writer already past its own field read lands in the same storage -
        // the wrapper delegates to this very map - so nothing can be lost either way.)
        synchronized (original) {
            agents.set(table, guarded(original));
        }
    }

    /** The guarded view of {@code map}: the same storage, every method under the view's own monitor. */
    static jade.util.leap.Map guarded(jade.util.leap.Map map) {
        return new SynchronizedLeapMap(map);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * A {@code jade.util.leap.Map} whose every method is {@code synchronized} on itself. JADE's
     * tables synchronize their lookups on the map object they hold; once that object is this one,
     * its writes and its lookups exclude each other.
     */
    static final class SynchronizedLeapMap implements jade.util.leap.Map {

        private final jade.util.leap.Map delegate;

        SynchronizedLeapMap(jade.util.leap.Map delegate) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public synchronized void clear() {
            delegate.clear();
        }

        @Override
        public synchronized boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public synchronized Object get(Object key) {
            return delegate.get(key);
        }

        @Override
        public synchronized boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public synchronized jade.util.leap.Set keySet() {
            return delegate.keySet();
        }

        @Override
        public synchronized Object put(Object key, Object value) {
            return delegate.put(key, value);
        }

        @Override
        public synchronized Object remove(Object key) {
            return delegate.remove(key);
        }

        @Override
        public synchronized int size() {
            return delegate.size();
        }

        @Override
        public synchronized jade.util.leap.Collection values() {
            return delegate.values();
        }
    }
}
