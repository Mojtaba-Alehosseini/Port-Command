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
 *   <li><b>Extra-tug surcharge → the size of the ship's escort.</b> It counts <em>tugs beyond
 *       the first on one escort</em>, and an escort is whatever is waiting on the ship, however
 *       many tenders it took to hire. Each award says how big the escort is with that tug in it
 *       ({@code TugJobAwardedEvent.escortSize}); the surcharge is billed when that size passes
 *       what was already billed. See {@link #onTugJobAwarded} for the three keys this has had.</li>
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
 * <h2>Every money rule is wired</h2>
 * Until S17b {@code IncomeRules} also priced hazmat, pilot service, bunker fuel and water/waste,
 * and {@code ExpenseRules} an incident fine. No event or message ever carried them, so none was
 * ever booked; they were deleted (decision E5) rather than given an invented trigger.
 */
public final class LedgerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LedgerCoordinator.class);

    private final WalletLedger wallet;
    private final ReputationLedger reputation;

    /** S9 (P1-8): Set → Map so the decline refund knows the recorded cost — the coordinator is
     * the money memory; the decline wire carries no amounts. Key stays conversationId|tugId. */
    private final java.util.concurrent.ConcurrentMap<String, Double> chargedTugJobs =
            new ConcurrentHashMap<>();
    /** Exactly-once refunds, mirroring the charge dedupe (S9). */
    private final Set<String> refundedTugJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> creditedContracts = ConcurrentHashMap.newKeySet();
    private final Set<String> chargedClearances = ConcurrentHashMap.newKeySet();
    private final Set<String> creditedDeals = ConcurrentHashMap.newKeySet();
    private final Set<String> penalisedWithdrawals = ConcurrentHashMap.newKeySet();
    /** vesselId -> the size of the ship's CURRENT escort that has already been surcharged. A first
     * tug starts a new escort and resets it — see {@link #onTugJobAwarded} (S17b, E11). */
    private final Map<String, Integer> billedEscortSize = new ConcurrentHashMap<>();
    private final EventBus eventBus;
    /** S17b: the day's score figures; {@code null} in harnesses that keep no score. */
    private final ScoreKeeper scoreKeeper;
    /** S17b: a ship's name as the player knows it, for the strip's money lines. */
    private final java.util.function.UnaryOperator<String> displayName;
    /** Task 23 balance: bounds cumulative unengaged-timeout loss at −5/day; rollover resets. */
    private final ReputationRules.UnengagedTimeoutDailyCap unengagedCap =
            new ReputationRules.UnengagedTimeoutDailyCap();

    private final List<Subscription<?>> subscriptions = new ArrayList<>();

    public LedgerCoordinator(EventBus eventBus, WalletLedger wallet, ReputationLedger reputation) {
        this(eventBus, wallet, reputation, null, java.util.function.UnaryOperator.identity());
    }

    /**
     * @param scoreKeeper told of every walk-in outcome exactly once (the dedupe is this class's);
     *                    {@code null} keeps no score
     * @param displayName vessel id -> the name shown to the player
     */
    public LedgerCoordinator(EventBus eventBus, WalletLedger wallet, ReputationLedger reputation,
                             ScoreKeeper scoreKeeper, java.util.function.UnaryOperator<String> displayName) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scoreKeeper = scoreKeeper;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.reputation = Objects.requireNonNull(reputation, "reputation");
        subscriptions.add(eventBus.subscribe(DealClosedEvent.class, this::onDealClosed, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(WithdrawalEvent.class, this::onWithdrawal, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                TugJobAwardedEvent.class, this::onTugJobAwarded, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                it.unige.portcommand.gui.events.TugJobDeclinedEvent.class,
                this::onTugJobDeclined, DeliveryMode.CALLER_THREAD));
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
        double premium = creditPremium(base, deal.vesselId(), deal.closedAtSimMillis());
        announce(base + premium, "deal with", deal.vesselId(), deal.closedAtSimMillis());
        if (scoreKeeper != null) {
            scoreKeeper.walkInDeal(deal.closedAtSimMillis());
        }
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
        if (scoreKeeper != null) {
            scoreKeeper.walkInLeft(event.simTimeMillis());
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
        double premium = creditPremium(base, event.vesselId(), event.simTimeMillis());
        announce(base + premium, "contract fee from", event.vesselId(), event.simTimeMillis());
    }

    /**
     * A tug won a Contract Net: the port pays the winning bid, and bills the vessel €500 for each
     * tug beyond the first ON ITS ESCORT.
     *
     * <p><b>The surcharge has had three keys; this is why it is the third.</b>
     * <ol>
     *   <li><i>The vessel's set of every tug ever awarded to it</i> (task 20). Wrong since task 24:
     *       a weather hold cancels the escort and re-tenders it, different tugs usually win, and
     *       the storm's T901 was billed as though it had held four tugs at once.</li>
     *   <li><i>The Contract Net conversation</i> (audit B-1, 2026-07-27): tugs beyond the first
     *       within one tender. Right for the hold - two real escorts, two surcharges. Wrong
     *       since S16: a ship short of a tug keeps the tug it has and the missing one is
     *       tendered again, so a two-tug escort arrives as two tenders of one tug each and was
     *       billed nothing (decision E11).</li>
     *   <li><i>The size of the ship's escort</i> (S17b). The HarbourMaster knows how many tugs
     *       are waiting on the ship, so the award says it. A first tug ({@code escortSize == 1})
     *       is a new escort - the old one was recalled, or its only tug declined - and what an
     *       earlier escort was billed no longer counts. A second tug is billed once, whichever
     *       tender hired it; a tug that only REPLACES one that declined brings the escort back to
     *       a size already billed and is not billed again.</li>
     * </ol>
     * The tug-job EXPENSE keeps its own key, {@code (conversationId, tugId)}: every award is a job
     * the port pays for.
     */
    public void onTugJobAwarded(TugJobAwardedEvent event) {
        if (chargedTugJobs.putIfAbsent(event.conversationId() + "|" + event.tugId(),
                event.cost()) != null) {
            log.warn("duplicate TugJobAwardedEvent for {}/{} — already charged, ignoring",
                    event.conversationId(), event.tugId());
            return;
        }
        charge(ExpenseRules.tugJob(event.cost()), ExpenseRules.SOURCE_TUG_JOB,
                event.vesselId(), event.simTimeMillis());

        // The read-compare-write on the billed size must be atomic: every money publisher is on
        // the single HarbourMaster thread today, but this class advertises concurrent keying, so
        // two co-winners racing must not both read "1" and both bill the second tug.
        double surcharge;
        synchronized (billedEscortSize) {
            int size = event.escortSize();
            // A first tug is a NEW escort: whatever an earlier one was billed is behind us.
            int billed = size <= 1 ? 1 : billedEscortSize.getOrDefault(event.vesselId(), 1);
            surcharge = size > billed
                    ? IncomeRules.extraTugSurcharge(size) - IncomeRules.extraTugSurcharge(billed)
                    : 0.0;
            billedEscortSize.put(event.vesselId(), Math.max(size, billed));
        }
        if (surcharge > 0) {
            credit(surcharge, IncomeRules.SOURCE_EXTRA_TUG, event.vesselId(), event.simTimeMillis());
        }
    }

    /**
     * S9 (P1-8): a winning tug REFUSEd after the charge-at-award landed — post the COMPENSATING
     * income entry ({@code tug_job_refund}, amount = the recorded charge). Append-only honoured
     * (never a mutation, never a negative expense — the {@code <=0} guards stand); exactly-once
     * by requiring the charge key PRESENT and the refund key ABSENT. A decline for a never-
     * charged pair is refused loudly — refunding money never spent would be invented income.
     * The extra-tug surcharge is deliberately NOT reversed (see TugJobDeclinedEvent's javadoc).
     */
    public void onTugJobDeclined(it.unige.portcommand.gui.events.TugJobDeclinedEvent event) {
        String key = event.conversationId() + "|" + event.tugId();
        Double cost = chargedTugJobs.get(key);
        if (cost == null) {
            log.warn("TugJobDeclinedEvent for {}/{} but no charge is recorded — refusing the refund",
                    event.conversationId(), event.tugId());
            return;
        }
        if (!refundedTugJobs.add(key)) {
            log.warn("duplicate TugJobDeclinedEvent for {}/{} — already refunded, ignoring",
                    event.conversationId(), event.tugId());
            return;
        }
        credit(IncomeRules.tugJobRefund(cost), IncomeRules.SOURCE_TUG_JOB_REFUND,
                event.vesselId(), event.simTimeMillis());
        log.info("{}: refunded €{} for {}'s declined award on {} ({})", event.vesselId(), cost,
                event.tugId(), event.conversationId(), event.reason());
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

    /** @return the premium credited, 0 if the port's standing has not earned one */
    private double creditPremium(double base, String vesselId, long simTime) {
        double premium = IncomeRules.premiumSurcharge(base, reputation.score());
        if (premium > 0) {
            credit(premium, IncomeRules.SOURCE_PREMIUM, vesselId, simTime);
        }
        return Math.max(0.0, premium);
    }

    /**
     * S17b (decision E6): the strip names money in exactly two cases — a walk-in deal and a
     * contracted fee, each as ONE line for what the ship paid (the fee and, if earned, the premium)
     * — plus the settlement's daily-costs line at midnight. Tug jobs, customs and surcharges move
     * the wallet without a line: a line per ledger entry was about fifteen in a few seconds.
     */
    private void announce(double amount, String what, String vesselId, long simTime) {
        if (amount <= 0) {
            return;
        }
        eventBus.publish(new it.unige.portcommand.gui.events.NotificationEvent(
                String.format(java.util.Locale.ROOT, "+€%,.0f %s %s", amount, what,
                        displayName.apply(vesselId)),
                it.unige.portcommand.gui.events.NotificationEvent.Severity.INFO, simTime));
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
