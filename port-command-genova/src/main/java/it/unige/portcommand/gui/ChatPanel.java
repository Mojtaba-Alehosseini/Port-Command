package it.unige.portcommand.gui;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Placeholder — the real chat (player commands + Assistant replies) is task
 * 19. Subscribes to {@link AssistantChatEvent} only to prove the
 * subscribe-in-constructor / cancel-in-{@link #removeNotify()} lifecycle every
 * real panel must follow (planning/17 hard constraint); no rendering happens
 * here.
 */
public final class ChatPanel extends JPanel {

    private final Subscription<AssistantChatEvent> subscription;
    private volatile int receivedCount;

    public ChatPanel(EventBus eventBus) {
        super(new BorderLayout());
        add(new JLabel("Chat (placeholder — task 19)", SwingConstants.CENTER), BorderLayout.CENTER);
        this.subscription = eventBus.subscribe(AssistantChatEvent.class, e -> receivedCount++, DeliveryMode.EDT);
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
