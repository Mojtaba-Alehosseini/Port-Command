package it.unige.portcommand.gui;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.gui.model.GameSpeedModel;
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
 *
 * <p>S14: the bar also carries the game-speed toggles (&times;1 / &times;2 / &times;4). They are
 * deliberately plain — the choices and their labels come from {@link GameSpeedModel}, applying one
 * is {@link GameSpeedControl#set(int)}, and the selection follows {@link GameSpeedChangedEvent},
 * so the Game &#9656; Speed menu (and its Ctrl+1 / Ctrl+2 / Ctrl+4 keys) stay in step. Not
 * focusable: a click must never pull keyboard focus out of the chat input.
 */
public final class HUDPanel extends JPanel {

    private final HUDModel model = new HUDModel();
    private final List<Subscription<?>> subscriptions = new ArrayList<>();
    private final JLabel dayLabel = new JLabel();
    private final JLabel timeLabel = new JLabel();
    private final JLabel walletLabel = new JLabel();
    private final JLabel reputationLabel = new JLabel();
    private final List<JToggleButton> speedToggles = new ArrayList<>();

    /** Component-name prefix of a speed toggle: {@code hud-speed-1}, {@code -2}, {@code -4}. */
    static final String NAME_SPEED_PREFIX = "hud-speed-";

    /** A HUD with no speed control (headless panel tests that only watch the labels). */
    public HUDPanel(EventBus eventBus) {
        this(eventBus, null);
    }

    public HUDPanel(EventBus eventBus, GameSpeedControl speedControl) {
        super(new FlowLayout(FlowLayout.LEFT, 16, 4));
        refreshLabels();
        add(dayLabel);
        add(timeLabel);
        add(walletLabel);
        add(reputationLabel);
        add(new JLabel(model.dailyTargetLabel()));
        if (speedControl != null) {
            addSpeedToggles(eventBus, speedControl);
        }

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
        subscriptions.add(eventBus.subscribe(GameSpeedChangedEvent.class,
                e -> selectSpeed(e.speed()), DeliveryMode.EDT));
    }

    private static final String SPEED_PROPERTY = "hud.speed";

    private void selectSpeed(int speed) {
        int shown = GameSpeedModel.normalise(speed);
        for (JToggleButton toggle : speedToggles) {
            toggle.setSelected(Integer.valueOf(shown).equals(toggle.getClientProperty(SPEED_PROPERTY)));
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
