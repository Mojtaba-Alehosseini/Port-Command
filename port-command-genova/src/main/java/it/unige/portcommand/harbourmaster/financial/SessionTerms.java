package it.unige.portcommand.harbourmaster.financial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.util.SimClock;

/**
 * What a game day is measured against in THIS session (S17b, ADR-22): its target, and how much of
 * the daily fixed cost it owes. Pure, and derived — nothing here is saved.
 *
 * <p><b>The target belongs to the session, not to Settings.</b> A scenario declares its own target
 * ({@code targetDailyRevenue}); it is used on the scenario's day and nowhere else. Free play, and
 * a scenario the player carries on with after its first midnight, use the player's own
 * {@code Settings.dailyTargetEur}. Before S17b the scenario's figure was shown in the New Game
 * dialog and then never used: the top bar and the end-of-day bonus both read the global €5,000.
 *
 * <p><b>The scenario is "live" for one day.</b> A scenario ends at its first midnight. That is
 * derived from the game day alone ({@link #scenarioLive}), so it survives a save and a load with
 * no flag: a save from the scenario's day loads as the scenario, a later one as free play.
 *
 * <p><b>A day pays for the hours it has.</b> The fixed cost is €5,200 per 24 hours
 * ({@link ExpenseRules#dailyFixed()}, design rule 7 — the rate is not touched). It is charged
 * by the hour: one twenty-fourth each time the clock passes a whole hour, so the profit shown
 * during the day already carries the cost of the hours behind it instead of dropping by €5,200 at
 * midnight. A game that opens at 08:00 has sixteen hours in its first day and owes sixteen
 * twenty-fourths ({@link #chargeableHours}); every later day owes the whole.
 */
public final class SessionTerms {

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final int HOURS_PER_DAY = 24;

    private final String scenarioName;
    private final double scenarioTargetEur;
    private final long startSimMillis;

    private SessionTerms(String scenarioName, double scenarioTargetEur, long startSimMillis) {
        this.scenarioName = scenarioName;
        this.scenarioTargetEur = scenarioTargetEur;
        this.startSimMillis = startSimMillis;
    }

    /** Free play: every day is a whole day and is measured against the player's own target. */
    public static SessionTerms freePlay() {
        return new SessionTerms(null, 0.0, 0L);
    }

    /**
     * A scenario: its name and target hold for the day it starts in, and that day is charged from
     * the hour it starts at.
     *
     * @param displayName    the scenario's name as the player sees it
     * @param targetEur      the scenario's own target net for its day
     * @param startSimMillis the sim instant the scenario opens at
     */
    public static SessionTerms scenario(String displayName, double targetEur, long startSimMillis) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("a scenario needs a name");
        }
        if (startSimMillis < 0) {
            throw new IllegalArgumentException("startSimMillis must be >= 0: " + startSimMillis);
        }
        return new SessionTerms(displayName, targetEur, startSimMillis);
    }

    /** Whether {@code gameDay} is the scenario's own day — the one it is scored on and ends with. */
    public boolean scenarioLive(int gameDay) {
        return scenarioName != null && gameDay == SimClock.gameDayOf(startSimMillis);
    }

    /** The scenario's name on its own day, {@code null} on every other day and in free play. */
    public String scenarioName(int gameDay) {
        return scenarioLive(gameDay) ? scenarioName : null;
    }

    /** The target net for {@code gameDay}: the scenario's on its day, the player's otherwise. */
    public double targetEur(int gameDay) {
        return scenarioLive(gameDay) ? scenarioTargetEur : Settings.load().dailyTargetEur();
    }

    /** How many of {@code gameDay}'s 24 hours this session has: 24, or fewer on its first day. */
    public int chargeableHours(int gameDay) {
        return HOURS_PER_DAY - firstHour(gameDay);
    }

    /**
     * How many of {@code gameDay}'s hours have been charged by {@code nowSimMillis}: one for each
     * whole clock hour that has passed since the day (or the session) began. All of them once the
     * day is over, none before it starts.
     */
    public int hoursCharged(int gameDay, long nowSimMillis) {
        long dayStart = (gameDay - 1L) * MILLIS_PER_DAY;
        if (nowSimMillis < dayStart) {
            return 0;
        }
        if (nowSimMillis >= dayStart + MILLIS_PER_DAY) {
            return chargeableHours(gameDay);
        }
        int clockHour = (int) ((nowSimMillis - dayStart) / MILLIS_PER_HOUR);
        return Math.max(0, clockHour - firstHour(gameDay));
    }

    /** The fixed cost charged for {@code gameDay} so far, in €. */
    public double fixedCostSoFar(int gameDay, long nowSimMillis) {
        return ExpenseRules.dailyFixed() * hoursCharged(gameDay, nowSimMillis) / HOURS_PER_DAY;
    }

    /** The whole fixed cost {@code gameDay} owes, in €: €5,200 for a 24-hour day. */
    public double fixedCostForDay(int gameDay) {
        return ExpenseRules.dailyFixed() * chargeableHours(gameDay) / HOURS_PER_DAY;
    }

    /**
     * {@link ExpenseRules#dailyFixedBreakdown()}'s five lines, each cut to the hours
     * {@code gameDay} has — what the end-of-day settlement books. A 24-hour day returns the
     * canonical figures themselves.
     */
    public Map<String, Double> fixedBreakdownForDay(int gameDay) {
        int hours = chargeableHours(gameDay);
        if (hours == HOURS_PER_DAY) {
            return ExpenseRules.dailyFixedBreakdown();
        }
        Map<String, Double> lines = new LinkedHashMap<>();
        ExpenseRules.dailyFixedBreakdown().forEach(
                (source, amount) -> lines.put(source, amount * hours / HOURS_PER_DAY));
        return Collections.unmodifiableMap(lines);
    }

    /** The clock hour {@code gameDay} starts at for this session: 0, or the opening hour. */
    private int firstHour(int gameDay) {
        if (gameDay != SimClock.gameDayOf(startSimMillis)) {
            return 0;
        }
        return (int) ((startSimMillis % MILLIS_PER_DAY) / MILLIS_PER_HOUR);
    }
}
