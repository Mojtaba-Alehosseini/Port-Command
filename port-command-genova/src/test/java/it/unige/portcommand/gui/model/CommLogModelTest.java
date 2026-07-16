package it.unige.portcommand.gui.model;

import java.util.List;

import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommLogModelTest {

    private static CommLogEvent event(long millis, String sender, int performative, String paraphrase) {
        return new CommLogEvent(millis, sender, List.of("v1"), performative, paraphrase, "conv-1");
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
    void boundedAtMaxEntriesEvictsOldestFirst() {
        CommLogModel model = new CommLogModel();
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
}
