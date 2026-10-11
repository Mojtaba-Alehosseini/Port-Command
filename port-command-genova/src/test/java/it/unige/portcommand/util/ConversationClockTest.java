package it.unige.portcommand.util;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for {@link ConversationClock} (S14, ADR-18). The clock is driven —
 * every millisecond it knows about is handed to {@code advance} — so nothing here sleeps and
 * every figure is exact.
 */
class ConversationClockTest {

    private static final long CAP = 300_000L;

    private static long remaining(ConversationClock clock, String id) {
        return clock.view(id).orElseThrow().remainingMillis();
    }

    @Test
    void anOpenConversationCountsRealTimeDownFromItsBudget() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);

        clock.advance(45_000L);

        ConversationClock.View view = clock.view("nego-1").orElseThrow();
        assertEquals(45_000L, view.elapsedMillis());
        assertEquals(75_000L, view.remainingMillis());
        assertEquals(120_000L, view.totalMillis(), "elapsed + remaining is the visible budget");
        assertFalse(view.expired());
    }

    @Test
    void itExpiresAtZeroAndNeverGoesNegative() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);

        clock.advance(119_999L);
        assertFalse(clock.view("nego-1").orElseThrow().expired(), "one millisecond left is not expired");
        clock.advance(500_000L);

        ConversationClock.View view = clock.view("nego-1").orElseThrow();
        assertTrue(view.expired());
        assertEquals(0L, view.remainingMillis(), "remaining is clamped at zero");
    }

    @Test
    void anUnknownConversationIsAbsentNotZero() {
        // "Zero remaining" would read as a timeout that never happened: a tab whose vessel is not
        // on the clock must show nothing, and the Assistant must see no counter at all.
        ConversationClock clock = new ConversationClock();
        assertEquals(Optional.empty(), clock.view("nego-nobody"));
    }

    @Test
    void conversationsRunIndependently() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);
        clock.advance(20_000L);
        clock.open("nego-2", 240_000L); // opens later: its own zero point

        clock.advance(10_000L);

        assertEquals(90_000L, remaining(clock, "nego-1"));
        assertEquals(230_000L, remaining(clock, "nego-2"));
    }

    // ---------------------------------------------------------------- the reply bonus

    @Test
    void aReplyBuysThirtySecondsMore() {
        ConversationClock clock = new ConversationClock();
        ConversationClock.Conversation conversation = clock.open("nego-1", 120_000L);
        clock.advance(100_000L);

        conversation.extend(30_000L, CAP);

        ConversationClock.View view = clock.view("nego-1").orElseThrow();
        assertEquals(50_000L, view.remainingMillis());
        assertEquals(100_000L, view.elapsedMillis(), "a bonus adds patience, it does not rewind time");
        assertEquals(150_000L, view.totalMillis(), "the visible total grows with the bonus");
    }

    @Test
    void aBonusNeverLeavesMoreThanTheCapOnTheClock() {
        ConversationClock clock = new ConversationClock();
        ConversationClock.Conversation conversation = clock.open("nego-1", 270_000L);

        conversation.extend(30_000L, CAP);
        assertEquals(300_000L, remaining(clock, "nego-1"), "270 + 30 lands exactly on the cap");
        conversation.extend(30_000L, CAP);
        conversation.extend(30_000L, CAP);

        assertEquals(300_000L, remaining(clock, "nego-1"), "further replies cannot push past 300 s");
    }

    @Test
    void theCapNeverShrinksARemainingTimeAlreadyAboveIt() {
        ConversationClock clock = new ConversationClock();
        ConversationClock.Conversation conversation = clock.open("nego-1", 400_000L);

        conversation.extend(30_000L, CAP);

        assertEquals(400_000L, remaining(clock, "nego-1"), "a bonus may add nothing, but it never takes away");
    }

    // ---------------------------------------------------------------- freeze (a pending Hint)

    @Test
    void aFrozenConversationStandsStillUntilReleased() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);
        clock.advance(10_000L);

        Runnable thaw = clock.freeze("nego-1");
        clock.advance(24_000L); // the model is thinking

        assertEquals(110_000L, remaining(clock, "nego-1"), "frozen while the Hint is pending");
        assertEquals(10_000L, clock.view("nego-1").orElseThrow().elapsedMillis());

        thaw.run();
        clock.advance(5_000L);
        assertEquals(105_000L, remaining(clock, "nego-1"), "running again once released");
    }

    @Test
    void freezingOneConversationLeavesTheOthersRunning() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);
        clock.open("nego-2", 120_000L);

        clock.freeze("nego-1");
        clock.advance(30_000L);

        assertEquals(120_000L, remaining(clock, "nego-1"));
        assertEquals(90_000L, remaining(clock, "nego-2"), "only the tab whose Hint is pending is frozen");
    }

    @Test
    void aReleaseRunsOnceHoweverOftenItIsCalled() {
        // Two Hints on one tab: the first release must not cancel the second Hint's hold too.
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);
        Runnable first = clock.freeze("nego-1");
        clock.freeze("nego-1"); // the second, still pending

        first.run();
        first.run();
        first.run();
        clock.advance(30_000L);

        assertEquals(120_000L, remaining(clock, "nego-1"), "still held by the second Hint");
    }

    @Test
    void anAbandonedFreezeThawsWhenItsLeaseRunsOut() {
        // A model call whose future nobody ever completes must not hold a vessel in port forever.
        ConversationClock clock = new ConversationClock(60_000L);
        clock.open("nego-1", 120_000L);
        clock.freeze("nego-1");

        clock.advance(59_999L);
        assertEquals(120_000L, remaining(clock, "nego-1"), "inside the lease: still frozen");
        clock.advance(1L); // the lease ends on this tick, which itself is not charged
        clock.advance(10_000L);

        assertEquals(110_000L, remaining(clock, "nego-1"), "the lease ran out: time runs again");
    }

    @Test
    void aLateReleaseOfAThawedFreezeDoesNotCancelANewerOne() {
        ConversationClock clock = new ConversationClock(60_000L);
        clock.open("nego-1", 120_000L);
        Runnable stale = clock.freeze("nego-1");
        clock.advance(60_000L); // lease out: thawed
        clock.freeze("nego-1"); // a fresh Hint

        stale.run(); // the old call finally returns
        clock.advance(10_000L);

        assertEquals(120_000L, remaining(clock, "nego-1"), "the fresh Hint's hold must survive");
    }

    @Test
    void freezingAnUnknownConversationIsInert() {
        ConversationClock clock = new ConversationClock();
        Runnable thaw = clock.freeze("nego-nobody");
        thaw.run();
        clock.advance(1_000L);
        assertEquals(Optional.empty(), clock.view("nego-nobody"));
    }

    // ---------------------------------------------------------------- lifecycle: close, replace, reset

    @Test
    void aClosedConversationLeavesTheClock() {
        ConversationClock clock = new ConversationClock();
        ConversationClock.Conversation conversation = clock.open("nego-1", 120_000L);

        conversation.close();
        conversation.close(); // idempotent

        assertEquals(Optional.empty(), clock.view("nego-1"));
        assertEquals(Optional.empty(), conversation.view());
    }

    @Test
    void aStaleHandleCannotTouchANewerConversationWithTheSameId() {
        // Ids recur: nego-WALKIN-1 exists again after a load. The old world's vessel may still be
        // winding down when the new one opens.
        ConversationClock clock = new ConversationClock();
        ConversationClock.Conversation old = clock.open("nego-WALKIN-1", 120_000L);
        Runnable oldThaw = clock.freeze("nego-WALKIN-1");
        clock.reset(); // world swap
        clock.open("nego-WALKIN-1", 240_000L);
        clock.freeze("nego-WALKIN-1");

        old.extend(30_000L, CAP);
        old.close();
        oldThaw.run();
        clock.advance(10_000L);

        assertEquals(240_000L, remaining(clock, "nego-WALKIN-1"),
                "the new conversation is untouched: not extended, not closed, still frozen");
        assertEquals(Optional.empty(), old.view(), "the stale handle reads as gone");
    }

    @Test
    void resetSweepsEveryConversation() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);
        clock.open("nego-2", 120_000L);

        clock.reset();

        assertEquals(Optional.empty(), clock.view("nego-1"));
        assertEquals(Optional.empty(), clock.view("nego-2"));
    }

    @Test
    void openingAgainUnderTheSameIdStartsAfresh() {
        ConversationClock clock = new ConversationClock();
        clock.open("nego-1", 120_000L);
        clock.advance(100_000L);

        clock.open("nego-1", 180_000L);

        ConversationClock.View view = clock.view("nego-1").orElseThrow();
        assertEquals(0L, view.elapsedMillis());
        assertEquals(180_000L, view.remainingMillis());
    }

    @Test
    void aNonPositiveBudgetIsRejectedAndNonPositiveTimeIsIgnored() {
        ConversationClock clock = new ConversationClock();
        assertThrows(IllegalArgumentException.class, () -> clock.open("nego-1", 0L));
        assertThrows(NullPointerException.class, () -> clock.open(null, 1_000L));

        clock.open("nego-1", 120_000L);
        clock.advance(0L);
        clock.advance(-5_000L);
        assertEquals(120_000L, remaining(clock, "nego-1"));
    }

    @Test
    void aHolderMayNameALongerLeaseThanTheDefault() {
        // A Hint under a raised llm.timeout_ms: the model may legitimately think for longer than
        // the default lease, and the vessel must stay frozen for all of it.
        ConversationClock clock = new ConversationClock(60_000L);
        clock.open("nego-1", 120_000L);
        Runnable thaw = clock.freeze("nego-1", 600_000L);

        clock.advance(500_000L);
        assertEquals(120_000L, remaining(clock, "nego-1"), "inside the holder's own lease: still frozen");

        thaw.run();
        clock.advance(10_000L);
        assertEquals(110_000L, remaining(clock, "nego-1"));
    }

    @Test
    void aNestedHoldNeverShortensALongerLease() {
        ConversationClock clock = new ConversationClock(60_000L);
        clock.open("nego-1", 120_000L);
        clock.freeze("nego-1", 600_000L);
        clock.advance(100_000L); // 500 s of the long lease left
        clock.freeze("nego-1"); // a second Hint, default 60 s lease

        clock.advance(400_000L);

        assertEquals(120_000L, remaining(clock, "nego-1"),
                "the first hold's lease has 100 s left; the shorter second one must not have cut it");
    }
}
