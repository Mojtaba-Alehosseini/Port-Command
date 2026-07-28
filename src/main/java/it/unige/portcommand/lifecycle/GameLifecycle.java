package it.unige.portcommand.lifecycle;

import it.unige.portcommand.lifecycle.events.GamePausedEvent;
import it.unige.portcommand.lifecycle.events.GameResumedEvent;
import it.unige.portcommand.lifecycle.events.QuitEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The master game-loop orchestrator (task 24): owns the {@link GameMode} state
 * machine and the {@link WallClockAdvancer}, and is the only mover of both.
 *
 * <p><b>Not a singleton.</b> planning/24 sketched a static
 * {@code getInstance()}; that contradicts the repo-wide constructor-DI rule
 * (ADR-02 — no static registries), so {@code Main} constructs one instance and
 * threads it to the GUI shell, exactly like {@code EventBus}/{@code SimClock}
 * (decision recorded in ADR-14). Likewise the sketched
 * {@code GameLifecycleListener} registry is dropped: the {@link EventBus}
 * already IS the observer mechanism, so transitions publish
 * {@link GamePausedEvent}/{@link GameResumedEvent} (and {@code GameOverGuard}
 * publishes {@code GameOverEvent}) instead of calling a second listener list.
 *
 * <p><b>Pause propagation.</b> {@code pause()} pauses the {@link SimClock};
 * every agent-side periodic behaviour is either driven by elapsed sim time
 * (movement, cargo, EOD detection — frozen time means nothing happens) or
 * explicitly gates on {@code simClock().isPaused()} (Poisson spawner, weather).
 * No agent holds a reference to this class — the clock's paused flag, already
 * injected everywhere, is the single mechanical gate (ADR-14).
 *
 * <p><b>Quit.</b> {@link #quit()} only publishes {@link QuitEvent};
 * {@code GameOverGuard} converts it to {@code GameOverEvent("quit")} and calls
 * {@link #gameOver()} — the guard stays the single emitter of
 * {@code GameOverEvent} AND the single caller of the GAME_OVER transition, so
 * bankruptcy/day-cap/quit cannot double-fire against each other.
 *
 * <p>Thread-safety: all mutators are {@code synchronized}; {@link #mode()} is
 * a volatile read. Mutators are called from the EDT (menu/Esc) and the JADE HM
 * thread (guard) — both are quick, non-blocking calls.
 */
public final class GameLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GameLifecycle.class);

    private final SimClock simClock;
    private final EventBus eventBus;
    private final WallClockAdvancer advancer;
    private volatile GameMode mode = GameMode.MENU;

    public GameLifecycle(SimClock simClock, EventBus eventBus) {
        this(simClock, eventBus, new WallClockAdvancer(simClock));
    }

    /** Test seam: inject a short-period advancer (or a stopped one). */
    GameLifecycle(SimClock simClock, EventBus eventBus, WallClockAdvancer advancer) {
        this.simClock = simClock;
        this.eventBus = eventBus;
        this.advancer = advancer;
    }

    /**
     * MENU → LOADING → RUNNING and start of live time. Today {@code Main} calls this right
     * after the agent roster spawns — there is no menu screen yet (tasks 21/23 own the real
     * MENU/LOADING content; the LOADING→MENU failed-load edge becomes reachable with task 22).
     */
    public synchronized void startNewGame() {
        transition(GameMode.LOADING);
        transition(GameMode.RUNNING);
        simClock.resume();
        advancer.start();
    }

    public synchronized void pause() {
        transition(GameMode.PAUSED);
        simClock.pause();
        eventBus.publish(new GamePausedEvent());
    }

    public synchronized void resume() {
        transition(GameMode.RUNNING);
        simClock.resume();
        eventBus.publish(new GameResumedEvent());
    }

    /** Publishes {@link QuitEvent}; the GAME_OVER transition itself is {@code GameOverGuard}'s. */
    public synchronized void quit() {
        eventBus.publish(new QuitEvent());
    }

    /**
     * Enters LOADING for a save-file load (task 22): freezes the clock and holds the mode
     * while the world is torn down and rebuilt. Legal from RUNNING, PAUSED and GAME_OVER
     * (the load-after-game-over "continue from the autosave" path). The advancer keeps
     * ticking the paused clock harmlessly — {@code SimClock.advance} is a no-op while paused.
     */
    public synchronized void beginLoading() {
        transition(GameMode.LOADING);
        simClock.pause();
    }

    /** LOADING → RUNNING once the rebuilt world is ready: clock resumes, advancer ensured live. */
    public synchronized void completeLoading() {
        transition(GameMode.RUNNING);
        simClock.resume();
        advancer.start(); // idempotent; also revives the post-game-over path (gameOver() stopped it)
        eventBus.publish(new GameResumedEvent());
    }

    /** LOADING → MENU on a failed load: the old world is gone, nothing playable is up. */
    public synchronized void failLoading() {
        transition(GameMode.MENU);
        simClock.pause();
        advancer.stop();
    }

    /**
     * {@link #pause()} if RUNNING, no-op otherwise; returns whether a pause happened. For
     * callers reacting to events that may race a game-over (the end-of-day dialog): a plain
     * {@code pause()} would throw from GAME_OVER.
     */
    public synchronized boolean pauseIfRunning() {
        if (mode != GameMode.RUNNING) {
            return false;
        }
        pause();
        return true;
    }

    /** {@link #resume()} if PAUSED, no-op otherwise (e.g. the run ended while paused). */
    public synchronized void resumeIfPaused() {
        if (mode == GameMode.PAUSED) {
            resume();
        }
    }

    /**
     * RUNNING|PAUSED → GAME_OVER: freezes the clock and stops the advancer. Called only by
     * {@code GameOverGuard} (single game-over authority); publishes nothing itself — the
     * guard's {@code GameOverEvent} is the signal, and it carries the final stats.
     */
    public synchronized void gameOver() {
        transition(GameMode.GAME_OVER);
        simClock.pause();
        advancer.stop();
    }

    public GameMode mode() {
        return mode;
    }

    public boolean isRunning() {
        return mode == GameMode.RUNNING;
    }

    /** The shared game clock — read-only convenience for the GUI shell (countdowns, day/time). */
    public SimClock simClock() {
        return simClock;
    }

    private void transition(GameMode to) {
        GameMode from = mode;
        if (!GameMode.canTransition(from, to)) {
            throw new IllegalStateException("illegal game-mode transition " + from + " -> " + to);
        }
        mode = to;
        log.info("game mode {} -> {}", from, to);
    }
}
