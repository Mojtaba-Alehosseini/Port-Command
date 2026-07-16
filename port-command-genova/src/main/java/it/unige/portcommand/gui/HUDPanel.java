package it.unige.portcommand.gui;

import java.awt.FlowLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;

import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Placeholder HUD bar — the real HUD (wallet, reputation, day/clock, weather
 * icon) is task 18. Subscribes to {@link SimClockTickEvent} only to prove the
 * subscribe-in-constructor / cancel-in-{@link #removeNotify()} lifecycle every
 * real panel must follow (planning/17 hard constraint); no rendering happens
 * here.
 */
public final class HUDPanel extends JPanel {

    private final Subscription<SimClockTickEvent> subscription;
    private volatile int receivedCount;

    public HUDPanel(EventBus eventBus) {
        super(new FlowLayout(FlowLayout.LEFT));
        add(new JLabel("HUD (placeholder — task 18)"));
        this.subscription = eventBus.subscribe(SimClockTickEvent.class, e -> receivedCount++, DeliveryMode.EDT);
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
