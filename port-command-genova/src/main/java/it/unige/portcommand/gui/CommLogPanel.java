package it.unige.portcommand.gui;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Placeholder — the real comm log (paraphrased ACL traffic, coloured by
 * performative) is task 18/19. Subscribes to {@link CommLogEvent} only to
 * prove the subscribe-in-constructor / cancel-in-{@link #removeNotify()}
 * lifecycle every real panel must follow (planning/17 hard constraint); no
 * rendering happens here.
 */
public final class CommLogPanel extends JPanel {

    private final Subscription<CommLogEvent> subscription;
    private volatile int receivedCount;

    public CommLogPanel(EventBus eventBus) {
        super(new BorderLayout());
        add(new JLabel("Comm Log (placeholder — task 18/19)", SwingConstants.CENTER), BorderLayout.CENTER);
        this.subscription = eventBus.subscribe(CommLogEvent.class, e -> receivedCount++, DeliveryMode.EDT);
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
