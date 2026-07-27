package it.unige.portcommand.gui.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import it.unige.portcommand.gui.LogArchiver;
import it.unige.portcommand.gui.events.CommLogEvent;

/**
 * Pure rendering model for {@code CommLogPanel}. No Swing/AWT import, no paraphrasing logic —
 * {@link CommLogEvent} already carries the finished {@code paraphrase()} text (produced by
 * {@code commlog.Paraphraser} on the HarbourMaster's send/receive path); this model only stores
 * and filters the events, it never re-derives their text.
 *
 * <p>Bounded to {@link #MAX_ENTRIES} so a long play session can't grow the log without limit;
 * oldest entries are evicted first, but are handed to a {@link LogArchiver} on their way out so the
 * full history survives on disk beyond the visible cap.
 */
public final class CommLogModel {

    public static final int MAX_ENTRIES = 500;

    private final Deque<CommLogEvent> entries = new ArrayDeque<>();
    private final Set<String> knownSenders = new LinkedHashSet<>();
    private final Set<String> knownReceivers = new LinkedHashSet<>();
    private final Set<String> mutedSenders = new LinkedHashSet<>();
    private final LogArchiver archiver;
    private Integer performativeFilter;
    private String senderFilter;
    private String receiverFilter;
    private Long fromMillis;
    private Long toMillis;
    private Runnable onChange = () -> { };

    /** Production: evicted entries are archived under {@code logs/} (see {@link LogArchiver}). */
    public CommLogModel() {
        this(new LogArchiver());
    }

    /** Test seam: inject a temp-dir (or otherwise redirected) {@link LogArchiver}. */
    CommLogModel(LogArchiver archiver) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public void add(CommLogEvent event) {
        entries.addLast(event);
        knownSenders.add(event.sender());
        knownReceivers.addAll(event.receivers());
        while (entries.size() > MAX_ENTRIES) {
            // Archive first, THEN discard: getFirst() peeks the about-to-be-evicted head so it is
            // handed to the archiver before removeFirst() drops it. Reversing the two would archive
            // whatever entry happened to be next, or nothing at all.
            archiver.archive(entries.getFirst());
            entries.removeFirst();
        }
        onChange.run();
    }

    /**
     * Clean slate on a load (checkpoint-#6 F4, fixed 2026-07-18): drops every entry — the
     * replaced timeline's MAS history no longer describes the world, same rule as the chat
     * tabs' reset on {@code GameLoadedEvent}. Entries are archived on their way out (the same
     * eviction contract as {@link #add}), so the on-disk history stays complete across the
     * cut. Filters, muted senders and the known sender/receiver sets survive — they are user
     * preferences and dropdown vocabulary, not history (and the panel's combos are add-only
     * by design).
     */
    public void clear() {
        // Audit C-06 (2026-07-27): ONE batched write instead of one file open/append/close per
        // entry. This runs on the EDT off GameLoadedEvent, and at the 500-entry cap the per-entry
        // form froze the UI for a quarter-second to over a second at exactly the moment the demo
        // switches scenarios.
        //
        // ARCHIVE, THEN DISCARD — in that order, matching add()'s eviction contract. The first cut
        // of this fix copied, cleared, and then archived; the adversarial review caught it. It was
        // only observably equivalent because archiveAll swallows its exceptions, which is precisely
        // the kind of "correct by accident" this ordering rule exists to forbid.
        archiver.archiveAll(new ArrayList<>(entries));
        entries.clear();
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

    /**
     * {@code null} clears the filter (show every receiver). A set receiver matches an event when it
     * is ANY ONE of that event's (possibly multiple) receivers, not the whole receiver list verbatim
     * — a broadcast to {@code [tug_1, tug_2]} is kept by a {@code tug_1} filter.
     */
    public void setReceiverFilter(String receiver) {
        this.receiverFilter = receiver;
        onChange.run();
    }

    /**
     * Bounds the visible window by {@code simTimeMillis}. Either bound {@code null} = unbounded on
     * that side; both {@code null} = no time filter at all. Inclusive on both ends.
     */
    public void setTimeRange(Long fromMillis, Long toMillis) {
        this.fromMillis = fromMillis;
        this.toMillis = toMillis;
        onChange.run();
    }

    /**
     * Mutes or un-mutes all traffic FROM {@code sender} (task 19: the "Hide weather" toggle mutes
     * {@code weather_agent}, whose alerts otherwise dominate a busy log). Distinct from
     * {@link #setSenderFilter} — muting HIDES the named sender while showing everything else,
     * whereas the sender filter shows ONLY the named sender. Composes with every other filter
     * (a muted sender never appears regardless of the other filter selections).
     */
    public void setSenderMuted(String sender, boolean muted) {
        boolean changed = muted ? mutedSenders.add(sender) : mutedSenders.remove(sender);
        if (changed) {
            onChange.run();
        }
    }

    /** Chronological order (oldest first); all filters applied with AND semantics, then muted
     * senders removed. */
    public List<CommLogEvent> filteredEntries() {
        return entries.stream()
                .filter(e -> !mutedSenders.contains(e.sender()))
                .filter(e -> performativeFilter == null || performativeFilter == e.performative())
                .filter(e -> senderFilter == null || senderFilter.equals(e.sender()))
                .filter(e -> receiverFilter == null || e.receivers().contains(receiverFilter))
                .filter(e -> fromMillis == null || e.simTimeMillis() >= fromMillis)
                .filter(e -> toMillis == null || e.simTimeMillis() <= toMillis)
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

    /**
     * Every receiver seen so far, first-seen order across all events' receiver lists — populates the
     * receiver filter dropdown. Same {@link List#copyOf} rationale as {@link #knownSenders()}. Never
     * shrinks (it only accumulates), so the dropdown can be grown by ADD only, never rebuilt.
     */
    public List<String> knownReceivers() {
        return List.copyOf(knownReceivers);
    }

    public int size() {
        return entries.size();
    }
}
