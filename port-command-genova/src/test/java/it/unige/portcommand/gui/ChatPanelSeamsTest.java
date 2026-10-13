package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.model.OpenDialogue;
import it.unige.portcommand.gui.model.PatienceCounterModel;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the chat column as the other two columns see it — {@link ChatPanel} as a
 * {@link DialogueDirectory}. The "Waiting for you" list reads the open dialogues and each tab's
 * shown time and selects a tab on a click; the comm log follows the selected tab's vessel. Pinned
 * here: what the directory answers, when its two kinds of listener are told, and that neither is
 * told a state the strip is only passing through (the removal guard, a load).
 *
 * <p>Headless, the {@code ChatPanelTest} idiom: a real {@link EventBus}, publish and flush the
 * EDT, a hand-driven {@link SimClock}. The directory is EDT-only, so every call to it goes through
 * {@link #ask} or {@link #onEdt}; {@code refreshCounters()} stands in for the one-second Swing
 * timer and a published {@link SimClockTickEvent} for the game's minute tick. Nothing sleeps.
 */
class ChatPanelSeamsTest {

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(300);
    private final ChatPanel panel = new ChatPanel(bus,
            (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);

    // --- driving the panel ---------------------------------------------------------------------

    private static NegotiationOpenedEvent openEvent(String vesselId, String name, double vesselOffer, int round) {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot(vesselId, "cargo_vessel", 9.0, 150.0, 30000,
                "general_cargo", "berth_1", 6, vesselOffer, 0.0, round, 0.0, false, name);
        return new NegotiationOpenedEvent("nego-" + vesselId, snapshot);
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** A walk-in hails: its tab {@code nego-<vesselId>} opens. */
    private void open(String vesselId, String name) throws Exception {
        bus.publish(openEvent(vesselId, name, 1500.0, 1));
        flush();
        assertTrue(panel.viewForTest("nego-" + vesselId) != null, "setup: the tab must have opened");
    }

    private void close(String vesselId) throws Exception {
        bus.publish(new NegotiationClosedEvent("nego-" + vesselId, vesselId, Deal.Outcome.DEAL));
        flush();
    }

    /** The game's per-sim-minute tick — what sweeps closed tabs off the strip. */
    private void minuteTick() throws Exception {
        bus.publish(new SimClockTickEvent(clock.nowSimMillis(), clock.gameDay(), clock.simHour(), clock.simMinute()));
        flush();
    }

    /** Runs a closed tab's 30-real-second dwell out and lets the next tick remove it. */
    private void dwellRunsOut() throws Exception {
        clock.advance(30_000);
        minuteTick();
    }

    /** What the one-second Swing timer does, on the EDT. */
    private void timerFires() throws Exception {
        SwingUtilities.invokeAndWait(panel::refreshCounters);
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /** Asks the panel something on the EDT (the directory's contract) and hands the answer back. */
    private static <T> T ask(Callable<T> question) throws Exception {
        AtomicReference<T> answer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                answer.set(question.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() instanceof Error error) {
            throw error;
        }
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        return answer.get();
    }

    private boolean select(String dialogueId) throws Exception {
        return ask(() -> panel.selectDialogue(dialogueId));
    }

    private OptionalLong shown(String dialogueId) throws Exception {
        return ask(() -> panel.shownRemainingMillis(dialogueId));
    }

    /** A listener that writes down every vessel id it is told (null included). */
    private List<String> listenForSelectedVessel() throws Exception {
        List<String> heard = new ArrayList<>();
        onEdt(() -> panel.addSelectedVesselListener(heard::add));
        return heard;
    }

    // --- openDialogues ---------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void openDialoguesListsOnlyTabsStillNegotiating() throws Exception {
        assertEquals(List.of(), ask(panel::openDialogues), "an empty strip: nobody is waiting");
        open("A", "Genoa Star");
        open("B", "Carthago");
        open("C", "Tirreno");
        List<OpenDialogue> all = List.of(new OpenDialogue("nego-A", "A", "Genoa Star"),
                new OpenDialogue("nego-B", "B", "Carthago"), new OpenDialogue("nego-C", "C", "Tirreno"));
        assertEquals(all, ask(panel::openDialogues), "tab order, with each vessel's id and display name");

        // A vessel countering must not move its entry: a list that reshuffled on every round
        // would move under the player's pointer.
        bus.publish(openEvent("A", "Genoa Star", 1650.0, 2));
        flush();
        assertEquals(all, ask(panel::openDialogues));

        close("B");
        assertEquals(List.of(all.get(0), all.get(2)), ask(panel::openDialogues),
                "a finished negotiation is not waiting for anyone");
        assertEquals(3, panel.stripTabCountForTest(), "although its tab stays on the strip for its dwell");

        dwellRunsOut();
        assertEquals(List.of(all.get(0), all.get(2)), ask(panel::openDialogues));
        assertEquals(2, panel.stripTabCountForTest());
    }

    // --- selectDialogue --------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void selectDialogueSelectsTheTabAndRefusesUnknownIds() throws Exception {
        assertFalse(select("nego-A"), "no tab at all");
        assertNull(ask(panel::selectedDialogueId));
        assertNull(ask(panel::selectedVesselId));
        open("A", "Genoa Star");
        open("B", "Carthago");
        open("C", "Tirreno");
        assertEquals("nego-A", ask(panel::selectedDialogueId), "the first tab of a strip selects itself");

        assertTrue(select("nego-C"));
        assertEquals("nego-C", ask(panel::selectedDialogueId));
        assertEquals("C", ask(panel::selectedVesselId));

        // The waiting list re-reads the directory when it is told "no such tab".
        assertFalse(select("nego-ghost"));
        assertFalse(select(null));
        assertFalse(select("C"), "a vessel id is not a dialogue id");
        assertEquals("nego-C", ask(panel::selectedDialogueId), "and a refused selection changes nothing");

        // A closed tab is still on the strip, so it can still be brought forward...
        close("B");
        assertTrue(select("nego-B"));
        assertEquals("nego-B", ask(panel::selectedDialogueId));
        // ...until its dwell has run out and it is gone.
        dwellRunsOut();
        assertFalse(select("nego-B"));
        assertEquals("nego-C", ask(panel::selectedDialogueId), "the tab that took its place is selected");
    }

    // --- the selected-vessel listener ------------------------------------------------------------

    @Test
    @Timeout(5)
    void theSelectedVesselListenerHearsEachChangeAndNullWhenTheLastTabLeaves() throws Exception {
        List<String> heard = listenForSelectedVessel();
        assertTrue(heard.isEmpty(), "registering tells the listener nothing");

        open("A", "Genoa Star");
        assertEquals(List.of("A"), heard, "the first tab selects itself");
        open("B", "Carthago");
        assertEquals(List.of("A"), heard, "a second tab opens behind the first: the selection has not moved");

        assertTrue(select("nego-B"));
        assertEquals(List.of("A", "B"), heard);

        close("B");
        assertEquals(List.of("A", "B"), heard, "closing the selected tab does not deselect it");
        assertEquals("B", ask(panel::selectedVesselId), "a closed tab that is still selected still names its vessel");

        dwellRunsOut(); // B leaves the strip; A takes its place
        assertEquals(List.of("A", "B", "A"), heard);

        close("A");
        dwellRunsOut(); // the last tab leaves
        assertEquals(Arrays.asList("A", "B", "A", null), heard, "null once no tab is left");
        assertNull(ask(panel::selectedVesselId));
    }

    @Test
    @Timeout(5)
    void aPlayersClickOnATabIsHeardToo() throws Exception {
        open("A", "Genoa Star");
        open("B", "Carthago");
        List<String> heard = listenForSelectedVessel();

        // What a click on the second tab's header does to the pane.
        onEdt(() -> ((JTabbedPane) panel.getComponent(0)).setSelectedIndex(1));

        assertEquals(List.of("B"), heard);
        assertEquals("nego-B", ask(panel::selectedDialogueId));
    }

    @Test
    @Timeout(5)
    void reselectingTheSelectedTabNotifiesNobody() throws Exception {
        open("A", "Genoa Star");
        open("B", "Carthago");
        List<String> heard = listenForSelectedVessel();
        int[] dialoguesFired = new int[1];
        onEdt(() -> panel.addDialoguesListener(() -> dialoguesFired[0]++));

        assertTrue(select("nego-A"), "already selected — still a success (the list wants the focus moved)");
        assertTrue(heard.isEmpty(), "but nothing changed, so nobody is told: " + heard);

        assertTrue(select("nego-B"));
        assertTrue(select("nego-B"));
        assertTrue(select("nego-B"));
        assertEquals(List.of("B"), heard, "told once, on the change");
        assertEquals(0, dialoguesFired[0], "and selecting a tab changes neither the open dialogues nor a counter");
    }

    @Test
    @Timeout(5)
    void aTabSlidingAlongTheStripIsNotAChange() throws Exception {
        // An earlier tab is removed: the selected tab's INDEX changes (the pane fires its change
        // event), its vessel does not.
        open("A", "Genoa Star");
        open("B", "Carthago");
        assertTrue(select("nego-B"));
        List<String> heard = listenForSelectedVessel();

        close("A");
        dwellRunsOut();

        assertEquals(1, panel.stripTabCountForTest(), "setup: the earlier tab is gone");
        assertEquals("nego-B", ask(panel::selectedDialogueId));
        assertTrue(heard.isEmpty(), "same vessel, nothing to tell: " + heard);
    }

    @Test
    @Timeout(5)
    void aLoadClearsTheSelectionAndTheNextWorldIsAnnouncedAfresh() throws Exception {
        open("A", "Genoa Star");
        open("B", "Carthago");
        List<String> heard = listenForSelectedVessel();

        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        flush();
        assertEquals(Arrays.asList((String) null), heard, "the old world's tabs are gone: nobody is selected");
        assertEquals(List.of(), ask(panel::openDialogues));
        assertNull(ask(panel::selectedDialogueId));
        assertFalse(select("nego-A"));

        // The new world's deterministic ids hand out the same vessel id again: it is a change
        // (from nobody), not a repeat of what was announced before the load.
        open("A", "Genoa Star");
        assertEquals(Arrays.asList(null, "A"), heard);
    }

    // --- the dialogues listener ------------------------------------------------------------------

    @Test
    @Timeout(5)
    void theDialoguesListenerFiresOnOpenCounterCloseRemovalLoadAndTick() throws Exception {
        int[] fired = new int[1];
        boolean[] offTheEdt = new boolean[1];
        onEdt(() -> panel.addDialoguesListener(() -> {
            fired[0]++;
            offTheEdt[0] |= !SwingUtilities.isEventDispatchThread();
        }));
        assertEquals(0, fired[0], "registering runs nothing");

        open("A", "Genoa Star");
        assertEquals(1, fired[0], "a tab opened");

        bus.publish(openEvent("A", "Genoa Star", 1650.0, 2));
        flush();
        assertEquals(2, fired[0], "the vessel countered: its tab re-read its counter");

        timerFires();
        assertEquals(3, fired[0], "the one-second counter refresh");
        timerFires();
        assertEquals(4, fired[0], "every one of them — the list has no clock of its own");

        close("A");
        assertEquals(5, fired[0], "a negotiation closed");

        minuteTick();
        assertEquals(5, fired[0], "a tick that removes nothing changes nothing");
        dwellRunsOut();
        assertEquals(6, fired[0], "the closed tab left the strip");

        open("B", "Carthago");
        assertEquals(7, fired[0]);
        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        flush();
        assertEquals(8, fired[0], "the world was replaced");

        // Things that change neither the open dialogues nor a counter are not announced. (No
        // counter CAN change here: this test never puts a conversation on the clock. When a
        // re-render does change one, it is announced — see the off-tick case further down.)
        open("C", "Tirreno");
        assertEquals(9, fired[0]);
        bus.publish(new AssistantChatEvent("nego-C", "Assistant: take it"));
        bus.publish(new SettingsChangedEvent("NORMAL", 300L, true, "microsoft/Phi-4-mini-instruct"));
        bus.publish(new NegotiationClosedEvent("nego-ghost", "X", Deal.Outcome.DEAL)); // no such tab
        flush();
        assertEquals(9, fired[0]);

        assertFalse(offTheEdt[0], "the listener always runs on the EDT");
    }

    @Test
    @Timeout(5)
    void aListenerSeesTheNewStateNotTheOldOne() throws Exception {
        // Listeners run at the END of each change: what they read is what the screen shows.
        List<List<OpenDialogue>> seen = new ArrayList<>();
        onEdt(() -> panel.addDialoguesListener(() -> seen.add(panel.openDialogues())));

        open("A", "Genoa Star");
        close("A");
        dwellRunsOut();

        assertEquals(List.of(List.of(new OpenDialogue("nego-A", "A", "Genoa Star")), List.of(), List.of()), seen,
                "opened: one waiting; closed: none; removed: none");
    }

    @Test
    @Timeout(5)
    void aListenerMaySelectADialogueDuringRemoval() throws Exception {
        // While expired tabs are being removed the pane's change listener stands down (it must
        // not move the focus). The dialogues listener is told only AFTER that guard is lowered,
        // so a selection made from inside it behaves like any other.
        open("A", "Genoa Star");
        open("B", "Carthago");
        open("C", "Tirreno");
        List<String> heard = listenForSelectedVessel();
        List<Boolean> selections = new ArrayList<>();
        onEdt(() -> panel.addDialoguesListener(() -> {
            if (selections.isEmpty() && panel.viewForTest("nego-A") == null) {
                selections.add(panel.selectDialogue("nego-C")); // the first call after A has left
            }
        }));

        close("A"); // A is the selected tab
        assertTrue(selections.isEmpty(), "setup: A is closed but still on the strip");
        dwellRunsOut();

        assertEquals(List.of(true), selections, "the selection made during the removal's callback succeeded");
        assertEquals("nego-C", ask(panel::selectedDialogueId));
        assertEquals(List.of("B", "C"), heard,
                "B took the removed tab's place (told once the strip had settled), then the listener chose C");
        assertEquals(2, panel.stripTabCountForTest());
    }

    // --- shownRemainingMillis --------------------------------------------------------------------

    @Test
    @Timeout(5)
    void shownRemainingMillisMatchesTheTabsCounter() throws Exception {
        clock.conversationClock().open("nego-A", 240_000L);
        open("A", "Genoa Star");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertEquals(OptionalLong.of(240_000L), shown("nego-A"));
        assertEquals("4:00", view.counterTextForTest());

        // It is what the counter SHOWS, not what the clock says: 25 s pass unread.
        clock.advance(25_000);
        assertEquals(OptionalLong.of(240_000L), shown("nego-A"), "the tab has not re-read the clock yet");
        assertEquals("4:00", view.counterTextForTest());

        timerFires();
        assertEquals(OptionalLong.of(215_000L), shown("nego-A"));
        assertEquals("3:35", view.counterTextForTest());
        assertEquals(view.counterTextForTest(), PatienceCounterModel.text(shown("nego-A").getAsLong()),
                "formatted the way the tab formats it, the two can never disagree");

        // A blank counter is "empty", never zero — zero would read as a timeout.
        open("B", "Carthago"); // nothing opened a conversation for it on the clock
        assertEquals("", panel.viewForTest("nego-B").counterTextForTest());
        assertEquals(OptionalLong.empty(), shown("nego-B"));

        close("A");
        assertEquals("", view.counterTextForTest());
        assertEquals(OptionalLong.empty(), shown("nego-A"), "a closed negotiation counts nothing down");

        assertEquals(OptionalLong.empty(), shown("nego-ghost"));
        assertEquals(OptionalLong.empty(), shown(null));
    }

    /**
     * Review finding (S15): the timer is not the only thing that re-reads the conversation clock.
     * A tab re-renders itself whenever something is written into it, and that re-read lands
     * between two ticks — the list must follow at once, not a second later (and, across the 0:30
     * line, in the old colour meanwhile).
     */
    @Test
    @Timeout(5)
    void aCounterChangedByTheTabsOwnReRenderIsAnnouncedAtOnce() throws Exception {
        WaitingListPanel list = ask(() -> new WaitingListPanel(panel));
        clock.conversationClock().open("nego-A", 29_400L);
        open("A", "Genoa Star");
        DialogueTabView view = panel.viewForTest("nego-A");
        assertEquals("0:30", view.counterTextForTest());
        assertEquals(List.of("Genoa Star 0:30"), waitingLines(list));
        int[] fired = new int[1];
        onEdt(() -> panel.addDialoguesListener(() -> fired[0]++));

        // 600 ms later — no timer tick in between — the Assistant writes into the tab.
        clock.advance(600);
        bus.publish(new AssistantChatEvent("nego-A", "Assistant: take it"));
        flush();
        assertEquals("0:29", view.counterTextForTest(), "the tab re-rendered and re-read the clock");
        assertEquals(1, fired[0], "and said so");
        assertEquals(OptionalLong.of(28_800L), shown("nego-A"));
        assertEquals(List.of("Genoa Star 0:29"), waitingLines(list), "so the list shows the tab's number");

        // The same again with the clock standing still: the counter did not change, nothing is said.
        bus.publish(new AssistantChatEvent("nego-A", "Assistant: or counter"));
        flush();
        assertEquals("0:29", view.counterTextForTest());
        assertEquals(1, fired[0], "a re-render that leaves the counter alone announces nothing");
    }

    /**
     * Fix-check finding (S15): the list SORTS by the shown times. If only the re-rendered tab
     * re-read the clock, its fresh number would be sorted against the other tabs' older ones and
     * two vessels less than a second apart would swap places until the next tick — under the
     * pointer of a player about to click the top one. So every tab re-reads.
     */
    @Test
    @Timeout(5)
    void anOffTickReRenderRefreshesEveryTabSoTheListSortsLikeWithLike() throws Exception {
        WaitingListPanel list = ask(() -> new WaitingListPanel(panel));
        clock.conversationClock().open("nego-A", 100_000L);
        clock.conversationClock().open("nego-B", 99_700L);
        open("A", "Genoa Star");
        open("B", "Carthago");
        assertEquals(List.of("Carthago 1:40", "Genoa Star 1:40"), waitingLines(list),
                "precondition: B has 300 ms less and is listed first");

        // 400 ms later only A's tab is written into. A fresh A (99_600) against a stale B
        // (99_700, really 99_300) would list A first.
        clock.advance(400);
        bus.publish(new AssistantChatEvent("nego-A", "Assistant: take it"));
        flush();
        assertEquals(OptionalLong.of(99_600L), shown("nego-A"));
        assertEquals(OptionalLong.of(99_300L), shown("nego-B"), "the other tab re-read the clock as well");
        assertEquals(List.of("Carthago 1:40", "Genoa Star 1:40"), waitingLines(list),
                "so the one closest to leaving is still first");
    }

    // --- with its real consumer ------------------------------------------------------------------

    private static String labelText(Container row, String name) {
        for (Component child : row.getComponents()) {
            if (name.equals(child.getName()) && child instanceof JLabel label) {
                return label.getText();
            }
        }
        throw new AssertionError("no " + name + " in the line");
    }

    private static void collectWaitingLines(Container container, List<String> into) {
        for (Component child : container.getComponents()) {
            if (WaitingListPanel.ROW_NAME.equals(child.getName()) && child instanceof Container row) {
                into.add(labelText(row, WaitingListPanel.ROW_VESSEL_NAME) + " "
                        + labelText(row, WaitingListPanel.ROW_TIME_NAME));
            } else if (child instanceof Container nested) {
                collectWaitingLines(nested, into);
            }
        }
    }

    /** The "Waiting for you" lines as the player reads them: {@code name m:ss}, top to bottom. */
    private static List<String> waitingLines(WaitingListPanel list) throws Exception {
        return ask(() -> {
            List<String> lines = new ArrayList<>();
            collectWaitingLines(list, lines);
            return lines;
        });
    }

    @Test
    @Timeout(5)
    void theRealWaitingListFollowsTheChatColumn() throws Exception {
        // The seam's one consumer in the left column, wired to the real chat column: no fake.
        // The list has no clock and no bus subscription; everything it shows arrives through the
        // directory this panel implements.
        WaitingListPanel list = ask(() -> new WaitingListPanel(panel));
        assertEquals(List.of(), waitingLines(list));

        clock.conversationClock().open("nego-A", 240_000L); // a cargo vessel
        clock.conversationClock().open("nego-B", 120_000L); // a ferry
        open("A", "Genoa Star");
        open("B", "Ligurian Dawn");
        assertEquals(List.of("Ligurian Dawn 2:00", "Genoa Star 4:00"), waitingLines(list),
                "listed as soon as the tabs open, the one closest to leaving first");

        clock.advance(95_000);
        assertEquals(List.of("Ligurian Dawn 2:00", "Genoa Star 4:00"), waitingLines(list),
                "nothing moves between ticks");
        timerFires();
        assertEquals(List.of("Ligurian Dawn 0:25", "Genoa Star 2:25"), waitingLines(list));
        assertEquals("0:25", panel.viewForTest("nego-B").counterTextForTest(), "the very number the tab shows");
        assertEquals("2:25", panel.viewForTest("nego-A").counterTextForTest());

        close("B");
        assertEquals(List.of("Genoa Star 2:25"), waitingLines(list), "a closed negotiation leaves the list at once");

        bus.publish(new GameLoadedEvent(1, 0.0, 50));
        flush();
        assertEquals(List.of(), waitingLines(list));
    }
}
