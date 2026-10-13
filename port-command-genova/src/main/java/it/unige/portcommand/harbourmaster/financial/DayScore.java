package it.unige.portcommand.harbourmaster.financial;

/**
 * One game day's score (S17b, ADR-22): four parts of 25 points, 100 in all — the proposal's
 * "scored on money earned, vessels handled, safety incidents and reputation against a target".
 * Pure: the figures go in, the points come out. {@link ScoreKeeper} collects the figures.
 *
 * <ul>
 *   <li><b>Money</b> — the day's net profit against the day's target, 0 to 25. The net already
 *       carries the daily fixed cost for the hours that have passed ({@link SessionTerms}).</li>
 *   <li><b>Walk-ins</b> — deals closed out of walk-in talks that ENDED today (deal, or the ship
 *       left). A talk still open counts for neither, so a ship that has only just hailed does not
 *       lower the score; a day on which no talk has ended scores the full 25.</li>
 *   <li><b>Safety</b> — 25, minus 5 for every unsafe move that was actually carried out. The
 *       movement gate should keep that at 0; {@link #blockedMoves} is how often it had to.</li>
 *   <li><b>Reputation</b> — 12.5 for an unchanged reputation, plus 2.5 per point gained today,
 *       0 to 25. Today's CHANGE, not the level: the scenarios start at 50, 60 and 70.</li>
 * </ul>
 *
 * @param gameDay          1-based game day the score is for
 * @param netProfit        income − variable costs − the fixed cost charged so far, in €
 * @param targetEur        the day's target net, in € (the scenario's own while a scenario runs)
 * @param walkInDeals      walk-in deals closed today
 * @param walkInsResolved  walk-in talks that ended today, deals included
 * @param unsafeMoves      move orders issued while the movement gate said the move was unsafe
 * @param blockedMoves     movements the gate refused: once per ship per hold
 * @param reputationChange today's change in reputation, in points
 */
public record DayScore(int gameDay, double netProfit, double targetEur, int walkInDeals,
                       int walkInsResolved, int unsafeMoves, int blockedMoves, double reputationChange) {

    /** Each of the four parts is worth this much. */
    public static final double PART_MAX = 25.0;
    /** Points lost per unsafe move carried out. */
    public static final double UNSAFE_MOVE_COST = 5.0;
    /** The reputation part of a day on which the reputation did not move. */
    public static final double REPUTATION_BASE = 12.5;
    /** Reputation-part points per reputation point gained (or lost) today. */
    public static final double REPUTATION_STEP = 2.5;

    public DayScore {
        if (walkInDeals < 0 || walkInsResolved < walkInDeals || unsafeMoves < 0 || blockedMoves < 0) {
            throw new IllegalArgumentException("impossible day counters: deals=" + walkInDeals
                    + " resolved=" + walkInsResolved + " unsafe=" + unsafeMoves + " blocked=" + blockedMoves);
        }
    }

    /** Net profit against the target. With no positive target a day that lost nothing has met it. */
    public double moneyPoints() {
        if (targetEur <= 0) {
            return netProfit >= 0 ? PART_MAX : 0.0;
        }
        return clamp(netProfit / targetEur * PART_MAX);
    }

    public double walkInPoints() {
        return walkInsResolved == 0 ? PART_MAX : clamp(PART_MAX * walkInDeals / walkInsResolved);
    }

    public double safetyPoints() {
        return clamp(PART_MAX - UNSAFE_MOVE_COST * unsafeMoves);
    }

    public double reputationPoints() {
        return clamp(REPUTATION_BASE + REPUTATION_STEP * reputationChange);
    }

    /** The four parts added up, 0 to 100, unrounded. */
    public double totalPoints() {
        return moneyPoints() + walkInPoints() + safetyPoints() + reputationPoints();
    }

    /** The score as it is shown: a whole number from 0 to 100. */
    public int total() {
        return (int) Math.round(totalPoints());
    }

    private static double clamp(double points) {
        return Math.max(0.0, Math.min(PART_MAX, points));
    }
}
