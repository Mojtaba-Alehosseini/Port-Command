package it.unige.portcommand.gui;

import java.awt.FlowLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Placeholder — the real notification strip (toast-style HUD notices) is task
 * 18. {@code FlowLayout} per planning/18's own spec for the eventual real
 * panel. Subscribes to {@link NotificationEvent} — the single canonical class
 * (MASTER_PLAN §8 fix 7) — only to prove the subscribe-in-constructor /
 * cancel-in-{@link #removeNotify()} lifecycle every real panel must follow; no
 * rendering happens here.
 */
public final class NotificationStrip extends JPanel {

    private final Subscription<NotificationEvent> subscription;
    private volatile int receivedCount;

    public NotificationStrip(EventBus eventBus) {
        super(new FlowLayout(FlowLayout.LEFT));
        add(new JLabel("Notifications (placeholder — task 18)"));
        this.subscription = eventBus.subscribe(NotificationEvent.class, e -> receivedCount++, DeliveryMode.EDT);
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        subscription.cancel();
    }

    /** Test-only: proves the constructor's subscription is live, and stops after {@link #removeNotify()}. */
    int receivedCountForTest() {
        return receivedCount;
    }
}
