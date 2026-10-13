package it.unige.portcommand.harbourmaster.financial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import it.unige.portcommand.gui.events.MovementSafetyEvent;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.ScoreChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import it.unige.portcommand.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the figures behind each game day's {@link DayScore} and announces the score as it moves
 * (S17b, ADR-22). Constructed by {@code HarbourMasterAgent.onSetup()} beside the
 * {@link LedgerCoordinator}; like it a plain bus subscriber, not a JADE behaviour, so the
 * behaviour catalogue does not move.
 *
 * <h2>Where each figure comes from</h2>
 * <ul>
 *   <li><b>Money</b> — every {@link WalletChangedEvent} that is a real booking (a non-zero delta),
 *       bucketed into the game day of its own sim time, the five fixed lines left out by source
 *       tag. The fixed cost is added by {@link SessionTerms}, hour by hour. A load re-seeds the
 *       running figure from the restored day's ledger entries ({@link #restore}).</li>
 *   <li><b>Walk-ins</b> — told by the {@link LedgerCoordinator}, which already sees every walk-in
 *       outcome exactly once ({@link #walkInDeal}, {@link #walkInLeft}). A walk-in a save dropped
 *       in mid-talk never has an outcome, so it is never counted.</li>
 *   <li><b>Safety</b> — {@link MovementSafetyEvent}, published at the movement gate.</li>
 *   <li><b>Reputation</b> — every {@link ReputationChangedEvent} that is a real adjustment, as the
 *       change it actually made (the ledger clamps at 0 and 100).</li>
 * </ul>
 *
 * <h2>Days</h2>
 * A figure belongs to the game day of the event's own sim time, never to "the day it arrived
 * in": midnight is detected up to a game minute late, and a deal closed in that minute belongs
 * to the new day. {@link #closeDay} is called by the end-of-day settlement with the day's settled
 * net and returns the day's final score.
 *
 * <h2>Threading</h2>
 * Every figure is published on the HarbourMaster's thread, but {@code GameSession} seeds the HUD
 * from other threads and may ask for the score there ({@link #publishNow}), so the state is
 * guarded by this object's monitor. Nothing is called under it except the bus — in particular no
 * ledger is read, because the wallet publishes inside its own lock and a second lock order would
 * be a deadlock waiting for a window to open.
 */
public final class ScoreKeeper {

    private static final Logger log = LoggerFactory.getLogger(ScoreKeeper.class);

    /** One game day's figures. Guarded by the keeper's monitor. */
    private static final class Tally {
        double incomeMinusVariable;
        int walkInDeals;
        int walkInsResolved;
        int unsafeMoves;
        int blockedMoves;
        double reputationChange;
    }

    /**
     * The current day's counters as a save carries them (additive within schema v1; a save
     * written before S17b has none and loads as a day with nothing counted yet). The money is
     * not here: it is re-read from the day's ledger entries, which the save already holds.
     */
    public record Saved(int walkInDeals, int walkInsResolved, int unsafeMoves, int blockedMoves,
                        double reputationChange) {
    }

    private final EventBus eventBus;
    private final SimClock simClock;
    private final WalletLedger walletLedger;
    private final SessionTerms terms;
    private final Map<Integer, Tally> tallies = new HashMap<>();
    private final List<Subscription<?>> subscriptions = new ArrayList<>();
    private double lastReputation;
    private ScoreChangedEvent lastPublished;

    public ScoreKeeper(EventBus eventBus, SimClock simClock, WalletLedger walletLedger,
                       ReputationLedger reputationLedger, SessionTerms terms) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.simClock = Objects.requireNonNull(simClock, "simClock");
        this.walletLedger = Objects.requireNonNull(walletLedger, "walletLedger");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.lastReputation = Objects.requireNonNull(reputationLedger, "reputationLedger").score();
        seedMoneyFromLedger();
        subscriptions.add(eventBus.subscribe(
                WalletChangedEvent.class, this::onWalletChanged, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                ReputationChangedEvent.class, this::onReputationChanged, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                MovementSafetyEvent.class, this::onMovementSafety, DeliveryMode.CALLER_THREAD));
        subscriptions.add(eventBus.subscribe(
                SimClockTickEvent.class, e -> publishIfChanged(), DeliveryMode.CALLER_THREAD));
    }

    // ---- the figures ---------------------------------------------------------------------

    /** A real booking moved the wallet. The five fixed lines are the settlement's, not the day's
     * running figure; a zero delta is a HUD seed, not money. */
    private synchronized void onWalletChanged(WalletChangedEvent event) {
        if (event.delta() == 0.0 || ExpenseRules.isFixedSource(event.source())) {
            return;
        }
        tally(SimClock.gameDayOf(event.simTimeMillis())).incomeMinusVariable += event.delta();
        publishIfChanged();
    }

    /** A real adjustment moved the reputation — by what it actually moved, clamp included. */
    private synchronized void onReputationChanged(ReputationChangedEvent event) {
        if (event.delta() == 0.0) {
            return; // a HUD seed (gui_ready / game_loaded) carries the level and no change
        }
        double applied = event.score() - lastReputation;
        lastReputation = event.score();
        tally(SimClock.gameDayOf(event.simTimeMillis())).reputationChange += applied;
        publishIfChanged();
    }

    private synchronized void onMovementSafety(MovementSafetyEvent event) {
        Tally tally = tally(SimClock.gameDayOf(event.simTimeMillis()));
        if (event.kind() == MovementSafetyEvent.Kind.UNSAFE_MOVE) {
            tally.unsafeMoves++;
            log.error("{}: an UNSAFE MOVE was ordered {} - the movement gate did not stop it",
                    event.vesselId(), event.ruleIds());
        } else {
            tally.blockedMoves++;
        }
        publishIfChanged();
    }

    /** A walk-in's talk ended in a deal. Called once per walk-in by the {@link LedgerCoordinator}. */
    public synchronized void walkInDeal(long simTimeMillis) {
        Tally tally = tally(SimClock.gameDayOf(simTimeMillis));
        tally.walkInDeals++;
        tally.walkInsResolved++;
        publishIfChanged();
    }

    /** A walk-in left without a deal. Called once per walk-in by the {@link LedgerCoordinator}. */
    public synchronized void walkInLeft(long simTimeMillis) {
        tally(SimClock.gameDayOf(simTimeMillis)).walkInsResolved++;
        publishIfChanged();
    }

    // ---- the score -----------------------------------------------------------------------

    /** The score of the day the clock is in, with the fixed cost of the hours behind it. */
    public synchronized DayScore current() {
        long now = simClock.nowSimMillis();
        int day = SimClock.gameDayOf(now);
        Tally tally = tally(day);
        return score(day, tally, tally.incomeMinusVariable - terms.fixedCostSoFar(day, now));
    }

    /**
     * Closes {@code day}: its final score, on the SETTLED net the end-of-day settlement worked
     * out from the ledger (income, variable costs and the whole fixed cost the day owed). The
     * day's figures are dropped — and any older day's with them — and the new day's score is
     * announced.
     */
    public synchronized DayScore closeDay(int day, double settledNet) {
        DayScore closed = score(day, tally(day), settledNet);
        tallies.keySet().removeIf(d -> d <= day);
        publishIfChanged();
        return closed;
    }

    /** Announces the current score even if it has not changed — for a window that just opened.
     * Safe from any thread. */
    public synchronized void publishNow() {
        lastPublished = null;
        publishIfChanged();
    }

    // ---- save / load ---------------------------------------------------------------------

    /** The current day's counters, for the save. */
    public synchronized Saved snapshot() {
        Tally tally = tally(SimClock.gameDayOf(simClock.nowSimMillis()));
        return new Saved(tally.walkInDeals, tally.walkInsResolved, tally.unsafeMoves,
                tally.blockedMoves, tally.reputationChange);
    }

    /**
     * After a load: the saved counters become the current day's, and the day's money is read
     * again from the ledger, whose history the load has just restored. {@code saved} is
     * {@code null} for a save written before S17b.
     */
    public synchronized void restore(Saved saved) {
        tallies.clear();
        seedMoneyFromLedger();
        if (saved != null) {
            Tally tally = tally(SimClock.gameDayOf(simClock.nowSimMillis()));
            tally.walkInDeals = Math.max(0, saved.walkInDeals());
            tally.walkInsResolved = Math.max(tally.walkInDeals, saved.walkInsResolved());
            tally.unsafeMoves = Math.max(0, saved.unsafeMoves());
            tally.blockedMoves = Math.max(0, saved.blockedMoves());
            tally.reputationChange = saved.reputationChange();
        }
    }

    /** Stops listening. The HarbourMaster's {@code onTakeDown()} calls this — the bus outlives
     * the agent, and a dead world's keeper must not go on announcing its score. */
    public void close() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    // ---- internals -----------------------------------------------------------------------

    /** Construction and load only: no other thread can hold the wallet's lock against us yet. */
    private void seedMoneyFromLedger() {
        int day = SimClock.gameDayOf(simClock.nowSimMillis());
        tally(day).incomeMinusVariable = IncomeRules.aggregateForDay(walletLedger, day)
                - ExpenseRules.variableForDay(walletLedger, day);
    }

    private Tally tally(int day) {
        return tallies.computeIfAbsent(day, d -> new Tally());
    }

    private DayScore score(int day, Tally tally, double net) {
        return new DayScore(day, net, terms.targetEur(day), tally.walkInDeals, tally.walkInsResolved,
                tally.unsafeMoves, tally.blockedMoves, tally.reputationChange);
    }

    /** Synchronized in its own right: the tick subscription calls it directly, and a tick may be
     * {@code GameSession}'s HUD seed on another thread - which could otherwise publish a figure
     * read before a booking after the HarbourMaster published the one read after it. */
    private synchronized void publishIfChanged() {
        DayScore score = current();
        ScoreChangedEvent event = new ScoreChangedEvent(score, terms.scenarioName(score.gameDay()));
        if (event.equals(lastPublished)) {
            return;
        }
        lastPublished = event;
        eventBus.publish(event);
    }
}
