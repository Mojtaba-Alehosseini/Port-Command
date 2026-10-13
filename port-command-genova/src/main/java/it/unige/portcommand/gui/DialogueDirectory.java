package it.unige.portcommand.gui;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;

import it.unige.portcommand.gui.model.OpenDialogue;

/**
 * What the other two columns need from the chat column (S15): which negotiations are open, how
 * to bring one forward, and which vessel the player is looking at. {@code ChatPanel} implements
 * it; the waiting list and the comm log depend only on this, so each is testable with a fake.
 *
 * <p>GUI-to-GUI, hence plain listeners and no {@code EventBus} event (the bus is the
 * GUI&#8596;agents boundary). EDT only, every method.
 */
public interface DialogueDirectory {

    /** The negotiations still open (closed tabs excluded), in tab order. */
    List<OpenDialogue> openDialogues();

    /**
     * Selects that dialogue's tab and puts the keyboard focus in its input.
     *
     * @return {@code false} if there is no such tab
     */
    boolean selectDialogue(String dialogueId);

    /**
     * The remaining time that tab's own counter last showed, in milliseconds — empty when the
     * counter is blank (closed tab, or a dialogue the conversation clock does not know). The
     * waiting list reads THIS rather than the clock, so list and tab can never disagree.
     */
    OptionalLong shownRemainingMillis(String dialogueId);

    /** Runs {@code listener} whenever the answer to {@link #openDialogues()} or to
     * {@link #shownRemainingMillis} may have changed: a tab opened, closed, left the strip, the
     * world was replaced, and on every one-second counter refresh. */
    void addDialoguesListener(Runnable listener);

    /** Tells {@code listener} the vessel id of the selected tab — {@code null} when there is
     * none — each time it CHANGES. A closed tab that is still selected still reports its vessel. */
    void addSelectedVesselListener(Consumer<String> listener);
}
