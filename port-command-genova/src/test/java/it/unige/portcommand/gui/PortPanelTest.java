package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.BerthOccupancyUpdate;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.PortStatusEvent.BerthRow;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the Port table's wiring — which event reaches which line, on which thread,
 * and that the lines fit a 180 px column. Every string itself is {@code PortTableModelTest}'s.
 *
 * <p>Headless, the publish-and-flush idiom of the other panel tests: the panel is a plain
 * {@code JPanel} that is never put in a window, events are published from this thread and the EDT
 * is drained with {@code invokeAndWait}. A panel with no peer is not laid out by
 * {@code validate()} (it returns at once without one), so the layout tests size the panel and call
 * {@code doLayout()} down the tree themselves.
 */
class PortPanelTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY1_1430 = 14 * HOUR + 30 * MINUTE;
    private static final long DAY2_0215 = 24 * HOUR + 2 * HOUR + 15 * MINUTE;

    private static final Position ANYWHERE = new Position(10, 20, 0);

    private final EventBus bus = new EventBus();
    private final PortStateArtifact portState = new PortStateArtifact();

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static PortStatusEvent status(int gameDay, List<String> queued, BerthRow... held) {
        List<BerthRow> rows = new ArrayList<>();
        for (String berthId : List.of("berth_1", "berth_2", "berth_3", "berth_4")) {
            BerthRow row = BerthRow.free(berthId);
            for (BerthRow candidate : held) {
                if (berthId.equals(candidate.berthId())) {
                    row = candidate;
                }
            }
            rows.add(row);
        }
        return new PortStatusEvent(gameDay, rows, queued);
    }

    private static BerthRow docked(String berthId, String vesselName, Long until) {
        return new BerthRow(berthId, vesselName, vesselName, Channel.CONTRACTED, Stage.DOCKED, until, false);
    }

    private static List<String> texts(List<JLabel> labels) {
        List<String> texts = new ArrayList<>();
        for (JLabel label : labels) {
            texts.add(label.getText());
        }
        return texts;
    }

    /** Four tugs in the artifact, three of them free (IDLE and BIDDING both count), one busy. */
    private void seedFleet() {
        portState.updateTug("tug_1", ANYWHERE, TugStatus.IDLE);
        portState.updateTug("tug_2", ANYWHERE, TugStatus.BIDDING);
        portState.updateTug("tug_3", ANYWHERE, TugStatus.ESCORTING);
        portState.updateTug("tug_4", ANYWHERE, TugStatus.IDLE);
    }

    /** Sizes {@code root} and lays the whole tree under it out — what a window would do. */
    private static void layOut(Container root, int width, int height) {
        root.setSize(width, height);
        layOutTree(root);
    }

    private static void layOutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layOutTree(nested);
            }
        }
    }

    private static void collect(Container container, List<Component> into) {
        for (Component child : container.getComponents()) {
            into.add(child);
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }

    // --- what the panel shows --------------------------------------------------------------------

    @Test
    @Timeout(5)
    void withNoWorldEveryLineReadsUnknown() {
        PortPanel panel = new PortPanel(bus, portState);

        for (int i = 0; i < 4; i++) {
            assertEquals(List.of("Berth " + (i + 1), "—", ""), texts(panel.berthLabelsForTest(i)));
            for (JLabel label : panel.berthLabelsForTest(i)) {
                assertNull(label.getToolTipText(), "an unknown line has nothing more to say in a tooltip");
            }
        }
        assertEquals("—", panel.queueLabelForTest().getText());
        assertEquals("—", panel.tugLabelForTest().getText());
        assertEquals("—", panel.weatherLabelForTest().getText());
        assertNull(panel.weatherLabelForTest().getToolTipText());
    }

    @Test
    @Timeout(5)
    void aStatusEventReachesTheBerthAndQueueLines() throws Exception {
        PortPanel panel = new PortPanel(bus, portState);

        bus.publish(status(1, List.of("Genoa Star", "C502"),
                docked("berth_1", "C501", DAY1_1430),
                new BerthRow("berth_3", "W003", "Ligurian Dawn", Channel.WALK_IN, Stage.AWAITING_TUG, null, false)));
        flush();

        assertEquals(List.of("Berth 1", "C501", "until 14:30"), texts(panel.berthLabelsForTest(0)));
        assertEquals(List.of("Berth 2", "free", ""), texts(panel.berthLabelsForTest(1)));
        assertEquals(List.of("Berth 3", "Ligurian Dawn", "awaiting tugs"), texts(panel.berthLabelsForTest(2)));
        assertEquals(List.of("Berth 4", "free", ""), texts(panel.berthLabelsForTest(3)));
        assertEquals("2 waiting · Genoa Star, C502", panel.queueLabelForTest().getText());

        assertEquals("Berth 1 · C501 · until 14:30", panel.berthLabelsForTest(0).get(1).getToolTipText(),
                "the full line is the tooltip of each of its cells");
        assertEquals("Berth 1 · C501 · until 14:30", panel.berthLabelsForTest(0).get(2).getToolTipText());
        assertEquals("Queue · 2 waiting · Genoa Star, C502", panel.queueLabelForTest().getToolTipText());
        assertTrue(panel.berthLabelsForTest(0).get(2).isVisible());
        assertFalse(panel.berthLabelsForTest(1).get(2).isVisible(),
                "an empty state cell is hidden, so the occupant gets the rest of the line");
    }

    @Test
    @Timeout(5)
    void aWeatherEventReachesTheWeatherLine() throws Exception {
        PortPanel panel = new PortPanel(bus, portState);

        bus.publish(new WeatherChangeEvent(18.4, "good", 1.2, "sunny", false));
        flush();
        assertEquals("18 kn · sunny · visibility good", panel.weatherLabelForTest().getText());
        assertEquals("Weather · 18 kn · sunny · visibility good", panel.weatherLabelForTest().getToolTipText());

        bus.publish(new WeatherChangeEvent(41.5, "poor", 3.4, "stormy", true));
        flush();
        assertEquals("42 kn · stormy · visibility poor", panel.weatherLabelForTest().getText());
    }

    // --- the artifact channel ----------------------------------------------------------------------

    @Test
    @Timeout(5)
    void theTugLineIsSeededFromTheArtifactAtConstruction() {
        // The fleet's one-time setup pushes happened before any window existed: without the
        // snapshot an idle fleet would read "—" until some tug moved.
        seedFleet();

        PortPanel panel = new PortPanel(bus, portState);

        assertEquals("3 free · 1 busy", panel.tugLabelForTest().getText(), "no EDT flush needed: seeded in place");
        assertEquals("Tugs · 3 free · 1 busy", panel.tugLabelForTest().getToolTipText());
    }

    @Test
    @Timeout(10)
    void aTugDeltaIsNotAppliedOnTheAgentsThreadButOnTheEdt() throws Exception {
        PortPanel panel = new PortPanel(bus, portState);

        // Park the EDT, so "not yet" is a fact and not a race: PortStateArtifact calls its
        // subscribers synchronously on the CALLING thread (this one — not the EDT). If the panel
        // mutated in that callback the label would already have changed when updateTug returns;
        // with the hop it cannot change until the EDT is released.
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            parked.countDown();
            try {
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(parked.await(8, TimeUnit.SECONDS), "the EDT did not reach the parking task");
        try {
            portState.updateTug("tug_1", ANYWHERE, TugStatus.ESCORTING);
            assertEquals("—", panel.tugLabelForTest().getText(),
                    "the model must not be touched on the calling (non-EDT) thread");
        } finally {
            release.countDown();
        }

        flush();
        assertEquals("0 free · 1 busy", panel.tugLabelForTest().getText(), "applied once the EDT drains");
    }

    @Test
    @Timeout(5)
    void tugDeltasKeepTheLineCurrentAndBerthDeltasAreIgnored() throws Exception {
        seedFleet();
        PortPanel panel = new PortPanel(bus, portState);

        assertEquals("3 free · 1 busy", panel.tugLabelForTest().getText());

        portState.updateTug("tug_1", ANYWHERE, TugStatus.EN_ROUTE_TO_VESSEL);
        portState.updateTug("tug_4", ANYWHERE, TugStatus.REFUELING);
        flush();
        assertEquals("1 free · 3 busy", panel.tugLabelForTest().getText(), "tug_1 and tug_4 left their bases");

        portState.updateTug("tug_3", ANYWHERE, TugStatus.RETURNING);
        portState.updateTug("tug_3", ANYWHERE, TugStatus.IDLE);
        flush();
        assertEquals("2 free · 2 busy", panel.tugLabelForTest().getText(),
                "deltas for one tug are applied in order: tug_3 is back");

        // The artifact shows a berth held by a walk-in as free, so its berth channel is not what
        // the table reads: a DOCKED delta there changes nothing on screen.
        portState.update(new BerthOccupancyUpdate("berth_1",
                new BerthOccupancy("berth_1", "C501", 0L, DAY1_1430, 1, BerthOccupancy.Status.DOCKED)));
        flush();
        assertEquals(List.of("Berth 1", "—", ""), texts(panel.berthLabelsForTest(0)));
    }

    // --- a replaced world ----------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void aLoadedWorldBlanksTheBerthsReseedsTheTugsAndKeepsTheWeather() throws Exception {
        seedFleet();
        PortPanel panel = new PortPanel(bus, portState);
        PortStatusEvent before = status(1, List.of("Genoa Star"), docked("berth_1", "C501", DAY1_1430));
        bus.publish(before);
        bus.publish(new WeatherChangeEvent(18.4, "good", 1.2, "sunny", false));
        flush();
        assertEquals(List.of("Berth 1", "C501", "until 14:30"), texts(panel.berthLabelsForTest(0)));

        // Make the table's idea of the fleet differ from the artifact's, so that "3 free · 1 busy"
        // after the load can only have come from a fresh snapshot.
        SwingUtilities.invokeAndWait(() -> panel.model().seedTugs(Map.of()));
        assertEquals("—", panel.tugLabelForTest().getText(), "precondition: the model drives the label");

        bus.publish(new GameLoadedEvent(1, 50_000.0, 70));
        flush();

        for (int i = 0; i < 4; i++) {
            assertEquals(List.of("Berth " + (i + 1), "—", ""), texts(panel.berthLabelsForTest(i)),
                    "the old world's berths are gone — unknown, not free");
        }
        assertEquals("—", panel.queueLabelForTest().getText());
        assertEquals("3 free · 1 busy", panel.tugLabelForTest().getText(), "reseeded from the artifact");
        assertEquals("18 kn · sunny · visibility good", panel.weatherLabelForTest().getText(), "weather is kept");

        // The new HarbourMaster re-announces right after the load. Even when that status equals
        // the one shown before the load, it must be shown again.
        bus.publish(status(1, List.of("Genoa Star"), docked("berth_1", "C501", DAY1_1430)));
        flush();
        assertEquals(List.of("Berth 1", "C501", "until 14:30"), texts(panel.berthLabelsForTest(0)));
        assertEquals("1 waiting · Genoa Star", panel.queueLabelForTest().getText());
    }

    // --- lifecycle -----------------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void removeNotifyStopsBothChannels() throws Exception {
        seedFleet();
        PortPanel panel = new PortPanel(bus, portState);
        bus.publish(status(1, List.of(), docked("berth_1", "C501", DAY1_1430)));
        bus.publish(new WeatherChangeEvent(18.4, "good", 1.2, "sunny", false));
        portState.updateTug("tug_1", ANYWHERE, TugStatus.ESCORTING);
        flush();
        assertEquals("C501", panel.berthLabelsForTest(0).get(1).getText(),
                "positive control: the bus channel is live");
        assertEquals("2 free · 2 busy", panel.tugLabelForTest().getText(),
                "positive control: the artifact channel is live");
        assertEquals(1, EventBusProbe.subscriberCount(bus, PortStatusEvent.class));
        assertEquals(1, EventBusProbe.subscriberCount(bus, WeatherChangeEvent.class));
        assertEquals(1, EventBusProbe.subscriberCount(bus, GameLoadedEvent.class));

        panel.removeNotify();

        assertEquals(0, EventBusProbe.subscriberCount(bus, PortStatusEvent.class));
        assertEquals(0, EventBusProbe.subscriberCount(bus, WeatherChangeEvent.class));
        assertEquals(0, EventBusProbe.subscriberCount(bus, GameLoadedEvent.class));

        bus.publish(status(2, List.of("Genoa Star"), docked("berth_2", "C777", DAY2_0215)));
        bus.publish(new WeatherChangeEvent(41.5, "poor", 3.4, "stormy", true));
        bus.publish(new GameLoadedEvent(2, 0.0, 0));
        portState.updateTug("tug_2", ANYWHERE, TugStatus.ESCORTING);
        portState.updateTug("tug_4", ANYWHERE, TugStatus.ESCORTING);
        flush();

        assertEquals(List.of("Berth 1", "C501", "until 14:30"), texts(panel.berthLabelsForTest(0)));
        assertEquals(List.of("Berth 2", "free", ""), texts(panel.berthLabelsForTest(1)));
        assertEquals("none", panel.queueLabelForTest().getText());
        assertEquals("18 kn · sunny · visibility good", panel.weatherLabelForTest().getText());
        assertEquals("2 free · 2 busy", panel.tugLabelForTest().getText(),
                "the artifact has no unsubscribe; the panel's own flag must stop it acting on deltas");
    }

    // --- a narrow column -----------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void atOneHundredAndEightyPixelsEveryCellStaysInsideItsLineAndLongTextIsCutNotSpilled() throws Exception {
        seedFleet();
        PortPanel panel = new PortPanel(bus, portState);
        bus.publish(status(1, List.of("Santa Margherita Ligure", "Camogli Express", "Portofino Trader"),
                docked("berth_1", "Santa Margherita Ligure", DAY2_0215),
                docked("berth_2", "C501", DAY1_1430)));
        bus.publish(new WeatherChangeEvent(18.4, "good", 1.2, "sunny", false));
        flush();

        SwingUtilities.invokeAndWait(() -> layOut(panel, 180, 400));

        List<JLabel> labels = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            labels.addAll(panel.berthLabelsForTest(i));
        }
        labels.add(panel.queueLabelForTest());
        labels.add(panel.tugLabelForTest());
        labels.add(panel.weatherLabelForTest());
        for (JLabel label : labels) {
            Container line = label.getParent();
            assertTrue(line.getWidth() > 0 && line.getWidth() <= 180, "a line is as wide as the column lets it be");
            if (!label.isVisible()) {
                continue;
            }
            assertTrue(label.getWidth() >= 0, label.getText() + ": negative width");
            assertTrue(label.getX() >= 0 && label.getX() + label.getWidth() <= line.getWidth(),
                    label.getText() + ": spills out of its line");
        }

        // The cells of one line do not overlap: berth | occupant | until.
        List<JLabel> first = panel.berthLabelsForTest(0);
        assertTrue(first.get(0).getX() + first.get(0).getWidth() <= first.get(1).getX());
        assertTrue(first.get(1).getX() + first.get(1).getWidth() <= first.get(2).getX());
        assertEquals(first.get(0).getPreferredSize().width, first.get(0).getWidth(), "the berth label is never cut");
        assertEquals(first.get(0).getX(), panel.queueLabelForTest().getParent().getComponent(0).getX(),
                "the seven line labels start in one column");
        assertEquals(first.get(1).getX(), panel.queueLabelForTest().getX(), "and so do the cells after them");

        // What does not fit is cut (the label is narrower than its text) and kept in the tooltip.
        JLabel queue = panel.queueLabelForTest();
        assertTrue(queue.getWidth() < queue.getPreferredSize().width, "three long names cannot fit 180 px");
        assertEquals("Queue · 3 waiting · Santa Margherita Ligure, Camogli Express, Portofino Trader",
                queue.getToolTipText());
        assertEquals("Berth 1 · Santa Margherita Ligure · until D2 02:15", first.get(1).getToolTipText());
    }

    @Test
    @Timeout(5)
    void theCellsThatCanBeLongAskForNoMinimumWidth() throws Exception {
        PortPanel panel = new PortPanel(bus, portState);
        bus.publish(status(1, List.of("Santa Margherita Ligure"),
                docked("berth_1", "Santa Margherita Ligure", DAY2_0215)));
        bus.publish(new WeatherChangeEvent(18.4, "good", 1.2, "sunny", false));
        flush();

        List<JLabel> canBeLong = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            canBeLong.add(panel.berthLabelsForTest(i).get(1));
            canBeLong.add(panel.berthLabelsForTest(i).get(2));
        }
        canBeLong.add(panel.queueLabelForTest());
        canBeLong.add(panel.tugLabelForTest());
        canBeLong.add(panel.weatherLabelForTest());
        for (JLabel label : canBeLong) {
            assertEquals(0, label.getMinimumSize().width, "\"" + label.getText() + "\" would hold the column wide");
        }
        assertTrue(panel.getMinimumSize().width < 180,
                "the panel itself must be able to go down to the left column's 180 px: " + panel.getMinimumSize());
    }

    @Test
    @Timeout(5)
    void lineLayoutGivesWayNameFirstThenTheTrailingCellAndNeverOverlaps() {
        // Fixed sizes instead of real text, so the numbers do not depend on the platform's font.
        JLabel label = sized(50);
        JLabel middle = sized(120);
        JLabel trailing = sized(90);
        JPanel line = new JPanel(new PortPanel.LineLayout(label, middle, trailing));
        line.add(label);
        line.add(middle);
        line.add(trailing);
        int gap = PortPanel.GAP;

        assertEquals(new Dimension(50 + gap + 120 + gap + 90, 16), line.getPreferredSize());
        assertEquals(new Dimension(50, 16), line.getMinimumSize(), "only the label insists on its width");

        layOut(line, 300, 16); // room to spare: the middle takes it
        assertBounds(label, 0, 50);
        assertBounds(middle, 50 + gap, 300 - 90 - gap - 50 - gap);
        assertBounds(trailing, 300 - 90, 90);

        layOut(line, 180, 16); // the middle shrinks, the trailing cell keeps its width
        assertBounds(label, 0, 50);
        assertBounds(middle, 50 + gap, 180 - 90 - gap - 50 - gap);
        assertBounds(trailing, 180 - 90, 90);

        layOut(line, 120, 16); // the middle is used up; now the trailing cell is cut
        assertBounds(label, 0, 50);
        assertBounds(middle, 50 + gap, 0);
        assertBounds(trailing, 50 + gap, 120 - 50 - gap);

        layOut(line, 40, 16); // narrower than the label alone: nothing goes negative or outside
        assertBounds(label, 0, 40);
        assertBounds(middle, 40, 0);
        assertBounds(trailing, 40, 0);

        trailing.setVisible(false); // a free berth: no state cell, the middle gets the rest
        layOut(line, 180, 16);
        assertBounds(middle, 50 + gap, 180 - 50 - gap);
        assertEquals(new Dimension(50 + gap + 120, 16), line.getPreferredSize());
    }

    @Test
    @Timeout(5)
    void aLineWithNoTrailingCellGivesTheValueEverythingAfterTheLabel() {
        JLabel label = sized(50);
        JLabel value = sized(400);
        JPanel line = new JPanel(new PortPanel.LineLayout(label, value, null));
        line.add(label);
        line.add(value);

        layOut(line, 180, 16);

        assertBounds(label, 0, 50);
        assertBounds(value, 50 + PortPanel.GAP, 180 - 50 - PortPanel.GAP);
    }

    private static JLabel sized(int width) {
        JLabel label = new JLabel();
        label.setPreferredSize(new Dimension(width, 16));
        return label;
    }

    private static void assertBounds(Component cell, int x, int width) {
        assertEquals(x, cell.getX(), "x");
        assertEquals(width, cell.getWidth(), "width");
    }

    // --- focus ---------------------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void nothingInThePanelCanTakeTheKeyboardFocus() {
        PortPanel panel = new PortPanel(bus, portState);

        assertFalse(panel.isFocusable(), "a click in the Port table must never pull focus out of the chat input");
        List<Component> all = new ArrayList<>();
        collect(panel, all);
        assertFalse(all.isEmpty());
        for (Component component : all) {
            assertFalse(component.isFocusable(), component.getClass().getSimpleName() + " is focusable");
            if (component instanceof JComponent swing) {
                assertFalse(swing.isRequestFocusEnabled(),
                        component.getClass().getSimpleName() + " would ask for the focus on a click");
            }
        }
    }

    @Test
    @Timeout(5)
    void theShellNamesThePanelItIsNotNamedHere() {
        // GameScreen sets "tutorial-port" on it (DECISIONS 14); a name set here would be a second
        // source of truth for the tutorial overlay's lookup.
        assertNull(new PortPanel(bus, portState).getName());
    }
}
