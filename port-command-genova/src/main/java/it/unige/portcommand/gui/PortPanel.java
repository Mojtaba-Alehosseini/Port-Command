package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.PortTableModel;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The Port table (S15, 2026-10-12), which replaced the map in the left column: one line per berth
 * (who holds it, and until when), then one line each for the berth queue, the tugs and the
 * weather. Every string comes from {@link PortTableModel}, a plain POJO unit-tested with no Swing
 * involved ({@code PortTableModelTest}); this class only places labels and keeps the model fed.
 *
 * <p><b>Two update channels, two different threading contracts</b> (INVARIANTS, task 18 — taken
 * over unchanged from the map panel this replaces). {@link EventBus} in {@link DeliveryMode#EDT}
 * already dispatches via {@code invokeLater}, so the three bus subscriptions below do NOT re-wrap.
 * {@link PortStateArtifact} is the other mechanism: it calls its subscribers synchronously on
 * whatever agent thread called {@code updateTug()} — never the EDT — so this panel does the EDT
 * hop itself, around that callback only.
 *
 * <p><b>{@code PortStateArtifact.subscribe} has no cancel method</b> (a task-06 API, not modified),
 * so the panel cannot literally unsubscribe from it in {@link #removeNotify()}. {@link #active} is
 * the "poor-man's unsubscribe": once {@code false}, the callback schedules nothing and already
 * scheduled work becomes a no-op. Safe in production (one {@code PortPanel} per artifact for the
 * JVM's life) and in tests (each builds its own artifact).
 *
 * <p><b>What comes from where.</b> Berths and queue: the HarbourMaster's {@link PortStatusEvent}
 * (the artifact knows contracted vessels only, and no queue). Tugs: the artifact — a snapshot at
 * construction, because a tug's first status push happens during fleet spawn, before any window
 * subscribes, then live deltas. Weather: {@link WeatherChangeEvent}; the top bar shows none, so
 * this line is the only place the player sees it. On {@link GameLoadedEvent} the berths and the
 * queue go back to "unknown" (the HarbourMaster re-announces a tick later) and the tugs are
 * reseeded from the artifact.
 *
 * <p><b>Built for a 180 px column.</b> A line never asks for more width than it gets: the cells
 * that can be long (a vessel's name, the three values) have no minimum width and are cut with an
 * ellipsis, and every line carries its full text as a tooltip. Nothing here takes the keyboard
 * focus — a click in this panel must never pull it out of the chat input.
 */
public final class PortPanel extends JPanel {

    static final String TITLE = "Port";
    /** Pixels between the cells of one line. */
    static final int GAP = 6;
    private static final int LINE_GAP = 2;

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color RULE = new Color(0xC8, 0xC8, 0xC8);

    private final PortTableModel model = new PortTableModel();
    private final PortStateArtifact portStateArtifact;
    private final List<Subscription<?>> subscriptions = new ArrayList<>();
    private final List<BerthLineView> berthViews = new ArrayList<>();
    private final InfoLineView queueView = new InfoLineView();
    private final InfoLineView tugView = new InfoLineView();
    private final InfoLineView weatherView = new InfoLineView();
    private volatile boolean active = true;

    public PortPanel(EventBus eventBus, PortStateArtifact portStateArtifact) {
        super(new BorderLayout());
        this.portStateArtifact = Objects.requireNonNull(portStateArtifact, "portStateArtifact");
        setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        JLabel title = new JLabel(TITLE);
        title.setFont(title.getFont().deriveFont(Font.BOLD));

        JPanel berthBlock = new JPanel(new GridLayout(0, 1, 0, LINE_GAP));
        int berthCount = model.berthRows().size();
        for (int i = 0; i < berthCount; i++) {
            BerthLineView view = new BerthLineView();
            berthViews.add(view);
            berthBlock.add(view);
        }

        JPanel infoBlock = new JPanel(new GridLayout(0, 1, 0, LINE_GAP));
        infoBlock.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, RULE),
                BorderFactory.createEmptyBorder(GAP - LINE_GAP, 0, 0, 0)));
        infoBlock.add(queueView);
        infoBlock.add(tugView);
        infoBlock.add(weatherView);

        // The lines sit at the TOP of the column at their natural height; whatever height the
        // column has beyond that stays empty rather than stretching the lines apart.
        JPanel stack = new JPanel(new BorderLayout(0, GAP));
        stack.add(title, BorderLayout.NORTH);
        stack.add(berthBlock, BorderLayout.CENTER);
        stack.add(infoBlock, BorderLayout.SOUTH);
        add(stack, BorderLayout.NORTH);
        quiet(this, title, berthBlock, infoBlock, stack);

        // Subscribed BEFORE the snapshot is taken, so a tug write is either in the snapshot or
        // followed by a delta this panel receives. The other order loses the write that lands in
        // between — and a tug going IDLE writes once and then says nothing until its next job, so
        // the line would count it as busy for as long as it rests. A delta only queues an EDT
        // hop; it is applied after the seed, and the last one applied is the latest write.
        portStateArtifact.subscribe(update -> {
            if (!active) {
                return;
            }
            switch (update) {
                case PortStateUpdate.TugDelta tug -> SwingUtilities.invokeLater(() -> {
                    if (active) {
                        model.applyTug(tug.tugId(), tug.status());
                    }
                });
                // Berths are NOT read from the artifact any more: it shows a berth held by a
                // walk-in as free. They come from PortStatusEvent, so no EDT hop is spent here.
                case PortStateUpdate.BerthDelta berth -> { }
            }
        });

        // The snapshot itself is needed because a tug's one-time setup push fired during fleet
        // spawn, long before this window existed: without it an idle fleet would read "—" until
        // some tug happened to move.
        model.seedTugs(portStateArtifact.tugSnapshot());
        render();
        alignLabelColumn();
        model.setOnChange(this::render);

        subscriptions.add(eventBus.subscribe(PortStatusEvent.class, model::applyStatus, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(WeatherChangeEvent.class, model::applyWeather, DeliveryMode.EDT));
        subscriptions.add(eventBus.subscribe(GameLoadedEvent.class, this::onGameLoaded, DeliveryMode.EDT));
    }

    /**
     * A world was replaced (load, scenario, free play). What the table showed belongs to the old
     * one: blank the berths and the queue until the new HarbourMaster reports — it is asked to
     * re-announce right after this event, and an equal-looking status is then shown again because
     * the model no longer holds the old one — and take the tugs from the artifact, which the new
     * fleet has already written to by the time this event is published.
     */
    private void onGameLoaded(GameLoadedEvent event) {
        model.clearStatus();
        model.seedTugs(portStateArtifact.tugSnapshot());
    }

    private void render() {
        List<PortTableModel.BerthLine> berths = model.berthRows();
        for (int i = 0; i < berthViews.size() && i < berths.size(); i++) {
            berthViews.get(i).display(berths.get(i));
        }
        queueView.display(model.queueRow());
        tugView.display(model.tugRow());
        weatherView.display(model.weatherRow());
    }

    /** Gives the seven line labels one common width, so the cells after them start in one column. */
    private void alignLabelColumn() {
        List<JLabel> labels = new ArrayList<>();
        for (BerthLineView view : berthViews) {
            labels.add(view.label);
        }
        labels.add(queueView.label);
        labels.add(tugView.label);
        labels.add(weatherView.label);
        int width = 0;
        for (JLabel label : labels) {
            width = Math.max(width, label.getPreferredSize().width);
        }
        for (JLabel label : labels) {
            label.setPreferredSize(new Dimension(width, label.getPreferredSize().height));
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        active = false;
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    /** Test-only: the model backing this panel's lines. */
    PortTableModel model() {
        return model;
    }

    /** Test-only: berth line {@code index} (0-based) as its three labels — berth, occupant, state. */
    List<JLabel> berthLabelsForTest(int index) {
        BerthLineView view = berthViews.get(index);
        return List.of(view.label, view.occupant, view.state);
    }

    /** Test-only: the value label of the queue line. */
    JLabel queueLabelForTest() {
        return queueView.value;
    }

    /** Test-only: the value label of the tug line. */
    JLabel tugLabelForTest() {
        return tugView.value;
    }

    /** Test-only: the value label of the weather line. */
    JLabel weatherLabelForTest() {
        return weatherView.value;
    }

    /** Never a focus owner, never a focus target: see the class javadoc's last paragraph. */
    private static void quiet(JComponent... parts) {
        for (JComponent part : parts) {
            part.setFocusable(false);
            part.setRequestFocusEnabled(false);
        }
    }

    private static void tip(String text, JComponent... parts) {
        for (JComponent part : parts) {
            part.setToolTipText(text);
        }
    }

    private static JLabel lineLabel() {
        JLabel label = new JLabel();
        label.setFont(label.getFont().deriveFont(Font.PLAIN));
        label.setForeground(MUTED);
        return label;
    }

    /**
     * A label that may be cut short: it asks for no minimum width, so a narrow column truncates
     * its text with an ellipsis instead of holding the layout wide.
     */
    static final class CutLabel extends JLabel {

        CutLabel() {
            setFont(getFont().deriveFont(Font.PLAIN));
        }

        @Override
        public Dimension getMinimumSize() {
            if (isMinimumSizeSet()) {
                return super.getMinimumSize();
            }
            return new Dimension(0, super.getMinimumSize().height);
        }
    }

    /** {@code Berth 1 | Genoa Star | until 14:30}. */
    private static final class BerthLineView extends JPanel {

        private final JLabel label = lineLabel();
        private final CutLabel occupant = new CutLabel();
        private final CutLabel state = new CutLabel();
        private final Color occupantColour = occupant.getForeground();

        BerthLineView() {
            setLayout(new LineLayout(label, occupant, state));
            add(label);
            add(occupant);
            add(state);
            quiet(this, label, occupant, state);
        }

        void display(PortTableModel.BerthLine line) {
            label.setText(line.label());
            occupant.setText(line.occupant());
            occupant.setForeground(line.isUnknown() ? MUTED : occupantColour);
            state.setText(line.state());
            // Hidden when empty so the occupant cell gets the whole rest of the line.
            state.setVisible(!line.state().isEmpty());
            tip(line.isUnknown() ? null : line.fullText(), this, label, occupant, state);
        }
    }

    /** {@code Tugs | 3 free · 1 busy}. */
    private static final class InfoLineView extends JPanel {

        private final JLabel label = lineLabel();
        private final CutLabel value = new CutLabel();
        private final Color valueColour = value.getForeground();

        InfoLineView() {
            setLayout(new LineLayout(label, value, null));
            add(label);
            add(value);
            quiet(this, label, value);
        }

        void display(PortTableModel.InfoLine line) {
            label.setText(line.label());
            value.setText(line.text());
            value.setForeground(line.isUnknown() ? MUTED : valueColour);
            tip(line.isUnknown() ? null : line.fullText(), this, label, value);
        }
    }

    /**
     * Lays one line out left to right: the label at its natural width, the middle cell, and — when
     * there is one and it is visible — a trailing cell flush right.
     *
     * <p>When the line is too narrow for everything, the cells give way in a fixed order and
     * nothing ever overlaps or leaves the line: the middle cell (a vessel's name) shrinks first,
     * down to nothing; only then the trailing cell (the time a berth frees — the part the player
     * plans by); the label last. {@code BorderLayout} would do the same down to the point where
     * the middle is used up, and then lay the trailing cell over the label — and where that point
     * falls depends on the platform's font, so it is not left to chance.
     */
    static final class LineLayout implements LayoutManager {

        private final Component label;
        private final Component middle;
        private final Component trailing;

        /** @param trailing may be {@code null}: a line with a label and one value */
        LineLayout(Component label, Component middle, Component trailing) {
            this.label = Objects.requireNonNull(label, "label");
            this.middle = Objects.requireNonNull(middle, "middle");
            this.trailing = trailing;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
            // The three cells are fixed at construction.
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            // The three cells are fixed at construction.
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            Dimension labelSize = label.getPreferredSize();
            Dimension middleSize = middle.getPreferredSize();
            int width = labelSize.width + GAP + middleSize.width;
            int height = Math.max(labelSize.height, middleSize.height);
            if (hasTrailing()) {
                Dimension trailingSize = trailing.getPreferredSize();
                width += GAP + trailingSize.width;
                height = Math.max(height, trailingSize.height);
            }
            return new Dimension(insets.left + insets.right + width, insets.top + insets.bottom + height);
        }

        /** Only the label insists on its width; that is what lets the column be narrow. */
        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            return new Dimension(insets.left + insets.right + label.getPreferredSize().width,
                    preferredLayoutSize(parent).height);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int top = insets.top;
            int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            int left = insets.left;
            int right = Math.max(left, parent.getWidth() - insets.right);

            int labelWidth = Math.min(right - left, label.getPreferredSize().width);
            label.setBounds(left, top, labelWidth, height);

            int middleLeft = Math.min(right, left + labelWidth + GAP);
            int middleRight = right;
            if (hasTrailing()) {
                int trailingWidth = Math.min(right - middleLeft, trailing.getPreferredSize().width);
                trailing.setBounds(right - trailingWidth, top, trailingWidth, height);
                middleRight = Math.max(middleLeft, right - trailingWidth - GAP);
            }
            middle.setBounds(middleLeft, top, middleRight - middleLeft, height);
        }

        private boolean hasTrailing() {
            return trailing != null && trailing.isVisible();
        }
    }
}
