package it.unige.portcommand.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Conversation time: the real seconds a walk-in negotiation has been running, minus the intervals
 * in which the game was paused or that conversation was frozen (S14, ADR-18).
 *
 * <p><b>Why this exists.</b> Negotiation patience used to be game time — sim-minutes on a clock
 * that runs a day in minutes, so a ferry gave up in 29-50 real seconds and a human could not type
 * and think. Patience is now the time the PLAYER experiences: real seconds, frozen while the game
 * is paused and while that tab's Hint is waiting for the model, and untouched by the speed control.
 * This is the one deliberate exception to "SimClock is the only game-time source": port events are
 * game time, a conversation with the player is conversation time.
 *
 * <p><b>Driven, like {@link SimClock} — and BY it.</b> It never reads a wall clock. Every game owns
 * one through {@link SimClock#conversationClock()}, and {@link SimClock#advance(long)} forwards
 * the real milliseconds it is given: real time enters the game in exactly one place, the
 * {@code WallClockAdvancer} stays the only wall-clock consumer, a paused game freezes patience
 * with no extra wiring (a paused {@code SimClock} forwards nothing), and the speed multiplier
 * never reaches this clock (it is applied to game time after the fork). Tests move it by hand —
 * directly, or through {@code SimClock.advance} — so nothing sleeps.
 *
 * <p><b>One conversation = one instance.</b> Ids recur ({@code nego-WALKIN-1} again after a load),
 * so the owner works through the {@link Conversation} handle {@link #open} returns; a handle whose
 * conversation was closed, replaced or swept by {@link #reset()} is inert. Reads go by id and say
 * "absent" for an unknown one — never "zero remaining", which would read as a timeout.
 *
 * <p>Thread-safe: one lock, no callbacks out of it. Touched by the vessel's agent thread (owner),
 * the HarbourMaster's (snapshot), the Assistant's (Hint freeze), the EDT (counter) and the advancer.
 */
public final class ConversationClock {

    /** How long a freeze holds without being released when its holder names no bound of its own
     * — and the least any holder gets. Counted in conversation-clock time. A holder that can
     * legitimately take longer (a Hint, under a raised {@code llm.timeout_ms}) passes its own
     * lease to {@link #freeze(String, long)}; a fixed number here would thaw a vessel while its
     * tab's Hint was still waiting (adversarial review, S14). */
    public static final long DEFAULT_FREEZE_LEASE_MILLIS = 150_000L;

    /** What the screen shows for one conversation. */
    public record View(long elapsedMillis, long remainingMillis) {
        /** The visible budget so far: what has run plus what is left (bonuses included). */
        public long totalMillis() {
            return elapsedMillis + remainingMillis;
        }

        public boolean expired() {
            return remainingMillis <= 0;
        }
    }

    /** The owner's handle to one conversation instance. */
    public final class Conversation {
        private final String id;
        private long elapsedMillis;
        private long remainingMillis;
        private int freezeHolds;
        private long leaseMillis;
        /** Bumped when the lease thaws the conversation, so a late release of a thawed hold
         * cannot cancel a hold taken afterwards. */
        private int freezeGeneration;

        private Conversation(String id, long budgetMillis) {
            this.id = id;
            this.remainingMillis = budgetMillis;
        }

        /** Adds {@code extraMillis} of patience, never beyond {@code capRemainingMillis} left. */
        public void extend(long extraMillis, long capRemainingMillis) {
            synchronized (ConversationClock.this) {
                if (isCurrent(this) && extraMillis > 0) {
                    // Never SHRINK: a remaining time already above the cap (a long base budget)
                    // simply gains nothing.
                    long capped = Math.min(remainingMillis + extraMillis, capRemainingMillis);
                    remainingMillis = Math.max(remainingMillis, capped);
                }
            }
        }

        /** This conversation's own view; empty once it is closed, replaced or reset away. */
        public Optional<View> view() {
            synchronized (ConversationClock.this) {
                return isCurrent(this) ? Optional.of(new View(elapsedMillis, remainingMillis)) : Optional.empty();
            }
        }

        /** Ends the conversation. Idempotent; never touches a newer conversation with the same id. */
        public void close() {
            synchronized (ConversationClock.this) {
                conversations.remove(id, this);
            }
        }
    }

    private final Map<String, Conversation> conversations = new HashMap<>();
    private final long freezeLeaseMillis;

    public ConversationClock() {
        this(DEFAULT_FREEZE_LEASE_MILLIS);
    }

    /** Test seam: a short lease, so the thaw-on-abandoned-freeze path is cheap to exercise. */
    ConversationClock(long freezeLeaseMillis) {
        this.freezeLeaseMillis = freezeLeaseMillis;
    }

    /**
     * Credits {@code realMillis} of real time to every open conversation that is not frozen.
     * Called by {@link SimClock#advance(long)} — which is what makes a paused game a no-op — and
     * directly by tests.
     */
    public synchronized void advance(long realMillis) {
        if (realMillis <= 0) {
            return;
        }
        for (Conversation c : conversations.values()) {
            if (c.freezeHolds > 0) {
                c.leaseMillis -= realMillis;
                if (c.leaseMillis <= 0) {
                    c.freezeHolds = 0; // the holder never came back — thaw rather than wait forever
                    c.freezeGeneration++;
                }
                continue;
            }
            c.elapsedMillis += realMillis;
            c.remainingMillis = Math.max(0L, c.remainingMillis - realMillis);
        }
    }

    /** Starts a conversation with {@code budgetMillis} of patience, replacing any with that id. */
    public synchronized Conversation open(String conversationId, long budgetMillis) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (budgetMillis <= 0) {
            throw new IllegalArgumentException("budgetMillis must be > 0, got " + budgetMillis);
        }
        Conversation conversation = new Conversation(conversationId, budgetMillis);
        conversations.put(conversationId, conversation);
        return conversation;
    }

    /** The conversation's elapsed and remaining time, or empty if there is none with that id. */
    public synchronized Optional<View> view(String conversationId) {
        Conversation c = conversations.get(conversationId);
        return c == null ? Optional.empty() : Optional.of(new View(c.elapsedMillis, c.remainingMillis));
    }

    /** {@link #freeze(String, long)} with this clock's default lease. */
    public synchronized Runnable freeze(String conversationId) {
        return freeze(conversationId, freezeLeaseMillis);
    }

    /**
     * Stops that conversation's time until the returned handle is run (a pending Hint). Holds
     * nest; each handle releases once, only its own conversation instance, and does nothing if
     * that instance is gone or the lease already thawed it. Unknown id: an inert handle.
     *
     * @param leaseMillis how long this hold may last unreleased before the conversation thaws on
     *                    its own — the holder's own worst case plus a margin. A nested hold never
     *                    shortens the lease a longer one already has left.
     */
    public synchronized Runnable freeze(String conversationId, long leaseMillis) {
        Conversation c = conversations.get(conversationId);
        if (c == null) {
            return () -> { };
        }
        c.leaseMillis = c.freezeHolds == 0 ? leaseMillis : Math.max(c.leaseMillis, leaseMillis);
        c.freezeHolds++;
        int generation = c.freezeGeneration;
        boolean[] released = {false};
        return () -> {
            synchronized (ConversationClock.this) {
                if (!released[0]) {
                    released[0] = true;
                    if (isCurrent(c) && c.freezeGeneration == generation && c.freezeHolds > 0) {
                        c.freezeHolds--;
                    }
                }
            }
        };
    }

    /** World swap (load, new game): open negotiations are never saved, so every conversation goes. */
    public synchronized void reset() {
        conversations.clear();
    }

    private boolean isCurrent(Conversation c) {
        return conversations.get(c.id) == c;
    }
}
