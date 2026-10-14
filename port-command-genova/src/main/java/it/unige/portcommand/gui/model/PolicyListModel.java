package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The autopilot policies in force, as the window last heard them (S18a, ADR-23, decision N11).
 * {@code MainWindow} keeps one for its lifetime and feeds it from {@code PolicyListEvent}; the
 * Settings form — which exists only while its dialog is open — reads it when it opens and listens
 * while it is on screen. Plain Java, no widget toolkit import and no thread assertion: the window
 * delivers on the EDT, tests call it straight from the JUnit thread.
 *
 * <p>It holds text, not rules: the list is what the Assistant SAYS it holds, each rule as the
 * parser understood it. The GUI never reads the registry itself.
 */
public final class PolicyListModel {

    /** What the list reads as when there is nothing in it. */
    public static final String NONE = "none";

    private List<String> descriptions = List.of();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Replaces the list and tells every listener — also when nothing changed, which is cheap and
     * keeps "the Assistant answered" observable. */
    public void set(List<String> newDescriptions) {
        descriptions = List.copyOf(newDescriptions);
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    /** The rules in the order they are consulted (the first that matches wins). */
    public List<String> descriptions() {
        return descriptions;
    }

    public boolean isEmpty() {
        return descriptions.isEmpty();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Test seam: how many forms are listening (a closed dialog must not be one of them). */
    public int listenerCount() {
        return listeners.size();
    }
}
