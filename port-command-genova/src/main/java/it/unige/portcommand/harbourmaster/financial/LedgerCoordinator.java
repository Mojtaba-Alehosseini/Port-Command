package it.unige.portcommand.harbourmaster.financial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the game's money-bearing events into ledger entries, as they happen, during the day
 * (task 20). This is the "per-deal income was already applied during the day" half of the EOD
 * double-charge guard: task 24 later only READS the day back and charges the fixed €5200.
 *
 * <p>A plain class, not a JADE behaviour — the behaviour catalogue stays locked at 51
 * (INVARIANTS / ADR-01). It subscribes to the bus and never touches an agent, and agents never
 * call it.
 *
 * <h2>Idempotency — the reason each key is what it is</h2>
 * Every charge is keyed, so a replayed or duplicated event cannot move the wallet twice:
 * <ul>
 *   <li><b>Tug job → {@code conversationId + "|" + tugId}.</b> {@code conversationId} ALONE is
 *       wrong: two co-winning tugs for one vessel share a single CNP {@code cfpId}, so keying on
 *       it collapses two real jobs into one and under-charges. {@code tugId} is the only per-tug
 *       discriminator that exists.</li>
 *   <li><b>Extra-tug surcharge → the vessel's set of distinct {@code tugId}s.</b> Deliberately a
 *       DIFFERENT key from the expense above, because the two things are counted differently: the
 *       expense counts <em>jobs</em>, the surcharge counts <em>tugs</em>. If a vessel ever has the
 *       same tug awarded twice on different conversations, that is two jobs to pay for but still
 *       one tug to bill for. <strong>Defence in depth, not a live path:</strong> the only retry
 *       today ({@code CnpRetryBehaviour}) fires exclusively on ZERO bids
 *       ({@code AwardContractBehaviour}'s {@code holdVessel(hm,"no_bids")}), and zero bids means
 *       zero awards — so a retry cannot currently re-award anything. Do not "simplify" the two
 *       keys into one on the strength of that: it is a property of today's retry trigger, not of
 *       the ledger. (The earlier version of this note claimed the retry itself was the motivating
 *       case; that was wrong — corrected after the task-20 adversarial review traced the trigger.)</li>
 *   <li><b>Contracted fee → {@code contractId}; customs → {@code vesselId}.</b></li>
 *   <li><b>Deal / withdrawal → {@code vesselId}.</b> A walk-in negotiates once.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Subscribes {@link DeliveryMode#CALLER_THREAD}: {@code ASYNC}'s bounded queue drops the oldest
 * event when it fills, and a dropped money event is money that silently never existed. Handlers
 * are O(1) — a set add plus a ledger write — so blocking the publishing agent thread is bounded.
 * The ledgers are themselves thread-safe; this class's keying sets are concurrent.
 *
 * <h2>What is NOT wired, and why</h2>
 * {@code IncomeRules} also prices hazmat, pilot service, bunker fuel and water/waste. None of them
 * is charged here: <strong>no event or message carries them</strong> — nothing in the game today
 * says "a pilot was used" or "bunker fuel was sold". Those rules exist for the §7.5 calibration
 * and for whoever adds a producer; inventing a trigger for them would make the wallet report
 * income the simulation never earned. Likewise {@code ExpenseRules.incidentFine()} — there is no
 * incident event yet.
 */
public final class LedgerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LedgerCoordinator.class);

    private final WalletLedger wallet;
    private final ReputationLedger reputation;

    private final Set<String> chargedTugJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> creditedContracts = ConcurrentHashMap.newKeySet();
    private final Set<String> chargedClearances = ConcurrentHashMap.newKeySet();
    private final Set<String> creditedDeals = ConcurrentHashMap.newKeySet();
    private final Set<String> penalisedWithdrawals = ConcurrentHashMap.newKeySet();
    /** vesselId -> the distinct tugs already billed as an extra-tug surcharge for that vessel. */
    private final Map<String, Set<String>> tugsPerVessel = new ConcurrentHashMap<>();

    private final List<Subscription<?>> subscriptions = new ArrayList<>();

    public LedgerCoordinator(EventBus eventBus, WalletLedger wallet, ReputationLedger reputation) {
        Objects.requireNonNull(eventBus, "eventBus");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.reputation = Objects.requireNonNull(reputation, "reputation");
        subscriptions.add(eventBus.subscribe(DealClosedEvent.class, this::onDealClosed, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(WithdrawalEvent.class, this::onWithdrawal, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                TugJobAwardedEvent.class, this::onTugJobAwarded, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                ContractedFeeEarnedEvent.class, this::onContractedFee, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                CustomsClearedEvent.class, this::onCustomsCleared, DeliveryMode.CALLER_THREAD));
    }

    /**
     * A walk-in deal closed: the negotiated fee, plus the premium surcharge if the port's standing
     * has earned it. Reputation moves per the canonical {@code ReputationRules} table — walk-ins
     * only; a contracted vessel is pre-agreed and earns none (see {@link ReputationRules}).
     */
    public void onDealClosed(DealClosedEvent event) {
        Deal deal = event.deal();
        if (deal.outcome() != Deal.Outcome.DEAL) {
            log.warn("DealClosedEvent with non-DEAL outcome {} for {} — ignoring (WithdrawalEvent's job)",
                    deal.outcome(), deal.vesselId());
            return;
        }
        if (!creditedDeals.add(deal.vesselId())) {
            log.warn("duplicate DealClosedEvent for {} — already credited, ignoring", deal.vesselId());
            return;
        }
        double base = IncomeRules.berthBase(null, deal.finalHours(), deal.finalPrice());
        credit(base, IncomeRules.SOURCE_BERTH_BASE, deal.vesselId(), deal.closedAtSimMillis());
        creditPremium(base, deal.vesselId(), deal.closedAtSimMillis());
        reputation.adjust(ReputationRules.deltaFor(Deal.Outcome.DEAL), "deal_closed",
                deal.vesselId(), deal.closedAtSimMillis());
    }

    /** A walk-in left without a deal: no money, and the reputation cost of the outcome. */
    public void onWithdrawal(WithdrawalEvent event) {
        if (!penalisedWithdrawals.add(event.vesselId())) {
            log.warn("duplicate WithdrawalEvent for {} — already penalised, ignoring", event.vesselId());
            return;
        }
        reputation.adjust(ReputationRules.deltaFor(event.outcome()),
                event.outcome().name().toLowerCase(java.util.Locale.ROOT), event.vesselId(),
                event.simTimeMillis());
    }

    /**
     * A contracted vessel was granted its berth: the contract's agreed fee, plus premium if
     * earned. No reputation — a pre-agreed contract is not a negotiation handled well or badly.
     */
    public void onContractedFee(ContractedFeeEarnedEvent event) {
        if (!creditedContracts.add(event.contractId())) {
            log.warn("duplicate ContractedFeeEarnedEvent for {} — already credited, ignoring",
                    event.contractId());
            return;
        }
        double base = IncomeRules.berthBase(null, event.hours(), event.fee());
        credit(base, IncomeRules.SOURCE_BERTH_BASE, event.vesselId(), event.simTimeMillis());
        creditPremium(base, event.vesselId(), event.simTimeMillis());
    }

    /**
     * A tug won a Contract Net: the port pays the winning bid, and bills the vessel €500 for each
     * tug beyond the first. See the class javadoc for why those two use different keys.
     */
    public void onTugJobAwarded(TugJobAwardedEvent event) {
        if (!chargedTugJobs.add(event.conversationId() + "|" + event.tugId())) {
            log.warn("duplicate TugJobAwardedEvent for {}/{} — already charged, ignoring",
                    event.conversationId(), event.tugId());
            return;
        }
        charge(ExpenseRules.tugJob(event.cost()), ExpenseRules.SOURCE_TUG_JOB,
                event.vesselId(), event.simTimeMillis());

        Set<String> tugs = tugsPerVessel.computeIfAbsent(event.vesselId(), k -> ConcurrentHashMap.newKeySet());
        // The add+size pair must be atomic TOGETHER. `add` alone is atomic, but reading size()
        // afterwards is not: two threads adding tug_1 and tug_2 concurrently would both observe
        // size()==2 and both bill 500-0, inventing a phantom €500. Unreachable today (every money
        // publisher is on the single HarbourMaster agent thread), but this class advertises
        // concurrent keying sets, so the lock keeps that promise honest rather than relying on a
        // single-threaded caller nobody wrote down. Found by the task-20 adversarial review.
        int tugsSoFar;
        synchronized (tugs) {
            if (!tugs.add(event.tugId())) {
                return; // this tug was already billed to this vessel — a re-award, not a new tug
            }
            tugsSoFar = tugs.size();
        }
        double surcharge = IncomeRules.extraTugSurcharge(tugsSoFar)
                - IncomeRules.extraTugSurcharge(tugsSoFar - 1);
        if (surcharge > 0) {
            credit(surcharge, IncomeRules.SOURCE_EXTRA_TUG, event.vesselId(), event.simTimeMillis());
        }
    }

    /** Customs granted a clearance: €100. */
    public void onCustomsCleared(CustomsClearedEvent event) {
        if (!chargedClearances.add(event.vesselId())) {
            log.warn("duplicate CustomsClearedEvent for {} — already charged, ignoring", event.vesselId());
            return;
        }
        charge(ExpenseRules.customsClearance(), ExpenseRules.SOURCE_CUSTOMS,
                event.vesselId(), event.simTimeMillis());
    }

    private void creditPremium(double base, String vesselId, long simTime) {
        double premium = IncomeRules.premiumSurcharge(base, reputation.score());
        if (premium > 0) {
            credit(premium, IncomeRules.SOURCE_PREMIUM, vesselId, simTime);
        }
    }

    private void credit(double amount, String source, String vesselId, long simTime) {
        if (amount <= 0) {
            return;
        }
        wallet.recordIncome(new IncomeEvent(amount, source, vesselId, simTime));
    }

    private void charge(double amount, String source, String vesselId, long simTime) {
        if (amount <= 0) {
            return;
        }
        wallet.recordExpense(new ExpenseEvent(amount, source, vesselId, simTime));
    }

    /** Stops feeding the ledgers. For test isolation; production never cancels. */
    public void close() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }
}
