package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.model.FinishedDialogue;
import it.unige.portcommand.gui.model.OpenDialogue;
import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (playtest 2, PT5): the "Finished" list against a fake {@link DialogueDirectory} — what reaches
 * the screen, and what a click asks of the chat column. Texts are {@code FinishedListModelTest}'s.
 *
 * <p>Headless, like {@code WaitingListPanelTest}: everything on the EDT, real {@link MouseEvent}s
 * dispatched to a line, and the tree laid out by hand first (a peerless panel is not laid out by
 * {@code validate()}, and a line needs real bounds to be clicked "inside").
 */
class FinishedListPanelTest {

    /** The chat column as the list sees it; every field is touched on the EDT only. */
    private static final class FakeDirectory implements DialogueDirectory {

        private final List<FinishedDialogue> finished = new ArrayList<>();
        private final List<Runnable> dialoguesListeners = new ArrayList<>();
        private final List<String> selected = new ArrayList<>();
        private boolean knowsThem = true;

        /** A negotiation ends: it goes on top, as the registry lists them. */
        void finish(String vesselId, String name, Deal.Outcome outcome, double price) {
            finished.add(0, new FinishedDialogue("nego-" + vesselId, name, outcome, price, price > 0 ? 6 : 0));
            fire();
        }

        void fire() {
            for (Runnable listener : List.copyOf(dialoguesListeners)) {
                listener.run();
            }
        }

        @Override
        public List<FinishedDialogue> finishedDialogues() {
            return List.copyOf(finished);
        }

        @Override
        public List<OpenDialogue> openDialogues() {
            return List.of();
        }

        @Override
        public boolean selectDialogue(String dialogueId) {
            selected.add(dialogueId);
            return knowsThem;
        }

        @Override
        public OptionalLong shownRemainingMillis(String dialogueId) {
            return OptionalLong.empty();
        }

        @Override
        public void addDialoguesListener(Runnable listener) {
            dialoguesListeners.add(listener);
        }

        @Override
        public void addSelectedVesselListener(Consumer<String> listener) {
        }
    }

    private final FakeDirectory directory = new FakeDirectory();
    private FinishedListPanel panel;

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    @BeforeEach
    void buildThePanel() throws Exception {
        onEdt(() -> panel = new FinishedListPanel(directory));
    }

    private static void collect(Container container, List<Component> into) {
        for (Component child : container.getComponents()) {
            into.add(child);
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }

    private List<JComponent> named(String name) {
        List<Component> all = new ArrayList<>();
        collect(panel, all);
        List<JComponent> found = new ArrayList<>();
        for (Component component : all) {
            if (name.equals(component.getName()) && component instanceof JComponent swing) {
                found.add(swing);
            }
        }
        return found;
    }

    private static void layOutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layOutTree(nested);
            }
        }
    }

    private static MouseEvent mouse(Component target, int id) {
        int held = id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0;
        return new MouseEvent(target, id, System.currentTimeMillis(), held, target.getWidth() / 2,
                target.getHeight() / 2, 1, false, MouseEvent.BUTTON1);
    }

    /** A left click on line {@code index}: the layout, then the press and the release a click is. */
    private void click(int index) throws Exception {
        onEdt(() -> {
            panel.setSize(200, 400);
            layOutTree(panel);
            JComponent line = named(FinishedListPanel.ROW_NAME).get(index);
            assertTrue(line.getWidth() > 0 && line.getHeight() > 0, "the line was not laid out");
            line.dispatchEvent(mouse(line, MouseEvent.MOUSE_PRESSED));
            line.dispatchEvent(mouse(line, MouseEvent.MOUSE_RELEASED));
        });
    }

    @Test
    @Timeout(10)
    void beforeAnythingEndsItSaysNoneYet() throws Exception {
        onEdt(() -> {
            assertTrue(panel.showsEmptyForTest());
            assertEquals(List.of(), panel.linesForTest());
        });
    }

    @Test
    @Timeout(10)
    void eachEndedNegotiationIsALineNewestFirst() throws Exception {
        onEdt(() -> {
            directory.finish("W001", "Genoa Star", Deal.Outcome.DEAL, 1700.0);
            directory.finish("W002", "Carthago", Deal.Outcome.TIMEOUT, 0);
            directory.finish("W003", "Tirreno", Deal.Outcome.PLAYER_REFUSED, 0);

            assertFalse(panel.showsEmptyForTest());
            assertEquals(List.of("Tirreno | turned away", "Carthago | left", "Genoa Star | deal · €1,700"),
                    panel.linesForTest());
        });
    }

    @Test
    @Timeout(10)
    void aClickShowsThatNegotiation() throws Exception {
        onEdt(() -> {
            directory.finish("W001", "Genoa Star", Deal.Outcome.DEAL, 1700.0);
            directory.finish("W002", "Carthago", Deal.Outcome.TIMEOUT, 0);
        });

        click(1);

        assertEquals(List.of("nego-W001"), directory.selected, "the line clicked, not the one on top");
    }

    @Test
    @Timeout(10)
    void aStaleLineIsReReadAfterTheClick() throws Exception {
        onEdt(() -> directory.finish("W001", "Genoa Star", Deal.Outcome.DEAL, 1700.0));
        onEdt(() -> {
            directory.knowsThem = false; // a new world replaced the old one between two refreshes
            directory.finished.clear();
        });

        click(0);

        onEdt(() -> assertTrue(panel.showsEmptyForTest(), "the refused click re-read the list"));
    }

    @Test
    @Timeout(10)
    void aLongGameScrollsInsteadOfPushingThePortTableAway() throws Exception {
        onEdt(() -> {
            directory.finish("W001", "Genoa Star", Deal.Outcome.DEAL, 1700.0);
            int oneLine = panel.getPreferredSize().height;
            for (int i = 2; i <= 12; i++) {
                directory.finish(String.format("W%03d", i), "Vessel " + i, Deal.Outcome.TIMEOUT, 0);
            }
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class,
                    named(FinishedListPanel.ROW_NAME).get(0));
            int rowHeight = named(FinishedListPanel.ROW_NAME).get(0).getPreferredSize().height;
            assertTrue(scroll.getPreferredSize().height <= rowHeight * FinishedListPanel.MAX_VISIBLE_ROWS + 2,
                    "never more than " + FinishedListPanel.MAX_VISIBLE_ROWS + " lines tall: "
                            + scroll.getPreferredSize().height);
            assertTrue(panel.getPreferredSize().height > oneLine, "but it does grow up to that");
            assertEquals(12, panel.linesForTest().size(), "every line is still there, to scroll to");
        });
    }

    @Test
    @Timeout(10)
    void nothingInTheListTakesTheKeyboardFocus() throws Exception {
        onEdt(() -> {
            directory.finish("W001", "Genoa Star", Deal.Outcome.DEAL, 1700.0);
            List<Component> all = new ArrayList<>();
            collect(panel, all);
            all.add(panel);
            for (Component component : all) {
                // A click must not move the focus: either it cannot be focused at all, or (the
                // scroll bar's own arrow buttons, built by the look and feel) a click never asks.
                boolean clickTakesFocus = component.isFocusable()
                        && (!(component instanceof JComponent swing) || swing.isRequestFocusEnabled());
                assertFalse(clickTakesFocus, "a click would take the focus: " + component);
            }
        });
    }
}
