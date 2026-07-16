package it.unige.portcommand.gui.model;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.SimClockTickEvent;
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
}
