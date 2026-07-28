package it.unige.portcommand.gui.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import it.unige.portcommand.gui.LogArchiver;
import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommLogModelTest {

    private static CommLogEvent event(long millis, String sender, int performative, String paraphrase) {
        return new CommLogEvent(millis, sender, List.of("v1"), performative, paraphrase, "conv-1");
    }

    /** Overload for the receiver-oriented tests: lets a case set its own (possibly multiple) receivers. */
    private static CommLogEvent event(long millis, String sender, List<String> receivers,
                                      int performative, String paraphrase) {
        return new CommLogEvent(millis, sender, receivers, performative, paraphrase, "conv-1");
    }

    @Test
    void filteredEntriesReturnsEverythingByDefaultInChronologicalOrder() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "first"));
        model.add(event(1000, "tug_1", ACLMessage.PROPOSE, "second"));

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(2, entries.size());
        assertEquals("first", entries.get(0).paraphrase());
        assertEquals("second", entries.get(1).paraphrase());
    }

    @Test
    void boundedAtMaxEntriesEvictsOldestFirst(@TempDir Path tempDir) {
        // Task 19: the no-arg constructor now defaults to a real-disk LogArchiver, so this
        // pre-existing (eviction-count-only) test is redirected to a temp dir purely to keep the
        // fast lane hermetic -- evictionArchivesTheOldestEntryBeforeDiscardingIt is what actually
        // asserts on archived content.
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        for (int i = 0; i < CommLogModel.MAX_ENTRIES + 5; i++) {
            model.add(event(i, "hm", ACLMessage.INFORM, "entry-" + i));
        }

        assertEquals(CommLogModel.MAX_ENTRIES, model.size());
        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals("entry-5", entries.get(0).paraphrase(), "the first 5 must have been evicted");
        assertEquals("entry-" + (CommLogModel.MAX_ENTRIES + 4), entries.get(entries.size() - 1).paraphrase());
    }

    @Test
    void performativeFilterShowsOnlyMatchingEntries() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "req"));
        model.add(event(1, "hm", ACLMessage.PROPOSE, "prop"));

        model.setPerformativeFilter(ACLMessage.PROPOSE);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("prop", entries.get(0).paraphrase());
    }

    @Test
    void senderFilterShowsOnlyMatchingEntries() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "from-hm"));
        model.add(event(1, "tug_1", ACLMessage.PROPOSE, "from-tug"));

        model.setSenderFilter("tug_1");

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("from-tug", entries.get(0).paraphrase());
    }

    @Test
    void bothFiltersCombineWithAndSemantics() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "hm-request"));
        model.add(event(1, "hm", ACLMessage.PROPOSE, "hm-propose"));
        model.add(event(2, "tug_1", ACLMessage.PROPOSE, "tug-propose"));

        model.setPerformativeFilter(ACLMessage.PROPOSE);
        model.setSenderFilter("hm");

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("hm-propose", entries.get(0).paraphrase());
    }

    @Test
    void clearingAFilterWithNullShowsEverythingAgain() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "a"));
        model.add(event(1, "tug_1", ACLMessage.PROPOSE, "b"));
        model.setSenderFilter("hm");

        model.setSenderFilter(null);

        assertEquals(2, model.filteredEntries().size());
    }

    @Test
    void knownSendersReturnsFirstSeenOrderNeverShrinks() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "a"));
        model.add(event(1, "tug_1", ACLMessage.PROPOSE, "b"));
        model.add(event(2, "hm", ACLMessage.INFORM, "c"));

        assertEquals(List.of("hm", "tug_1"), model.knownSenders());
    }

    @Test
    void sizeReflectsTheRawUnfilteredCount() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", ACLMessage.REQUEST, "a"));
        model.add(event(1, "tug_1", ACLMessage.PROPOSE, "b"));
        model.setSenderFilter("hm"); // filters the VIEW, not the underlying store

        assertEquals(2, model.size());
        assertEquals(1, model.filteredEntries().size());
    }

    @Test
    void onChangeFiresOnAddAndOnEachFilterChange() {
        CommLogModel model = new CommLogModel();
        int[] changes = {0};
        model.setOnChange(() -> changes[0]++);

        model.add(event(0, "hm", ACLMessage.REQUEST, "a"));
        model.setPerformativeFilter(ACLMessage.REQUEST);
        model.setSenderFilter("hm");

        assertTrue(changes[0] >= 3, "add + 2 filter changes must each notify listeners");
    }

    @Test
    void receiverFilterShowsOnlyMatchingEntries() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", List.of("tug_1"), ACLMessage.REQUEST, "to-tug1"));
        model.add(event(1, "hm", List.of("tug_2"), ACLMessage.REQUEST, "to-tug2"));

        model.setReceiverFilter("tug_2");

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("to-tug2", entries.get(0).paraphrase());
    }

    @Test
    void receiverFilterMatchesAnyOneOfMultipleReceivers() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", List.of("tug_1", "tug_2", "tug_3"), ACLMessage.CFP, "broadcast"));
        model.add(event(1, "hm", List.of("tug_9"), ACLMessage.CFP, "other"));

        model.setReceiverFilter("tug_2"); // matches the broadcast on ONE of its three receivers

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("broadcast", entries.get(0).paraphrase());
    }

    @Test
    void timeRangeFilterWithOnlyFromBoundIsInclusiveAndUnboundedAbove() {
        CommLogModel model = new CommLogModel();
        model.add(event(1000, "hm", ACLMessage.INFORM, "early"));
        model.add(event(2000, "hm", ACLMessage.INFORM, "mid"));
        model.add(event(3000, "hm", ACLMessage.INFORM, "late"));

        model.setTimeRange(2000L, null);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(2, entries.size());
        assertEquals("mid", entries.get(0).paraphrase());
        assertEquals("late", entries.get(1).paraphrase());
    }

    @Test
    void timeRangeFilterWithOnlyToBoundIsInclusiveAndUnboundedBelow() {
        CommLogModel model = new CommLogModel();
        model.add(event(1000, "hm", ACLMessage.INFORM, "early"));
        model.add(event(2000, "hm", ACLMessage.INFORM, "mid"));
        model.add(event(3000, "hm", ACLMessage.INFORM, "late"));

        model.setTimeRange(null, 2000L);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(2, entries.size());
        assertEquals("early", entries.get(0).paraphrase());
        assertEquals("mid", entries.get(1).paraphrase());
    }

    @Test
    void timeRangeFilterWithBothBoundsIsInclusiveOnBothEnds() {
        CommLogModel model = new CommLogModel();
        model.add(event(1000, "hm", ACLMessage.INFORM, "a"));
        model.add(event(2000, "hm", ACLMessage.INFORM, "b"));
        model.add(event(3000, "hm", ACLMessage.INFORM, "c"));

        model.setTimeRange(2000L, 3000L);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(2, entries.size());
        assertEquals("b", entries.get(0).paraphrase());
        assertEquals("c", entries.get(1).paraphrase());
    }

    @Test
    void timeRangeFilterWithBothBoundsNullIsUnbounded() {
        CommLogModel model = new CommLogModel();
        model.add(event(1000, "hm", ACLMessage.INFORM, "a"));
        model.add(event(2000, "hm", ACLMessage.INFORM, "b"));

        model.setTimeRange(500L, 1500L); // first restrict to just "a"...
        assertEquals(1, model.filteredEntries().size());

        model.setTimeRange(null, null); // ...then both-null clears the time filter entirely
        assertEquals(2, model.filteredEntries().size());
    }

    @Test
    void knownReceiversReturnsFirstSeenOrderNeverShrinks() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "hm", List.of("tug_1", "tug_2"), ACLMessage.CFP, "a"));
        model.add(event(1, "hm", List.of("tug_1"), ACLMessage.REQUEST, "b")); // tug_1 already known
        model.add(event(2, "hm", List.of("tug_3"), ACLMessage.REQUEST, "c"));

        assertEquals(List.of("tug_1", "tug_2", "tug_3"), model.knownReceivers());
    }

    @Test
    void allFourFiltersCombineWithAndSemantics() {
        CommLogModel model = new CommLogModel();
        // Target row satisfies every filter; each decoy fails exactly ONE, proving AND.
        model.add(event(2000, "hm", List.of("tug_1"), ACLMessage.PROPOSE, "match"));
        model.add(event(2000, "hm", List.of("tug_1"), ACLMessage.REQUEST, "wrong-performative"));
        model.add(event(2000, "tug_9", List.of("tug_1"), ACLMessage.PROPOSE, "wrong-sender"));
        model.add(event(2000, "hm", List.of("tug_2"), ACLMessage.PROPOSE, "wrong-receiver"));
        model.add(event(9000, "hm", List.of("tug_1"), ACLMessage.PROPOSE, "out-of-time-range"));

        model.setPerformativeFilter(ACLMessage.PROPOSE);
        model.setSenderFilter("hm");
        model.setReceiverFilter("tug_1");
        model.setTimeRange(1000L, 3000L);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("match", entries.get(0).paraphrase());
    }

    @Test
    void mutingASenderHidesOnlyThatSenderAndShowsEverythingElse() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "weather_agent", ACLMessage.INFORM, "urgent weather"));
        model.add(event(1, "hm", ACLMessage.REQUEST, "berth request"));
        model.add(event(2, "weather_agent", ACLMessage.INFORM, "more weather"));

        model.setSenderMuted("weather_agent", true);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size(), "both weather entries hidden, the hm entry shown");
        assertEquals("berth request", entries.get(0).paraphrase());
    }

    @Test
    void unmutingASenderBringsItBack() {
        CommLogModel model = new CommLogModel();
        model.add(event(0, "weather_agent", ACLMessage.INFORM, "w"));
        model.add(event(1, "hm", ACLMessage.REQUEST, "r"));
        model.setSenderMuted("weather_agent", true);
        assertEquals(1, model.filteredEntries().size());

        model.setSenderMuted("weather_agent", false);

        assertEquals(2, model.filteredEntries().size());
    }

    @Test
    void mutingComposesWithTheOtherFiltersAndRemovesTheMutedSenderRegardless() {
        CommLogModel model = new CommLogModel();
        // A weather INFORM that WOULD match a performative=INFORM filter must still be hidden by mute.
        model.add(event(0, "weather_agent", ACLMessage.INFORM, "weather-inform"));
        model.add(event(1, "hm", ACLMessage.INFORM, "hm-inform"));

        model.setPerformativeFilter(ACLMessage.INFORM);
        model.setSenderMuted("weather_agent", true);

        List<CommLogEvent> entries = model.filteredEntries();
        assertEquals(1, entries.size());
        assertEquals("hm-inform", entries.get(0).paraphrase());
    }

    @Test
    void mutingReTogglingTheSameStateDoesNotSpuriouslyNotify() {
        CommLogModel model = new CommLogModel();
        int[] changes = {0};
        model.setOnChange(() -> changes[0]++);

        model.setSenderMuted("weather_agent", true);
        model.setSenderMuted("weather_agent", true); // already muted -> no-op, no notify
        assertEquals(1, changes[0], "a redundant mute must not fire onChange");

        model.setSenderMuted("weather_agent", false);
        model.setSenderMuted("weather_agent", false); // already un-muted -> no-op
        assertEquals(2, changes[0]);
    }

    @Test
    void evictionArchivesTheOldestEntryBeforeDiscardingIt(@TempDir Path tempDir) throws Exception {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        int overflow = 3;
        for (int i = 0; i < CommLogModel.MAX_ENTRIES + overflow; i++) {
            model.add(event(i, "hm", ACLMessage.INFORM, "entry-" + i));
        }

        // The visible buffer stays capped...
        assertEquals(CommLogModel.MAX_ENTRIES, model.size());
        // ...and the evicted heads (entry-0..entry-2) were archived, in eviction order, BEFORE drop.
        Path archive = tempDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl");
        List<String> lines = Files.readAllLines(archive);
        assertEquals(overflow, lines.size(), "one archived line per evicted entry");
        assertTrue(lines.get(0).contains("\"entry-0\""), "first archived line is the first-evicted head");
        assertTrue(lines.get(2).contains("\"entry-2\""), "archive order follows eviction order");
    }

    /** Checkpoint-#6 F4 (fixed 2026-07-18): the load-time clean slate — every entry dropped
     * from the visible buffer, every dropped entry archived first (the {@code add} eviction
     * contract), filters/known-vocabulary untouched. */
    @Test
    void clearDropsAllEntriesArchivingThemAndKeepsFilterVocabulary(@TempDir Path tempDir) throws Exception {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        model.add(event(0, "hm", ACLMessage.REQUEST, "pre-load-1"));
        model.add(event(1, "tug_1", ACLMessage.PROPOSE, "pre-load-2"));
        boolean[] changed = {false};
        model.setOnChange(() -> changed[0] = true);

        model.clear();

        assertTrue(changed[0], "clear must notify the panel to re-render");
        assertEquals(0, model.size(), "the replaced timeline's entries are gone");
        assertTrue(model.filteredEntries().isEmpty());
        Path archive = tempDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl");
        List<String> lines = Files.readAllLines(archive);
        assertEquals(2, lines.size(), "cleared entries are archived on their way out, like evictions");
        assertTrue(lines.get(0).contains("\"pre-load-1\""));
        assertEquals(List.of("hm", "tug_1"), model.knownSenders(),
                "dropdown vocabulary survives the clear (add-only combos)");
    }
}
