package it.unige.portcommand.gui.model;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.SimClockTickEvent;

/**
 * Pure rendering model for {@code HUDPanel}'s day/time display. No Swing/AWT import.
 *
 * <p>Wallet, reputation, and daily-target have no live data path yet — no event carries the
 * ledgers' values and no starting figure is published anywhere to compute an absolute number from
 * a delta (task 20/24 territory) — so {@code HUDPanel} renders those as static placeholder text
 * directly; this model deliberately carries no fields for them.
 */
public final class HUDModel {

    private int gameDay = 1;
    private int simHour;
    private int simMinute;

    public void onSimClockTick(SimClockTickEvent event) {
        this.gameDay = event.gameDay();
        this.simHour = event.simHour();
        this.simMinute = event.simMinute();
    }

    public String dayLabel() {
        return "Day " + gameDay + " of " + Settings.load().maxGameDays();
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
