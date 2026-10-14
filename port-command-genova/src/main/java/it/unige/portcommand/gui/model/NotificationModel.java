package it.unige.portcommand.gui.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import it.unige.portcommand.gui.events.NotificationEvent;

/**
 * Pure rendering model for {@code NotificationStrip}. No Swing/AWT import and no timer/threading
 * logic — the {@code javax.swing.Timer} that drives auto-dismiss belongs to the panel (a model has
 * no business owning a Swing timer object); this class only tracks which chips are visible.
 *
 * <p>Caps visible chips at {@link #MAX_VISIBLE}; the oldest is evicted when a new one would push
 * the count over the cap. {@link #add} returns the evicted id (if any) in the same call so the
 * panel can atomically stop/remove that chip's own {@code Timer}/UI — no separate "read the last
 * eviction" side channel to get out of sync.
 */
public final class NotificationModel {

    public static final int MAX_VISIBLE = 5;

    private final LinkedHashMap<Long, NotificationEvent> chips = new LinkedHashMap<>();
    private long nextId;
    private Runnable onChange = () -> { };

    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /**
     * @param replacedIds S18b (PT10): the chips the new one replaces — same topic, or (for a
     *                    notice with no topic) the very same words; the panel removes them with
     *                    their timers
     */
    public record AddResult(long id, OptionalLong evictedId, List<Long> replacedIds) {

        public AddResult(long id, OptionalLong evictedId) {
            this(id, evictedId, List.of());
        }
    }

    public AddResult add(NotificationEvent event) {
        List<Long> replaced = new java.util.ArrayList<>();
        for (var it = chips.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, NotificationEvent> shown = it.next();
            if (replaces(event, shown.getValue())) {
                replaced.add(shown.getKey());
                it.remove();
            }
        }
        long id = nextId++;
        chips.put(id, event);
        OptionalLong evicted = OptionalLong.empty();
        if (chips.size() > MAX_VISIBLE) {
            var it = chips.keySet().iterator();
            if (it.hasNext()) {
                long oldest = it.next();
                it.remove();
                evicted = OptionalLong.of(oldest);
            }
        }
        onChange.run();
        return new AddResult(id, evicted, List.copyOf(replaced));
    }

    /**
     * S18b (PT10): whether {@code incoming} replaces the chip of {@code shown} — a notice on the
     * same topic does; a notice with no topic replaces only a topic-less chip with its very words.
     */
    static boolean replaces(NotificationEvent incoming, NotificationEvent shown) {
        if (incoming.topic() != null) {
            return incoming.topic().equals(shown.topic());
        }
        return shown.topic() == null && incoming.severity() == shown.severity()
                && java.util.Objects.equals(incoming.text(), shown.text());
    }

    public void dismiss(long id) {
        if (chips.remove(id) != null) {
            onChange.run();
        }
    }

    /** Insertion order (oldest first). */
    public List<Map.Entry<Long, NotificationEvent>> chips() {
        return List.copyOf(chips.entrySet());
    }

    public int size() {
        return chips.size();
    }
}
