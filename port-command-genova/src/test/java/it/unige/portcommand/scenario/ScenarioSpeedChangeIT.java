package it.unige.portcommand.scenario;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.ContractedFeeEarnedEvent;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.scenario.events.TutorialStepAdvancedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14 acceptance, the speed control under a live scenario: "change speed mid-run (x1 -> x4 -> x1)
 * during a scenario; it still completes, end-of-day fires exactly once, no duplicate or lost
 * spawns, timers re-time correctly."
 *
 * <p>On the REAL boot path, like {@link ScenarioSessionIT}: {@code startScenario("tutorial")},
 * the production {@code WallClockAdvancer} running. That matters here — the sim tickers and the
 * scripted-event engine are armed in WALL time from the pace in force when they were armed, so
 * only a clock that really runs can show whether they follow a speed change. The speed is applied
 * the way {@code GameSpeedControl} applies it, {@code SimClock.setSpeedMultiplier}.
 *
 * <p><b>What this does NOT cover</b> (adversarial review, S14): the two long sim-deadline waits —
 * a vessel's approach to its ETA and the Poisson gap. Both scripted tutorial vessels have ETA =
 * now, so their arrival wait is over before it starts, and nothing here asserts when a Poisson
 * walk-in arrives. The arrival waker's pause-hold and re-timing are pinned by {@code
 * ArrivalWaitIT}; the wait logic itself by {@code SimDeadlineWaitTest}.
 *
 * <p><b>Pace.</b> 1 440 real seconds per game day, pinned through the explicit {@code --day-length}
 * marker (the tutorial's own pace is 1 800): one sim-minute per real second at x1, a quarter of a
 * second at x4. The 5 s/day the other scenario cases use cannot show a cadence at all — the
 * advancer moves the clock in 100 ms steps, and one {@code SimClockTickEvent} is published per
 * sim-minute only when the clock has moved, so the tick period has to stay well above 100 ms at
 * x4 for a x1/x4 difference to exist. The price is wall time: about 25 s, most of it the
 * tutorial's 1 500 sim-second script.
 *
 * <p>Waits are the committed bounded sleep-poll (design rule 5).
 */
@Tag("integration")
class ScenarioSpeedChangeIT {

    private static final int TEST_PORT = 18099;

    private static final long REAL_SECONDS_PER_GAME_DAY = 1_440L;
    private static final long MILLIS_PER_GAME_DAY = 86_400_000L;
    /** The tutorial boots on day 1 at 08:00; its scripted times count from there. */
    private static final long SCENARIO_START_SIM_MILLIS = 28_800_000L;
    private static final long PHASE_TIMEOUT_MS = 30_000L;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @BeforeEach
    void markDayLengthExplicit() {
        // Same contract ScenarioSessionIT relies on (GameSession.resolveDayLength): without the
        // marker the tutorial's own 1800 s/day would override this IT's pace.
        System.setProperty("simclock.day.length.explicit", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("simclock.day.length.explicit");
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void tutorialCompletesExactlyOnceAcrossSpeedChanges() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", REAL_SECONDS_PER_GAME_DAY),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));

        session.startScenario("tutorial");
        SimClock clock = boot.getSimClock();
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertEquals(REAL_SECONDS_PER_GAME_DAY, clock.realSecondsPerGameDay(),
                "fixture: the explicit pace must beat the tutorial's own 1800 s/day");
        assertEquals(1, clock.speedMultiplier(), "fixture: a scenario starts at x1");

        // --- x1: the script opens (tutorial steps 1 and 2 are due in the first 200 sim-seconds) ---
        double normalBefore = wallMillisPerTick(3, "x1, before the change");
        awaitSimTime(clock, SCENARIO_START_SIM_MILLIS + 250_000L, "the x1 opening stretch");

        // --- x4: the contracted arrival (300 s), step 3 (600 s) and the walk-in (900 s) fall here ---
        clock.setSpeedMultiplier(4);
        long fastFromSim = clock.nowSimMillis();
        awaitNextTick("the tick still armed at the x1 period");
        double fast = wallMillisPerTick(6, "x4");
        awaitSimTime(clock, SCENARIO_START_SIM_MILLIS + 1_000_000L, "the x4 stretch");
        long fastToSim = clock.nowSimMillis();

        // --- back to x1: steps 4 (1100 s) and 5 (1500 s) finish the script ---
        clock.setSpeedMultiplier(1);
        awaitNextTick("the tick still armed at the x4 period");
        long slowFromSim = clock.nowSimMillis();
        double normalAfter = wallMillisPerTick(3, "x1, after the change back");
        long slowToSim = clock.nowSimMillis();
        assertTrue(waitUntil(() -> !hm.scriptedWaveActive(), PHASE_TIMEOUT_MS),
                "the tutorial's script must still run to its end after x1 -> x4 -> x1; sim time "
                        + clock.nowSimMillis() + ", steps seen " + tutorialSteps());

        // "Timers re-time correctly": the sim-minute tick follows the speed, both ways — judged in
        // GAME time. A re-timed ticker reports every sim-minute (gaps of ~60 000 sim-ms) at any
        // speed; one that kept its x1 period would report every FOURTH minute at x4 (240 000), and
        // one that kept its x4 period would report four times a minute back at x1 (15 000). A
        // stall on a loaded machine can only lengthen a gap, and the median absorbs isolated ones,
        // so neither check can go red from load (the wall figures below are a diagnostic only —
        // they were assertions until the adversarial review pointed out a 1.5 s stall would fail
        // them with the ticker re-timed correctly).
        String cadence = "wall ms per sim-minute tick — x1 before: " + normalBefore + ", x4: " + fast
                + ", x1 after: " + normalAfter;
        List<Long> fastGaps = tickGapsBetween(fastFromSim, fastToSim);
        assertTrue(fastGaps.size() >= 4, "fixture: too few ticks inside the x4 stretch to judge: " + fastGaps);
        assertTrue(median(fastGaps) < 180_000L,
                "at x4 the clock must still report every sim-minute, got sim-ms gaps " + fastGaps
                        + "; " + cadence);
        List<Long> slowGaps = tickGapsBetween(slowFromSim, slowToSim);
        assertTrue(slowGaps.size() >= 2, "fixture: too few ticks inside the x1 stretch to judge: " + slowGaps);
        assertTrue(median(slowGaps) >= 45_000L,
                "back at x1 the tick must slow down again — one per sim-minute, not four — got sim-ms gaps "
                        + slowGaps + "; " + cadence);

        // --- "it still completes": every scripted event fired exactly once, in script order ---
        assertTrue(waitUntil(() -> tutorialSteps().size() >= 5, 10_000),
                "all five tutorial steps must have been published, got " + tutorialSteps());
        assertEquals(List.of(1, 2, 3, 4, 5), tutorialSteps(),
                "each tutorial step exactly once and in order — a re-timed script must not skip or repeat");
        assertNotNull(awaitEvent(ContractedFeeEarnedEvent.class, e -> "C-501".equals(e.contractId()), 20_000),
                "the scripted contracted spawn must have arrived and been granted (not lost)");
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class,
                        e -> "nego-WALKIN-S1".equals(e.dialogueId()), 20_000),
                "the scripted walk-in must have arrived and hailed (not lost)");

        // --- the day's end: jump to three sim-minutes short of midnight, cross it at x4 ---
        // (SimClock.advance also lets those real minutes pass for every open conversation, so the
        // unanswered walk-ins time out here — which is simply what waiting that long does.)
        long toMidnight = MILLIS_PER_GAME_DAY - (clock.nowSimMillis() % MILLIS_PER_GAME_DAY);
        clock.advance(clock.simSecondsToWallMs(toMidnight / 1_000L - 180L));
        assertEquals(1, clock.gameDay(), "fixture: the jump must land short of midnight, at " + clock.nowSimMillis());
        assertTrue(published(EndOfDayEvent.class).isEmpty(), "fixture: no end-of-day before midnight");

        clock.setSpeedMultiplier(4);
        EndOfDayEvent endOfDay = awaitEvent(EndOfDayEvent.class, e -> true, PHASE_TIMEOUT_MS);
        assertNotNull(endOfDay, "midnight must still be detected when it is crossed at x4; sim time "
                + clock.nowSimMillis());
        assertEquals(1, endOfDay.gameDay(), "the day that ended is day 1");

        // Keep the end-of-day check ticking through one more speed change: four sim-minutes into
        // day 2 at x4, then three more at x1. A second EndOfDayEvent would be a second settlement.
        awaitSimTime(clock, MILLIS_PER_GAME_DAY + 4 * 60_000L, "the first minutes of day 2 at x4");
        clock.setSpeedMultiplier(1);
        awaitSimTime(clock, MILLIS_PER_GAME_DAY + 7 * 60_000L, "the next minutes of day 2 at x1");
        assertEquals(2, clock.gameDay(), "fixture: the run is in day 2 now");
        assertEquals(1, published(EndOfDayEvent.class).size(),
                "exactly one end-of-day for the one midnight crossed, got " + published(EndOfDayEvent.class));
        assertNotNull(awaitEvent(EndOfDayCompletedEvent.class, e -> true, 10_000), "the day must be settled");
        assertEquals(1, published(EndOfDayCompletedEvent.class).size(), "and settled exactly once");

        // --- "no duplicate or lost spawns", over the whole run ---
        assertEquals(1, published(ContractedFeeEarnedEvent.class).stream()
                        .filter(e -> "C-501".equals(e.contractId())).count(),
                "the contracted vessel was granted, and paid for, exactly once");
        assertEquals(1, published(NegotiationOpenedEvent.class).stream()
                        .filter(e -> "nego-WALKIN-S1".equals(e.dialogueId())).count(),
                "the scripted walk-in opened exactly one dialogue (nobody countered it)");

        // Every vessel announces itself to the HarbourMaster exactly once: a contracted one with
        // its berth REQUEST, a walk-in with its opening PROPOSE (no counters follow — the autopilot
        // is off in the tutorial and nobody answers). A vessel spawned twice would announce twice.
        Map<String, Integer> announcements = new TreeMap<>();
        for (CommLogEvent line : published(CommLogEvent.class)) {
            boolean announcement = line.performative() == ACLMessage.REQUEST
                    || line.performative() == ACLMessage.PROPOSE;
            if (announcement && line.sender() != null && line.sender().startsWith("vessel_")) {
                announcements.merge(line.sender(), 1, Integer::sum);
            }
        }
        assertTrue(announcements.containsKey("vessel_c501") && announcements.containsKey("vessel_walkin_s1"),
                "both scripted vessels must have announced themselves: " + announcements);
        for (Map.Entry<String, Integer> vessel : announcements.entrySet()) {
            assertEquals(1, vessel.getValue().intValue(),
                    vessel.getKey() + " announced itself more than once — a duplicate spawn: " + announcements);
        }
        // Background (Poisson) walk-ins are numbered by the spawner's own counter, so the ones that
        // announced themselves must be WALKIN-1..k with no number missing: a hole is a spawn that
        // was counted and never arrived.
        List<Integer> ordinals = new ArrayList<>();
        Pattern numbered = Pattern.compile("vessel_walkin_(\\d+)");
        for (String sender : announcements.keySet()) {
            Matcher matcher = numbered.matcher(sender);
            if (matcher.matches()) {
                ordinals.add(Integer.parseInt(matcher.group(1)));
            }
        }
        Collections.sort(ordinals);
        for (int i = 0; i < ordinals.size(); i++) {
            assertEquals(i + 1, ordinals.get(i).intValue(),
                    "background walk-ins must be numbered without a gap, got " + ordinals);
        }
    }

    // ---- cadence ----

    private long tickCount() {
        return published(SimClockTickEvent.class).size();
    }

    /** Bounded wait for the next {@code SimClockTickEvent}. After a speed change one tick is
     * still armed at the OLD period ({@code SimTickerBehaviour} re-times itself only after a
     * tick), so a cadence measurement starts after it. */
    private void awaitNextTick(String what) throws InterruptedException {
        long before = tickCount();
        assertTrue(waitUntil(() -> tickCount() > before, PHASE_TIMEOUT_MS),
                "no SimClockTickEvent arrived (" + what + ") — still " + before + " ticks");
    }

    /**
     * Wall milliseconds per {@code SimClockTickEvent}, timed from one tick to the {@code ticks}-th
     * after it — aligned on a tick at both ends, so the figure is the ticker's real period and not
     * an artefact of where an observation window happened to start.
     */
    private double wallMillisPerTick(int ticks, String phase) throws InterruptedException {
        awaitNextTick("aligning the " + phase + " measurement");
        long alignedCount = tickCount();
        long alignedAt = System.nanoTime();
        assertTrue(waitUntil(() -> tickCount() >= alignedCount + ticks, PHASE_TIMEOUT_MS),
                "fewer than " + ticks + " ticks at " + phase + " within " + PHASE_TIMEOUT_MS + " ms — got "
                        + (tickCount() - alignedCount));
        long elapsedMillis = (System.nanoTime() - alignedAt) / 1_000_000L;
        return (double) elapsedMillis / (tickCount() - alignedCount);
    }

    /** Sim-time gaps between consecutive ticks published while sim time was in (from, to]. */
    private List<Long> tickGapsBetween(long fromSimMillis, long toSimMillis) {
        List<Long> stamps = new ArrayList<>();
        for (SimClockTickEvent tick : published(SimClockTickEvent.class)) {
            if (tick.simMillis() > fromSimMillis && tick.simMillis() <= toSimMillis) {
                stamps.add(tick.simMillis());
            }
        }
        Collections.sort(stamps);
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < stamps.size(); i++) {
            gaps.add(stamps.get(i) - stamps.get(i - 1));
        }
        return gaps;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    // ---- helpers (the committed IT pattern: bounded polls, explicit timeouts) ----

    private void awaitSimTime(SimClock clock, long simMillis, String what) throws InterruptedException {
        assertTrue(waitUntil(() -> clock.nowSimMillis() >= simMillis, PHASE_TIMEOUT_MS),
                "sim time never reached " + simMillis + " (" + what + ") within " + PHASE_TIMEOUT_MS
                        + " ms — it stands at " + clock.nowSimMillis() + ", speed x" + clock.speedMultiplier());
    }

    /** The tutorial steps published so far, in publish order. */
    private List<Integer> tutorialSteps() {
        return published(TutorialStepAdvancedEvent.class).stream()
                .map(TutorialStepAdvancedEvent::step)
                .toList();
    }

    private <E extends Event> List<E> published(Class<E> type) {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private <E extends Event> E awaitEvent(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (E event : published(type)) {
                if (matcher.test(event)) {
                    return event;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** 20 ms cadence rather than the 50 ms the sibling ITs use: the x4 tick is 250 ms, and the
     * cadence figures are timed through this poll. */
    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
