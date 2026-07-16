package it.unige.portcommand.gui;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import it.unige.portcommand.gui.events.VesselArrivedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Placeholder — the real map (vessel positions, berths, weather overlay) is
 * task 18. Subscribes to {@link VesselArrivedEvent} only to prove the
 * subscribe-in-constructor / cancel-in-{@link #removeNotify()} lifecycle every
 * real panel must follow (planning/17 hard constraint); no rendering happens
 * here.
 */
public final class MapPanel extends JPanel {

    private final Subscription<VesselArrivedEvent> subscription;
    private volatile int receivedCount;

    public MapPanel(EventBus eventBus) {
        super(new BorderLayout());
        add(new JLabel("Map (placeholder — task 18)", SwingConstants.CENTER), BorderLayout.CENTER);
        this.subscription = eventBus.subscribe(VesselArrivedEvent.class, e -> receivedCount++, DeliveryMode.EDT);
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
