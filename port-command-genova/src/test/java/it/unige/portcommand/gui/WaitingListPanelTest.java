package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.model.OpenDialogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the "Waiting for you" list against a fake {@link DialogueDirectory} — the
 * list has no clock and no bus of its own, so the fake IS its whole world. Ordering and texts are
 * {@code WaitingListModelTest}'s; pinned here is what reaches the screen and what a click does.
 *
 * <p>Headless: the panel is never put in a window. Everything that touches it runs on the EDT
 * through {@code invokeAndWait} (the directory's contract is EDT-only); clicks are real
 * {@link MouseEvent}s dispatched to the line, so the panel's own mouse handling is what is
 * tested. A peerless panel is not laid out by {@code validate()}, so the click tests size it and
 * call {@code doLayout()} down the tree first — a line needs real bounds to be clicked "inside".
 */
class WaitingListPanelTest {

    /** The chat column as the list sees it; every field is touched on the EDT only. */
    private static final class FakeDirectory implements DialogueDirectory {

        private final List<OpenDialogue> open = new ArrayList<>();
        private final Map<String, Long> shown = new HashMap<>();
        private final List<Runnable> dialoguesListeners = new ArrayList<>();
        private final List<String> selected = new ArrayList<>();
        private int selectedVesselListeners;

        void put(String vesselId, String vesselName, Long shownRemainingMillis) {
            String dialogueId = "nego-" + vesselId;
            if (open.stream().noneMatch(dialogue -> dialogue.dialogueId().equals(dialogueId))) {
                open.add(new OpenDialogue(dialogueId, vesselId, vesselName));
            }
            if (shownRemainingMillis == null) {
                shown.remove(dialogueId);
            } else {
                shown.put(dialogueId, shownRemainingMillis);
            }
        }

        void close(String vesselId) {
            open.removeIf(dialogue -> dialogue.vesselId().equals(vesselId));
            shown.remove("nego-" + vesselId);
        }

        /** What {@code ChatPanel} does after a tab opened or closed, and on every one-second tick. */
        void fire() {
            for (Runnable listener : List.copyOf(dialoguesListeners)) {
                listener.run();
            }
        }

        @Override
        public List<OpenDialogue> openDialogues() {
            return List.copyOf(open);
        }

        @Override
        public boolean selectDialogue(String dialogueId) {
            selected.add(dialogueId);
            return open.stream().anyMatch(dialogue -> dialogue.dialogueId().equals(dialogueId));
        }

        @Override
        public OptionalLong shownRemainingMillis(String dialogueId) {
            Long remaining = shown.get(dialogueId);
            return remaining == null ? OptionalLong.empty() : OptionalLong.of(remaining);
        }

        @Override
        public void addDialoguesListener(Runnable listener) {
            dialoguesListeners.add(listener);
        }

        @Override
        public void addSelectedVesselListener(Consumer<String> listener) {
            selectedVesselListeners++;
        }
    }

    private final FakeDirectory directory = new FakeDirectory();
    private WaitingListPanel panel;

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    @BeforeEach
    void buildThePanel() throws Exception {
        onEdt(() -> panel = new WaitingListPanel(directory));
    }

    // --- reading the panel -------------------------------------------------------------------------

    private static void collect(Container container, List<Component> into) {
        for (Component child : container.getComponents()) {
            into.add(child);
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }

    private List<Component> everything() {
        List<Component> all = new ArrayList<>();
        collect(panel, all);
        return all;
    }

    private List<JComponent> named(String name) {
        List<JComponent> found = new ArrayList<>();
        for (Component component : everything()) {
            if (name.equals(component.getName()) && component instanceof JComponent swing) {
                found.add(swing);
            }
        }
        return found;
    }

    /** The lines on screen, top to bottom. */
    private List<JComponent> lines() {
        return named(WaitingListPanel.ROW_NAME);
    }

    private static JLabel labelIn(JComponent line, String name) {
        for (Component child : line.getComponents()) {
            if (name.equals(child.getName())) {
                return (JLabel) child;
            }
        }
        throw new AssertionError("no " + name + " in the line");
    }

    private List<String> vessels() {
        List<String> names = new ArrayList<>();
        for (JComponent line : lines()) {
            names.add(labelIn(line, WaitingListPanel.ROW_VESSEL_NAME).getText());
        }
        return names;
    }

    private List<String> times() {
        List<String> times = new ArrayList<>();
        for (JComponent line : lines()) {
            times.add(labelIn(line, WaitingListPanel.ROW_TIME_NAME).getText());
        }
        return times;
    }

    private JLabel emptyLabel() {
        return (JLabel) named(WaitingListPanel.EMPTY_NAME).get(0);
    }

    private boolean emptyStateShowing() {
        JLabel label = emptyLabel();
        return label.isVisible() && label.getParent().isVisible();
    }

    // --- driving the panel -------------------------------------------------------------------------

    /** Sizes the panel like a narrow left column and lays the whole tree under it out. */
    private void layOut() {
        panel.setSize(200, 240);
        layOutTree(panel);
    }

    private static void layOutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layOutTree(nested);
            }
        }
    }

    private static MouseEvent mouse(Component target, int id, int x, int y, int button) {
        int held = 0;
        if (id == MouseEvent.MOUSE_PRESSED) {
            held = button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
        }
        return new MouseEvent(target, id, System.currentTimeMillis(), held, x, y, 1, false, button);
    }

    private static void press(JComponent line, int button) {
        line.dispatchEvent(
                mouse(line, MouseEvent.MOUSE_PRESSED, line.getWidth() / 2, line.getHeight() / 2, button));
    }

    private static void releaseInside(JComponent line, int button) {
        line.dispatchEvent(
                mouse(line, MouseEvent.MOUSE_RELEASED, line.getWidth() / 2, line.getHeight() / 2, button));
    }

    /** A left click on line {@code index}: the layout, then the press and the release a player's click is. */
    private void click(int index) throws Exception {
        onEdt(() -> {
            layOut();
            JComponent line = lines().get(index);
            assertTrue(line.getWidth() > 0 && line.getHeight() > 0, "the line was not laid out");
            press(line, MouseEvent.BUTTON1);
            releaseInside(line, MouseEvent.BUTTON1);
        });
    }

    private void threeWaiting() throws Exception {
        onEdt(() -> {
            directory.put("A", "Genoa Star", 144_000L);    // 2:24
            directory.put("B", "Ligurian Dawn", 24_000L);  // 0:24, orange
            directory.put("C", "Tirreno", 8_500L);         // 0:09, red
            directory.fire();
        });
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void withNobodyWaitingItShowsItsTitleAndTheEmptyState() {
        assertEquals("waiting-list", panel.getName());

        List<String> texts = new ArrayList<>();
        for (Component component : everything()) {
            if (component instanceof JLabel label && label.isVisible()) {
                texts.add(label.getText());
            }
        }
        assertEquals(List.of("Waiting for you", "No one is waiting."), texts,
                "a title and the empty state — no header row");
        assertTrue(emptyStateShowing());
        assertTrue(lines().isEmpty());
    }

    @Test
    @Timeout(5)
    void itRegistersOneDialoguesListenerAndNothingElse() {
        assertEquals(1, directory.dialoguesListeners.size(), "the directory's listener is its only clock");
        assertEquals(0, directory.selectedVesselListeners, "which vessel is selected is the comm log's business");
        assertTrue(directory.selected.isEmpty(), "building the list selects nothing");
    }

    @Test
    @Timeout(5)
    void eachLineIsTheNameAndTheBareTimeSoonestFirstInTheTabsColours() throws Exception {
        threeWaiting();

        assertEquals(List.of("Tirreno", "Ligurian Dawn", "Genoa Star"), vessels(), "least time left on top");
        assertEquals(List.of("0:09", "0:24", "2:24"), times());
        assertFalse(emptyStateShowing());

        List<JComponent> lines = lines();
        assertEquals(CounterColours.CRITICAL, labelIn(lines.get(0), WaitingListPanel.ROW_TIME_NAME).getForeground());
        assertEquals(CounterColours.WARNING, labelIn(lines.get(1), WaitingListPanel.ROW_TIME_NAME).getForeground());
        assertEquals(CounterColours.NORMAL, labelIn(lines.get(2), WaitingListPanel.ROW_TIME_NAME).getForeground());
        assertEquals(DialogueTabView.COUNTER_CRITICAL,
                labelIn(lines.get(0), WaitingListPanel.ROW_TIME_NAME).getForeground(), "the very colour the tab uses");
        for (JComponent line : lines) {
            assertTrue(labelIn(line, WaitingListPanel.ROW_TIME_NAME).getFont().isBold(), "the time is bold");
            assertEquals(Font.PLAIN, labelIn(line, WaitingListPanel.ROW_VESSEL_NAME).getFont().getStyle(),
                    "the name is not");
        }
    }

    @Test
    @Timeout(5)
    void theListMovesOnlyWhenTheDirectoryTicks() throws Exception {
        threeWaiting();

        // One second later every tab shows one second less; the list has not been told yet.
        onEdt(() -> {
            directory.put("A", "Genoa Star", 143_000L);
            directory.put("B", "Ligurian Dawn", 23_000L);
            directory.put("C", "Tirreno", 7_500L);
        });
        assertEquals(List.of("0:09", "0:24", "2:24"), times(), "no timer of its own: nothing moved");

        onEdt(directory::fire);
        assertEquals(List.of("0:08", "0:23", "2:23"), times());

        // Ligurian Dawn drops under 10 s: its line turns red on the same tick the number does.
        onEdt(() -> {
            directory.put("B", "Ligurian Dawn", 9_000L);
            directory.put("C", "Tirreno", 20_000L);
            directory.fire();
        });
        assertEquals(List.of("Ligurian Dawn", "Tirreno", "Genoa Star"), vessels(), "and the order follows");
        assertEquals(List.of("0:09", "0:20", "2:23"), times());
        assertEquals(CounterColours.CRITICAL,
                labelIn(lines().get(0), WaitingListPanel.ROW_TIME_NAME).getForeground());
        assertEquals(CounterColours.WARNING,
                labelIn(lines().get(1), WaitingListPanel.ROW_TIME_NAME).getForeground());
    }

    @Test
    @Timeout(5)
    void aDialogueWhoseTabShowsNoTimeIsListedLastWithABlankTime() throws Exception {
        onEdt(() -> {
            directory.put("X", "No Clock", null);
            directory.put("A", "Genoa Star", 144_000L);
            directory.fire();
        });

        assertEquals(List.of("Genoa Star", "No Clock"), vessels());
        assertEquals(List.of("2:24", ""), times(), "blank, never 0:00");
    }

    @Test
    @Timeout(5)
    void aClickOnALineSelectsThatDialogue() throws Exception {
        threeWaiting();

        click(1);
        assertEquals(List.of("nego-B"), directory.selected, "the second line is Ligurian Dawn");

        click(0);
        click(2);
        assertEquals(List.of("nego-B", "nego-C", "nego-A"), directory.selected);
    }

    @Test
    @Timeout(5)
    void aPressDraggedOffTheLineSelectsNothing() throws Exception {
        threeWaiting();

        onEdt(() -> {
            layOut();
            JComponent line = lines().get(0);
            press(line, MouseEvent.BUTTON1);
            // AWT delivers the release to the component the press went to, with coordinates
            // relative to it — here well below the line.
            line.dispatchEvent(mouse(line, MouseEvent.MOUSE_RELEASED, line.getWidth() / 2, line.getHeight() + 40,
                    MouseEvent.BUTTON1));
            // ...and the abandoned press must not turn the next plain release into a click.
            releaseInside(line, MouseEvent.BUTTON1);
        });

        assertTrue(directory.selected.isEmpty(), "got " + directory.selected);
    }

    @Test
    @Timeout(5)
    void onlyTheLeftButtonOpensALine() throws Exception {
        threeWaiting();

        onEdt(() -> {
            layOut();
            JComponent line = lines().get(0);
            press(line, MouseEvent.BUTTON3);
            releaseInside(line, MouseEvent.BUTTON3);
        });

        assertTrue(directory.selected.isEmpty(), "got " + directory.selected);
    }

    @Test
    @Timeout(5)
    void theLinePressedIsTheOneOpenedEvenIfTheListReordersBeforeTheRelease() throws Exception {
        threeWaiting();

        onEdt(() -> {
            layOut();
            JComponent top = lines().get(0); // Tirreno, 0:09
            press(top, MouseEvent.BUTTON1);
            // The one-second tick lands between press and release and Genoa Star's time
            // collapses: the top line now shows another vessel.
            directory.put("A", "Genoa Star", 1_000L);
            directory.fire();
            assertEquals("Genoa Star", labelIn(top, WaitingListPanel.ROW_VESSEL_NAME).getText(),
                    "precondition: the same line view, re-used, now shows Genoa Star");
            releaseInside(top, MouseEvent.BUTTON1);
        });

        assertEquals(List.of("nego-C"), directory.selected, "the player aimed at Tirreno");
    }

    @Test
    @Timeout(5)
    void aRefreshNeverReplacesALineThatIsStillNeeded() throws Exception {
        // A line swapped for a new component between a press and its release would swallow the
        // click (the release would have nowhere to go) — and the list refreshes every second.
        threeWaiting();
        List<JComponent> before = lines();

        onEdt(() -> {
            directory.put("A", "Genoa Star", 143_000L);
            directory.put("D", "Levante", 60_000L);
            directory.fire();
        });

        List<JComponent> after = lines();
        assertEquals(4, after.size());
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), after.get(i), "line " + i + " was rebuilt");
        }
        assertEquals(List.of("Tirreno", "Ligurian Dawn", "Levante", "Genoa Star"), vessels());
    }

    @Test
    @Timeout(5)
    void aClosedDialogueLeavesTheListAndTheEmptyStateComesBack() throws Exception {
        threeWaiting();

        onEdt(() -> {
            directory.close("C");
            directory.fire();
        });
        assertEquals(List.of("Ligurian Dawn", "Genoa Star"), vessels());
        assertFalse(emptyStateShowing());

        onEdt(() -> {
            directory.close("A");
            directory.close("B");
            directory.fire();
        });
        assertTrue(lines().isEmpty());
        assertTrue(emptyStateShowing());
        assertEquals("No one is waiting.", emptyLabel().getText());

        // ...and a new arrival replaces the empty state again.
        onEdt(() -> {
            directory.put("E", "Riviera", 200_000L);
            directory.fire();
        });
        assertEquals(List.of("Riviera"), vessels());
        assertEquals(List.of("3:20"), times());
        assertFalse(emptyStateShowing());
    }

    @Test
    @Timeout(5)
    void aLineWhoseTabIsGoneDisappearsWhenItIsClicked() throws Exception {
        threeWaiting();
        // The tab closed, and the tick that would have told the list has not come yet.
        onEdt(() -> directory.close("C"));
        assertEquals(List.of("Tirreno", "Ligurian Dawn", "Genoa Star"), vessels(), "precondition: a stale line");

        click(0);

        assertEquals(List.of("nego-C"), directory.selected, "the directory was asked, and said no such tab");
        assertEquals(List.of("Ligurian Dawn", "Genoa Star"), vessels(), "so the list re-read it at once");
    }

    @Test
    @Timeout(5)
    void nothingInTheListCanTakeTheKeyboardFocus() throws Exception {
        threeWaiting();

        assertFalse(panel.isFocusable(), "a click here must never pull the focus out of the chat input");
        assertFalse(panel.isRequestFocusEnabled());
        List<Component> all = everything();
        assertTrue(all.size() >= 9, "three lines of a panel and two labels each, at least: " + all.size());
        for (Component component : all) {
            assertFalse(component.isFocusable(), component.getName() + " is focusable");
            if (component instanceof JComponent swing) {
                assertFalse(swing.isRequestFocusEnabled(), component.getName() + " would ask for the focus on a click");
            }
        }
    }

    @Test
    @Timeout(5)
    void theTimeIsABareNumberNeverLabelledOrExplained() throws Exception {
        threeWaiting();

        for (String time : times()) {
            assertTrue(time.matches("\\d+:\\d\\d"), "a bare m:ss, got: " + time);
        }
        assertNull(panel.getToolTipText());
        for (Component component : everything()) {
            if (component instanceof JComponent swing) {
                assertNull(swing.getToolTipText(), "no tooltip anywhere in the list: " + component.getName());
            }
            if (component instanceof JLabel label) {
                String text = label.getText().toLowerCase(Locale.ROOT);
                for (String word : List.of("patience", "remaining", "time", "left", "seconds", "countdown")) {
                    assertFalse(text.contains(word), "\"" + label.getText() + "\" explains the counter");
                }
            }
        }
        // Three lines of two labels, the title and the (hidden) empty state: no header row.
        long labels = everything().stream().filter(JLabel.class::isInstance).count();
        assertEquals(3 * 2 + 2, labels);
    }
}
