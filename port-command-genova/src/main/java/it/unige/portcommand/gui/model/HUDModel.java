package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.ScoreChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.harbourmaster.financial.DayScore;
import it.unige.portcommand.gui.events.WalletChangedEvent;

/**
 * Pure rendering model for {@code HUDPanel}. No Swing/AWT import.
 *
 * <p>Wallet and reputation went LIVE in task 20: {@code WalletChangedEvent} /
 * {@code ReputationChangedEvent} carry the post-mutation ABSOLUTE figure, which is what the
 * task-18 placeholders were waiting for — a delta alone was unusable because no starting figure
 * was published anywhere. This model therefore never accumulates deltas; it assigns the absolute,
 * so a missed event self-corrects on the next one.
 *
 * <p>Until the first money event of a run arrives there is genuinely nothing to show — the ledger
 * seeds are agent-side ({@code HarbourMasterInitArgs}) and unpublished — so the wallet/reputation
 * labels keep task 18's {@code "—"} placeholder rather than claiming a false €0/0.
 *
 * <p>Money is formatted with {@link Locale#ROOT} explicitly: the default locale would render the
 * thousands separator differently on Moji's box than in CI.
 */
public final class HUDModel {

    private static final String NO_DATA = "—";

    private int gameDay = 1;
    private int simHour;
    private int simMinute;
    private Double wallet;
    private Double reputation;
    /** S17b: the day's score as last announced; {@code null} until a world announces one. */
    private DayScore score;
    /** S17b: the scenario's display name while its own day runs, else {@code null}. */
    private String scenarioName;

    public void onSimClockTick(SimClockTickEvent event) {
        this.gameDay = event.gameDay();
        this.simHour = event.simHour();
        this.simMinute = event.simMinute();
    }

    public void onWalletChanged(WalletChangedEvent event) {
        this.wallet = event.balance();
    }

    public void onReputationChanged(ReputationChangedEvent event) {
        this.reputation = event.score();
    }

    /** S17b (ADR-22): the HarbourMaster's score keeper announced the day's score. */
    public void onScoreChanged(ScoreChangedEvent event) {
        this.score = event.score();
        this.scenarioName = event.scenarioName();
    }

    /** S17b: a world was replaced - its score, target and scenario are not the next world's. */
    public void onWorldReplaced() {
        this.score = null;
        this.scenarioName = null;
    }

    public String walletLabel() {
        return "Wallet: " + (wallet == null ? NO_DATA : String.format(Locale.ROOT, "€%,.0f", wallet));
    }

    public String reputationLabel() {
        return "Reputation: " + (reputation == null ? NO_DATA : String.valueOf(Math.round(reputation)));
    }

    /**
     * S17b (decisions E3, E4): today's profit so far against today's target - "Today: €2,960 /
     * €5,000". Both figures come with the score: the profit already carries the daily costs of
     * the hours that have passed, and the target is the SESSION's (a scenario's own while it
     * runs), not a global setting. Before S17b this cell was a static "Daily Target: €5,000"
     * read once from Settings.
     */
    public String profitLabel() {
        if (score == null) {
            return "Today: " + NO_DATA;
        }
        return "Today: " + euro(score.netProfit()) + " / " + euro(score.targetEur());
    }

    public String profitTooltip() {
        return score == null ? null
                : "Today's profit so far against today's target. The daily costs (€5,200 a day) "
                        + "are taken off hour by hour.";
    }

    /** S17b: "Score 88" - the four parts added up; a dash until a world announces its score. */
    public String scoreLabel() {
        return score == null ? "Score: " + NO_DATA : "Score " + score.total();
    }

    /**
     * S17b: the four parts behind the score, for the hover - each with its points out of 25 and
     * the figures it was worked out from. HTML so that Swing shows it on four lines.
     */
    public String scoreTooltip() {
        if (score == null) {
            return null;
        }
        return "<html>" + String.join("<br>", scoreLines(score)) + "</html>";
    }

    /** The four parts as plain lines - shared by the top bar's hover and the end-of-day window. */
    public static java.util.List<String> scoreLines(DayScore score) {
        return java.util.List.of(
                part("Money", score.moneyPoints(),
                        "net " + euro(score.netProfit()) + " of the " + euro(score.targetEur()) + " target"),
                part("Walk-ins", score.walkInPoints(), score.walkInsResolved() == 0
                        ? "no walk-in has left or closed a deal yet"
                        : score.walkInDeals() + " deal" + (score.walkInDeals() == 1 ? "" : "s") + " of "
                                + score.walkInsResolved() + " ship" + (score.walkInsResolved() == 1 ? "" : "s")),
                part("Safety", score.safetyPoints(),
                        "unsafe moves " + score.unsafeMoves() + " · blocked: " + score.blockedMoves()),
                part("Reputation", score.reputationPoints(),
                        signed(score.reputationChange()) + " today"));
    }

    private static String part(String name, double points, String detail) {
        return String.format(Locale.ROOT, "%-11s %4.1f / 25   %s", name, points, detail);
    }

    private static String signed(double change) {
        long whole = Math.round(change);
        return whole > 0 ? "+" + whole : whole < 0 ? "−" + (-whole) : "±0";
    }

    /** {@code €2,960}, or {@code −€1,183} for a loss - never "-€0" for a few cents under zero. */
    static String euro(double amount) {
        long whole = Math.round(amount);
        return (whole < 0 ? "−" : "") + String.format(Locale.ROOT, "€%,d", Math.abs(whole));
    }

    /**
     * "Day 3 of 30" - or, while a scenario's own day runs, the scenario (S17b, decision E10): a
     * scenario is one day long, so "Day 1 of 30" was simply wrong for it. The bar shows the short
     * name ("Tutorial"); {@link #dayTooltip()} has the whole one.
     */
    public String dayLabel() {
        if (scenarioName != null) {
            int dash = scenarioName.indexOf(" — ");
            return dash > 0 ? scenarioName.substring(0, dash) : scenarioName;
        }
        return "Day " + gameDay + " of " + Settings.load().maxGameDays();
    }

    public String dayTooltip() {
        return scenarioName == null ? null : scenarioName + " — the scenario ends at midnight";
    }

    public String timeLabel() {
        return String.format("%02d:%02d", simHour, simMinute);
    }

    public int gameDay() {
        return gameDay;
    }

    public int simHour() {
        return simHour;
    }

    public int simMinute() {
        return simMinute;
    }
}
