package it.unige.portcommand.lifecycle;

import java.util.Map;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.harbourmaster.financial.ReputationRules;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.lifecycle.events.DayRolloverEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import it.unige.portcommand.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>The single owner of end-of-day computation</strong> (task 24;
 * INVARIANTS "EOD math belongs to DayRolloverCoordinator" / MASTER_PLAN §8 fix
 * 2). Tasks 11 and 20 only emit events toward it: the HarbourMaster's
 * {@code EndOfDayDetectBehaviour} detects midnight and publishes the bare
 * {@link EndOfDayEvent}; {@code LedgerCoordinator} applies per-deal money
 * DURING the day. This class settles the day: read-only aggregation, the one
 * fixed €5,200 charge, the daily-target reputation bonus, the counter freeze,
 * and the completed-day event fan-out.
 *
 * <p>Constructed by {@code HarbourMasterAgent.onSetup()} — the
 * {@code LedgerCoordinator} precedent — because every collaborator (ledgers,
 * counter) is agent-owned state. Like it, this is a plain bus subscriber, NOT
 * a JADE behaviour: the catalogue stays locked at 51 (ADR-01).
 *
 * <h2>Why this is race-free without pausing the clock</h2>
 * Every money mutation in the game is published on the HarbourMaster's JADE
 * thread ({@code DealClosedEvent}, {@code TugJobAwardedEvent},
 * {@code ContractedFeeEarnedEvent}, {@code CustomsClearedEvent} — all sent by
 * HM behaviours; {@code LedgerCoordinator} writes on the publisher's thread via
 * CALLER_THREAD). {@link EndOfDayEvent} is published by
 * {@code EndOfDayDetectBehaviour} on that same thread, and this coordinator
 * subscribes CALLER_THREAD — so the entire settlement runs synchronously
 * between two HM behaviour steps, serialized with every ledger write. The
 * task-file sketch's {@code simClock.pause()}/{@code resume()} bracket is
 * dropped (ADR-14): with settlement on the writer thread there is nothing to
 * freeze out, and an unconditional resume would clobber a player pause or a
 * game-over that fires mid-sequence. Likewise {@code advanceToNextDay()} is
 * NOT called: detection happens after midnight has already crossed, so the
 * clock is in the new day before the sequence starts, and jumping again would
 * skip a day.
 *
 * <p>Publish order is a published contract:
 * {@link EndOfDayCompletedEvent} first (dialog + bankruptcy check read the
 * ended day), then {@link AutosaveRequestedEvent} (task 22's hook — state is
 * consistent once the summary exists), then {@link DayRolloverEvent} last
 * (day-cap check; "must fire before DayRolloverEvent" per
 * {@code EndOfDayCompletedEvent}'s own javadoc).
 */
public final class DayRolloverCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DayRolloverCoordinator.class);
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final EventBus eventBus;
    private final SimClock simClock;
    private final WalletLedger walletLedger;
    private final ReputationLedger reputationLedger;
    private final PerformativeCounter performativeCounter;
    private final Subscription<EndOfDayEvent> subscription;

    /** EOD fires exactly once per game day (planning/24 hard constraint). */
    private volatile int lastEndOfDayProcessed = 0;

    public DayRolloverCoordinator(EventBus eventBus, SimClock simClock, WalletLedger walletLedger,
                                  ReputationLedger reputationLedger, PerformativeCounter performativeCounter) {
        this.eventBus = eventBus;
        this.simClock = simClock;
        this.walletLedger = walletLedger;
        this.reputationLedger = reputationLedger;
        this.performativeCounter = performativeCounter;
        this.subscription = eventBus.subscribe(EndOfDayEvent.class, this::onEndOfDay, DeliveryMode.CALLER_THREAD);
    }

    /** Public so tests drive it directly (the task-10 subscribe-once precedent). */
    public void onEndOfDay(EndOfDayEvent event) {
        int day = event.gameDay();
        if (day <= lastEndOfDayProcessed) {
            log.debug("EOD for day {} already settled — ignoring duplicate", day);
            return;
        }
        lastEndOfDayProcessed = day;

        // 1. Read-only aggregation of the day's already-applied per-deal money (task 20's
        //    double-charge guard: money lands when it happens; EOD only reads it back).
        double income = IncomeRules.aggregateForDay(walletLedger, day);
        double variableExpense = ExpenseRules.variableForDay(walletLedger, day);

        // 2. The ONLY EOD charge: the five fixed lines, iterated from the canonical breakdown
        //    (never re-typed — CLAUDE.md rule 7). Stamped at the ended day's final millisecond
        //    so the history buckets them into the day they paid for; variableForDay excludes
        //    them by SOURCE TAG regardless (replay-safe).
        long stamp = endOfDayStamp(day);
        for (Map.Entry<String, Double> line : ExpenseRules.dailyFixedBreakdown().entrySet()) {
            walletLedger.recordExpense(new ExpenseEvent(line.getValue(), line.getKey(), null, stamp));
        }
        double fixedExpense = ExpenseRules.dailyFixed();

        // 3. Daily-target reputation (+2 canonical) BEFORE the summary snapshot, so the ended
        //    day's own summary shows the reputation it earned.
        double net = income - variableExpense - fixedExpense;
        if (net >= Settings.load().dailyTargetEur()) {
            reputationLedger.adjust(ReputationRules.dailyTargetBonus(), "daily_target", null, stamp);
        }

        // 4. Freeze the performative tally — resetForNewDay()'s atomic swap IS the freeze
        //    (snapshot-then-reset would lose boundary messages; task-20 measured 17/4000).
        Map<String, Integer> performatives = performativeCounter.resetForNewDay();

        EndOfDaySummary summary = new EndOfDaySummary(day, income, variableExpense + fixedExpense,
                walletLedger.balance(), (int) Math.round(reputationLedger.score()), performatives);
        log.info("day {} settled: income={} expense={} wallet={} reputation={} messages={}",
                day, summary.income(), summary.totalExpense(), summary.endingWallet(),
                summary.endingReputation(), summary.totalMessages());

        // 5-7. The contracted fan-out order (see class javadoc).
        eventBus.publish(new EndOfDayCompletedEvent(summary));
        eventBus.publish(new AutosaveRequestedEvent(day));
        eventBus.publish(new DayRolloverEvent(simClock.gameDay(), summary));
    }

    /** The last sim-millisecond of 1-based {@code day} — {@code gameDayOf(stamp) == day}. */
    static long endOfDayStamp(int day) {
        return day * MILLIS_PER_DAY - 1;
    }

    /** Unsubscribes — the HM's {@code onTakeDown()} calls this (respawn double-settle guard). */
    public void close() {
        subscription.cancel();
    }
}
