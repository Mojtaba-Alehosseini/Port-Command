package it.unige.portcommand.harbourmaster.financial;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.ontology.Deal;

/**
 * The canonical reputation delta per negotiation outcome (task 20), keyed by the single
 * canonical {@link Deal.Outcome} taxonomy so the table is exhaustive by construction — a new
 * outcome cannot be added without this class failing to load.
 *
 * <h2>Where these numbers come from (reconciliation, 2026-07-17)</h2>
 * {@code planning/20} cites "Job 2 §2.2" — <strong>job2 is not in this repository</strong>
 * (CLAUDE.md lists job1..job5 as historical rationale; only ADR-06/ADR-13 still quote them). So
 * the table could not be copied from its cited source. It is instead pinned from the two things
 * that ARE canonical and in-tree:
 * <ul>
 *   <li>the task-20 brief's stated values — deal closed +1, over-priced withdrawal −2, timeout −3;</li>
 *   <li>{@code docs/DEMO_SCRIPT_DRAFT.md}, which independently corroborates them: it shows
 *       "Reputation +1" on a walk-in dock (:154), "Reputation -2" on an over-priced withdrawal
 *       (:339), and a day summary of "50 (start) → 51 (end, +1 net)" over 4 served + 1 withdrawn
 *       (:362). That arithmetic only closes at +1 if <strong>contracted</strong> vessels earn no
 *       reputation and the three <strong>walk-in</strong> deals earn +1 each: 3×(+1) + (−2) = +1.
 *       4×(+1) + (−2) would be +2 and the demo would read 52.</li>
 * </ul>
 * That corroboration is why reputation is wired to {@code DealClosedEvent} (walk-in only) and NOT
 * to {@code ContractedFeeEarnedEvent} — a pre-agreed contract is not a negotiation you can handle
 * well or badly. {@code PLAYER_REFUSED} is <strong>0</strong>: no source gives it a value, and the
 * player deliberately declining a bad deal is a legitimate move, not a failure to punish. Both of
 * those are task-20 decisions, not recovered canon — revisit if job2 ever resurfaces.
 *
 * <p>Stored in {@code defaults.json}'s {@code reputation} block per {@code planning/20}'s "Hard
 * constraints" ("referenced but stored in defaults.json"), read here through this class's own
 * loader rather than {@code core.Settings} — Settings is a flat record of scalars, this is a
 * table. Same precedent as {@code LLMBridge} reading {@code llm.timeout_ms} independently.
 */
public final class ReputationRules {

    /** JSON key per outcome, in {@code defaults.json}'s {@code reputation} block. */
    private static final Map<Deal.Outcome, String> JSON_KEYS = Map.of(
            Deal.Outcome.DEAL, "deal_closed",
            Deal.Outcome.WITHDRAW_PRICE, "withdraw_price",
            Deal.Outcome.WITHDRAW_DURATION, "withdraw_duration",
            Deal.Outcome.TIMEOUT, "timeout",
            Deal.Outcome.PLAYER_REFUSED, "player_refused");

    private static final Map<Deal.Outcome, Double> DELTAS = readFromClasspath();
    private static final double DAILY_TARGET_BONUS = readReputationScalar("daily_target");
    private static final double TIMEOUT_UNENGAGED_DELTA = readReputationScalar("timeout_unengaged");
    private static final double TIMEOUT_UNENGAGED_DAILY_CAP =
            readReputationScalar("timeout_unengaged_daily_cap");

    private ReputationRules() {
    }

    /**
     * The per-day floor on CUMULATIVE unengaged-timeout reputation loss (task 23,
     * checkpoint-#6 balance call, 2026-07-18: 70 → 9 over 8 mostly-idle days — passive
     * walk-ins the player never touched were the dominant bleed). −5.0 canonical, a
     * {@code reputation}-block scalar like {@link #timeoutUnengagedDelta()}. Applies ONLY
     * to the unengaged split; engaged-timeout (−3) and every other outcome stay uncapped —
     * abandoning a negotiation you started remains fully priced.
     */
    public static double timeoutUnengagedDailyCap() {
        return TIMEOUT_UNENGAGED_DAILY_CAP;
    }

    /**
     * Day-scoped accumulator enforcing {@link #timeoutUnengagedDailyCap()}. Owned by
     * {@code LedgerCoordinator} (one per HarbourMaster life), reset on
     * {@code DayRolloverEvent}. Not persisted: a mid-day save/load restarts the day's
     * budget — worst case one extra capful that day, the same order as the pre-cap
     * behaviour and far inside the harm the cap exists to stop (documented limitation).
     */
    public static final class UnengagedTimeoutDailyCap {

        private double appliedToday;

        /**
         * Clamps {@code delta} (negative) so today's cumulative unengaged loss never
         * exceeds the cap; returns the portion to apply (0.0 once the budget is spent).
         */
        public double clamp(double delta) {
            double budget = timeoutUnengagedDailyCap() - appliedToday; // e.g. -5 - (-4) = -1
            double applied = Math.max(delta, Math.min(budget, 0.0));
            appliedToday += applied;
            return applied;
        }

        /** New day, fresh budget (the rollover hook). */
        public void resetForNewDay() {
            appliedToday = 0.0;
        }
    }

    /**
     * The reputation bonus for closing a day at or above {@code Settings.dailyTargetEur} net
     * (task 24, session brief 2026-07-17 — "+2 on hit, the table's row"). Lives in the same
     * {@code defaults.json} {@code reputation} block as the outcome deltas but NOT in
     * {@link #table()}: hitting a daily target is not a {@link Deal.Outcome}, and folding it in
     * would break the table's exhaustive-by-outcome contract. Sole consumer:
     * {@code DayRolloverCoordinator}'s EOD sequence.
     */
    public static double dailyTargetBonus() {
        return DAILY_TARGET_BONUS;
    }

    /**
     * The reputation delta for a walk-in that left without a deal, split by ENGAGEMENT for the
     * {@code TIMEOUT} outcome only (task 24, visual checkpoint #5 — Moji's balance call). A
     * walk-in the player never even countered (a never-engaged timeout) costs the gentler
     * {@link #timeoutUnengagedDelta()} (−1); one the player negotiated and then abandoned costs
     * the full {@code deltaFor(TIMEOUT)} (−3). Every other outcome ignores {@code playerEngaged}
     * and returns its canonical table value: {@code WITHDRAW_PRICE}/{@code WITHDRAW_DURATION}
     * necessarily engaged (the engine only ever evaluates a player counter), {@code PLAYER_REFUSED}
     * is 0 either way. This is the ONLY reputation path that reads engagement — the €5,200/day
     * pressure was making an untouched day bleed ~−24 reputation, so a passive walk-in the player
     * had no chance to reach is now a lighter touch than one they engaged and dropped.
     */
    public static double withdrawalDelta(Deal.Outcome outcome, boolean playerEngaged) {
        if (outcome == Deal.Outcome.TIMEOUT && !playerEngaged) {
            return TIMEOUT_UNENGAGED_DELTA;
        }
        return deltaFor(outcome);
    }

    /** The never-engaged timeout penalty (−1), a {@code reputation}-block scalar like the bonus,
     * NOT a {@link Deal.Outcome} row (there is one TIMEOUT outcome; engagement is a modifier). */
    public static double timeoutUnengagedDelta() {
        return TIMEOUT_UNENGAGED_DELTA;
    }

    /**
     * The reputation delta for {@code outcome} — positive rewards, negative penalises. Total by
     * construction: every {@link Deal.Outcome} has an entry or the class fails to initialise.
     */
    public static double deltaFor(Deal.Outcome outcome) {
        Double delta = DELTAS.get(outcome);
        if (delta == null) {
            throw new IllegalStateException("no reputation delta for outcome " + outcome);
        }
        return delta;
    }

    /** The whole canonical table, for the table-driven tests and the EOD/debug surfaces. */
    public static Map<Deal.Outcome, Double> table() {
        return DELTAS;
    }

    /** The hard-coded canonical values — also exactly what ships in {@code defaults.json}. */
    public static Map<Deal.Outcome, Double> defaults() {
        Map<Deal.Outcome, Double> table = new EnumMap<>(Deal.Outcome.class);
        table.put(Deal.Outcome.DEAL, 1.0);
        table.put(Deal.Outcome.WITHDRAW_PRICE, -2.0);
        // Task 19b: same shape of failure as a price withdrawal — a vessel left unserved over
        // terms that never met — so it carries the same penalty. No canonical source names it
        // (the outcome is new with §7.3's duration leg); mirroring withdraw_price is the
        // conservative reading, and the demo-script arithmetic above is untouched.
        table.put(Deal.Outcome.WITHDRAW_DURATION, -2.0);
        table.put(Deal.Outcome.TIMEOUT, -3.0);
        table.put(Deal.Outcome.PLAYER_REFUSED, 0.0);
        return Collections.unmodifiableMap(table);
    }

    /** Reads one numeric scalar from the {@code reputation} block (the non-{@link Deal.Outcome}
     * keys task 24 added: {@code daily_target}, {@code timeout_unengaged}). */
    private static double readReputationScalar(String key) {
        try (InputStream in = ReputationRules.class.getResourceAsStream("/data/defaults.json")) {
            if (in == null) {
                throw new IllegalStateException("defaults.json not found on classpath (/data/)");
            }
            JsonNode block = new ObjectMapper().readTree(in).get("reputation");
            JsonNode value = block == null ? null : block.get(key);
            if (value == null || !value.isNumber()) {
                throw new IllegalStateException(
                        "defaults.json reputation block is missing a numeric \"" + key + "\" (task 24)");
            }
            return value.doubleValue();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load defaults.json", e);
        }
    }

    private static Map<Deal.Outcome, Double> readFromClasspath() {
        try (InputStream in = ReputationRules.class.getResourceAsStream("/data/defaults.json")) {
            if (in == null) {
                throw new IllegalStateException("defaults.json not found on classpath (/data/)");
            }
            JsonNode block = new ObjectMapper().readTree(in).get("reputation");
            if (block == null) {
                throw new IllegalStateException(
                        "defaults.json has no \"reputation\" block — task 20 requires the canonical delta table");
            }
            Map<Deal.Outcome, Double> table = new EnumMap<>(Deal.Outcome.class);
            for (Deal.Outcome outcome : Deal.Outcome.values()) {
                JsonNode value = block.get(JSON_KEYS.get(outcome));
                if (value == null || !value.isNumber()) {
                    throw new IllegalStateException(
                            "defaults.json reputation block is missing a numeric \""
                                    + JSON_KEYS.get(outcome) + "\" (outcome " + outcome + ")");
                }
                table.put(outcome, value.doubleValue());
            }
            return Collections.unmodifiableMap(table);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load defaults.json", e);
        }
    }
}
