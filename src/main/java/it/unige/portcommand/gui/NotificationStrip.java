package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.model.NotificationModel;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Real notification strip: each {@link NotificationEvent} becomes a small chip (severity marker +
 * text) that auto-dismisses after {@link #autoDismissMs} real milliseconds via a
 * {@link javax.swing.Timer} (fires on the EDT, so chip removal is EDT-safe), or is retained
 * indefinitely on click (cancels the pending dismiss). Caps visible chips at
 * {@link NotificationModel#MAX_VISIBLE}; {@link NotificationModel#add} reports the evicted chip
 * (if any) in the same call so its timer/UI is torn down atomically alongside the new one's setup.
 *
 * <p>The public constructor defaults to 8000ms (a real-seconds toast, deliberately NOT sim-time —
 * a notification that froze during a sim-pause would be a worse UX). The package-private overload
 * lets tests use a short delay instead of a slow literal 8s wait.
 */
public final class NotificationStrip extends JPanel {

    private static final long DEFAULT_AUTO_DISMISS_MS = 8000L;

    private final NotificationModel model = new NotificationModel();
    private final Subscription<NotificationEvent> subscription;
    private final long autoDismissMs;
    private final Map<Long, Timer> dismissTimers = new HashMap<>();
    private final Map<Long, JPanel> chipComponents = new HashMap<>();

    public NotificationStrip(EventBus eventBus) {
        this(eventBus, DEFAULT_AUTO_DISMISS_MS);
    }

    NotificationStrip(EventBus eventBus, long autoDismissMs) {
        super(new FlowLayout(FlowLayout.LEFT));
        this.autoDismissMs = autoDismissMs;
        this.subscription = eventBus.subscribe(NotificationEvent.class, this::onNotification, DeliveryMode.EDT);
    }

    private void onNotification(NotificationEvent event) {
        NotificationModel.AddResult result = model.add(event);
        result.evictedId().ifPresent(this::removeChip);
        addChip(result.id(), event);
    }

    private void addChip(long id, NotificationEvent event) {
        JLabel label = new JLabel(severityMarker(event.severity()) + " " + event.text());
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        chip.setBorder(BorderFactory.createLineBorder(borderColour(event.severity())));
        chip.add(label);
        // Mouse events don't bubble in Swing -- attach to both the chip and its label so a click
        // anywhere on the chip (not just its padding) retains it.
        MouseAdapter retainOnClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                retain(id);
            }
        };
        chip.addMouseListener(retainOnClick);
        label.addMouseListener(retainOnClick);

        chipComponents.put(id, chip);
        add(chip);

        Timer timer = new Timer((int) autoDismissMs, e -> dismiss(id));
        timer.setRepeats(false);
        dismissTimers.put(id, timer);
        timer.start();

        revalidate();
        repaint();
    }

    /** Timer fire: actually dismisses (model + UI). */
    private void dismiss(long id) {
        model.dismiss(id);
        removeChip(id);
    }

    /** Click: cancels the pending auto-dismiss, chip stays visible. */
    private void retain(long id) {
        Timer timer = dismissTimers.remove(id);
        if (timer != null) {
            timer.stop();
        }
    }

    /** UI-only teardown (also used for model-side eviction, which has already updated the model). */
    private void removeChip(long id) {
        Timer timer = dismissTimers.remove(id);
        if (timer != null) {
            timer.stop();
        }
        JPanel chip = chipComponents.remove(id);
        if (chip != null) {
            remove(chip);
            revalidate();
            repaint();
        }
    }

    private static Color borderColour(NotificationEvent.Severity severity) {
        return switch (severity) {
            case INFO -> new Color(66, 133, 244);
            case WARNING -> new Color(251, 188, 5);
            case ERROR -> new Color(234, 67, 53);
        };
    }

    private static String severityMarker(NotificationEvent.Severity severity) {
        return switch (severity) {
            case INFO -> "[i]";
            case WARNING -> "[!]";
            case ERROR -> "[x]";
        };
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        subscription.cancel();
        for (Timer timer : dismissTimers.values()) {
            timer.stop();
        }
    }

    /** Test-only: the model backing this panel's chip list. */
    NotificationModel model() {
        return model;
    }
}
