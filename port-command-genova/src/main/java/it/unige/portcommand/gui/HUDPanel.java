package it.unige.portcommand.gui;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;

import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.gui.model.HUDModel;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Real HUD bar. Day/Time are driven by {@link SimClockTickEvent} (whose live per-sim-minute
 * publisher is task 24's wall-clock tick driver). Wallet and Reputation went LIVE in task 20 —
 * {@link WalletChangedEvent} / {@link ReputationChangedEvent} are published by the ledgers on
 * every mutation, replacing the static {@code "—"} placeholders task 18 left here. Daily Target is
 * the canonical €5,000 from {@code Settings}.
 *
 * <p>All three subscriptions are {@link DeliveryMode#EDT}, which already dispatches on the Event
 * Dispatch Thread — deliberately NOT re-wrapped in another {@code invokeLater} (INVARIANTS,
 * task 18: the two-channel threading contract).
 */
public final class HUDPanel extends JPanel {

    private final HUDModel model = new HUDModel();
    private final List<Subscription<?>> subscriptions = new ArrayList<>();
    private final JLabel dayLabel = new JLabel();
    private final JLabel timeLabel = new JLabel();
    private final JLabel walletLabel = new JLabel();
    private final JLabel reputationLabel = new JLabel();

    public HUDPanel(EventBus eventBus) {
        super(new FlowLayout(FlowLayout.LEFT, 16, 4));
        refreshLabels();
        add(dayLabel);
        add(timeLabel);
        add(walletLabel);
        add(reputationLabel);
        add(new JLabel(model.dailyTargetLabel()));

        subscriptions.add(eventBus.subscribe(SimClockTickEvent.class, this::onSimClockTick, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(WalletChangedEvent.class, this::onWalletChanged, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(
                ReputationChangedEvent.class, this::onReputationChanged, DeliveryMode.EDT));
    }

    private void onSimClockTick(SimClockTickEvent event) {
        model.onSimClockTick(event);
        refreshLabels();
    }

    private void onWalletChanged(WalletChangedEvent event) {
        model.onWalletChanged(event);
        refreshLabels();
    }

    private void onReputationChanged(ReputationChangedEvent event) {
        model.onReputationChanged(event);
        refreshLabels();
    }

    private void refreshLabels() {
        dayLabel.setText(model.dayLabel());
        timeLabel.setText(model.timeLabel());
        walletLabel.setText(model.walletLabel());
        reputationLabel.setText(model.reputationLabel());
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    /** Test-only: the model backing this panel's display. */
    HUDModel model() {
        return model;
    }
}
