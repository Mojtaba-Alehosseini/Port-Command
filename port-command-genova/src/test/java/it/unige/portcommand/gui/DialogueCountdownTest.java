package it.unige.portcommand.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.ConversationClock;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk-in tab's live counter (S14, ADR-18), which replaced the "~Ns remaining" text this file
 * used to test. That text counted a nominal 60 s window down on game time and matched nothing a
 * vessel actually did (backlog R-01). The counter is the vessel's REAL remaining patience, read
 * off the dialogue's conversation clock: {@code m:ss}, orange under 30 s, red under 10 s — a
 * number and a colour, nothing else.
 *
 * <p>Headless, the {@code ChatPanelTest} publish-and-flush idiom. The clock is driven by hand and
 * {@code refreshCounters()} stands in for the one-second Swing timer, so nothing sleeps.
 */
class DialogueCountdownTest {

    private static final String DIALOGUE = "nego-V1";
    /** A neutral cargo vessel's opening patience. */
    private static final long BUDGET = 240_000L;

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private final ChatPanel panel = new ChatPanel(bus,
            (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);

    private static NegotiationOpenedEvent openEvent(String dialogueId, String vesselId) {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot(vesselId, "cargo_vessel", 9.0, 150.0,
                30000, "general_cargo", "berth_1", 6, 1500.0, 0.0, 1, 0.0, false, "Genoa Star");
        return new NegotiationOpenedEvent(dialogueId, snapshot);
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** Opens the conversation the way the vessel does (BEFORE its opening offer), then the tab. */
    private DialogueTabView openTab() throws Exception {
        return openTab(clock.conversationClock().open(DIALOGUE, BUDGET));
    }

    private DialogueTabView openTab(ConversationClock.Conversation ignored) throws Exception {
        bus.publish(openEvent(DIALOGUE, "V1"));
        flush();
        return panel.viewForTest(DIALOGUE);
    }

    /** What the one-second Swing timer does, on the EDT. */
    private void timerFires() throws Exception {
        SwingUtilities.invokeAndWait(panel::refreshCounters);
    }

    @Test
    @Timeout(5)
    void theCounterShowsTheVesselsRealRemainingTimeAndTurnsOrangeThenRed() throws Exception {
        DialogueTabView view = openTab();
        assertEquals("4:00", view.counterTextForTest(), "the full budget the moment the tab opens");
        assertEquals(CounterColours.NORMAL, view.counterColourForTest());

        clock.advance(75_000);
        timerFires();
        assertEquals("2:45", view.counterTextForTest());
        assertEquals(CounterColours.NORMAL, view.counterColourForTest());

        clock.advance(136_000); // 29 s left
        timerFires();
        assertEquals("0:29", view.counterTextForTest());
        assertEquals(CounterColours.WARNING, view.counterColourForTest(), "orange under 30 s");

        clock.advance(20_000); // 9 s left
        timerFires();
        assertEquals("0:09", view.counterTextForTest());
        assertEquals(CounterColours.CRITICAL, view.counterColourForTest(), "red under 10 s");

        clock.advance(60_000); // long past the end
        timerFires();
        assertEquals("0:00", view.counterTextForTest(), "clamped at zero");
        assertEquals(CounterColours.CRITICAL, view.counterColourForTest());
    }

    @Test
    @Timeout(5)
    void theCounterIsANumberAndNothingElse() throws Exception {
        // Never labelled "patience", never explained: players discover why vessels differ.
        DialogueTabView view = openTab();
        clock.advance(61_000);
        timerFires();

        assertTrue(view.counterTextForTest().matches("\\d:\\d\\d"), "got: " + view.counterTextForTest());
        String strip = view.offerStripTextForTest();
        assertFalse(strip.contains("remaining"), "the fake countdown is gone from the strip: " + strip);
        assertFalse(strip.toLowerCase(java.util.Locale.ROOT).contains("patience"), strip);
        // S15 (2026-10-12): the strip is the two-row offer line now, and the round is lower-case.
        assertTrue(strip.contains("round 1 of "), "the strip still carries the offers and the round: " + strip);
        // S15: nothing else on the tab explains the number either — not the offer rows, not the
        // input's example, not any button.
        List<String> texts = new ArrayList<>(view.offerRowsForTest());
        texts.add(view.placeholderForTest());
        texts.addAll(view.buttonLabelsForTest());
        for (String text : texts) {
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            for (String word : List.of("patience", "remaining", "time", "wait", "seconds", "countdown")) {
                assertFalse(lower.contains(word), "\"" + text + "\" explains the counter");
            }
        }
        assertEquals(java.util.OptionalLong.of(BUDGET - 61_000L), panel.shownRemainingMillis(DIALOGUE),
                "and the column reports the very number the counter shows (the waiting list repeats it)");
    }

    @Test
    @Timeout(5)
    void theCounterOnlyMovesWhenTheTimerReadsTheClock() throws Exception {
        // The tab holds no time of its own: nothing drifts between refreshes, and a refresh for
        // any other reason (a player bubble, a system line) shows the clock's value, never a
        // stale or re-derived one — the class of bug the old countdown's origin anchor had.
        DialogueTabView view = openTab();
        clock.advance(25_000);
        assertEquals("4:00", view.counterTextForTest(), "not yet re-read");

        SwingUtilities.invokeAndWait(view::refresh);
        assertEquals("3:35", view.counterTextForTest(), "any refresh shows the clock's own value");
        SwingUtilities.invokeAndWait(view::refresh);
        assertEquals("3:35", view.counterTextForTest(), "and refreshing again changes nothing");
    }

    @Test
    @Timeout(5)
    void aPausedGameFreezesTheCounter() throws Exception {
        DialogueTabView view = openTab();
        clock.advance(10_000);
        timerFires();
        assertEquals("3:50", view.counterTextForTest());

        clock.pause();
        clock.advance(200_000); // ignored while paused — the player is thinking
        timerFires();
        assertEquals("3:50", view.counterTextForTest(), "paused game, frozen counter");

        clock.resume();
        clock.advance(5_000);
        timerFires();
        assertEquals("3:45", view.counterTextForTest());
    }

    @Test
    @Timeout(5)
    void theSpeedControlDoesNotMoveTheCounter() throws Exception {
        DialogueTabView view = openTab();
        clock.setSpeedMultiplier(4);

        clock.advance(30_000);
        timerFires();

        assertEquals("3:30", view.counterTextForTest(), "30 real seconds are 30 seconds at x4 too");
    }

    @Test
    @Timeout(5)
    void aReplyBonusAndAHintFreezeShowOnTheCounter() throws Exception {
        ConversationClock.Conversation conversation = clock.conversationClock().open(DIALOGUE, BUDGET);
        DialogueTabView view = openTab(conversation);
        clock.advance(100_000);

        conversation.extend(30_000L, 300_000L); // the player sent a message
        timerFires();
        assertEquals("2:50", view.counterTextForTest(), "140 s left + 30 s");

        Runnable thaw = clock.conversationClock().freeze(DIALOGUE); // a Hint is waiting for the model
        clock.advance(24_000);
        timerFires();
        assertEquals("2:50", view.counterTextForTest(), "frozen while the Hint is pending");

        thaw.run();
        clock.advance(10_000);
        timerFires();
        assertEquals("2:40", view.counterTextForTest());
    }

    @Test
    @Timeout(5)
    void aDialogueTheClockDoesNotKnowShowsNoCounterRatherThanZero() throws Exception {
        // Nothing opened a conversation for this dialogue (a restored world, a test vessel):
        // "0:00" in red would announce a timeout that is not happening.
        bus.publish(openEvent(DIALOGUE, "V1"));
        flush();
        DialogueTabView view = panel.viewForTest(DIALOGUE);

        timerFires();

        assertEquals("", view.counterTextForTest());
    }

    @Test
    @Timeout(5)
    void aClosedTabBlanksItsCounter() throws Exception {
        DialogueTabView view = openTab();
        clock.advance(30_000);
        timerFires();
        assertEquals("3:30", view.counterTextForTest());

        bus.publish(new NegotiationClosedEvent(DIALOGUE, "V1", Deal.Outcome.DEAL));
        flush();
        timerFires();

        assertEquals("", view.counterTextForTest(), "a closed negotiation counts nothing down");
    }

    @Test
    @Timeout(5)
    void everyOpenTabHasItsOwnCounter() throws Exception {
        clock.conversationClock().open("nego-A", 120_000L); // a ferry
        clock.conversationClock().open("nego-B", 240_000L); // a cargo vessel
        bus.publish(openEvent("nego-A", "A"));
        bus.publish(openEvent("nego-B", "B"));
        flush();

        clock.advance(95_000);
        timerFires();

        assertEquals("0:25", panel.viewForTest("nego-A").counterTextForTest());
        assertEquals(CounterColours.WARNING, panel.viewForTest("nego-A").counterColourForTest());
        assertEquals("2:25", panel.viewForTest("nego-B").counterTextForTest());
        assertEquals(CounterColours.NORMAL, panel.viewForTest("nego-B").counterColourForTest());
    }

    /**
     * The Assistant's urgency must reason from the number the player is looking at. The stored
     * snapshot's time figures date from the vessel's last message; a Hint asked 60 s later has to
     * carry the counter as it reads at that moment — elapsed and total, in real seconds.
     */
    @Test
    @Timeout(5)
    void aHintCarriesTheCounterAsItReadsNow() throws Exception {
        List<HintButtonEvent> hints = new ArrayList<>();
        bus.subscribe(HintButtonEvent.class, hints::add, DeliveryMode.CALLER_THREAD);
        ConversationClock.Conversation conversation = clock.conversationClock().open(DIALOGUE, BUDGET);
        DialogueTabView view = openTab(conversation);

        clock.advance(60_000);
        conversation.extend(30_000L, 300_000L);
        SwingUtilities.invokeAndWait(() -> view.clickForTest("Hint"));

        assertEquals(1, hints.size());
        WalkInDialogueSnapshot snapshot = hints.get(0).snapshot();
        assertEquals(60.0, snapshot.timeUsedSec(), 1e-9, "elapsed, as the counter has run");
        assertEquals(270.0, snapshot.timeLimitSec(), 1e-9, "total = elapsed 60 + remaining 210");
        assertEquals(1500.0, snapshot.lastVesselOffer(), 1e-9, "everything else rides along unchanged");
    }

    @Test
    @Timeout(5)
    void aHintWithNoConversationOnTheClockCarriesNoCounter() throws Exception {
        List<HintButtonEvent> hints = new ArrayList<>();
        bus.subscribe(HintButtonEvent.class, hints::add, DeliveryMode.CALLER_THREAD);
        bus.publish(openEvent(DIALOGUE, "V1"));
        flush();

        SwingUtilities.invokeAndWait(() -> panel.viewForTest(DIALOGUE).clickForTest("Hint"));

        assertEquals(WalkInDialogueSnapshot.NO_TIME_LIMIT, hints.get(0).snapshot().timeLimitSec(),
                "no counter on screen, so no time pressure for the Assistant to invent");
    }

    @Test
    @Timeout(5)
    void theRefreshTimerRunsOnlyWhileThePanelIsDisplayable() throws Exception {
        assertFalse(panel.counterTimerRunningForTest(), "a panel nobody is showing reads no clock");

        SwingUtilities.invokeAndWait(panel::addNotify);
        assertTrue(panel.counterTimerRunningForTest());
        assertEquals(1_000, ChatPanel.COUNTER_REFRESH_MILLIS, "refreshed every second");

        SwingUtilities.invokeAndWait(panel::removeNotify);
        assertFalse(panel.counterTimerRunningForTest(), "and stops with the panel");
    }
}
