package it.unige.portcommand.harbourmaster;

import java.util.List;

import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 22: {@link WalletLedger#restoreHistory} seeds history WITHOUT moving money. */
class WalletLedgerRestoreTest {

    @Test
    void restoreHistoryNeitherMovesTheBalanceNorPublishes() {
        EventBus bus = new EventBus();
        WalletLedger ledger = new WalletLedger(47_350.0, bus); // persisted balance ALREADY includes the entries
        ledger.restoreHistory(
                List.of(new IncomeEvent(2_450.0, "berth_base", "WALKIN-1", 6_000_000L)),
                List.of(new ExpenseEvent(350.0, "tug_job", "C001", 6_100_000L)));

        assertEquals(47_350.0, ledger.balance(), "restoring history must not re-apply it");
        assertTrue(EventBusProbe.published(bus).stream().noneMatch(WalletChangedEvent.class::isInstance),
                "restoring history must not publish a change (nothing changed)");
        assertEquals(1, ledger.incomeHistory().size(), "the day's entries ARE readable for the EOD");
        assertEquals(1, ledger.expenseHistory().size());
    }
}
