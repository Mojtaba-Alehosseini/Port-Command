package it.unige.portcommand.gui.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import it.unige.portcommand.core.RuleCatalogue;
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
 *
 * <p>S15 (2026-10-12): the view is chosen with one of four {@link CommLogPreset}s — All, This
 * vessel, Rules, Weather — instead of the five filter widgets. Each row is classified ONCE, when
 * it is added ({@link CommLogClassifier}), and stored with its {@link CommLogFacts}; a small
 * conversation index ties a tug tender's rows to the vessel the tender is for. The older filters
 * (performative, sender, receiver, time range, mute) are kept, unchanged, and AND-combine with
 * the preset.
 *
 * <p>Like the panel that owns it, an instance is used from the EDT only; nothing here is
 * synchronised.
 */
public final class CommLogModel {

    public static final int MAX_ENTRIES = 500;

    /**
     * How many conversations the conversation index remembers (S15). A tug tender is one
     * conversation and a busy day runs a few dozen, so this is several game days of tenders; the
     * cap only stops a very long session from growing the index without limit.
     */
    public static final int MAX_CONVERSATIONS = 1024;

    /** S15: what an empty view says under each preset — one grey line, never a blank pane. */
    static final String HINT_ALL = "No messages yet.";
    static final String HINT_RULES = "No message has cited a rule yet.";
    static final String HINT_WEATHER = "No weather messages yet.";
    static final String HINT_NO_TAB = "No chat tab is selected, so there is no \"this vessel\" yet. "
            + "Open a walk-in's chat tab to follow its messages here.";

    /** S15: one stored row — the event as published, and what the presets need to know of it. */
    /** @param unchangedCheck S18b (PT9): a routine weather check that reads the same as the one before it */
    private record Entry(CommLogEvent event, CommLogFacts facts, boolean unchangedCheck) {
    }

    /** S18b (PT9): what the last routine weather check read ({@link CommLogClassifier#weatherReading}). */
    private String lastWeatherReading;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Set<String> knownSenders = new LinkedHashSet<>();
    private final Set<String> knownReceivers = new LinkedHashSet<>();
    private final Set<String> mutedSenders = new LinkedHashSet<>();
    private final LogArchiver archiver;
    /** S15: which rule ids exist — decides whether a bracketed {@code [R7]} makes a row a rule row. */
    private final Predicate<String> knownRuleId;
    /**
     * S15: conversation id to the vessels that conversation is about, learnt from the rows that
     * say so. Most rows of a tug tender name no vessel at all — a bid carries cost, ETA and fuel;
     * the loser's REJECT carries nothing — and share only the {@code cnp-<UUID>} conversation id
     * with the CFP and the award, which do name it. This is what lets "This vessel" show the
     * whole tender.
     *
     * <p>It is deliberately SEPARATE from {@link #entries}: the CFP can be evicted from the
     * 500-row window while later rows of its conversation are still on screen. Access-ordered and
     * capped at {@link #MAX_CONVERSATIONS}, so the conversation that has been silent longest is
     * the one forgotten.
     */
    private final Map<String, Set<String>> vesselsByConversation = new ConversationIndex();
    private Integer performativeFilter;
    private String senderFilter;
    private String receiverFilter;
    private Long fromMillis;
    private Long toMillis;
    private CommLogPreset preset = CommLogPreset.ALL;
    /** S15: the selected chat tab's vessel, raw id as the chat reports it; {@code null} = no tab. */
    private String focusVesselId;
    /** {@link #focusVesselId} in key space ({@link CommLogClassifier#keyOfVesselId}), kept in step. */
    private String focusVesselKey;
    private Runnable onChange = () -> { };

    /** Production: evicted entries are archived under {@code logs/} (see {@link LogArchiver}). */
    public CommLogModel() {
        this(new LogArchiver());
    }

    /** Test seam: inject a temp-dir (or otherwise redirected) {@link LogArchiver}. Public since
     * S15 only so that {@code CommLogPanel} can hand one through from its own test seam. */
    public CommLogModel(LogArchiver archiver) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        // S15: RuleCatalogue.get() is evaluated HERE, once — so a missing or invalid catalogue
        // fails when the panel is built, not on the first message that happens to cite a rule.
        this.knownRuleId = RuleCatalogue.get()::contains;
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public void add(CommLogEvent event) {
        // S15: a null event always failed here (ArrayDeque refuses null); say so before anything
        // is stored, now that the deque holds a wrapper that would accept it.
        Objects.requireNonNull(event, "event");
        CommLogFacts facts = CommLogClassifier.classify(event, knownRuleId);
        learnConversation(event.conversationId(), facts);
        boolean unchangedCheck = false;
        if (facts.periodicWeather()) {
            String reading = CommLogClassifier.weatherReading(event);
            unchangedCheck = reading != null && reading.equals(lastWeatherReading);
            lastWeatherReading = reading;
        }
        entries.addLast(new Entry(event, facts, unchangedCheck));
        knownSenders.add(event.sender());
        knownReceivers.addAll(event.receivers());
        while (entries.size() > MAX_ENTRIES) {
            // Archive first, THEN discard: getFirst() peeks the about-to-be-evicted head so it is
            // handed to the archiver before removeFirst() drops it. Reversing the two would archive
            // whatever entry happened to be next, or nothing at all.
            archiver.archive(entries.getFirst().event());
            entries.removeFirst();
        }
        onChange.run();
    }

    /**
     * S15: remembers which vessels a conversation is about. Only for a conversation whose id
     * does not already name its vessel (in practice {@code cnp-<UUID>}; a {@code nego-},
     * {@code berth-}, {@code term-} or {@code cust-} row attributes itself), and only from a row
     * that names one — the CFP, the award ACCEPT, the post-award REFUSE, the escort-complete
     * INFORM. A row that names nothing (a bid) only keeps the conversation fresh.
     */
    private void learnConversation(String conversationId, CommLogFacts facts) {
        if (conversationId == null || conversationId.isBlank()
                || CommLogClassifier.vesselKeyOfConversationId(conversationId) != null) {
            return;
        }
        // get() on the access-ordered map marks the conversation as recently used either way.
        Set<String> known = vesselsByConversation.get(conversationId);
        if (facts.vesselKeys().isEmpty()) {
            return;
        }
        if (known == null) {
            known = new HashSet<>();
            vesselsByConversation.put(conversationId, known); // put() is what evicts the eldest
        }
        known.addAll(facts.vesselKeys());
    }

    /**
     * Clean slate on a load (checkpoint-#6 F4, fixed 2026-07-18): drops every entry — the
     * replaced timeline's MAS history no longer describes the world, same rule as the chat
     * tabs' reset on {@code GameLoadedEvent}. Entries are archived on their way out (the same
     * eviction contract as {@link #add}), so the on-disk history stays complete across the
     * cut. Filters, muted senders and the known sender/receiver sets survive — they are user
     * preferences and dropdown vocabulary, not history (and the panel's combos are add-only
     * by design).
     *
     * <p>S15: the conversation index IS history and goes with the entries — a tender of the
     * replaced timeline must not attribute a row of the new one. The preset and the focus vessel
     * survive, like the other filters (the panel drops the focus itself on a load).
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
        archiver.archiveAll(entries.stream().map(Entry::event).toList());
        entries.clear();
        vesselsByConversation.clear();
        lastWeatherReading = null;
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

    /**
     * S15: chooses the view. Notifies on every call, like the other filter setters. The default
     * is {@link CommLogPreset#ALL}, and the choice survives {@link #clear()}.
     */
    public void setPreset(CommLogPreset preset) {
        this.preset = Objects.requireNonNull(preset, "preset");
        onChange.run();
    }

    public CommLogPreset preset() {
        return preset;
    }

    /**
     * S15: names the vessel {@link CommLogPreset#THIS_VESSEL} is about — the raw id of the
     * selected chat tab's vessel ({@code WALKIN-3}), or {@code null} (a blank id counts as
     * {@code null}) when no tab is selected. It may be set under any preset; it only shows under
     * This vessel. Notifies ONLY when the vessel actually changes: the chat reports its selection
     * on every tab event, and re-rendering 500 rows for a selection that did not move would be
     * wasted work on the EDT.
     */
    public void setFocusVessel(String rawVesselIdOrNull) {
        String next = rawVesselIdOrNull == null || rawVesselIdOrNull.isBlank() ? null : rawVesselIdOrNull;
        if (Objects.equals(next, focusVesselId)) {
            return;
        }
        focusVesselId = next;
        focusVesselKey = CommLogClassifier.keyOfVesselId(next);
        onChange.run();
    }

    /** The focus vessel's raw id as it was given (for texts shown to the player), or {@code null}. */
    public String focusVesselId() {
        return focusVesselId;
    }

    /**
     * S15: the one line to show INSTEAD of rows when {@link #filteredEntries()} is empty, so the
     * pane never goes blank without saying why. It depends on the preset (and, under This vessel,
     * on the focus) only.
     */
    public String emptyStateHint() {
        return switch (preset) {
            case ALL -> HINT_ALL;
            case RULES -> HINT_RULES;
            case WEATHER -> HINT_WEATHER;
            case THIS_VESSEL -> focusVesselId == null
                    ? HINT_NO_TAB
                    : "No messages about " + focusVesselId + " in the log yet.";
        };
    }

    /**
     * S18b (PT9): how many routine weather checks "All" is leaving out right now — 0 under any
     * other view. The panel shows the number beside the log.
     */
    public int hiddenUnchangedChecks() {
        if (preset != CommLogPreset.ALL) {
            return 0;
        }
        return (int) entries.stream().filter(Entry::unchangedCheck).count();
    }

    /**
     * Chronological order (oldest first); all filters applied with AND semantics, then muted
     * senders removed. S15: the preset is one more condition in the same AND chain.
     */
    public List<CommLogEvent> filteredEntries() {
        return entries.stream()
                .filter(this::matchesPreset)
                .map(Entry::event)
                .filter(e -> !mutedSenders.contains(e.sender()))
                .filter(e -> performativeFilter == null || performativeFilter == e.performative())
                .filter(e -> senderFilter == null || senderFilter.equals(e.sender()))
                .filter(e -> receiverFilter == null || e.receivers().contains(receiverFilter))
                .filter(e -> fromMillis == null || e.simTimeMillis() >= fromMillis)
                .filter(e -> toMillis == null || e.simTimeMillis() <= toMillis)
                .toList();
    }

    /** S15: what each preset means, in one place. */
    private boolean matchesPreset(Entry entry) {
        return switch (preset) {
            // S18b (playtest 2, PT9): everything but the routine weather checks that say nothing
            // new — 96 a game day, most of them the reading of the check before. They stay under
            // Weather, the end-of-day tally still counts them, and the panel says how many "All"
            // leaves out ({@link #hiddenUnchangedChecks}).
            case ALL -> !entry.unchangedCheck();
            case WEATHER -> entry.facts().weather();
            case RULES -> entry.facts().citesRule();
            case THIS_VESSEL -> isAboutFocusVessel(entry);
        };
    }

    /**
     * A row is about the focus vessel when it names it itself, or when its conversation is known
     * to be about it. Set membership on keys, never a prefix test: {@code WALKIN-3} must not pick
     * up {@code vessel_walkin_30}. With no focus vessel nothing matches.
     */
    private boolean isAboutFocusVessel(Entry entry) {
        if (focusVesselKey == null) {
            return false;
        }
        if (entry.facts().vesselKeys().contains(focusVesselKey)) {
            return true;
        }
        String conversationId = entry.event().conversationId();
        if (conversationId == null) {
            return false;
        }
        Set<String> vessels = vesselsByConversation.get(conversationId);
        return vessels != null && vessels.contains(focusVesselKey);
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

    /** Test-only (S15): how many conversations the index currently remembers. */
    int conversationIndexSizeForTest() {
        return vesselsByConversation.size();
    }

    /**
     * S15: the conversation index's storage — a {@link LinkedHashMap} in ACCESS order that drops
     * its least recently used conversation once it holds more than {@link #MAX_CONVERSATIONS}
     * (the {@code RecommendationCache} idiom, as a named class so the bound is stated once).
     */
    private static final class ConversationIndex extends LinkedHashMap<String, Set<String>> {

        private static final long serialVersionUID = 1L;

        ConversationIndex() {
            super(64, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
            return size() > MAX_CONVERSATIONS;
        }
    }
}
