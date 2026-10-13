package it.unige.portcommand.harbourmaster.financial;

import java.util.List;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.MovementSafetyEvent;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.ScoreChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S17b (ADR-22): the figures behind the day's score — where each one comes from, which day it
 * belongs to, and that the score is announced when (and only when) it moves.
 */
class ScoreKeeperTest {

    private static final double EPS = 1e-6;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final double HOURLY = 5_200.0 / 24;

    private EventBus bus;
    private SimClock clock;
    private WalletLedger wallet;
    private ReputationLedger reputation;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        clock = new SimClock(300);
        wallet = new WalletLedger(50_000.0, bus);
        reputation = new ReputationLedger(70.0, bus);
    }

    private ScoreKeeper freePlayKeeper() {
        return new ScoreKeeper(bus, clock, wallet, reputation, SessionTerms.freePlay());
    }

    private void clockAt(long simMillis) {
        clock.restore(simMillis, 300);
    }

    /** What the HarbourMaster's tick publisher does once a game minute. */
    private void tick() {
        long now = clock.nowSimMillis();
        bus.publish(new SimClockTickEvent(now, SimClock.gameDayOf(now), clock.simHour(), clock.simMinute()));
    }

    private List<ScoreChangedEvent> announced() {
        return EventBusProbe.published(bus).stream()
                .filter(ScoreChangedEvent.class::isInstance).map(ScoreChangedEvent.class::cast).toList();
    }

    // ---- money ----

    @Test
    void aBookingMovesTheDaysProfitAtOnce() {
        ScoreKeeper keeper = freePlayKeeper();

        wallet.recordIncome(new IncomeEvent(2_500.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 1_000L));
        wallet.recordExpense(new ExpenseEvent(400.0, ExpenseRules.SOURCE_TUG_JOB, "V1", 2_000L));

        assertEquals(2_100.0, keeper.current().netProfit(), EPS);
        assertEquals(2_100.0, announced().get(announced().size() - 1).score().netProfit(), EPS);
    }

    /** Decision E3: before S17b the day's profit stood €5,200 too high until midnight. */
    @Test
    void theFixedCostIsTakenOffHourByHour() {
        ScoreKeeper keeper = freePlayKeeper();
        wallet.recordIncome(new IncomeEvent(6_000.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 1_000L));
        assertEquals(6_000.0, keeper.current().netProfit(), EPS, "00:00 - no hour has passed");

        clockAt(HOUR);
        assertEquals(6_000.0 - HOURLY, keeper.current().netProfit(), EPS, "01:00");
        clockAt(12 * HOUR + 1_800_000L);
        assertEquals(6_000.0 - 12 * HOURLY, keeper.current().netProfit(), EPS, "12:30");
        clockAt(DAY - 1);
        assertEquals(6_000.0 - 23 * HOURLY, keeper.current().netProfit(), EPS, "23:59");
    }

    @Test
    void anHourPassingIsAnnouncedOnceNotEveryMinute() {
        freePlayKeeper();
        clockAt(HOUR - 60_000L);
        tick();
        int before = announced().size();

        clockAt(HOUR);
        tick();
        clockAt(HOUR + 60_000L);
        tick();
        clockAt(HOUR + 120_000L);
        tick();

        assertEquals(before + 1, announced().size(), "one announcement for the hour, none for the minutes");
        assertEquals(-HOURLY, announced().get(announced().size() - 1).score().netProfit(), EPS);
    }

    /** The five fixed lines are the settlement's; the day's running figure must not count them. */
    @Test
    void theSettlementsFixedLinesAreNotCountedIntoTheRunningProfit() {
        ScoreKeeper keeper = freePlayKeeper();

        ExpenseRules.dailyFixedBreakdown().forEach((source, amount) ->
                wallet.recordExpense(new ExpenseEvent(amount, source, null, 5_000L)));

        assertEquals(0.0, keeper.current().netProfit(), EPS);
    }

    /** GameSession seeds the HUD with zero-delta events; they are not money and not reputation. */
    @Test
    void aHudSeedChangesNothing() {
        ScoreKeeper keeper = freePlayKeeper();

        bus.publish(new WalletChangedEvent(123_456.0, 0.0, "gui_ready", null, 1_000L));
        bus.publish(new ReputationChangedEvent(12.0, 0.0, "game_loaded", null, 1_000L));
        // Checked BEFORE the next real adjustment: read as a change, the seed's level (12 against
        // 70) would be -58 here, and the adjustment after it would add the +59 back - the two
        // errors cancel, and an assertion only at the end stays green (found by its own
        // reversion proof).
        assertEquals(0.0, keeper.current().reputationChange(), EPS, "a seed carries a level, not a change");
        reputation.adjust(1.0, "deal_closed", "V1", 2_000L);

        assertEquals(0.0, keeper.current().netProfit(), EPS);
        assertEquals(1.0, keeper.current().reputationChange(), EPS, "the seed's level was not read as a change");
    }

    // ---- the scenario's day ----

    @Test
    void aScenarioDayIsChargedFromItsOpeningHourAgainstItsOwnTarget() {
        clockAt(8 * HOUR);
        ScoreKeeper keeper = new ScoreKeeper(bus, clock, wallet, reputation,
                SessionTerms.scenario("Storm Event", 8_000.0, 8 * HOUR));

        assertEquals(0.0, keeper.current().netProfit(), EPS, "08:00 - the scenario has only just opened");
        assertEquals(8_000.0, keeper.current().targetEur(), EPS);

        clockAt(9 * HOUR);
        tick();
        ScoreChangedEvent last = announced().get(announced().size() - 1);
        assertEquals(-HOURLY, last.score().netProfit(), EPS, "09:00 - one hour");
        assertEquals("Storm Event", last.scenarioName());

        clockAt(DAY + HOUR);
        tick();
        last = announced().get(announced().size() - 1);
        assertEquals(2, last.score().gameDay());
        assertNull(last.scenarioName(), "past its first midnight the scenario is free play");
        assertEquals(Settings.load().dailyTargetEur(), last.score().targetEur(), EPS);
    }

    // ---- walk-ins ----

    /** Decision E2, through the ledger coordinator - the one place every outcome is seen once. */
    @Test
    void walkInsAreCountedWhenTheirTalkEndsAndOnlyOnce() {
        ScoreKeeper keeper = freePlayKeeper();
        new LedgerCoordinator(bus, wallet, reputation, keeper, id -> id);
        assertEquals(25.0, keeper.current().walkInPoints(), EPS, "no talk has ended yet");

        Deal deal = new Deal("D-V1", "V1", "berth_1", 2_200.0, 8, 1_000L, Deal.Outcome.DEAL);
        bus.publish(new DealClosedEvent(deal));
        bus.publish(new DealClosedEvent(deal)); // a replay
        bus.publish(new WithdrawalEvent("V2", Deal.Outcome.WITHDRAW_PRICE, true, 2_000L));
        bus.publish(new WithdrawalEvent("V2", Deal.Outcome.WITHDRAW_PRICE, true, 2_000L));
        bus.publish(new WithdrawalEvent("V3", Deal.Outcome.TIMEOUT, false, 3_000L));

        DayScore score = keeper.current();
        assertEquals(1, score.walkInDeals());
        assertEquals(3, score.walkInsResolved());
        assertEquals(25.0 / 3, score.walkInPoints(), EPS);
    }

    /** An unengaged timeout past the day's reputation cap costs no reputation - it is still a
     * ship that left without a deal. */
    @Test
    void aTimeoutPastTheReputationCapStillCountsAsAShipThatLeft() {
        ScoreKeeper keeper = freePlayKeeper();
        new LedgerCoordinator(bus, wallet, reputation, keeper, id -> id);

        for (int i = 1; i <= 8; i++) {
            bus.publish(new WithdrawalEvent("U" + i, Deal.Outcome.TIMEOUT, false, 1_000L * i));
        }

        assertEquals(8, keeper.current().walkInsResolved());
        assertEquals(-5.0, keeper.current().reputationChange(), EPS, "the cap held the reputation");
    }

    // ---- safety ----

    @Test
    void aBlockedMoveIsCountedAndAnUnsafeOneCostsFivePoints() {
        ScoreKeeper keeper = freePlayKeeper();

        bus.publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.BLOCKED, "T901", List.of("R15"), 1_000L));
        bus.publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.BLOCKED, "W1", List.of("R16", "R19"), 2_000L));
        assertEquals(2, keeper.current().blockedMoves());
        assertEquals(25.0, keeper.current().safetyPoints(), EPS);

        bus.publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.UNSAFE_MOVE, "T901", List.of("R15"), 3_000L));
        assertEquals(1, keeper.current().unsafeMoves());
        assertEquals(20.0, keeper.current().safetyPoints(), EPS);
    }

    // ---- reputation ----

    @Test
    void reputationIsTheChangeItActuallyMadeToday() {
        reputation.adjust(29.0, "fixture", null, 0L); // 99 before the keeper exists
        ScoreKeeper keeper = freePlayKeeper();

        reputation.adjust(1.0, "deal_closed", "V1", 1_000L);  // 100
        reputation.adjust(1.0, "deal_closed", "V2", 2_000L);  // clamped: still 100

        assertEquals(1.0, keeper.current().reputationChange(), EPS, "the clamp ate the second point");
        assertEquals(15.0, keeper.current().reputationPoints(), EPS);
    }

    // ---- days ----

    /** Midnight is detected up to a game minute late. A deal in that minute is the NEW day's. */
    @Test
    void aFigureBelongsToTheDayOfItsOwnTime() {
        ScoreKeeper keeper = freePlayKeeper();
        wallet.recordIncome(new IncomeEvent(1_000.0, IncomeRules.SOURCE_BERTH_BASE, "V1", DAY - 5_000L));
        keeper.walkInDeal(DAY - 5_000L);
        clockAt(DAY + 20_000L); // midnight has passed; the settlement has not run yet
        wallet.recordIncome(new IncomeEvent(700.0, IncomeRules.SOURCE_BERTH_BASE, "V2", DAY + 10_000L));
        keeper.walkInDeal(DAY + 10_000L);
        reputation.adjust(1.0, "deal_closed", "V2", DAY + 10_000L);

        DayScore dayOne = keeper.closeDay(1, 1_000.0 - 5_200.0);

        assertEquals(1, dayOne.gameDay());
        assertEquals(1, dayOne.walkInDeals(), "the 00:00:10 deal is not day 1's");
        assertEquals(0.0, dayOne.reputationChange(), EPS);
        assertEquals(-4_200.0, dayOne.netProfit(), EPS, "the settled net, as given");
        DayScore dayTwo = keeper.current();
        assertEquals(2, dayTwo.gameDay());
        assertEquals(1, dayTwo.walkInDeals());
        assertEquals(700.0, dayTwo.netProfit(), EPS);
        assertEquals(1.0, dayTwo.reputationChange(), EPS);
    }

    @Test
    void closingADayStartsTheNextOneClean() {
        ScoreKeeper keeper = freePlayKeeper();
        keeper.walkInLeft(1_000L);
        bus.publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.BLOCKED, "V1", List.of("R15"), 2_000L));
        reputation.adjust(-2.0, "withdraw_price", "V1", 1_000L);
        clockAt(DAY + 60_000L);

        keeper.closeDay(1, -5_200.0);

        DayScore fresh = keeper.current();
        assertEquals(2, fresh.gameDay());
        assertEquals(0, fresh.walkInsResolved());
        assertEquals(0, fresh.blockedMoves());
        assertEquals(0.0, fresh.reputationChange(), EPS);
        assertEquals(63, fresh.total(), "a day on which nothing has happened yet");
    }

    // ---- save / load ----

    @Test
    void aSaveCarriesTheDaysCountersAndALoadReadsTheMoneyBackFromTheLedger() {
        ScoreKeeper keeper = freePlayKeeper();
        clockAt(10 * HOUR);
        wallet.recordIncome(new IncomeEvent(3_000.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 9 * HOUR));
        wallet.recordExpense(new ExpenseEvent(450.0, ExpenseRules.SOURCE_TUG_JOB, "V1", 9 * HOUR));
        keeper.walkInDeal(9 * HOUR);
        keeper.walkInLeft(9 * HOUR);
        bus.publish(new MovementSafetyEvent(MovementSafetyEvent.Kind.BLOCKED, "V1", List.of("R15"), 9 * HOUR));
        reputation.adjust(1.0, "deal_closed", "V1", 9 * HOUR);
        DayScore before = keeper.current();
        ScoreKeeper.Saved saved = keeper.snapshot();
        keeper.close();

        // A load: a new world on the same bus, the balances and today's entries restored.
        WalletLedger loadedWallet = new WalletLedger(wallet.balance(), bus);
        ReputationLedger loadedReputation = new ReputationLedger(reputation.score(), bus);
        ScoreKeeper loaded = new ScoreKeeper(bus, clock, loadedWallet, loadedReputation, SessionTerms.freePlay());
        loadedWallet.restoreHistory(wallet.incomeHistory(), wallet.expenseHistory());
        loaded.restore(saved);

        assertEquals(before, loaded.current());
    }

    /** A save written before S17b has no counters: the day loads with its money and nothing else. */
    @Test
    void anOlderSaveLoadsWithTheDaysMoneyAndNoCounters() {
        clockAt(10 * HOUR);
        ScoreKeeper loaded = freePlayKeeper();
        wallet.restoreHistory(
                List.of(new IncomeEvent(3_000.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 9 * HOUR)), List.of());

        loaded.restore(null);

        assertEquals(3_000.0 - 10 * HOURLY, loaded.current().netProfit(), EPS);
        assertEquals(0, loaded.current().walkInsResolved());
    }

    @Test
    void aClosedKeeperNoLongerAnnounces() {
        ScoreKeeper keeper = freePlayKeeper();
        keeper.close();
        int before = announced().size();

        wallet.recordIncome(new IncomeEvent(2_500.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 1_000L));
        tick();

        assertEquals(before, announced().size());
    }

    @Test
    void aWindowThatHasJustOpenedIsToldTheScoreEvenIfNothingMoved() {
        ScoreKeeper keeper = freePlayKeeper();
        keeper.publishNow();
        int before = announced().size();

        keeper.publishNow();

        assertEquals(before + 1, announced().size());
    }
}
