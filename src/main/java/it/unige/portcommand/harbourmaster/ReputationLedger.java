package it.unige.portcommand.harbourmaster;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.util.EventBus;

/**
 * The port's standing with the vessels it negotiates with: a running score clamped to 0..100,
 * plus the <strong>append-only history</strong> of every adjustment that produced it (task 20 —
 * a score-only shell before). Deltas come from the canonical
 * {@code financial.ReputationRules} table.
 *
 * <p>Threading contract is {@link WalletLedger}'s, for the same reason: score, history and the
 * published {@link ReputationChangedEvent} must move together or the HUD can render a stale
 * figure as the final one. A {@code CALLER_THREAD} subscriber must return promptly; the in-tree
 * subscriber (the HUD) is {@code EDT}.
 */
public final class ReputationLedger {

    /** One applied adjustment. {@code applied} differs from {@code delta} at a clamp boundary. */
    public record Adjustment(double delta, double applied, String reason, String vesselId, long simTime) {
    }

    /** Lock-free reads; all writes go through {@code synchronized} blocks. */
    private final AtomicReference<Double> score;
    private final Queue<Adjustment> history = new ConcurrentLinkedQueue<>();
    private final EventBus eventBus;

    public ReputationLedger(double startingScore, EventBus eventBus) {
        this.score = new AtomicReference<>(clamp(startingScore));
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public double score() {
        return score.get();
    }

    /**
     * Applies {@code delta} (clamped into 0..100), records it, and publishes the change.
     *
     * @param reason   the canonical {@code ReputationRules} tag that fired — carried into the
     *                 event so the notification text names the rule, not the arithmetic
     * @param vesselId the vessel this is attributable to, or {@code null} for port-wide
     * @param simTime  sim time of the adjustment
     */
    public void adjust(double delta, String reason, String vesselId, long simTime) {
        synchronized (this) {
            double before = score.get();
            double after = score.updateAndGet(s -> clamp(s + delta));
            history.add(new Adjustment(delta, after - before, reason, vesselId, simTime));
            eventBus.publish(new ReputationChangedEvent(after, delta, reason, vesselId, simTime));
        }
    }

    /** Every adjustment ever applied, in order. Snapshot — safe to iterate while writes continue. */
    public List<Adjustment> history() {
        return List.copyOf(history);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }
}
