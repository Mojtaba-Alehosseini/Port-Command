package it.unige.portcommand.gui.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import it.unige.portcommand.gui.events.CommLogEvent;

/**
 * Pure rendering model for {@code CommLogPanel}. No Swing/AWT import, no paraphrasing logic —
 * {@link CommLogEvent} already carries the finished {@code paraphrase()} text (produced by
 * {@code commlog.Paraphraser} on the HarbourMaster's send/receive path); this model only stores
 * and filters the events, it never re-derives their text.
 *
 * <p>Bounded to {@link #MAX_ENTRIES} so a long play session can't grow the log without limit;
 * oldest entries are evicted first.
 */
public final class CommLogModel {

    public static final int MAX_ENTRIES = 500;

    private final Deque<CommLogEvent> entries = new ArrayDeque<>();
    private final Set<String> knownSenders = new LinkedHashSet<>();
    private Integer performativeFilter;
    private String senderFilter;
    private Runnable onChange = () -> { };

    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public void add(CommLogEvent event) {
        entries.addLast(event);
        knownSenders.add(event.sender());
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        onChange.run();
    }

    /** {@code null} clears the filter (show every performative). */
    public void setPerformativeFilter(Integer performative) {
        this.performativeFilter = performative;
        onChange.run();
    }

    /** {@code null} clears the filter (show every sender). */
    public void setSenderFilter(String sender) {
        this.senderFilter = sender;
        onChange.run();
    }

    /** Chronological order (oldest first), both filters applied (AND semantics). */
    public List<CommLogEvent> filteredEntries() {
        return entries.stream()
                .filter(e -> performativeFilter == null || performativeFilter == e.performative())
                .filter(e -> senderFilter == null || senderFilter.equals(e.sender()))
                .toList();
    }

    /**
     * Every sender seen so far, first-seen order — populates the sender filter dropdown.
     * Deliberately {@link List#copyOf}, not {@code Set.copyOf}: the latter's iteration order over
     * a {@link LinkedHashSet} source is unspecified (the same per-JVM-launch-salt risk as {@code
     * Map.copyOf}), which would make the dropdown's item order jump around between runs instead of
     * staying in first-seen order.
     */
    public List<String> knownSenders() {
        return List.copyOf(knownSenders);
    }

    public int size() {
        return entries.size();
    }
}
