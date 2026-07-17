package it.unige.portcommand.lifecycle;

import java.time.LocalDate;
import java.util.List;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.DayRolloverEvent;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import it.unige.portcommand.lifecycle.events.QuitEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single game-over authority (task 24): watches the bus for the three
 * ending conditions and, on the FIRST one, moves {@link GameLifecycle} to
 * GAME_OVER, records the run on the {@link Leaderboard}, and publishes the one
 * {@link GameOverEvent} of the run.
 *
 * <p><b>Triggers</b> (ADR-14):
 * <ul>
 *   <li><b>bankrupt</b> — the settled wallet is below
 *       {@code Settings.bankruptcyFloorEur} (−€20,000 canonical) at end-of-day
 *       for {@value #CONSECUTIVE_BANKRUPT_DAYS} CONSECUTIVE days (session brief
 *       2026-07-17; supersedes planning/24's any-negative-EOD rule). A single
 *       recovered day resets the streak. Mid-day debt of any depth never ends
 *       the game — the HUD just shows the negative balance.</li>
 *   <li><b>day_cap</b> — the day counter advances past
 *       {@code Settings.maxGameDays} (default 30, the demo soft cap).</li>
 *   <li><b>quit</b> — {@link QuitEvent} from the menu (via
 *       {@code GameLifecycle.quit()}), already player-confirmed by the GUI.</li>
 * </ul>
 *
 * <p>Constructed by {@code Main} (not the HarbourMaster): every input arrives
 * on the bus — the settled summaries carry the EOD figures, and the ledgers
 * publish ABSOLUTE wallet/reputation on every mutation, which this guard
 * tracks so a quit mid-day reports true current stats. All subscriptions are
 * CALLER_THREAD: the EOD-path events arrive on the HarbourMaster thread inside
 * the coordinator's publish (so a game-over lands before the next behaviour
 * step), quit arrives on the EDT, and the wallet/reputation handlers are plain
 * field writes — safely prompt inside the ledgers' write lock (INVARIANTS
 * "ledgers publish INSIDE their write lock").
 */
public final class GameOverGuard {

    static final int CONSECUTIVE_BANKRUPT_DAYS = 3;

    private static final Logger log = LoggerFactory.getLogger(GameOverGuard.class);

    private final EventBus eventBus;
    private final GameLifecycle lifecycle;
    private final Leaderboard leaderboard;
    private final List<Subscription<?>> subscriptions;

    private volatile double lastKnownWallet;
    private volatile double lastKnownReputation;
    private volatile int lastKnownDay = 1;
    private int consecutiveDeepDebtDays;
    /** The last deep-debt day NUMBER — "consecutive" means adjacent game days, not merely
     * successive events (adversarial review n1: a skipped day restarts the streak). */
    private int lastDeepDebtDay;
    private boolean gameOverFired;

    /**
     * @param startingWallet     fallback stats for a quit before the first money event —
     * @param startingReputation seeded from {@code AgentRoster}'s canonical starting figures
     */
    public GameOverGuard(EventBus eventBus, GameLifecycle lifecycle, Leaderboard leaderboard,
                         double startingWallet, double startingReputation) {
        this.eventBus = eventBus;
        this.lifecycle = lifecycle;
        this.leaderboard = leaderboard;
        this.lastKnownWallet = startingWallet;
        this.lastKnownReputation = startingReputation;
        this.subscriptions = List.of(
                eventBus.subscribe(EndOfDayCompletedEvent.class, this::onEndOfDayCompleted,
                        DeliveryMode.CALLER_THREAD),
                eventBus.subscribe(DayRolloverEvent.class, this::onDayRollover, DeliveryMode.CALLER_THREAD),
                eventBus.subscribe(QuitEvent.class, this::onQuit, DeliveryMode.CALLER_THREAD),
                eventBus.subscribe(WalletChangedEvent.class, e -> lastKnownWallet = e.balance(),
                        DeliveryMode.CALLER_THREAD),
                eventBus.subscribe(ReputationChangedEvent.class, e -> lastKnownReputation = e.score(),
                        DeliveryMode.CALLER_THREAD));
    }

    /**
     * Seeds the bankruptcy streak and day counter from a save file (task 22 load): a run
     * saved two deep-debt days into the three-day streak must not get a fresh streak for
     * free on reload. Called once, right after construction, before any event flows.
     */
    public void restoreProgress(int currentDay, int consecutiveDeepDebtDays, int lastDeepDebtDay) {
        this.lastKnownDay = currentDay;
        this.consecutiveDeepDebtDays = consecutiveDeepDebtDays;
        this.lastDeepDebtDay = lastDeepDebtDay;
    }

    /** Public so tests drive the handlers directly (task-10 precedent). */
    public void onEndOfDayCompleted(EndOfDayCompletedEvent event) {
        EndOfDaySummary summary = event.summary();
        if (summary.endingWallet() < Settings.load().bankruptcyFloorEur()) {
            // Adjacent day numbers extend the streak; a gap (or a replayed day) restarts it.
            consecutiveDeepDebtDays =
                    summary.gameDay() == lastDeepDebtDay + 1 ? consecutiveDeepDebtDays + 1 : 1;
            lastDeepDebtDay = summary.gameDay();
            log.warn("day {} ended at {} — below the {} bankruptcy floor ({} consecutive of {})",
                    summary.gameDay(), summary.endingWallet(), Settings.load().bankruptcyFloorEur(),
                    consecutiveDeepDebtDays, CONSECUTIVE_BANKRUPT_DAYS);
            if (consecutiveDeepDebtDays >= CONSECUTIVE_BANKRUPT_DAYS) {
                fire(GameOverEvent.REASON_BANKRUPT, summary.endingWallet(), summary.gameDay(),
                        summary.endingReputation());
            }
        } else {
            consecutiveDeepDebtDays = 0;
        }
    }

    public void onDayRollover(DayRolloverEvent event) {
        lastKnownDay = event.newDay();
        if (event.newDay() > Settings.load().maxGameDays()) {
            EndOfDaySummary summary = event.summary();
            fire(GameOverEvent.REASON_DAY_CAP, summary.endingWallet(), summary.gameDay(),
                    summary.endingReputation());
        }
    }

    public void onQuit(QuitEvent event) {
        fire(GameOverEvent.REASON_QUIT, lastKnownWallet, lastKnownDay,
                (int) Math.round(lastKnownReputation));
    }

    private synchronized void fire(String reason, double finalWallet, int finalDay, int finalReputation) {
        if (gameOverFired) {
            return; // one GameOverEvent per run — later triggers lose
        }
        gameOverFired = true;
        try {
            lifecycle.gameOver();
        } catch (IllegalStateException e) {
            // Defensive: a quit routed here while still MENU/LOADING. The run is over either
            // way; keep the event flowing so the GUI can react.
            log.warn("game-over transition refused ({}) — continuing with the event", e.getMessage());
        }
        // Wall-clock LocalDate is deliberate: recorded_on is a real-world audit field
        // (task 20's Leaderboard schema), not game logic.
        boolean highScore = leaderboard.recordIfHighScore(finalWallet, finalDay, finalReputation,
                LocalDate.now());
        log.info("GAME OVER ({}): wallet={} day={} reputation={} highScore={}",
                reason, finalWallet, finalDay, finalReputation, highScore);
        eventBus.publish(new GameOverEvent(reason, finalWallet, finalDay, finalReputation));
    }

    /** The live streak length — read by task 22's snapshot (on the HM thread, like the writes). */
    public int consecutiveDeepDebtDays() {
        return consecutiveDeepDebtDays;
    }

    /** The last deep-debt day number — read by task 22's snapshot alongside the streak. */
    public int lastDeepDebtDay() {
        return lastDeepDebtDay;
    }

    /** Test seam + symmetry with the other bus subscribers. */
    public void close() {
        subscriptions.forEach(Subscription::cancel);
    }
}
