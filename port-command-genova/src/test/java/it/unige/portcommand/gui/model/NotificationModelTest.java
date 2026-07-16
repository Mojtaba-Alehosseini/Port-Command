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
