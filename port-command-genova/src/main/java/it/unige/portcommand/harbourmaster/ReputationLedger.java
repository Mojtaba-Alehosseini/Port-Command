package it.unige.portcommand.harbourmaster;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe running reputation score, clamped to 0..100. Read-side only for this
 * task; mutation is {@code DayRolloverCoordinator}'s (task 24), same as
 * {@link WalletLedger}.
 */
public final class ReputationLedger {

    private final AtomicReference<Double> score;

    public ReputationLedger(double startingScore) {
        this.score = new AtomicReference<>(clamp(startingScore));
    }

    public double score() {
        return score.get();
    }

    public void adjust(double delta) {
        score.updateAndGet(s -> clamp(s + delta));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }
}
