package it.unige.portcommand.gui.model;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HUDModelTest {

    @Test
    void defaultsToDayOneMidnightBeforeAnyTick() {
        HUDModel model = new HUDModel();

        assertEquals(1, model.gameDay());
        assertEquals(0, model.simHour());
        assertEquals(0, model.simMinute());
        assertEquals("00:00", model.timeLabel());
        assertEquals("Day 1 of " + Settings.load().maxGameDays(), model.dayLabel());
    }

    @Test
    void onSimClockTickUpdatesDayAndTime() {
        HUDModel model = new HUDModel();

        model.onSimClockTick(new SimClockTickEvent(52_260_000L, 3, 14, 31));

        assertEquals(3, model.gameDay());
        assertEquals(14, model.simHour());
        assertEquals(31, model.simMinute());
        assertEquals("14:31", model.timeLabel());
        assertEquals("Day 3 of " + Settings.load().maxGameDays(), model.dayLabel());
    }

    @Test
    void timeLabelZeroPadsSingleDigitHourAndMinute() {
        HUDModel model = new HUDModel();

        model.onSimClockTick(new SimClockTickEvent(0L, 1, 5, 7));

        assertEquals("05:07", model.timeLabel());
    }

    @Test
    void aLaterTickOverwritesAnEarlierOneRatherThanAccumulating() {
        HUDModel model = new HUDModel();

        model.onSimClockTick(new SimClockTickEvent(0L, 1, 9, 0));
        model.onSimClockTick(new SimClockTickEvent(60_000L, 1, 9, 1));

        assertEquals("09:01", model.timeLabel(), "must reflect only the latest tick");
    }

    // ---- wallet / reputation went live in task 20 ----

    @Test
    void walletAndReputationShowTheTaskEighteenPlaceholderUntilTheFirstEvent() {
        HUDModel model = new HUDModel();

        assertEquals("Wallet: —", model.walletLabel(), "no false EUR0 before any money event");
        assertEquals("Reputation: —", model.reputationLabel());
    }

    @Test
    void walletLabelShowsThePublishedAbsoluteBalance() {
        HUDModel model = new HUDModel();

        model.onWalletChanged(new WalletChangedEvent(52_200.0, 2_200.0, "berth_base", "V1", 0L));

        assertEquals("Wallet: €52,200", model.walletLabel());
    }

    @Test
    void reputationLabelRoundsThePublishedScore() {
        HUDModel model = new HUDModel();

        model.onReputationChanged(new ReputationChangedEvent(51.4, 1.0, "deal_closed", "V1", 0L));

        assertEquals("Reputation: 51", model.reputationLabel());
    }

    /**
     * The model assigns the absolute, never accumulates the delta — so a missed or out-of-order
     * event self-corrects on the next one instead of leaving the HUD permanently wrong.
     */
    @Test
    void aLaterWalletEventOverwritesRatherThanAccumulating() {
        HUDModel model = new HUDModel();

        model.onWalletChanged(new WalletChangedEvent(52_200.0, 2_200.0, "berth_base", "V1", 0L));
        model.onWalletChanged(new WalletChangedEvent(51_800.0, -400.0, "tug_job", "V1", 10L));

        assertEquals("Wallet: €51,800", model.walletLabel(), "the absolute, not 52200-400 accumulated");
    }

    @Test
    void aNegativeWalletRenders() {
        HUDModel model = new HUDModel();

        model.onWalletChanged(new WalletChangedEvent(-1_250.0, -5_200.0, "salaries", null, 0L));

        assertEquals("Wallet: €-1,250", model.walletLabel());
    }

    // ---- S17b (ADR-22): the score, the profit against the target, the scenario's name ----

    private static it.unige.portcommand.harbourmaster.financial.DayScore day(double net, double target) {
        return new it.unige.portcommand.harbourmaster.financial.DayScore(1, net, target, 2, 3, 0, 1, 1.0);
    }

    @Test
    void untilAWorldAnnouncesItsScoreTheCellsShowADash() {
        HUDModel model = new HUDModel();

        assertEquals("Today: —", model.profitLabel());
        assertEquals("Score: —", model.scoreLabel());
        assertEquals(null, model.scoreTooltip());
        assertEquals(null, model.profitTooltip());
    }

    /** Decisions E3/E4: the profit so far - daily costs already in it - against the SESSION's target. */
    @Test
    void theProfitCellShowsTodaysNetAgainstTheTargetThatCameWithTheScore() {
        HUDModel model = new HUDModel();

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(2_960.4, 3_500.0), null));

        assertEquals("Today: €2,960 / €3,500", model.profitLabel(),
                "the target is the one the event carries, not Settings' global figure");
        org.junit.jupiter.api.Assertions.assertNotEquals(3_500.0, Settings.load().dailyTargetEur(), 1e-9,
                "fixture: the global figure differs");
    }

    @Test
    void aLossIsShownWithAMinusSignAndAFewCentsUnderZeroAsZero() {
        HUDModel model = new HUDModel();

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(-1_183.0, 5_000.0), null));
        assertEquals("Today: −€1,183 / €5,000", model.profitLabel());

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(-0.3, 5_000.0), null));
        assertEquals("Today: €0 / €5,000", model.profitLabel());
    }

    @Test
    void theScoreCellShowsTheTotalAndItsHoverTheFourParts() {
        HUDModel model = new HUDModel();

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(3_640.0, 5_000.0), null));

        assertEquals("Score 75", model.scoreLabel());
        assertEquals(java.util.List.of(
                "Money       18.2 / 25   net €3,640 of the €5,000 target",
                "Walk-ins    16.7 / 25   2 deals of 3 ships",
                "Safety      25.0 / 25   unsafe moves 0 · blocked: 1",
                "Reputation  15.0 / 25   +1 today"),
                HUDModel.scoreLines(day(3_640.0, 5_000.0)));
        assertEquals("<html>" + String.join("<br>", HUDModel.scoreLines(day(3_640.0, 5_000.0))) + "</html>",
                model.scoreTooltip());
    }

    @Test
    void thePartsAreWordedForOneShipNoShipAndAFallingReputation() {
        var quiet = new it.unige.portcommand.harbourmaster.financial.DayScore(1, 0, 5_000, 0, 0, 0, 0, 0.0);
        var one = new it.unige.portcommand.harbourmaster.financial.DayScore(1, 0, 5_000, 1, 1, 0, 0, -2.0);

        assertEquals("Walk-ins    25.0 / 25   no walk-in has left or closed a deal yet",
                HUDModel.scoreLines(quiet).get(1));
        assertEquals("Reputation  12.5 / 25   ±0 today", HUDModel.scoreLines(quiet).get(3));
        assertEquals("Walk-ins    25.0 / 25   1 deal of 1 ship", HUDModel.scoreLines(one).get(1));
        assertEquals("Reputation   7.5 / 25   −2 today", HUDModel.scoreLines(one).get(3));
    }

    /** Decision E10: a scenario is one day long, so the day cell names the scenario instead. */
    @Test
    void whileAScenarioRunsTheDayCellShowsItsShortNameAndTheHoverTheWholeOne() {
        HUDModel model = new HUDModel();

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(
                day(0, 12_000.0), "Busy Day — Stress Test"));
        assertEquals("Busy Day", model.dayLabel());
        assertEquals("Busy Day — Stress Test — the scenario ends at midnight", model.dayTooltip());

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(0, 8_000.0), "Storm Event"));
        assertEquals("Storm Event", model.dayLabel(), "a name with no dash is shown whole");

        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(0, 5_000.0), null));
        assertEquals("Day 1 of " + Settings.load().maxGameDays(), model.dayLabel(), "free play: the day again");
        assertEquals(null, model.dayTooltip());
    }

    @Test
    void aReplacedWorldTakesItsScoreAndItsScenarioWithIt() {
        HUDModel model = new HUDModel();
        model.onScoreChanged(new it.unige.portcommand.gui.events.ScoreChangedEvent(day(100, 8_000.0), "Storm Event"));

        model.onWorldReplaced();

        assertEquals("Score: —", model.scoreLabel());
        assertEquals("Today: —", model.profitLabel());
        assertEquals("Day 1 of " + Settings.load().maxGameDays(), model.dayLabel());
    }
}
