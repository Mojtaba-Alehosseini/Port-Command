package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.NotificationEvent.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationModelTest {

    // ---- S18b (playtest 2, PT10): a newer notice on the same subject replaces the older chip ----

    private static NotificationEvent about(String topic, String text) {
        return new NotificationEvent(text, NotificationEvent.Severity.WARNING, 0L, topic);
    }

    @org.junit.jupiter.api.Test
    void aNoticeOnTheSameTopicReplacesTheOlderChip() {
        NotificationModel model = new NotificationModel();
        NotificationModel.AddResult worsening = model.add(about(NotificationEvent.WEATHER_TOPIC, "Weather worsening: 38 kn"));
        model.add(about(NotificationEvent.berthTopic("berth_2"), "Berth 2 flooded"));

        NotificationModel.AddResult clearing = model.add(about(NotificationEvent.WEATHER_TOPIC, "Weather clearing: 22 kn"));

        assertEquals(java.util.List.of(worsening.id()), clearing.replacedIds(), "the clearing replaces the worsening");
        assertEquals(java.util.List.of("Berth 2 flooded", "Weather clearing: 22 kn"),
                model.chips().stream().map(e -> e.getValue().text()).toList(), "another topic stays");
    }

    @org.junit.jupiter.api.Test
    void aRepeatReplacesItselfInsteadOfPilingUp() {
        NotificationModel model = new NotificationModel();
        model.add(event("Free play started"));
        NotificationModel.AddResult again = model.add(event("Free play started"));

        assertEquals(1, again.replacedIds().size());
        assertEquals(1, model.size(), "one chip, not two");
    }

    @org.junit.jupiter.api.Test
    void aNoticeWithoutATopicLeavesTopicChipsAlone() {
        NotificationModel model = new NotificationModel();
        model.add(about(NotificationEvent.holdTopic("C501"), "C501 held outside: wind 38 kn [R15]"));

        model.add(new NotificationEvent("C501 held outside: wind 38 kn [R15]", NotificationEvent.Severity.WARNING, 0L));

        assertEquals(2, model.size(), "only the same topic, or two topic-less notices with the same words, replace");
    }

    private static NotificationEvent event(String text) {
        return new NotificationEvent(text, Severity.INFO, 0L);
    }

    @Test
    void addAssignsIncreasingIdsAndNoEvictionUnderTheCap() {
        NotificationModel model = new NotificationModel();

        NotificationModel.AddResult r1 = model.add(event("a"));
        NotificationModel.AddResult r2 = model.add(event("b"));

        assertTrue(r2.id() > r1.id());
        assertTrue(r1.evictedId().isEmpty());
        assertTrue(r2.evictedId().isEmpty());
        assertEquals(2, model.size());
    }

    @Test
    void aSixthChipEvictsTheOldestAndReportsItsId() {
        NotificationModel model = new NotificationModel();
        long firstId = model.add(event("1")).id();
        model.add(event("2"));
        model.add(event("3"));
        model.add(event("4"));
        model.add(event("5"));

        NotificationModel.AddResult sixth = model.add(event("6"));

        assertEquals(NotificationModel.MAX_VISIBLE, model.size());
        assertEquals(firstId, sixth.evictedId().orElseThrow(), "the oldest (first) chip must be evicted");
        List<Map.Entry<Long, NotificationEvent>> chips = model.chips();
        assertFalse(chips.stream().anyMatch(e -> e.getKey() == firstId), "evicted chip must be gone");
        assertEquals("2", chips.get(0).getValue().text(), "the new oldest survivor must be chip 2");
    }

    @Test
    void dismissRemovesTheChip() {
        NotificationModel model = new NotificationModel();
        long id = model.add(event("a")).id();

        model.dismiss(id);

        assertEquals(0, model.size());
    }

    @Test
    void dismissingAnUnknownIdIsANoOpAndDoesNotNotify() {
        NotificationModel model = new NotificationModel();
        int[] changes = {0};
        model.setOnChange(() -> changes[0]++);

        model.dismiss(999L);

        assertEquals(0, changes[0]);
    }

    @Test
    void chipsPreservesInsertionOrder() {
        NotificationModel model = new NotificationModel();
        model.add(event("a"));
        model.add(event("b"));
        model.add(event("c"));

        List<String> texts = model.chips().stream().map(e -> e.getValue().text()).toList();
        assertEquals(List.of("a", "b", "c"), texts);
    }

    @Test
    void onChangeFiresOnAddAndOnDismiss() {
        NotificationModel model = new NotificationModel();
        int[] changes = {0};
        model.setOnChange(() -> changes[0]++);

        long id = model.add(event("a")).id();
        model.dismiss(id);

        assertEquals(2, changes[0]);
    }
}
