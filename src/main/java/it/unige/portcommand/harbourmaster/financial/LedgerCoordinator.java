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
 *   <li><b>Extra-tug surcharge → {@code vesselId + "|" + conversationId} → that escort's set of
 *       distinct {@code tugId}s.</b> The two still COUNT different things — the expense counts
 *       <em>jobs</em>, the surcharge counts <em>tugs beyond the first on one escort</em> — but as of
 *       2026-07-27 they no longer <em>partition</em> differently: a {@code conversationId} is a
 *       cfpId and belongs to exactly one vessel, so both sets are now keyed inside the same
 *       (conversation, tug) cell. The earlier note here said "do not simplify them into one key";
 *       that rationale is retired, and the honest statement is that the surcharge's own dedupe is
 *       currently unreachable — see {@link #onTugJobAwarded}.
 *       <p>The conversation id joined the key in <b>audit B-1</b>. It used to be the vessel alone,
 *       on the stated assumption that a vessel's tug set never turns over — true of the zero-bid
 *       retry, and NOT true of task 24's weather hold, which cancels the assigned tugs and
 *       re-tenders a fresh Contract Net that different tugs usually win. On the {@code storm}
 *       scenario that credited the port €500 it never earned.</li>
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
    /** vesselId|conversationId -> the distinct tugs already billed as an extra-tug surcharge on
     * that ESCORT. Keyed per escort, not per vessel — see {@link #onTugJobAwarded} (audit B-1). */
    private final Map<String, Set<String>> tugsPerEscort = new ConcurrentHashMap<>();
    /** Task 23 balance: bounds cumulative unengaged-timeout loss at −5/day; rollover resets. */
    private final ReputationRules.UnengagedTimeoutDailyCap unengagedCap =
            new ReputationRules.UnengagedTimeoutDailyCap();

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
        // DayRolloverEvent is the EOD fan-out's LAST event (DayRolloverCoordinator contract),
        // so the ended day's own withdrawals were all clamped before this reset runs.
        subscriptions.add(eventBus.subscribe(
                it.unige.portcommand.lifecycle.events.DayRolloverEvent.class,
                e -> unengagedCap.resetForNewDay(), DeliveryMode.CALLER_THREAD));
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

    /**
     * A walk-in left without a deal: no money, and the reputation cost of the outcome. Task 24
     * (Moji's balance call) splits the TIMEOUT penalty by engagement — a never-engaged timeout
     * (the player never even countered) is the gentler −1, an abandoned one −3; every other
     * outcome ignores the bit. The ledger reason string names the split so the audit history and
     * the EOD breakdown stay legible.
     */
    public void onWithdrawal(WithdrawalEvent event) {
        if (!penalisedWithdrawals.add(event.vesselId())) {
            log.warn("duplicate WithdrawalEvent for {} — already penalised, ignoring", event.vesselId());
            return;
        }
        double delta = ReputationRules.withdrawalDelta(event.outcome(), event.playerEngaged());
        boolean unengagedTimeout = event.outcome() == Deal.Outcome.TIMEOUT && !event.playerEngaged();
        if (unengagedTimeout) {
            // Task 23 balance (checkpoint-#6): a mostly-idle day was bleeding ~−8/day from
            // walk-ins nobody touched. Cumulative unengaged loss is floored at −5/day;
            // the engaged −3 path below stays uncapped.
            double clamped = unengagedCap.clamp(delta);
            if (clamped == 0.0) {
                log.info("unengaged timeout for {} — daily reputation cap ({}) reached, no further penalty",
                        event.vesselId(), ReputationRules.timeoutUnengagedDailyCap());
                return;
            }
            delta = clamped;
        }
        String reason = unengagedTimeout
                ? "timeout_unengaged"
                : event.outcome().name().toLowerCase(java.util.Locale.ROOT);
        reputation.adjust(delta, reason, event.vesselId(), event.simTimeMillis());
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
     * tug beyond the first ON THAT ESCORT. See the class javadoc for why those two use different
     * keys.
     *
     * <p><b>Audit B-1 (2026-07-27) — why the surcharge key gained the conversation id.</b> It used
     * to be the vessel's set of every distinct tug ever awarded to it, for the vessel's whole life.
     * That is only equal to "€500 per tug beyond the first" if the vessel's tug set never turns
     * over, and task 20 justified the assumption against the only re-award path that existed then:
     * {@code CnpRetryBehaviour}, which fires exclusively on ZERO bids and therefore re-awards the
     * same non-bidders. <b>Task 24's weather hold added a second re-award path four tasks later</b>
     * — {@code holdVessel} CANCELs the assigned tugs and resets tracking, and the clear sweep runs
     * a FRESH Contract Net whose winners are usually DIFFERENT tugs (R12's score is fuel-dominated
     * and the released pair burned fuel travelling). Neither task's diff contained both halves, so
     * no per-task review could see it.
     *
     * <p>On the shipped {@code storm} script that over-credited the port €500: T901 took 2 tugs,
     * was held, and was re-escorted by 2 more, which billed as though it had held four at once.
     * Keying per escort makes the surcharge mean what its name says.
     *
     * <p><b>What changed for the two cases the old key was chosen for</b> (stated precisely after the
     * adversarial review corrected an earlier "both behave identically", which was true only for one
     * tug): a retry re-awarding the SAME single tug still bills nothing extra, because a fresh cfpId
     * with one tug in it is a size-1 set; and co-winners on one cfpId still bill exactly once. But a
     * re-tender that re-awards the same TWO tugs now bills €500 twice where the old key billed it
     * once. That is the intended per-escort semantics — two real escorts, two extra tugs, matching
     * the two extra jobs the expense side already pays for — and it is a genuine behaviour change,
     * not a no-op.
     *
     * <p><b>The {@code tugs.add} guard below is currently UNREACHABLE.</b> {@code chargedTugJobs} is
     * keyed {@code (conversationId, tugId)} and returns early on a duplicate, and a cfpId belongs to
     * one vessel — so reaching the add implies the pair is new, which implies the tug is not yet in
     * this escort's set. It is kept as defence in depth against a future change to either key, and
     * said out loud here rather than left to read as live logic. The {@code synchronized} block
     * around it is NOT dead: two different tugs on one cfpId can still race, and the add+size pair
     * must stay atomic together.
     */
    public void onTugJobAwarded(TugJobAwardedEvent event) {
        if (!chargedTugJobs.add(event.conversationId() + "|" + event.tugId())) {
            log.warn("duplicate TugJobAwardedEvent for {}/{} — already charged, ignoring",
                    event.conversationId(), event.tugId());
            return;
        }
        charge(ExpenseRules.tugJob(event.cost()), ExpenseRules.SOURCE_TUG_JOB,
                event.vesselId(), event.simTimeMillis());

        Set<String> tugs = tugsPerEscort.computeIfAbsent(
                event.vesselId() + "|" + event.conversationId(), k -> ConcurrentHashMap.newKeySet());
        // The add+size pair must be atomic TOGETHER. `add` alone is atomic, but reading size()
        // afterwards is not: two threads adding tug_1 and tug_2 concurrently would both observe
        // size()==2 and both bill 500-0, inventing a phantom €500. Unreachable today (every money
        // publisher is on the single HarbourMaster agent thread), but this class advertises
        // concurrent keying sets, so the lock keeps that promise honest rather than relying on a
        // single-threaded caller nobody wrote down. Found by the task-20 adversarial review.
        int tugsSoFar;
        synchronized (tugs) {
            if (!tugs.add(event.tugId())) {
                // Unreachable today — see the javadoc. Defence in depth if either key changes.
                return;
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
