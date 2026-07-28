package it.unige.portcommand.harbourmaster;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.util.EventBus;

/**
 * The port's wallet: a running balance plus the <strong>append-only history</strong> of every
 * income and expense that produced it (task 20 — this class was a balance-only shell before).
 *
 * <p>The history is what makes {@code DayRolloverCoordinator} (task 24) possible without
 * double-charging. Per-deal money is applied HERE, as it happens, during the day; at end-of-day
 * task 24 only READS the day back ({@code IncomeRules.aggregateForDay} /
 * {@code ExpenseRules.variableForDay}) and charges exactly one new thing — the fixed €5200.
 * Nothing re-charges what the history already contains.
 *
 * <p>An event's game day is DERIVED from its own {@code simTime}
 * ({@code SimClock.gameDayOf}) rather than stamped at record time, so a day bucket can never
 * disagree with the timestamps in it, and a day's total is reproducible from history alone.
 *
 * <h2>Threading</h2>
 * Fed from JADE agent threads (several, concurrently). Writes are {@code synchronized} because
 * the three effects of a mutation — move the balance, append to history, publish
 * {@link WalletChangedEvent} — must be atomic <em>together</em>: an {@code AtomicReference}
 * alone would let two threads interleave and publish their absolute balances out of order,
 * leaving the HUD showing a stale figure as the final number. Reads
 * ({@link #balance()}, the history accessors) never block. Contention is negligible — a handful
 * of money events per game day.
 *
 * <p>Because publication happens under the lock, a {@code CALLER_THREAD} subscriber to
 * {@link WalletChangedEvent} MUST return promptly — a slow one holds this ledger. The
 * in-tree subscriber (the HUD) is {@code EDT}, which returns immediately.
 */
public final class WalletLedger {

    /** Lock-free reads; all writes go through {@code synchronized} blocks. */
    private final AtomicReference<Double> balance;
    private final Queue<IncomeEvent> incomeHistory = new ConcurrentLinkedQueue<>();
    private final Queue<ExpenseEvent> expenseHistory = new ConcurrentLinkedQueue<>();
    private final EventBus eventBus;

    public WalletLedger(double startingBalance, EventBus eventBus) {
        this.balance = new AtomicReference<>(startingBalance);
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public double balance() {
        return balance.get();
    }

    /** Applies {@code event} to the balance, appends it to history, and publishes the change. */
    public void recordIncome(IncomeEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (this) {
            double updated = balance.updateAndGet(b -> b + event.amount());
            incomeHistory.add(event);
            eventBus.publish(new WalletChangedEvent(
                    updated, event.amount(), event.source(), event.vesselId(), event.simTime()));
        }
    }

    /** Applies {@code event} as a debit, appends it to history, and publishes the change. */
    public void recordExpense(ExpenseEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (this) {
            double updated = balance.updateAndGet(b -> b - event.amount());
            expenseHistory.add(event);
            eventBus.publish(new WalletChangedEvent(
                    updated, -event.amount(), event.source(), event.vesselId(), event.simTime()));
        }
    }

    /**
     * Seeds history from a save file (task 22 load) WITHOUT touching the balance or publishing:
     * the persisted balance (this ledger's constructor arg on the restore path) already includes
     * every restored entry, so applying them again would double-count. Only the in-progress
     * day's entries are restored — that is exactly what {@code IncomeRules.aggregateForDay}/
     * {@code ExpenseRules.variableForDay} read at the next end-of-day; settled days feed nothing.
     */
    public synchronized void restoreHistory(List<IncomeEvent> income, List<ExpenseEvent> expense) {
        incomeHistory.addAll(income);
        expenseHistory.addAll(expense);
    }

    /** Every income ever recorded, in order. Snapshot — safe to iterate while writes continue. */
    public List<IncomeEvent> incomeHistory() {
        return List.copyOf(incomeHistory);
    }

    /** Every expense ever recorded, in order. Snapshot — safe to iterate while writes continue. */
    public List<ExpenseEvent> expenseHistory() {
        return List.copyOf(expenseHistory);
    }
}
