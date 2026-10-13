package it.unige.portcommand.lifecycle;

import java.util.List;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.harbourmaster.financial.ScoreKeeper;
import it.unige.portcommand.harbourmaster.financial.SessionTerms;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17b (ADR-22): the settlement of a session that has terms of its own — a scenario that opened at
 * 08:00 with a target of its own, and the free play it becomes after its first midnight.
 */
class DayRolloverSessionTermsTest {

    private static final double EPS = 1e-6;
    private static final long HOUR = 3_600_000L;
    private static final long EIGHT_AM = 8 * HOUR;
    private static final long NOON = 12 * HOUR;
    private static final long DAY = 24 * HOUR;

    private EventBus bus;
    private SimClock clock;
    private WalletLedger wallet;
    private ReputationLedger reputation;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        clock = new SimClock(300);
        clock.restore(EIGHT_AM, 300);
        wallet = new WalletLedger(15_000.0, bus);
        reputation = new ReputationLedger(50.0, bus);
        SessionTerms terms = SessionTerms.scenario("Tutorial — Calm Morning", 3_500.0, EIGHT_AM);
        ScoreKeeper keeper = new ScoreKeeper(bus, clock, wallet, reputation, terms);
        new DayRolloverCoordinator(bus, clock, wallet, reputation, new PerformativeCounter(bus), terms, keeper);
    }

    private EndOfDaySummary settle(int dayJustEnded) {
        while (clock.gameDay() <= dayJustEnded) {
            clock.advanceToNextDay();
        }
        bus.publish(new EndOfDayEvent(dayJustEnded));
        List<EndOfDayCompletedEvent> completed = EventBusProbe.published(bus).stream()
                .filter(EndOfDayCompletedEvent.class::isInstance).map(EndOfDayCompletedEvent.class::cast)
                .filter(e -> e.summary().gameDay() == dayJustEnded).toList();
        assertEquals(1, completed.size(), "exactly one settled summary for day " + dayJustEnded);
        return completed.get(0).summary();
    }

    private List<String> strip() {
        return EventBusProbe.published(bus).stream()
                .filter(NotificationEvent.class::isInstance).map(NotificationEvent.class::cast)
                .map(NotificationEvent::text).toList();
    }

    /** Decision E3. Born red: every day was charged the whole €5,200, sixteen hours or not. */
    @Test
    void aDayThatOpenedAtEightIsChargedSixteenTwentyFourthsOfEachFixedLine() {
        EndOfDaySummary summary = settle(1);

        assertEquals(5_200.0 * 16 / 24, summary.totalExpense(), EPS);
        assertEquals(15_000.0 - 5_200.0 * 16 / 24, wallet.balance(), EPS);
        List<ExpenseEvent> lines = wallet.expenseHistory();
        assertEquals(5, lines.size(), "still the five canonical lines");
        assertEquals(400.0, lines.stream().filter(e -> ExpenseRules.SOURCE_SALARIES.equals(e.source()))
                .findFirst().orElseThrow().amount(), EPS, "salaries 600 x 16/24");
        assertEquals(List.of("−€3,467 daily costs"), strip());
    }

    @Test
    void theNextDayOfTheSameSessionIsAWholeDay() {
        settle(1);

        EndOfDaySummary second = settle(2);

        assertEquals(5_200.0, second.totalExpense(), EPS);
        assertEquals("−€5,200 daily costs", strip().get(strip().size() - 1));
    }

    /** Decision E8: the scenario ends with its first midnight, and only with that one. */
    @Test
    void onlyTheScenariosOwnDayEndsTheScenario() {
        EndOfDaySummary first = settle(1);
        EndOfDaySummary second = settle(2);

        assertTrue(first.endsScenario());
        assertEquals("Tutorial — Calm Morning", first.scenarioName());
        assertFalse(second.endsScenario(), "day 2 is free play: an ordinary end of day");
        assertNull(second.scenarioName());
    }

    /**
     * Decision E4. Born red: the bonus was measured against the global €5,000, so a tutorial day
     * that beat its own €3,500 target was told it had missed.
     */
    @Test
    void theTargetBonusIsMeasuredAgainstTheScenariosOwnTarget() {
        // 16-hour day: fixed 3,466.67. Net = 7,500 - 3,466.67 = 4,033.33: over 3,500, under 5,000.
        wallet.recordIncome(new IncomeEvent(7_500.0, "berth_base", "V1", NOON));
        assertTrue(Settings.load().dailyTargetEur() > 4_033.34, "fixture: the global target is higher");

        EndOfDaySummary summary = settle(1);

        assertEquals(52.0, reputation.score(), EPS, "target met: +2");
        assertEquals(52, summary.endingReputation());
    }

    @Test
    void afterTheScenarioThePlayersOwnTargetCountsAgain() {
        settle(1);
        // A whole day: net = 9,400 - 5,200 = 4,200: over this fixture's scenario target of 3,500,
        // under the player's own free-play target (the Settings default, 8,000).
        wallet.recordIncome(new IncomeEvent(9_400.0, "berth_base", "V2", DAY + NOON));

        settle(2);

        assertEquals(50.0, reputation.score(), EPS, "day 2 is measured against the player's own target");
    }

    /** The card shows the day's score on the SETTLED figures, the bonus included. */
    @Test
    void theSummaryCarriesTheDaysFinalScore() {
        wallet.recordIncome(new IncomeEvent(7_500.0, "berth_base", "V1", NOON));
        wallet.recordExpense(new ExpenseEvent(500.0, "tug_job", "V1", NOON));

        EndOfDaySummary summary = settle(1);

        assertNotNull(summary.score());
        assertEquals(1, summary.score().gameDay());
        assertEquals(summary.net(), summary.score().netProfit(), EPS, "one net: the report's and the score's");
        assertEquals(7_500.0 - 500.0 - 5_200.0 * 16 / 24, summary.score().netProfit(), EPS);
        assertEquals(3_500.0, summary.score().targetEur(), EPS);
        assertEquals(2.0, summary.score().reputationChange(), EPS, "the day's own +2 is the day's");
        assertEquals(25.0, summary.score().moneyPoints(), EPS);
    }
}
