package it.unige.portcommand.harbourmaster;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe running wallet balance. HarbourMaster owns this read-side only per
 * planning/11 — {@code recordIncome}/{@code recordExpense} exist so
 * {@code DayRolloverCoordinator} (task 24) has somewhere to write; nothing in this
 * task calls them.
 */
public final class WalletLedger {

    private final AtomicReference<Double> balance;

    public WalletLedger(double startingBalance) {
        this.balance = new AtomicReference<>(startingBalance);
    }

    public double balance() {
        return balance.get();
    }

    public void recordIncome(IncomeEvent event) {
        balance.updateAndGet(b -> b + event.amount());
    }

    public void recordExpense(ExpenseEvent event) {
        balance.updateAndGet(b -> b - event.amount());
    }
}
