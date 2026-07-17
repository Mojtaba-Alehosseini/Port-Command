package it.unige.portcommand.harbourmaster.financial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import jade.lang.acl.ACLMessage;

/**
 * Counts FIPA performatives seen today — the academic centrepiece of the end-of-day summary
 * ("every speech act in active use", rendered as a literal report).
 *
 * <p>Reads the RAW {@link CommLogEvent} stream, never {@code CommLogModel}'s 500-entry display
 * view (INVARIANTS, task 18 — that view evicts). Counts <strong>all 10</strong> canonical
 * performatives independently and always reports all 10, including the zeroes: a calm tutorial
 * day exercises 8 of them, and showing {@code CANCEL: 0} is the point — the missing two are a
 * scenario fact (CANCEL in Storm, DISCONFIRM in Busy-Day), not an absence of instrumentation.
 * "All 10" is a whole-project claim across the three scenarios (task 26), never a single day.
 *
 * <p>Scope caveat worth knowing at the oral: comm-log instrumentation today covers only the
 * HarbourMaster's own send/receive path (INVARIANTS, task 17), so these are the performatives the
 * HM saw — not every message on the platform. A tug's outbound send to another agent is invisible.
 *
 * <h2>Threading</h2>
 * Subscribes with {@link DeliveryMode#CALLER_THREAD}: {@code ASYNC}'s bounded queue drops the
 * oldest event when full, and a dropped {@code CommLogEvent} is a silently wrong tally. The
 * handler is an {@link AtomicInteger} increment on a {@link ConcurrentHashMap} — nanoseconds — so
 * the cost of blocking the publishing agent thread is negligible and bounded.
 *
 * <p>The daily reset swaps in a fresh map under a <strong>write lock</strong>, while increments
 * hold the <strong>read lock</strong> — which is what makes the midnight boundary exact under
 * concurrent publishing (planning/20's flagged risk). A bare atomic swap is NOT enough and was
 * measurably wrong: an incrementer reads the map reference, then increments, and a swap landing
 * between those two steps sends the increment into the map that was just rendered and discarded.
 * A concurrency test lost 17 of 4000 messages that way before the lock went in. The read lock is
 * shared, so increments still run concurrently with each other; it only excludes the swap.
 *
 * <p>Task 24 should use {@code resetForNewDay()}'s RETURN as the summary's counts — one atomic
 * operation. Calling {@link #snapshot()} and then {@code resetForNewDay()} leaves a window in
 * which an arriving message is counted into neither day.
 */
public final class PerformativeCounter {

    /**
     * The 10 canonical FIPA performatives in {@code planning/20}'s enumeration order — which is
     * also the EOD report's display order. Duplicated from {@code commlog.PerformativeColours}'s
     * key set on purpose: that class imports {@code java.awt.Color}, and nothing under
     * {@code harbourmaster/} may import AWT/Swing (INVARIANTS, task 17). The two lists are pinned
     * in agreement by {@code PerformativeCounterTest}.
     *
     * <p>Note the DISPLAY names come from {@code ACLMessage.getPerformative(int)}, which returns
     * the real FIPA spelling — {@code ACCEPT-PROPOSAL}, hyphenated. planning/20 and the demo
     * transcript both write {@code ACCEPT_PROPOSAL}, but that is the Java CONSTANT's name, not the
     * performative's; verified against the JADE jar rather than copied from the doc. Read the
     * names from {@link #canonicalNames()} instead of hand-typing them.
     */
    private static final List<Integer> CANONICAL_ORDER = List.of(
            ACLMessage.REQUEST,
            ACLMessage.INFORM,
            ACLMessage.PROPOSE,
            ACLMessage.ACCEPT_PROPOSAL,
            ACLMessage.REJECT_PROPOSAL,
            ACLMessage.REFUSE,
            ACLMessage.CFP,
            ACLMessage.CANCEL,
            ACLMessage.CONFIRM,
            ACLMessage.DISCONFIRM);

    private final AtomicReference<ConcurrentHashMap<Integer, AtomicInteger>> counts =
            new AtomicReference<>(new ConcurrentHashMap<>());
    /** Increments take {@code readLock} (shared); the daily swap takes {@code writeLock}. */
    private final ReadWriteLock dayBoundary = new ReentrantReadWriteLock();
    private final Subscription<CommLogEvent> subscription;

    public PerformativeCounter(EventBus eventBus) {
        this.subscription = eventBus.subscribe(CommLogEvent.class, this::onCommLog, DeliveryMode.CALLER_THREAD);
    }

    /** Public so a test can drive it directly, mirroring the task-10 subscribe-once pattern. */
    public void onCommLog(CommLogEvent event) {
        dayBoundary.readLock().lock();
        try {
            counts.get().computeIfAbsent(event.performative(), k -> new AtomicInteger()).incrementAndGet();
        } finally {
            dayBoundary.readLock().unlock();
        }
    }

    /** The 10 canonical performative display names, in report order — the single source. */
    public static List<String> canonicalNames() {
        return CANONICAL_ORDER.stream().map(ACLMessage::getPerformative).toList();
    }

    /**
     * Today's counts so far: all 10 canonical performatives, in canonical order, zeroes included.
     * A non-canonical performative that was really sent (e.g. {@code QUERY_REF}, which the
     * player's ASK command genuinely produces) is counted internally but deliberately NOT
     * reported — the EOD table is the FIPA-10 claim, and widening it would muddy that.
     */
    public Map<String, Integer> snapshot() {
        return render(counts.get());
    }

    /**
     * Atomically ends the day: swaps in fresh counters and returns the day's final counts in the
     * same shape as {@link #snapshot()}. Prefer this over snapshot-then-reset (see class javadoc).
     */
    public Map<String, Integer> resetForNewDay() {
        dayBoundary.writeLock().lock();
        try {
            return render(counts.getAndSet(new ConcurrentHashMap<>()));
        } finally {
            dayBoundary.writeLock().unlock();
        }
    }

    private static Map<String, Integer> render(Map<Integer, AtomicInteger> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int performative : CANONICAL_ORDER) {
            AtomicInteger counter = raw.get(performative);
            out.put(ACLMessage.getPerformative(performative), counter == null ? 0 : counter.get());
        }
        // LinkedHashMap + unmodifiableMap, never Map.copyOf — INVARIANTS' determinism rule
        // (Map.copyOf randomises iteration order per JVM launch, so the report's column order
        // would change between runs).
        return Collections.unmodifiableMap(out);
    }

    /**
     * Seeds today's counts from a save file (task 22 load), keyed by the canonical display
     * names {@link #snapshot()} renders. Unknown names are ignored; non-canonical performatives
     * counted-but-not-reported before the save (e.g. QUERY_REF) are not round-tripped — they
     * never appear in the EOD table, so nothing observable changes (dated note, planning/22).
     */
    public void restore(Map<String, Integer> byCanonicalName) {
        dayBoundary.writeLock().lock();
        try {
            ConcurrentHashMap<Integer, AtomicInteger> fresh = new ConcurrentHashMap<>();
            for (int performative : CANONICAL_ORDER) {
                Integer count = byCanonicalName.get(ACLMessage.getPerformative(performative));
                if (count != null && count > 0) {
                    fresh.put(performative, new AtomicInteger(count));
                }
            }
            counts.set(fresh);
        } finally {
            dayBoundary.writeLock().unlock();
        }
    }

    /** Stops counting. For symmetry with the GUI panels' lifecycle; production never cancels. */
    public void close() {
        subscription.cancel();
    }
}
