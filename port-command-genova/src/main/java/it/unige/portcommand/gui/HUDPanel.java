package it.unige.portcommand.gui;

import java.awt.FlowLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;

import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.model.HUDModel;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Real HUD bar. Day/Time are driven by {@link SimClockTickEvent} — a real event type with a real
 * subscription here, even though its live per-sim-minute publisher isn't wired until task 24's
 * wall-clock tick driver (unit-tested by publishing the event directly). Wallet, Reputation, and
 * Daily Target have no live data path yet: {@code WalletLedger}/{@code ReputationLedger} exist but
 * are GUI-unreachable, and {@code DealClosedEvent}/{@code EndOfDayCompletedEvent} carry deltas
 * with no published starting figure to compute an absolute number from — so those three render as
 * static placeholder text (dated 2026-07-16, pending task 20/24), not wired to any subscription.
 */
public final class HUDPanel extends JPanel {

    private final HUDModel model = new HUDModel();
    private final Subscription<SimClockTickEvent> subscription;
    private final JLabel dayLabel = new JLabel();
    private final JLabel timeLabel = new JLabel();

    public HUDPanel(EventBus eventBus) {
        super(new FlowLayout(FlowLayout.LEFT, 16, 4));
        refreshClockLabels();
        add(dayLabel);
        add(timeLabel);
        // 2026-07-16: no WalletLedger/ReputationLedger/daily-target event exists yet (task 20/24) —
        // placeholders, deliberately not wired to any subscription.
        add(new JLabel("Wallet: —"));
        add(new JLabel("Reputation: —"));
        add(new JLabel("Daily Target: —"));

        this.subscription = eventBus.subscribe(SimClockTickEvent.class, this::onSimClockTick, DeliveryMode.EDT);
    }

    private void onSimClockTick(SimClockTickEvent event) {
        model.onSimClockTick(event);
        refreshClockLabels();
    }

    private void refreshClockLabels() {
        dayLabel.setText(model.dayLabel());
        timeLabel.setText(model.timeLabel());
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        subscription.cancel();
    }

    /** Test-only: the model backing this panel's day/time display. */
    HUDModel model() {
        return model;
    }
}
