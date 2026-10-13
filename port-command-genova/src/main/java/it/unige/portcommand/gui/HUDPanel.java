package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.gui.model.GameSpeedModel;
import it.unige.portcommand.gui.model.HUDModel;
import it.unige.portcommand.gui.model.HudWeatherModel;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
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
 * <p>Every subscription is {@link DeliveryMode#EDT}, which already dispatches on the Event
 * Dispatch Thread — deliberately NOT re-wrapped in another {@code invokeLater} (INVARIANTS,
 * task 18: the two-channel threading contract).
 *
 * <p>S14: the bar also carries the game-speed toggles (&times;1 / &times;2 / &times;4). They are
 * deliberately plain — the choices and their labels come from {@link GameSpeedModel}, applying one
 * is {@link GameSpeedControl#set(int)}, and the selection follows {@link GameSpeedChangedEvent},
 * so the Game &#9656; Speed menu (and its Ctrl+1 / Ctrl+2 / Ctrl+4 keys) stay in step. Not
 * focusable: a click must never pull keyboard focus out of the chat input.
 *
 * <p><b>S16 (2026-10-13): the toggles show the scenario-script lock.</b> While a scenario's
 * scripted events are still to come the speed is held at &times;1
 * ({@link GameSpeedControl#scriptPending()}), and the three toggles are then DISABLED and say why
 * in a tooltip — a control that silently snaps back reads as a bug. They re-read the lock on every
 * {@link GameSpeedChangedEvent}, which the control publishes whenever the lock changes. The two
 * older refusals (a load in flight, no game running) keep their snap-back: they last a moment or
 * belong to an empty window, and this bar is not told about either.
 *
 * <p><b>S16: the weather is on this bar</b>, after everything that was here before — the last
 * {@link WeatherChangeEvent} as one line ({@link HudWeatherModel}), {@code —} until the first one
 * and again when a world is replaced ({@link GameLoadedEvent}; the next world announces its own
 * weather as it boots). It is the one cell of the bar that gives way: {@link BarLayout} hands it
 * only the room the others leave, so at the window's minimum width it is cut with an ellipsis
 * (its tooltip has the whole line) and never pushes a speed toggle off the bar. A click on it
 * opens the forecast ({@link WeatherForecastPopup}); like everything else on the bar it is not
 * focusable, and neither is the forecast.
 */
public final class HUDPanel extends JPanel {

    /** Pixels between two cells of the bar, and before the first (as the {@code FlowLayout} had). */
    static final int HGAP = 16;
    /** Pixels above and below the row. */
    static final int VGAP = 4;

    private final HUDModel model = new HUDModel();
    private final HudWeatherModel weatherModel = new HudWeatherModel();
    private final WeatherForecastPopup forecastPopup = new WeatherForecastPopup();
    private final List<Subscription<?>> subscriptions = new ArrayList<>();
    private final JLabel dayLabel = new JLabel();
    private final JLabel timeLabel = new JLabel();
    private final JLabel walletLabel = new JLabel();
    private final JLabel reputationLabel = new JLabel();
    private final JLabel weatherLabel = new JLabel();
    /** The weather label's own font, and the same font underlined — the cue, as for a rule id in
     * the comm log, that the text can be clicked. Only a reading is underlined, not the dash. */
    private final Font weatherPlainFont = weatherLabel.getFont();
    private final Font weatherLinkFont =
            weatherPlainFont.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
    private final List<JToggleButton> speedToggles = new ArrayList<>();
    /** {@code null} on a bar without speed toggles. */
    private final GameSpeedControl speedControl;

    /** Component-name prefix of a speed toggle: {@code hud-speed-1}, {@code -2}, {@code -4}. */
    static final String NAME_SPEED_PREFIX = "hud-speed-";
    /** Component name of the weather line (S16). */
    static final String NAME_WEATHER = "hud-weather";

    /** A HUD with no speed control (headless panel tests that only watch the labels). */
    public HUDPanel(EventBus eventBus) {
        this(eventBus, null);
    }

    public HUDPanel(EventBus eventBus, GameSpeedControl speedControl) {
        this.speedControl = speedControl;
        setLayout(new BarLayout(HGAP, VGAP, weatherLabel));
        refreshLabels();
        add(dayLabel);
        add(timeLabel);
        add(walletLabel);
        add(reputationLabel);
        add(new JLabel(model.dailyTargetLabel()));
        if (speedControl != null) {
            addSpeedToggles(eventBus, speedControl);
        }
        addWeather(eventBus);

        subscriptions.add(eventBus.subscribe(SimClockTickEvent.class, this::onSimClockTick, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(WalletChangedEvent.class, this::onWalletChanged, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(
                ReputationChangedEvent.class, this::onReputationChanged, DeliveryMode.EDT));
    }

    private void addSpeedToggles(EventBus eventBus, GameSpeedControl speedControl) {
        JPanel box = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        box.add(new JLabel("Speed"));
        ButtonGroup group = new ButtonGroup();
        for (int speed : GameSpeedModel.options()) {
            JToggleButton toggle = new JToggleButton(GameSpeedModel.label(speed));
            toggle.setName(NAME_SPEED_PREFIX + speed);
            toggle.setFocusable(false);
            toggle.setMargin(new java.awt.Insets(1, 6, 1, 6));
            toggle.putClientProperty(SPEED_PROPERTY, speed);
            toggle.addActionListener(e -> speedControl.set(speed));
            group.add(toggle);
            speedToggles.add(toggle);
            box.add(toggle);
        }
        add(box);
        selectSpeed(speedControl.current());
        reflectScriptLock();
        subscriptions.add(eventBus.subscribe(GameSpeedChangedEvent.class, e -> {
            selectSpeed(e.speed());
            reflectScriptLock();
        }, DeliveryMode.EDT));
    }

    private static final String SPEED_PROPERTY = "hud.speed";

    private void selectSpeed(int speed) {
        int shown = GameSpeedModel.normalise(speed);
        for (JToggleButton toggle : speedToggles) {
            toggle.setSelected(Integer.valueOf(shown).equals(toggle.getClientProperty(SPEED_PROPERTY)));
        }
    }

    /**
     * S16: disabled, with the reason as a tooltip, while the scenario's script holds the speed;
     * as they were otherwise. Read from the control, never tracked here: the control is the one
     * place that knows, and it announces every change of the lock (see the class comment).
     */
    private void reflectScriptLock() {
        boolean locked = speedControl != null && speedControl.scriptPending();
        for (JToggleButton toggle : speedToggles) {
            toggle.setEnabled(!locked);
            toggle.setToolTipText(locked ? GameSpeedModel.SCRIPT_LOCK_TOOLTIP : null);
        }
    }

    /** Test-only: the speed whose toggle is selected, or 0 when the bar has no speed control. */
    int selectedSpeedForTest() {
        return speedToggles.stream().filter(JToggleButton::isSelected)
                .map(t -> (Integer) t.getClientProperty(SPEED_PROPERTY)).findFirst().orElse(0);
    }

    /** Test-only: clicks the toggle for {@code speed} exactly as the player would. */
    void clickSpeedForTest(int speed) {
        speedToggles.stream()
                .filter(t -> Integer.valueOf(speed).equals(t.getClientProperty(SPEED_PROPERTY)))
                .findFirst().orElseThrow().doClick();
    }

    /**
     * S16: the weather line — the bar's last cell, a label that is clicked rather than a button
     * (it should read as part of the bar's text, and a label has no focus to give away).
     */
    private void addWeather(EventBus eventBus) {
        weatherLabel.setName(NAME_WEATHER);
        weatherLabel.setFocusable(false);
        weatherLabel.setRequestFocusEnabled(false);
        weatherLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        weatherLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    forecastPopup.toggle(weatherLabel, weatherModel.state(), e.getWhen());
                }
            }
        });
        refreshWeather();
        add(weatherLabel);

        subscriptions.add(eventBus.subscribe(WeatherChangeEvent.class, this::onWeatherChanged, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(GameLoadedEvent.class, this::onGameLoaded, DeliveryMode.EDT));
    }

    private void onWeatherChanged(WeatherChangeEvent event) {
        String stateBefore = weatherModel.state();
        if (!weatherModel.apply(event)) {
            return; // the same reading again: the weather agent re-announces every fifteen game minutes
        }
        if (!Objects.equals(stateBefore, weatherModel.state())) {
            // An open forecast was worked out from the state that has just ended.
            forecastPopup.hide();
        }
        refreshWeather();
    }

    /**
     * A world was replaced (load, scenario, free play): its weather is not the next world's. Back
     * to the placeholder — {@code GameSession} publishes the new world's opening reading right
     * after this event, so the dash is on screen for one turn of the EDT, not for the fifteen
     * game minutes until the new weather agent's first broadcast.
     */
    private void onGameLoaded(GameLoadedEvent event) {
        forecastPopup.hide();
        if (weatherModel.clear()) {
            refreshWeather();
        }
    }

    private void refreshWeather() {
        weatherLabel.setText(weatherModel.text());
        weatherLabel.setFont(weatherModel.hasReading() ? weatherLinkFont : weatherPlainFont);
        weatherLabel.setToolTipText(weatherModel.tooltip());
    }

    /** Test-only: the weather line. */
    JLabel weatherLabelForTest() {
        return weatherLabel;
    }

    /** Test-only: what a click on the weather line would show now (the popup itself needs a
     * window to open in). */
    JPanel forecastContentForTest() {
        return WeatherForecastPopup.content(weatherModel.state());
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
        forecastPopup.hide();
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    /** Test-only: the model backing this panel's display. */
    HUDModel model() {
        return model;
    }

    /** Test-only: the model backing the weather line. */
    HudWeatherModel weatherModel() {
        return weatherModel;
    }

    /**
     * The bar's layout (S16, 2026-10-13): one row, left to right, every cell at its preferred
     * size, {@code hgap} pixels apart and before the first, centred on the row's height — which
     * is where {@code FlowLayout.LEFT} put the same cells before — with one difference: the
     * {@code elastic} cell gets only the room that is left, down to nothing.
     *
     * <p>That cell is the weather. {@code FlowLayout} gives every cell its preferred width and
     * wraps what does not fit onto a second row, which the bar — as tall as one row — would
     * simply not show. With the weather line added, the bar's preferred width is close to the
     * window's 1000 px minimum (the weather alone is some 270 px at its longest), and how close
     * depends on the platform's font and on how many digits the wallet has. So the cell whose
     * text can be cut IS cut, with the label's own ellipsis, and the speed toggles before it
     * keep their place and their size at every width.
     */
    static final class BarLayout implements LayoutManager {

        private final int hgap;
        private final int vgap;
        private final Component elastic;

        BarLayout(int hgap, int vgap, Component elastic) {
            this.hgap = hgap;
            this.vgap = vgap;
            this.elastic = Objects.requireNonNull(elastic, "elastic");
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
            // Nothing is kept per cell: every pass reads the container's own list.
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            // Nothing is kept per cell: every pass reads the container's own list.
        }

        /** Every cell at its preferred width: the width at which nothing is cut. */
        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return size(parent, true);
        }

        /** The same without the elastic cell, which can go down to nothing. */
        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return size(parent, false);
        }

        private Dimension size(Container parent, boolean withElastic) {
            Insets insets = parent.getInsets();
            int width = hgap;
            int height = 0;
            for (Component cell : parent.getComponents()) {
                if (!cell.isVisible()) {
                    continue;
                }
                Dimension preferred = cell.getPreferredSize();
                height = Math.max(height, preferred.height);
                if (withElastic || cell != elastic) {
                    width += preferred.width + hgap;
                }
            }
            return new Dimension(insets.left + insets.right + width, insets.top + insets.bottom + height + 2 * vgap);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int rowHeight = 0;
            for (Component cell : parent.getComponents()) {
                if (cell.isVisible()) {
                    rowHeight = Math.max(rowHeight, cell.getPreferredSize().height);
                }
            }
            int right = parent.getWidth() - insets.right - hgap;
            int x = insets.left + hgap;
            int top = insets.top + vgap;
            for (Component cell : parent.getComponents()) {
                if (!cell.isVisible()) {
                    continue;
                }
                Dimension preferred = cell.getPreferredSize();
                int width = cell == elastic ? Math.max(0, Math.min(preferred.width, right - x)) : preferred.width;
                cell.setBounds(x, top + (rowHeight - preferred.height) / 2, width, preferred.height);
                x += width + hgap;
            }
        }
    }
}
