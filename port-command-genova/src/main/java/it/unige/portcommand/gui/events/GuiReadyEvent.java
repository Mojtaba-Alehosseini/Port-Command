package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Fired once by {@code gui.MainWindow} after its first paint. {@code TutorialOverlay}
 * (task 21) and scenario boot (task 23) subscribe/poll to know the window is
 * actually on screen before acting. No payload — a pure readiness signal.
 */
public record GuiReadyEvent() implements Event {
}
