package it.unige.portcommand.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.coordination.PoissonSpawnBehaviour;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.CallNextShipEvent;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.MovementSafetyEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.ScoreChangedEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17b (ADR-22) on real sessions: a scenario is scored on its own day and ends at its first
 * midnight; a scripted walk-in waits for a free slot (T1); "Call next ship" brings a walk-in in
 * within the two limits (PT8).
 */
@Tag("integration")
class ScenarioScoreIT {

    private static final int TEST_PORT = 18099;
    private static final double EPS = 1e-6;
    private static final String TUTORIAL = "Tutorial — Calm Morning";
    /** Every packaged scenario opens on day 1 at 08:00. */
    private static final long SCENARIO_START_SIM_MILLIS = 8 * 3_600_000L;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @BeforeEach
    void markDayLengthExplicit() {
        // The IT's own day length must beat the scenario's pacing override (--day-length contract).
        System.setProperty("simclock.day.length.explicit", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("simclock.day.length.explicit");
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
        Settings.applyLive(Settings.classpathDefaults());
    }

    private GameSession newSession(long daySeconds) {
        boot = new JadeBootstrap();
        return new GameSession(boot, new BootstrapConfig(TEST_PORT, false, "realtime", daySeconds),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
    }

    /**
     * Decisions E3, E4, E8, E10 end to end. The tutorial opens at 08:00 with a target of its own;
     * its day is sixteen hours; at its first midnight the settlement says "this ends the
     * scenario" exactly once, and the session goes on as free play - also after a load.
     */
    @Test
    void aScenarioIsScoredOnItsOwnDayEndsAtItsFirstMidnightAndGoesOnAsFreePlay() throws Exception {
        GameSession session = newSession(8); // 16 game hours in about 5 real seconds
        session.startScenario("tutorial");
        HarbourMasterAgent hm = harbourMaster(session);

        // The stand-in player takes Santa Lucia's first offer, so the day has one walk-in deal.
        NegotiationOpenedEvent hail = awaitEvent(NegotiationOpenedEvent.class,
                e -> "WALKIN-S1".equals(e.snapshot().vesselId()), 20_000);
        assertNotNull(hail, "fixture: the tutorial's walk-in must hail");
        boot.getEventBus().publish(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, "WALKIN-S1", Map.of()));

        ScoreChangedEvent live = awaitEvent(ScoreChangedEvent.class,
                e -> e.score().gameDay() == 1 && e.score().walkInDeals() == 1, 20_000);
        assertNotNull(live, "the top bar's feed: the day's score moves with the deal");
        assertEquals(TUTORIAL, live.scenarioName(), "the top bar shows the scenario, not 'Day 1 of 30'");
        assertEquals(7_000.0, live.score().targetEur(), EPS, "the scenario's own target, not the global one");

        EndOfDayCompletedEvent first = awaitEvent(EndOfDayCompletedEvent.class,
                e -> e.summary().gameDay() == 1, 30_000);
        assertNotNull(first, "the scenario's first midnight must settle");
        EndOfDaySummary dayOne = first.summary();
        assertTrue(dayOne.endsScenario(), "the first midnight ends the scenario");
        assertEquals(TUTORIAL, dayOne.scenarioName());
        assertNotNull(dayOne.score(), "one window: the report carries the score");
        assertEquals(7_000.0, dayOne.score().targetEur(), EPS);
        assertEquals(1, dayOne.score().walkInDeals());
        assertEquals(1, dayOne.score().walkInsResolved());
        assertEquals(0, dayOne.score().unsafeMoves(), "the movement gate let nothing unsafe through");
        assertEquals(dayOne.net(), dayOne.score().netProfit(), EPS, "one net: the report's and the score's");
        assertEquals(5_200.0 * 16 / 24, fixedCharged(hm, 1), EPS, "a sixteen-hour day pays 16/24");
        assertTrue(strip().contains("−€3,467 daily costs"), "the one money line at midnight: " + strip());
        assertTrue(strip().stream().anyMatch(t -> t.startsWith("+€") && t.endsWith("deal with Santa Lucia")),
                "the deal's own line: " + strip());

        // Reloading a LATER save does not end the scenario a second time: the autosave the
        // settlement itself asked for is from day 2, 00:00.
        Path autosave = new SaveLoadManager(tempDir).autosavePath();
        assertTrue(waitUntil(() -> Files.exists(autosave), 15_000), "the end-of-day autosave");
        session.loadGame(autosave);
        HarbourMasterAgent reloaded = harbourMaster(session);

        ScoreChangedEvent afterLoad = awaitAfterLoad(ScoreChangedEvent.class, e -> true, 15_000);
        assertNotNull(afterLoad, "a loaded game announces its score");
        assertEquals(2, afterLoad.score().gameDay());
        assertNull(afterLoad.scenarioName(), "day 2 is free play: the top bar shows the day again");
        assertEquals(Settings.load().dailyTargetEur(), afterLoad.score().targetEur(), EPS);

        EndOfDayCompletedEvent second = awaitAfterLoad(EndOfDayCompletedEvent.class, e -> true, 30_000);
        assertNotNull(second, "the reloaded game reaches its next midnight");
        assertEquals(2, second.summary().gameDay());
        assertFalse(second.summary().endsScenario(), "an ordinary end of day - no second score card");
        assertEquals(5_200.0, fixedCharged(reloaded, 2), EPS, "day 2 is a whole day");
    }

    /**
     * Decision T1 - born red. Busy Day scripts two walk-ins at t=300 and a third at t=900. Scripted
     * spawns went round the "at most two negotiating" limit, so a player still talking to the
     * first two had three conversations at once. The third now waits for a free slot; the script
     * file is unchanged and the script is not "complete" until it has come.
     */
    @Test
    void aScriptedWalkInWaitsUntilOneOfTheTwoTalksHasEnded() throws Exception {
        GameSession session = newSession(60); // t=900 game seconds is about 0.6 real seconds in
        session.startScenario("busy_day");
        HarbourMasterAgent hm = harbourMaster(session);
        SimClock clock = boot.getSimClock();

        assertNotNull(awaitHail("WALKIN-B1", 20_000), "fixture: the first scripted walk-in hails");
        assertNotNull(awaitHail("WALKIN-B2", 20_000), "fixture: the second scripted walk-in hails");
        // Nobody answers them. Let the script run well past the third walk-in's own time.
        assertTrue(waitUntil(() -> clock.nowSimMillis() >= SCENARIO_START_SIM_MILLIS + 1_800_000L, 20_000),
                "fixture: the clock passes t=1800 s");

        assertEquals(2, negotiating(hm), "fixture: both talks are still open");
        assertNull(awaitHail("WALKIN-B3", 1_500), "the third walk-in must wait while two are talking");
        assertTrue(hm.scriptedWaveActive(), "the script is not over while one of its ships is still to come");

        // "Call next ship" is off while a scenario's own ships are still arriving (PT8).
        boot.getEventBus().publish(new CallNextShipEvent());
        assertNotNull(awaitEvent(NotificationEvent.class,
                        e -> e.text().contains("still arriving"), 10_000),
                "the press is answered in the strip, not ignored: " + strip());
        assertEquals(2, hails().size(), "and no ship was called in: " + hails());

        // The player says no to the first one: a slot is free.
        boot.getEventBus().publish(new PlayerCommandEvent(PlayerCommandKind.REJECT, "WALKIN-B1", Map.of()));

        assertNotNull(awaitHail("WALKIN-B3", 20_000), "with a slot free the third walk-in hails");
        assertTrue(negotiating(hm) <= PoissonSpawnBehaviour.MAX_NEGOTIATING_WALKINS,
                "never more than two at once: " + negotiating(hm));
        assertTrue(waitUntil(() -> !hm.scriptedWaveActive(), 15_000), "and now the script is complete");
    }

    /**
     * Playtest 2, PT8 - born red: nothing listened to the button. In free play a press brings the
     * next walk-in at once, and the two limits hold: two negotiating, four in port.
     */
    @Test
    void callNextShipBringsAWalkInAtOnceWithinTheTwoLimits() throws Exception {
        GameSession session = newSession(3_600); // real pace: nothing arrives or leaves by itself here
        session.startSandbox(42L);
        HarbourMasterAgent hm = harbourMaster(session);

        // Three presses in one breath. A ship that was called in is not tracked until its opening
        // offer arrives, some hundred real milliseconds later - so without the "about to hail"
        // count all three presses would see an empty port (adversarial review: this is what
        // HarbourMasterAgent.walkInsEnRoute is for, and nothing exercised it).
        boot.getEventBus().publish(new CallNextShipEvent());
        boot.getEventBus().publish(new CallNextShipEvent());
        boot.getEventBus().publish(new CallNextShipEvent());
        assertTrue(waitUntil(() -> hails().size() >= 2, 15_000), "two presses bring two walk-ins");
        assertNotNull(awaitEvent(NotificationEvent.class, e -> e.text().contains("waiting for your answer"), 10_000),
                "the third is refused, and says why: " + strip());
        assertFalse(waitUntil(() -> hails().size() >= 3, 1_500), "and no third ship comes: " + hails());
        assertEquals(2, negotiating(hm), "two talks");
        assertEquals(2, walkInsInPort(hm), "two ships");

        // The player takes both offers; the ships stay in port, but nobody is waiting any more.
        acceptEveryOpenTalk(hm);
        boot.getEventBus().publish(new CallNextShipEvent());
        assertTrue(waitUntil(() -> walkInsInPort(hm) >= 3, 15_000), "a third walk-in may come now");
        acceptEveryOpenTalk(hm);
        boot.getEventBus().publish(new CallNextShipEvent());
        assertTrue(waitUntil(() -> walkInsInPort(hm) >= 4, 15_000), "and a fourth - one per berth");
        acceptEveryOpenTalk(hm);

        // Four walk-ins in port: the in-port limit refuses the fifth.
        boot.getEventBus().publish(new CallNextShipEvent());
        // S18b (PT21): the sentence now names the berth queue - "Four walk-ins in port (…) - wait".
        assertNotNull(awaitEvent(NotificationEvent.class, e -> e.text().startsWith("Four walk-ins in port"), 10_000),
                "the in-port limit is said in the strip: " + strip());
        assertEquals(4, walkInsInPort(hm), "four walk-ins, not five");
        assertEquals(0, countSafety(MovementSafetyEvent.Kind.UNSAFE_MOVE), "nothing unsafe was ordered");
    }

    /**
     * A real save and a real load in the middle of the scenario's day (adversarial review: the
     * only JSON round trip of the score was the midnight autosave, whose counters are all zero).
     * The day's counters come back, the session is still the scenario - its name, its target, its
     * sixteen hours - and it still ends at its first midnight, with the deal made before the save
     * in its final score.
     */
    @Test
    void aSaveInTheMiddleOfTheScenariosDayLoadsAsTheScenarioWithItsScore() throws Exception {
        GameSession session = newSession(30); // 16 game hours in 20 real seconds
        session.startScenario("tutorial");
        assertNotNull(awaitHail("WALKIN-S1", 20_000), "fixture: the tutorial's walk-in hails");
        boot.getEventBus().publish(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, "WALKIN-S1", Map.of()));
        assertNotNull(awaitEvent(ScoreChangedEvent.class, e -> e.score().walkInDeals() == 1
                && e.score().reputationChange() == 1.0, 20_000), "fixture: the deal is in the day's score");

        Path saved = saveWhenAllowed(session, 20_000);
        assertEquals(1, SimClock.gameDayOf(new SaveLoadManager(tempDir).load(saved).simClock().simMillis()),
                "fixture: the save is from the scenario's own day");
        session.loadGame(saved);
        HarbourMasterAgent reloaded = harbourMaster(session);

        ScoreChangedEvent afterLoad = awaitAfterLoad(ScoreChangedEvent.class, e -> true, 15_000);
        assertNotNull(afterLoad, "a loaded game announces its score");
        assertEquals(1, afterLoad.score().gameDay());
        assertEquals(1, afterLoad.score().walkInDeals(), "the day's deals came back with the save");
        assertEquals(1, afterLoad.score().walkInsResolved());
        assertEquals(1.0, afterLoad.score().reputationChange(), EPS, "and today's reputation change");
        assertEquals(TUTORIAL, afterLoad.scenarioName(), "it is still the scenario");
        assertEquals(7_000.0, afterLoad.score().targetEur(), EPS, "with the scenario's own target");
        assertEquals(16, reloaded.sessionTerms().chargeableHours(1), "and its sixteen-hour day");

        EndOfDayCompletedEvent end = awaitAfterLoad(EndOfDayCompletedEvent.class, e -> true, 40_000);
        assertNotNull(end, "the loaded scenario reaches its first midnight");
        assertTrue(end.summary().endsScenario(), "and ends there - once, load or no load");
        assertEquals(1, end.summary().score().walkInDeals(), "the deal made before the save is in the final score");
        assertEquals(5_200.0 * 16 / 24, fixedCharged(reloaded, 1), EPS);
    }

    /** A manual save, retried while the port is momentarily not in a state that can be saved. */
    private Path saveWhenAllowed(GameSession session, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return session.saveNow();
            } catch (it.unige.portcommand.persistence.SaveNotAllowedException notYet) {
                Thread.sleep(150);
            }
        }
        throw new AssertionError("a manual save never became possible within " + timeoutMs + " ms");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private HarbourMasterAgent harbourMaster(GameSession session) throws InterruptedException {
        assertTrue(waitUntil(() -> session.directory().byName("harbour_master").isPresent(), 15_000),
                "the HarbourMaster registers");
        return (HarbourMasterAgent) session.directory().byName("harbour_master").orElseThrow();
    }

    /** Takes the standing offer of every walk-in that is still negotiating, until none is. */
    private void acceptEveryOpenTalk(HarbourMasterAgent hm) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (negotiating(hm) > 0 && System.currentTimeMillis() < deadline) {
            for (VesselTracking t : hm.activeVessels().values()) {
                if (t.channel() == VesselTracking.Channel.WALK_IN && t.stage() == VesselTracking.Stage.NEGOTIATING) {
                    boot.getEventBus().publish(
                            new PlayerCommandEvent(PlayerCommandKind.ACCEPT, t.vesselId(), Map.of()));
                }
            }
            Thread.sleep(200);
        }
        assertEquals(0, negotiating(hm), "every open talk was closed with a deal");
    }

    private static long negotiating(HarbourMasterAgent hm) {
        return hm.activeVessels().values().stream()
                .filter(t -> t.channel() == VesselTracking.Channel.WALK_IN
                        && t.stage() == VesselTracking.Stage.NEGOTIATING).count();
    }

    private static long walkInsInPort(HarbourMasterAgent hm) {
        return hm.activeVessels().values().stream()
                .filter(t -> t.channel() == VesselTracking.Channel.WALK_IN).count();
    }

    /** The fixed lines the settlement booked for {@code day}, summed. */
    private static double fixedCharged(HarbourMasterAgent hm, int day) {
        return hm.walletLedger().expenseHistory().stream()
                .filter(e -> ExpenseRules.isFixedSource(e.source()))
                .filter(e -> SimClock.gameDayOf(e.simTime()) == day)
                .mapToDouble(ExpenseEvent::amount).sum();
    }

    private List<String> strip() {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(NotificationEvent.class::isInstance).map(NotificationEvent.class::cast)
                .map(NotificationEvent::text).toList();
    }

    /** The vessel ids that have hailed, each once (a counter-offer re-publishes the dialogue). */
    private List<String> hails() {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(NegotiationOpenedEvent.class::isInstance).map(NegotiationOpenedEvent.class::cast)
                .map(e -> e.snapshot().vesselId()).distinct().toList();
    }

    private NegotiationOpenedEvent awaitHail(String vesselId, long timeoutMs) throws InterruptedException {
        return awaitEvent(NegotiationOpenedEvent.class, e -> vesselId.equals(e.snapshot().vesselId()), timeoutMs);
    }

    private long countSafety(MovementSafetyEvent.Kind kind) {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(MovementSafetyEvent.class::isInstance).map(MovementSafetyEvent.class::cast)
                .filter(e -> e.kind() == kind).count();
    }

    private <E extends Event> E awaitEvent(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Event event : EventBusProbe.published(boot.getEventBus())) {
                if (type.isInstance(event) && matcher.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** As {@link #awaitEvent}, but only among what was published after the last load. */
    private <E extends Event> E awaitAfterLoad(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<Event> all = EventBusProbe.published(boot.getEventBus());
            int marker = -1;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i) instanceof GameLoadedEvent) {
                    marker = i;
                    break;
                }
            }
            assertTrue(marker >= 0, "no GameLoadedEvent in the audit log - the load marker was evicted");
            for (Event event : all.subList(marker + 1, all.size())) {
                if (type.isInstance(event) && matcher.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
