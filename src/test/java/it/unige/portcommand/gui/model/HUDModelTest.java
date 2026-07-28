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

    @Test
    void dailyTargetLabelShowsTheCanonicalFiveThousand() {
        assertEquals("Daily Target: €5,000", new HUDModel().dailyTargetLabel());
        assertEquals(5_000.0, Settings.load().dailyTargetEur(), 1e-9, "the canonical figure");
    }
}
