package it.unige.portcommand.util;

/**
 * Waiting for a sim-time deadline with a timer that can only be armed in WALL time (S14).
 *
 * <p>A JADE waker is armed once, for a wall delay worked out from the pace in force at that
 * moment. That was harmless while the pace never changed. It is not any more: the player has a
 * speed control (&times;1 / &times;2 / &times;4), a 60-minute day makes a contracted vessel's
 * two-sim-hour approach a five-minute wall wait, and the player may now sit in a pause and type.
 * A waker armed for 300 s at &times;1 fires three sim-hours late after a switch to &times;4, and
 * one armed before a pause fires as if the pause had been game time.
 *
 * <p>So the wait is taken in chunks of at most {@link #CHUNK_WALL_MS}, and at each chunk
 * boundary the caller asks {@link #next()}:
 * <ul>
 *   <li>nothing disturbed the pace since arming ({@link SimClock#timingEpoch()} unchanged, clock
 *       not paused) &rarr; the wall budget simply runs down, chunk by chunk — <b>the total is
 *       exactly the single delay the old one-shot waker used</b>, so an unchanged pace behaves as
 *       before (including under a test clock nothing advances);</li>
 *   <li>the clock is paused short of the deadline &rarr; hold, {@link #HOLD_WALL_MS} at a time;</li>
 *   <li>the pace changed, or a pause came and went &rarr; the rest of the wait is re-derived from
 *       the sim-time distance still to go.</li>
 * </ul>
 *
 * <p>Pure: no JADE, no wall-clock read (the chunks are counted, not timed). Not thread-safe —
 * owned by the one behaviour that waits on it, on its agent's thread.
 */
public final class SimDeadlineWait {

    /** The longest single wall sleep: how late a pace change can be noticed. */
    public static final long CHUNK_WALL_MS = 1_000L;
    /** How often a wait held by a pause looks again — shorter than a chunk, because after a
     * resume game time is running and every millisecond of a stale hold is lateness. */
    public static final long HOLD_WALL_MS = 250L;

    private static final long MILLIS_PER_SECOND = 1_000L;
    /** Never a real {@link SimClock#timingEpoch()} value (those start at 0 and only grow). */
    private static final long NO_EPOCH = -1L;

    private final SimClock simClock;
    private long deadlineSimMillis;
    private long remainingWallMs;
    private long armedEpoch;

    public SimDeadlineWait(SimClock simClock) {
        this.simClock = simClock;
    }

    /**
     * Starts waiting for {@code deadlineSimMillis}.
     *
     * @return the first wall delay to sleep; {@code 0} when the deadline is already due
     */
    public long armUntil(long deadlineSimMillis) {
        this.deadlineSimMillis = deadlineSimMillis;
        // Epoch FIRST: a pace change landing between this read and the conversion below is then
        // caught by the next() comparison instead of being silently baked into the budget.
        long epoch = simClock.timingEpoch();
        // Armed WHILE PAUSED (every load and scenario start arms the Poisson gap under the
        // loader's pause): resume() does not move the epoch, so nothing would ever tell next()
        // that the first chunk below was slept through a standing clock. No epoch is ever
        // negative, so this forces the first un-paused next() to re-derive from sim time.
        this.armedEpoch = simClock.isPaused() ? NO_EPOCH : epoch;
        long remainingSim = deadlineSimMillis - simClock.nowSimMillis();
        // Floor division, exactly as the one-shot wakers computed their single delay.
        this.remainingWallMs = remainingSim <= 0 ? 0L
                : simClock.simSecondsToWallMs(remainingSim / MILLIS_PER_SECOND);
        return takeChunk();
    }

    /**
     * Called when the delay last returned has elapsed.
     *
     * @return the next wall delay to sleep; {@code 0} when the wait is over
     */
    public long next() {
        long remainingSim = deadlineSimMillis - simClock.nowSimMillis();
        if (remainingSim > 0 && simClock.isPaused()) {
            return HOLD_WALL_MS; // game time is standing still short of the deadline: hold
        }
        long epoch = simClock.timingEpoch();
        if (epoch != armedEpoch) {
            armedEpoch = epoch;
            // Round UP here: re-derived waits must not end a fraction of a sim-second early.
            remainingWallMs = remainingSim <= 0 ? 0L : Math.max(1L, simClock.simSecondsToWallMs(
                    (remainingSim + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND));
        }
        return takeChunk();
    }

    private long takeChunk() {
        long chunk = Math.min(remainingWallMs, CHUNK_WALL_MS);
        remainingWallMs -= chunk;
        return chunk;
    }
}
