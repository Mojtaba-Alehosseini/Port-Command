package it.unige.portcommand.gui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.MapModel;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * Real map: harbour outline, the 4 berths colour-coded by occupancy, a tug-base landmark, live tug
 * dots and a weather chip. (S9: the arrival/departure chip channel was DELETED — its two events
 * never gained a production publisher in the project's whole life; Moji's recorded decision was
 * remove-not-wire, since berth labels already show arrivals honestly. Audit C-05's content half.)
 * Simple shapes only (no sprites — see {@link MapPainter}'s javadoc). Rendering is delegated to
 * {@link MapPainter} against a {@link MapModel} the panel keeps up to date; the model itself is a
 * plain POJO, unit-tested headless with no Swing involved (see {@code MapModelTest}).
 *
 * <p><b>Two update channels, two different threading contracts.</b> {@link PortStateArtifact}
 * (berths/tugs) calls its subscribers synchronously on whatever agent thread called {@code
 * update()}/{@code updateTug()} — never the EDT (verified by reading the artifact; it has no
 * delivery-mode concept at all, unlike {@link EventBus}) — so this panel does the EDT hop itself
 * via {@link SwingUtilities#invokeLater} before touching the model. {@link EventBus} in
 * {@link DeliveryMode#EDT} mode already dispatches via {@code invokeLater} internally, so the
 * weather subscription below does NOT re-wrap — doing so would just be a
 * redundant extra queue hop.
 *
 * <p><b>{@code PortStateArtifact.subscribe} has no cancel method</b> (a task-06 API — out of scope
 * to modify here), so this panel cannot literally unsubscribe from it in {@link #removeNotify()}
 * the way it can for its {@link EventBus} subscriptions. Instead, {@link #active} is a
 * "poor-man's unsubscribe": once {@code false}, the artifact callback's scheduled EDT work becomes
 * a no-op, so the panel still honours the subscribe-in-constructor/cancel-in-{@code removeNotify()}
 * contract from the caller's point of view, even though the artifact itself keeps the raw
 * subscriber registered for its own lifetime. Safe in production (exactly one {@code MapPanel} per
 * {@code PortStateArtifact} for the JVM's life) and in tests (each test constructs its own fresh
 * artifact instance, so there is no cross-talk between tests).
 */
public final class MapPanel extends JPanel {

    private final MapModel model = new MapModel();
    private final Subscription<WeatherChangeEvent> weatherSubscription;
    private volatile boolean active = true;

    public MapPanel(EventBus eventBus, PortStateArtifact portStateArtifact) {
        setDoubleBuffered(true);
        setPreferredSize(new Dimension(800, 450));
        model.setOnChange(this::repaint);

        portStateArtifact.berthSnapshot().forEach(
                (berthId, occupancy) -> model.applyBerthDelta(new PortStateUpdate.BerthDelta(berthId, occupancy)));

        portStateArtifact.subscribe(update -> {
            if (!active) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!active) {
                    return;
                }
                switch (update) {
                    case PortStateUpdate.BerthDelta bd -> model.applyBerthDelta(bd);
                    case PortStateUpdate.TugDelta td -> model.applyTugDelta(td);
                }
            });
        });

        this.weatherSubscription =
                eventBus.subscribe(WeatherChangeEvent.class, model::applyWeather, DeliveryMode.EDT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        MapPainter.paint((Graphics2D) g, getWidth(), getHeight(), model);
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        active = false;
        weatherSubscription.cancel();
    }

    /** Test-only: the model backing this panel's rendering. */
    MapModel model() {
        return model;
    }
}
