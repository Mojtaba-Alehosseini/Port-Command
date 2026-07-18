package it.unige.portcommand.harbourmaster.financial;

import java.util.List;

import it.unige.portcommand.gui.events.ContractedFeeEarnedEvent;
import it.unige.portcommand.gui.events.CustomsClearedEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerCoordinatorTest {

    private static final double EPS = 1e-9;

    private EventBus bus;
    private WalletLedger wallet;
    private ReputationLedger reputation;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        wallet = new WalletLedger(10_000.0, bus);
        reputation = new ReputationLedger(50.0, bus);
        new LedgerCoordinator(bus, wallet, reputation);
    }

    private static Deal deal(String vesselId, double price) {
        return new Deal("D-" + vesselId, vesselId, "berth_1", price, 8, 1_000L, Deal.Outcome.DEAL);
    }

    // ---- income ----

    @Test
    void aClosedWalkInDealCreditsTheFeeAndLiftsReputation() {
        bus.publish(new DealClosedEvent(deal("V1", 2_200.0)));

        assertEquals(12_200.0, wallet.balance(), EPS);
        assertEquals(51.0, reputation.score(), EPS);
        assertEquals(List.of(IncomeRules.SOURCE_BERTH_BASE),
                wallet.incomeHistory().stream().map(IncomeEvent::source).toList());
    }

    @Test
    void aContractedGrantCreditsTheFeeButEarnsNoReputation() {
        bus.publish(new ContractedFeeEarnedEvent("CONTRACT-1", "C001", "berth_1", 5_200.0, 8, 500L));

        assertEquals(15_200.0, wallet.balance(), EPS);
        assertEquals(50.0, reputation.score(), EPS, "a pre-agreed contract is not a negotiation");
    }

    @Test
    void premiumSurchargeIsCreditedOnlyOnceReputationReachesEighty() {
        bus.publish(new DealClosedEvent(deal("V1", 1_000.0)));
        assertEquals(11_000.0, wallet.balance(), EPS, "rep 51 — no premium");

        reputation.adjust(30.0, "test_boost", null, 0L); // -> 81
        bus.publish(new DealClosedEvent(deal("V2", 1_000.0)));

        assertEquals(12_150.0, wallet.balance(), EPS, "1000 base + 150 premium");
        assertEquals(IncomeRules.SOURCE_PREMIUM, wallet.incomeHistory().get(2).source());
    }

    // ---- reputation ----

    @Test
    void eachWithdrawalOutcomeAppliesItsCanonicalPenalty() {
        bus.publish(new WithdrawalEvent("V1", Deal.Outcome.WITHDRAW_PRICE, true, 1_000L));
        assertEquals(48.0, reputation.score(), EPS);

        bus.publish(new WithdrawalEvent("V2", Deal.Outcome.TIMEOUT, true, 1_000L)); // engaged -> −3
        assertEquals(45.0, reputation.score(), EPS);

        bus.publish(new WithdrawalEvent("V3", Deal.Outcome.PLAYER_REFUSED, false, 1_000L));
        assertEquals(45.0, reputation.score(), EPS, "refusing a bad deal is a legitimate move");
    }

    /**
     * Task 24 (Moji's balance call): the TIMEOUT penalty splits by engagement — a walk-in the
     * player never even countered is the gentler −1; one they negotiated then abandoned is the
     * full −3. Only TIMEOUT reads the bit; the ledger reason string names the split.
     */
    @Test
    void aNeverEngagedTimeoutIsTheGentlerPenalty() {
        bus.publish(new WithdrawalEvent("V1", Deal.Outcome.TIMEOUT, false, 1_000L));
        assertEquals(49.0, reputation.score(), EPS, "never-engaged timeout costs −1, not −3");
        assertEquals("timeout_unengaged", reputation.history().get(0).reason());

        bus.publish(new WithdrawalEvent("V2", Deal.Outcome.TIMEOUT, true, 1_000L));
        assertEquals(46.0, reputation.score(), EPS, "engaged-then-abandoned timeout costs −3");
        assertEquals("timeout", reputation.history().get(1).reason());
    }

    /**
     * Task 23 (checkpoint-#6 balance call, 2026-07-18): cumulative UNENGAGED-timeout loss is
     * floored at −5 per day (evidence: 70 → 9 over 8 mostly-idle days — passive walk-ins the
     * player never touched were the dominant bleed). Ten unengaged timeouts in one day cost
     * exactly −5 total; the day rollover resets the budget; the engaged −3 stays uncapped.
     */
    @Test
    void unengagedTimeoutLossIsCappedAtMinusFivePerDayAndResetsAtRollover() {
        for (int i = 1; i <= 10; i++) {
            bus.publish(new WithdrawalEvent("U" + i, Deal.Outcome.TIMEOUT, false, 1_000L * i));
        }
        assertEquals(45.0, reputation.score(), EPS,
                "10 unengaged timeouts in one day cost the −5 cap, not −10");

        bus.publish(new WithdrawalEvent("E1", Deal.Outcome.TIMEOUT, true, 20_000L));
        assertEquals(42.0, reputation.score(), EPS, "the engaged −3 is NOT capped");

        bus.publish(new it.unige.portcommand.lifecycle.events.DayRolloverEvent(2, null));
        bus.publish(new WithdrawalEvent("U11", Deal.Outcome.TIMEOUT, false, 90_000_000L));
        assertEquals(41.0, reputation.score(), EPS,
                "a new day has a fresh −5 unengaged budget (rollover reset)");
    }

    /**
     * A withdrawal's reputation penalty must be stamped with the sim time it happened, not 0.
     * Before {@code WithdrawalEvent} carried a timestamp, every penalty in the ledger's history
     * bucketed to day 1 forever — corrupt audit data the moment anything reads reputation by day.
     * (Adversarial-review finding.)
     */
    @Test
    void aWithdrawalPenaltyIsStampedWithItsRealSimTime() {
        long day3 = 2 * 86_400_000L + 5_000L;

        bus.publish(new WithdrawalEvent("V1", Deal.Outcome.WITHDRAW_PRICE, true, day3));

        assertEquals(day3, reputation.history().get(0).simTime(), "not 0L");
        assertEquals(3, it.unige.portcommand.util.SimClock.gameDayOf(
                reputation.history().get(0).simTime()), "buckets to day 3, not day 1");
    }

    @Test
    void aWithdrawalMovesNoMoney() {
        bus.publish(new WithdrawalEvent("V1", Deal.Outcome.WITHDRAW_PRICE, true, 1_000L));

        assertEquals(10_000.0, wallet.balance(), EPS);
        assertEquals(List.of(), wallet.incomeHistory());
    }

    /** {@code WithdrawalEvent} owns the non-DEAL outcomes; a mis-published DEAL must not pay out. */
    @Test
    void aDealClosedEventCarryingANonDealOutcomeIsIgnored() {
        Deal notADeal = new Deal("D-V9", "V9", "berth_1", 2_200.0, 8, 1_000L, Deal.Outcome.TIMEOUT);

        bus.publish(new DealClosedEvent(notADeal));

        assertEquals(10_000.0, wallet.balance(), EPS);
        assertEquals(50.0, reputation.score(), EPS);
    }

    // ---- the double-charge guards (the May audit's flagged risk) ----

    @Test
    void aTugAwardChargesTheWinningBidExactlyOnce() {
        TugJobAwardedEvent award = new TugJobAwardedEvent("V1", "tug_1", 425.0, "cnp-abc", 2_000L);

        bus.publish(award);
        bus.publish(award); // a replay must not charge twice

        assertEquals(10_000.0 - 425.0, wallet.balance(), EPS);
        assertEquals(1, wallet.expenseHistory().size());
        assertEquals(ExpenseRules.SOURCE_TUG_JOB, wallet.expenseHistory().get(0).source());
    }

    /**
     * The sharpest trap the survey found: two co-winning tugs for ONE vessel share a single CNP
     * {@code cfpId}. Deduping on conversationId alone would collapse two real jobs into one and
     * under-charge — so both must be charged, and the vessel billed one €500 extra-tug surcharge.
     */
    @Test
    void twoCoWinningTugsOnOneConversationAreBothChargedAndBillOneExtraTugSurcharge() {
        bus.publish(new TugJobAwardedEvent("V1", "tug_1", 400.0, "cnp-abc", 2_000L));
        bus.publish(new TugJobAwardedEvent("V1", "tug_2", 450.0, "cnp-abc", 2_000L));

        assertEquals(2, wallet.expenseHistory().size(), "two real tug jobs, not one");
        assertEquals(850.0, wallet.expenseHistory().stream().mapToDouble(ExpenseEvent::amount).sum(), EPS);
        assertEquals(List.of(IncomeRules.SOURCE_EXTRA_TUG),
                wallet.incomeHistory().stream().map(IncomeEvent::source).toList());
        assertEquals(500.0, wallet.incomeHistory().get(0).amount(), EPS, "one surcharge for the 2nd tug");
        assertEquals(10_000.0 - 850.0 + 500.0, wallet.balance(), EPS);
    }

    /**
     * A CNP retry mints a fresh cfpId. Re-awarding the SAME tug is a genuinely new job to pay for
     * — but NOT a second tug to bill the vessel a €500 surcharge for. This is why the expense and
     * the surcharge deliberately use different keys.
     */
    @Test
    void aRetryReawardingTheSameTugChargesAgainButInventsNoExtraTugSurcharge() {
        bus.publish(new TugJobAwardedEvent("V1", "tug_1", 400.0, "cnp-abc", 2_000L));
        bus.publish(new TugJobAwardedEvent("V1", "tug_1", 400.0, "cnp-retry-1", 9_000L));

        assertEquals(2, wallet.expenseHistory().size(), "two jobs — the retry is real work");
        assertEquals(List.of(), wallet.incomeHistory(), "still only one tug ever touched this vessel");
        assertEquals(10_000.0 - 800.0, wallet.balance(), EPS);
    }

    @Test
    void aThirdDistinctTugBillsASecondExtraTugSurcharge() {
        bus.publish(new TugJobAwardedEvent("V1", "tug_1", 400.0, "cnp-abc", 2_000L));
        bus.publish(new TugJobAwardedEvent("V1", "tug_2", 400.0, "cnp-abc", 2_000L));
        bus.publish(new TugJobAwardedEvent("V1", "tug_3", 400.0, "cnp-abc", 2_000L));

        assertEquals(1_000.0, wallet.incomeHistory().stream().mapToDouble(IncomeEvent::amount).sum(), EPS,
                "extraTugSurcharge(3) = 2 x 500");
    }

    @Test
    void tugsForDifferentVesselsDoNotShareASurchargeCount() {
        bus.publish(new TugJobAwardedEvent("V1", "tug_1", 400.0, "cnp-a", 2_000L));
        bus.publish(new TugJobAwardedEvent("V2", "tug_2", 400.0, "cnp-b", 2_000L));

        assertEquals(List.of(), wallet.incomeHistory(), "one tug each — neither vessel owes a surcharge");
    }

    @Test
    void aCustomsClearanceChargesOneHundredExactlyOnce() {
        bus.publish(new CustomsClearedEvent("V1", "CL-2026-1", 3_000L));
        bus.publish(new CustomsClearedEvent("V1", "CL-2026-1", 3_000L));

        assertEquals(9_900.0, wallet.balance(), EPS);
        assertEquals(1, wallet.expenseHistory().size());
        assertEquals(ExpenseRules.SOURCE_CUSTOMS, wallet.expenseHistory().get(0).source());
    }

    @Test
    void aDuplicateDealOrContractOrWithdrawalIsCreditedOnce() {
        bus.publish(new DealClosedEvent(deal("V1", 2_200.0)));
        bus.publish(new DealClosedEvent(deal("V1", 2_200.0)));
        bus.publish(new ContractedFeeEarnedEvent("CONTRACT-1", "C001", "berth_1", 5_200.0, 8, 0L));
        bus.publish(new ContractedFeeEarnedEvent("CONTRACT-1", "C001", "berth_1", 5_200.0, 8, 0L));
        bus.publish(new WithdrawalEvent("V2", Deal.Outcome.TIMEOUT, true, 1_000L));
        bus.publish(new WithdrawalEvent("V2", Deal.Outcome.TIMEOUT, true, 1_000L));

        assertEquals(10_000.0 + 2_200.0 + 5_200.0, wallet.balance(), EPS);
        assertEquals(2, wallet.incomeHistory().size());
        assertEquals(50.0 + 1.0 - 3.0, reputation.score(), EPS);
    }

    // ---- day bucketing, end to end through the rules ----

    @Test
    void aDaysIncomeAndVariableExpenseAggregateBackOutOfHistory() {
        long day2 = 86_400_000L;
        bus.publish(new DealClosedEvent(
                new Deal("D-V1", "V1", "berth_1", 2_200.0, 8, 1_000L, Deal.Outcome.DEAL)));
        bus.publish(new TugJobAwardedEvent("V1", "tug_1", 400.0, "cnp-a", 1_500L));
        bus.publish(new DealClosedEvent(
                new Deal("D-V2", "V2", "berth_1", 1_800.0, 8, day2 + 1_000L, Deal.Outcome.DEAL)));

        assertEquals(2_200.0, IncomeRules.aggregateForDay(wallet, 1), EPS);
        assertEquals(400.0, ExpenseRules.variableForDay(wallet, 1), EPS);
        assertEquals(1_800.0, IncomeRules.aggregateForDay(wallet, 2), EPS);
        assertEquals(0.0, ExpenseRules.variableForDay(wallet, 2), EPS);
    }
}
