package it.unige.portcommand.gui;

import java.awt.Color;

import it.unige.portcommand.gui.model.PatienceCounterModel;

/**
 * The three colours of a walk-in's time counter — normal, under 30 s, under 10 s — in ONE place
 * (S15), because two views now draw the same number: the tab's counter and the "Waiting for you"
 * list. The thresholds are {@link PatienceCounterModel}'s; this only maps its levels to AWT
 * colours, which is why it lives here and not in the Swing-free {@code gui.model}.
 */
public final class CounterColours {

    public static final Color NORMAL = new Color(0x33, 0x33, 0x33);
    public static final Color WARNING = new Color(0xE0, 0x7B, 0x00);
    public static final Color CRITICAL = new Color(0xC6, 0x28, 0x28);

    private CounterColours() {
    }

    public static Color of(PatienceCounterModel.Level level) {
        return switch (level) {
            case NORMAL -> NORMAL;
            case WARNING -> WARNING;
            case CRITICAL -> CRITICAL;
        };
    }
}
