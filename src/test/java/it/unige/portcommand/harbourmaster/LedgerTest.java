package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WalletLedger} / {@link ReputationLedger} — history, published events, thread-safety. */
class LedgerTest {

    private static final double EPS = 1e-9;

    // ---- WalletLedger ----

    @Test
    void incomeAndExpenseMoveTheBalanceAndAppendToHistory() {
        EventBus bus = new EventBus();
        WalletLedger ledger = new WalletLedger(1_000.0, bus);

        ledger.recordIncome(new IncomeEvent(500.0, "berth_base", "V1", 10L));
        ledger.recordExpense(new ExpenseEvent(200.0, "tug_job", "V1", 20L));

        assertEquals(1_300.0, ledger.balance(), EPS);
        assertEquals(1, ledger.incomeHistory().size());
        assertEquals(1, ledger.expenseHistory().size());
        assertEquals(500.0, ledger.incomeHistory().get(0).amount(), EPS);
        assertEquals(200.0, ledger.expenseHistory().get(0).amount(), EPS);
    }

    @Test
    void everyMutationPublishesTheAbsolutePostBalance() {
        EventBus bus = new EventBus();
        List<WalletChangedEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(WalletChangedEvent.class, seen::add, DeliveryMode.CALLER_THREAD);
        WalletLedger ledger = new WalletLedger(1_000.0, bus);

        ledger.recordIncome(new IncomeEvent(500.0, "berth_base", "V1", 10L));
        ledger.recordExpense(new ExpenseEvent(200.0, "tug_job", "V1", 20L));

        assertEquals(2, seen.size());
        assertEquals(1_500.0, seen.get(0).balance(), EPS);
        assertEquals(500.0, seen.get(0).delta(), EPS);
        assertEquals("berth_base", seen.get(0).source());
        assertEquals(1_300.0, seen.get(1).balance(), EPS);
        assertEquals(-200.0, seen.get(1).delta(), EPS, "an expense publishes a negative delta");
    }

    @Test
    void historyIsASnapshotAndCannotBeMutatedByACaller() {
        WalletLedger ledger = new WalletLedger(0.0, new EventBus());
        ledger.recordIncome(new IncomeEvent(1.0, "berth_base", "V1", 0L));

        List<IncomeEvent> snapshot = ledger.incomeHistory();
        ledger.recordIncome(new IncomeEvent(2.0, "berth_base", "V2", 0L));

        assertEquals(1, snapshot.size(), "the snapshot does not grow behind the caller");
        assertEquals(2, ledger.incomeHistory().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new IncomeEvent(9.0, "x", "y", 0L)));
    }

    @Test
    void walletRejectsANullEventBus() {
        assertThrows(NullPointerException.class, () -> new WalletLedger(0.0, null));
    }

    /**
     * Concurrent writes from many agent threads must lose nothing: the balance, the history, and
     * the published events must all agree at the end.
     */
    @Test
    void concurrentIncomeAndExpenseLoseNothing() throws Exception {
        EventBus bus = new EventBus();
        List<WalletChangedEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(WalletChangedEvent.class, seen::add, DeliveryMode.CALLER_THREAD);
        WalletLedger ledger = new WalletLedger(0.0, bus);

        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                for (int j = 0; j < perThread; j++) {
                    ledger.recordIncome(new IncomeEvent(3.0, "berth_base", "V", 0L));
                    ledger.recordExpense(new ExpenseEvent(1.0, "tug_job", "V", 0L));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int ops = threads * perThread;
        assertEquals(ops * 2.0, ledger.balance(), EPS, "3 in, 1 out, per iteration");
        assertEquals(ops, ledger.incomeHistory().size());
        assertEquals(ops, ledger.expenseHistory().size());
        assertEquals(ops * 2, seen.size(), "one event per mutation, none dropped");
    }

    /**
     * The reason writes are {@code synchronized} rather than a bare {@code AtomicReference}: the
     * LAST published balance must be the ledger's real final balance. If the mutation and the
     * publish were not atomic together, two interleaving threads could publish out of order and
     * leave the HUD showing a stale figure forever.
     */
    @Test
    void theLastPublishedBalanceAlwaysMatchesTheFinalBalance() throws Exception {
        EventBus bus = new EventBus();
        List<WalletChangedEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(WalletChangedEvent.class, seen::add, DeliveryMode.CALLER_THREAD);
        WalletLedger ledger = new WalletLedger(0.0, bus);

        int threads = 8;
        int perThread = 400;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                for (int j = 0; j < perThread; j++) {
                    ledger.recordIncome(new IncomeEvent(1.0, "berth_base", "V", 0L));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(ledger.balance(), seen.get(seen.size() - 1).balance(), EPS);
    }

    // ---- ReputationLedger ----

    @Test
    void adjustMovesTheScoreAndPublishesTheAbsoluteValue() {
        EventBus bus = new EventBus();
        List<ReputationChangedEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(ReputationChangedEvent.class, seen::add, DeliveryMode.CALLER_THREAD);
        ReputationLedger ledger = new ReputationLedger(50.0, bus);

        ledger.adjust(1.0, "deal_closed", "V1", 10L);

        assertEquals(51.0, ledger.score(), EPS);
        assertEquals(1, seen.size());
        assertEquals(51.0, seen.get(0).score(), EPS);
        assertEquals(1.0, seen.get(0).delta(), EPS);
        assertEquals("deal_closed", seen.get(0).reason());
    }

    @Test
    void scoreIsClampedToZeroAndOneHundred() {
        ReputationLedger ledger = new ReputationLedger(99.0, new EventBus());

        ledger.adjust(50.0, "boost", null, 0L);
        assertEquals(100.0, ledger.score(), EPS);

        ledger.adjust(-500.0, "disaster", null, 0L);
        assertEquals(0.0, ledger.score(), EPS);
    }

    @Test
    void startingScoreIsClamped() {
        assertEquals(100.0, new ReputationLedger(150.0, new EventBus()).score(), EPS);
        assertEquals(0.0, new ReputationLedger(-10.0, new EventBus()).score(), EPS);
    }

    /**
     * At a clamp boundary the APPLIED change is smaller than the requested delta. The event still
     * carries the requested delta verbatim, so the notification says "Reputation −3" (the rule
     * that fired) rather than "−1" (the arithmetic remainder) — but history records both.
     */
    @Test
    void aClampedAdjustmentRecordsRequestedAndAppliedSeparately() {
        EventBus bus = new EventBus();
        List<ReputationChangedEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(ReputationChangedEvent.class, seen::add, DeliveryMode.CALLER_THREAD);
        ReputationLedger ledger = new ReputationLedger(1.0, bus);

        ledger.adjust(-3.0, "timeout", "V1", 0L);

        assertEquals(0.0, ledger.score(), EPS);
        assertEquals(-3.0, ledger.history().get(0).delta(), EPS, "what the rule asked for");
        assertEquals(-1.0, ledger.history().get(0).applied(), EPS, "what the clamp allowed");
        assertEquals(-3.0, seen.get(0).delta(), EPS, "the event names the rule, not the remainder");
        assertEquals(0.0, seen.get(0).score(), EPS, "but the score is the clamped truth");
    }

    @Test
    void concurrentAdjustmentsLoseNothing() throws Exception {
        ReputationLedger ledger = new ReputationLedger(50.0, new EventBus());
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                for (int j = 0; j < perThread; j++) {
                    ledger.adjust(1.0, "deal_closed", "V", 0L);
                    ledger.adjust(-1.0, "withdraw_price", "V", 0L);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(50.0, ledger.score(), EPS, "+1/-1 pairs net to zero");
        assertEquals(threads * perThread * 2, ledger.history().size());
    }
}
