package it.unige.portcommand.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.lifecycle.events.DayRolloverEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayRolloverCoordinatorTest {

    private static final double EPS = 1e-9;
    private static final long DAY_1_NOON = 43_200_000L;

    private EventBus bus;
    private SimClock clock;
    private WalletLedger wallet;
    private ReputationLedger reputation;
    private PerformativeCounter counter;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        clock = new SimClock(300);
        wallet = new WalletLedger(50_000.0, bus);
        reputation = new ReputationLedger(70.0, bus);
        counter = new PerformativeCounter(bus);
        new DayRolloverCoordinator(bus, clock, wallet, reputation, counter);
    }

    /** The live trigger shape: midnight already crossed, then the detect event arrives. */
    private void crossMidnightAndSettle(int dayJustEnded) {
        while (clock.gameDay() <= dayJustEnded) {
            clock.advanceToNextDay();
        }
        bus.publish(new EndOfDayEvent(dayJustEnded));
    }

    private EndOfDaySummary settledSummary() {
        List<EndOfDayCompletedEvent> completed = EventBusProbe.published(bus).stream()
                .filter(EndOfDayCompletedEvent.class::isInstance)
                .map(EndOfDayCompletedEvent.class::cast)
                .toList();
        assertEquals(1, completed.size(), "exactly one settled summary expected");
        return completed.get(0).summary();
    }

    @Test
    void settlesADayFromTheLedgerHistoryAndChargesTheFixedCostOnce() {
        wallet.recordIncome(new IncomeEvent(18_950.0, "berth_base", "V1", DAY_1_NOON));
        wallet.recordExpense(new ExpenseEvent(3_325.0, "tug_job", "V1", DAY_1_NOON));

        crossMidnightAndSettle(1);

        EndOfDaySummary summary = settledSummary();
        assertEquals(1, summary.gameDay());
        assertEquals(18_950.0, summary.income(), EPS);
        assertEquals(3_325.0 + 5_200.0, summary.totalExpense(), EPS);
        // 50,000 + 18,950 − 3,325 − 5,200 — the ledger balance after the ONLY EOD charge.
        assertEquals(60_425.0, summary.endingWallet(), EPS);
        assertEquals(wallet.balance(), summary.endingWallet(), EPS);

        List<ExpenseEvent> fixedLines = wallet.expenseHistory().stream()
                .filter(e -> ExpenseRules.dailyFixedBreakdown().containsKey(e.source()))
                .toList();
        assertEquals(5, fixedLines.size(), "each fixed line charged exactly once");
        assertEquals(5_200.0, fixedLines.stream().mapToDouble(ExpenseEvent::amount).sum(), EPS);
        assertEquals(Set.of(1), fixedLines.stream()
                        .map(e -> SimClock.gameDayOf(e.simTime()))
                        .collect(java.util.stream.Collectors.toSet()),
                "fixed charges stamp into the day they pay for");
    }

    @Test
    void hittingTheDailyTargetEarnsTheReputationBonus() {
        // Net = 18,950 − 5,200 = 13,750 ≥ the €5,000 target.
        wallet.recordIncome(new IncomeEvent(18_950.0, "berth_base", "V1", DAY_1_NOON));

        crossMidnightAndSettle(1);

        assertEquals(72.0, reputation.score(), EPS, "+2 daily-target bonus");
        assertEquals(72, settledSummary().endingReputation(),
                "the bonus lands BEFORE the summary snapshot");
        assertEquals("daily_target", reputation.history().get(0).reason());
    }

    @Test
    void missingTheDailyTargetEarnsNothing() {
        // Empty day: net = −5,200 < target.
        crossMidnightAndSettle(1);

        assertEquals(70.0, reputation.score(), EPS);
        assertEquals(70, settledSummary().endingReputation());
    }

    @Test
    void freezesThePerformativeTallyIntoTheSummaryAndResetsIt() {
        bus.publish(new CommLogEvent(DAY_1_NOON, "harbour_master", List.of("tug_1"),
                ACLMessage.CFP, "call for proposals", "cnp-1"));
        bus.publish(new CommLogEvent(DAY_1_NOON, "tug_1", List.of("harbour_master"),
                ACLMessage.PROPOSE, "bid", "cnp-1"));

        crossMidnightAndSettle(1);

        Map<String, Integer> counts = settledSummary().performativeCounts();
        assertEquals(1, counts.get("CFP"));
        assertEquals(1, counts.get("PROPOSE"));
        assertEquals(2, settledSummary().totalMessages());
        assertTrue(counter.snapshot().values().stream().allMatch(v -> v == 0),
                "the live counter starts the new day at zero");
    }

    @Test
    void aDuplicateEndOfDayEventIsANoOp() {
        crossMidnightAndSettle(1);
        bus.publish(new EndOfDayEvent(1)); // replay

        assertEquals(1, EventBusProbe.published(bus).stream()
                .filter(EndOfDayCompletedEvent.class::isInstance).count());
        long fixedLineCount = wallet.expenseHistory().stream()
                .filter(e -> ExpenseRules.dailyFixedBreakdown().containsKey(e.source()))
                .count();
        assertEquals(5, fixedLineCount, "no second €5,200 on a replayed EOD");
    }

    @Test
    void publishOrderIsCompletedThenAutosaveThenRollover() {
        crossMidnightAndSettle(1);

        List<Event> published = EventBusProbe.published(bus);
        int completedAt = indexOf(published, EndOfDayCompletedEvent.class);
        int autosaveAt = indexOf(published, AutosaveRequestedEvent.class);
        int rolloverAt = indexOf(published, DayRolloverEvent.class);
        assertTrue(completedAt < autosaveAt, "EndOfDayCompletedEvent must precede AutosaveRequestedEvent");
        assertTrue(autosaveAt < rolloverAt, "AutosaveRequestedEvent must precede DayRolloverEvent");
    }

    @Test
    void rolloverCarriesTheClockAuthoritativeNewDayAndTheSettledSummary() {
        wallet.recordIncome(new IncomeEvent(1_000.0, "berth_base", "V1", DAY_1_NOON));
        crossMidnightAndSettle(1);

        DayRolloverEvent rollover = EventBusProbe.published(bus).stream()
                .filter(DayRolloverEvent.class::isInstance)
                .map(DayRolloverEvent.class::cast)
                .findFirst().orElseThrow();
        assertEquals(2, rollover.newDay(), "the clock already crossed into day 2");
        assertEquals(1, rollover.summary().gameDay());
        assertEquals(1_000.0, rollover.summary().income(), EPS);
    }

    @Test
    void moneyStampedIntoTheNewDayIsNotSweptIntoTheEndedDay() {
        wallet.recordIncome(new IncomeEvent(1_000.0, "berth_base", "V1", DAY_1_NOON));
        clock.advanceToNextDay();
        // A deal that lands after midnight, before the detect event is handled — the
        // day-boundary race the simTime-derived bucketing makes harmless.
        wallet.recordIncome(new IncomeEvent(777.0, "berth_base", "V2", clock.nowSimMillis() + 1_000));

        bus.publish(new EndOfDayEvent(1));

        assertEquals(1_000.0, settledSummary().income(), EPS,
                "day-2 money must not inflate day 1's settlement");
    }

    private static int indexOf(List<Event> events, Class<? extends Event> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
