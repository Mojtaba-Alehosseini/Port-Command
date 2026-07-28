package it.unige.portcommand.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code --smoke}: boots each of the three shipped scenarios headlessly, at a compressed day
 * length, and checks the demo claims that <b>no single test covers</b> (planning/26 §26.2).
 *
 * <h2>What it checks, and what it deliberately does not</h2>
 *
 * <p>The one criterion nothing else can reach is <b>DEMO_CHECKLIST row 10</b>: "all 10 FIPA
 * performatives across {@code tutorial} + {@code busy_day} + {@code storm} combined". It is a
 * cross-scenario, whole-project claim — every individual IT runs one world, and the end-of-day
 * summary shows one day. This runner is the only thing that plays all three and takes the union,
 * so that is what it exists for. Alongside it, it checks each scenario boots, runs its whole
 * script, keeps its clock ticking, and raises no uncaught exception.
 *
 * <p><b>It does not pretend to automate the other nine checklist rows.</b> Rows 1-9 each have a
 * half that is a claim about pixels — a banner appears, a dialog shows, the comm log does not
 * fight the reader's scroll — and a headless runner that printed PASS for those would be
 * manufacturing evidence, which is worse than no evidence. Their ENGINE halves are already
 * covered by named integration tests; {@link #COVERAGE} maps each row to whichever it is, and
 * {@code docs/DEMO_CHECKLIST.md} carries the same mapping so the two cannot drift silently.
 *
 * <h2>Honesty rules this class follows</h2>
 * <ul>
 *   <li>A scenario that fails to reach its assertions is a FAILURE, never a skip.</li>
 *   <li>A missing performative names itself and the scenarios that were searched.</li>
 *   <li>An exception that reaches a thread's default handler fails the run. <b>Scope, stated
 *       rather than overclaimed:</b> JADE catches a Throwable thrown inside a behaviour and kills
 *       that agent itself, so this handler will not see most behaviour bugs. What DOES catch those
 *       is the liveness check below — a dead HarbourMaster stops the sim clock, and a scenario
 *       whose ticks stop is failed by name.</li>
 * </ul>
 */
public final class SmokeRunner {

    private static final Logger log = LoggerFactory.getLogger(SmokeRunner.class);

    /** The three shipped scenarios, in the order the demo plays them. */
    private static final List<String> SCENARIOS = List.of("tutorial", "busy_day", "storm");

    /**
     * A dedicated JADE Main Container port, NOT the 1099 default. A running {@code --gui}
     * session holds 1099 and a smoke run against it dies with the misleading null-container NPE
     * (docs/testing.md); 18099 is the integration lane's. Override with
     * {@code -Djade.main.port}.
     */
    private static final int DEFAULT_SMOKE_PORT = 18299;

    /**
     * Fallback day length, used only for a scenario that declares no
     * {@code settingsOverride.realSecondsPerGameDay} — all three shipped ones do (900/900/1800),
     * so in practice each scenario runs at ITS OWN declared pacing.
     *
     * <p><b>This runner deliberately does NOT compress the day</b>, unlike the integration tests.
     * The first version pinned 5 s/day the way the ITs do, and the storm's whole timeline then
     * drained in about a tenth of a second: the tanker had not been granted a berth, let alone
     * assigned a tug, by the time the wind crossed 30 kn, so there was no escort to CANCEL and
     * the run reported a missing performative that the game produces perfectly well. The scripted
     * timings encode a reaction window measured against the scenario's own pacing (that is the
     * whole point of the 2026-07-18 relative-time fix), and a smoke run at a pacing the demo
     * never uses proves nothing about the demo. Measured cost: ~100 s for all three (tutorial 48 s,
     * busy_day 19 s, storm 30 s) plus teardown, against ~10 s at the compressed pacing.
     */
    private static final long DAY_SECONDS = 900L;

    /**
     * Wall-clock bound per scenario: the longest script drains in ~31 s (tutorial, 1500 sim-s at
     * 1800 s/day), and the ACL cascades it triggers need room after that.
     */
    private static final long SCENARIO_TIMEOUT_MS = 120_000L;

    /** Sim ticks a scenario must produce after its script drains — proof the world is still live. */
    private static final int TICKS_AFTER_SCRIPT = 10;

    /** DEMO_CHECKLIST row -> what actually establishes it. Mirrored in docs/DEMO_CHECKLIST.md. */
    private static final Map<String, String> COVERAGE = new LinkedHashMap<>();

    static {
        COVERAGE.put("1  Channel A fully automatic",
                "engine: ContractedVesselAgentIT + ScenarioSessionIT | GUI half: manual");
        COVERAGE.put("2  Walk-in negotiation, happy path",
                "engine: WalkInVesselAgentIT + NegotiationEngine tests | GUI half: manual");
        COVERAGE.put("3  Walk-in withdrawal, timeout",
                "engine: WalkInVesselAgentIT | GUI half: manual");
        COVERAGE.put("4  Walk-in withdrawal, over-priced",
                "engine: WalkInVesselAgentIT | GUI half: manual");
        COVERAGE.put("5  Autopilot ON",
                "engine: AssistantAgentIT | Settings toggle + GUI half: manual");
        COVERAGE.put("6  Policy parsing via PolicyParser",
                "engine: PolicyParserTest | GUI half: manual");
        COVERAGE.put("7  Weather event, tanker hold",
                "engine: WeatherAgentIT + PrologRulesIT (R15-R20) | GUI half: manual");
        COVERAGE.put("8  Save & load",
                "engine: SaveLoadSessionIT + SaveLoadMidTransitIT | dialog + previews: manual");
        COVERAGE.put("9  End-of-day summary",
                "engine: GameLifecycleIT + EndOfDaySummaryModelTest | dialog: manual");
        COVERAGE.put("10 All 10 performatives across the 3 scenarios",
                "THIS RUNNER — nothing else plays all three and takes the union");
    }

    private SmokeRunner() {
    }

    /** @return the number of failures; 0 means green. Prints its own report to stdout. */
    public static int runAll() {
        List<String> failures = new ArrayList<>();
        Map<String, Set<String>> seenByScenario = new LinkedHashMap<>();
        ConcurrentLinkedQueue<String> uncaught = new ConcurrentLinkedQueue<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                uncaught.add(t.getName() + ": " + e));

        try {
            for (String scenario : SCENARIOS) {
                try {
                    seenByScenario.put(scenario, runScenario(scenario, failures));
                } catch (Exception e) {
                    // A scenario that throws is a FAILURE, not a skip — the whole point of a
                    // smoke run is that it cannot quietly cover less than it claims.
                    log.error("scenario {} blew up", scenario, e);
                    failures.add("scenario " + scenario + " failed to run: " + e);
                    seenByScenario.put(scenario, Set.of());
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        uncaught.forEach(u -> failures.add("uncaught exception on a live thread: " + u));

        Set<String> union = new LinkedHashSet<>();
        seenByScenario.values().forEach(union::addAll);
        List<String> missing = PerformativeCounter.canonicalNames().stream()
                .filter(name -> !union.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            failures.add("DEMO_CHECKLIST row 10 FAILS: " + missing
                    + " never appeared across " + SCENARIOS + ". This is the "
                    + "'all 10 performatives across the three scenarios combined' criterion "
                    + "(PROJECT_DEFINITION §13) — CANCEL comes from the storm's weather hold and "
                    + "DISCONFIRM from busy_day's scripted BerthFlood, so a miss usually means one "
                    + "of those scripted events did not fire, not that the tally is broken.");
        }

        report(seenByScenario, missing, failures);
        return failures.size();
    }

    /** @return the canonical performative names the HarbourMaster saw during this scenario. */
    private static Set<String> runScenario(String scenario, List<String> failures) throws Exception {
        log.info("--smoke: booting scenario '{}'", scenario);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger ticks = new AtomicInteger();

        int port = Integer.getInteger("jade.main.port", DEFAULT_SMOKE_PORT);
        // Deliberately NOT setting simclock.day.length.explicit: that marker is how an explicit
        // --day-length overrides a scenario's own pacing, and here the scenario's pacing is
        // precisely what we want to exercise (see DAY_SECONDS).
        JadeBootstrap boot = new JadeBootstrap();
        Path work = Files.createTempDirectory("port-command-smoke-" + scenario);
        Subscription<CommLogEvent> commLog = null;
        Subscription<SimClockTickEvent> tickSub = null;
        try {
            BootstrapConfig cfg = new BootstrapConfig(port, false, "realtime", DAY_SECONDS);
            GameSession session = new GameSession(boot, cfg,
                    new SaveLoadManager(work), new Leaderboard(work.resolve("scores.json")));

            // Boot the platform FIRST, purely so the EventBus exists and can be subscribed to
            // before any world does. GameSession creates the bus inside startScenario, and a
            // subscription taken after it returns would miss every message the agent spawn and
            // the t=0 scripted events already produced — in the storm scenario that is the
            // tanker's opening REQUEST. startScenario then tears this container down and
            // rebuilds it (its normal teardown/rebuild body), and the EventBus deliberately
            // SURVIVES that cycle (JadeBootstrap creates it only when null, so the GUI can hold
            // it across a load) — which is exactly what makes this subscription outlive the
            // restart. Costs one extra container boot per scenario; buys a complete tally.
            boot.start(cfg);

            // CALLER_THREAD for the same reason PerformativeCounter uses it: ASYNC's bounded
            // queue drops the OLDEST event when full, and a dropped CommLogEvent here is a
            // performative silently missing from the row-10 union.
            commLog = boot.getEventBus().subscribe(CommLogEvent.class,
                    e -> seen.add(jade.lang.acl.ACLMessage.getPerformative(e.performative())),
                    DeliveryMode.CALLER_THREAD);
            tickSub = boot.getEventBus().subscribe(SimClockTickEvent.class,
                    e -> ticks.incrementAndGet(), DeliveryMode.CALLER_THREAD);

            session.startScenario(scenario);

            HarbourMasterAgent hm = (HarbourMasterAgent) session.directory()
                    .byName("harbour_master").orElseThrow(() ->
                            new IllegalStateException("no harbour_master after startScenario"));

            if (!waitUntil(() -> !hm.scriptedWaveActive(), SCENARIO_TIMEOUT_MS)) {
                failures.add(scenario + ": the scripted event wave never drained within "
                        + SCENARIO_TIMEOUT_MS + " ms");
            }
            int afterScript = ticks.get();
            if (!waitUntil(() -> ticks.get() >= afterScript + TICKS_AFTER_SCRIPT, 30_000)) {
                failures.add(scenario + ": the sim clock stopped ticking after the script drained "
                        + "(saw " + ticks.get() + " ticks, wanted " + (afterScript + TICKS_AFTER_SCRIPT)
                        + ") — a deadlocked world, not a finished one");
            }
            log.info("--smoke: '{}' done — {} performative(s): {}", scenario, seen.size(), sorted(seen));
            return new LinkedHashSet<>(sorted(seen));
        } finally {
            if (commLog != null) {
                commLog.cancel();
            }
            if (tickSub != null) {
                tickSub.cancel();
            }
            if (boot.isStarted()) {
                boot.shutdown();
            }
            deleteRecursively(work);
        }
    }

    private static void report(Map<String, Set<String>> seenByScenario, List<String> missing,
                              List<String> failures) {
        StringBuilder sb = new StringBuilder("\n=== --smoke: Port Command Genova ===\n\n");
        sb.append("DEMO_CHECKLIST row 10 — all 10 FIPA performatives across the three scenarios\n\n");
        sb.append(String.format("| %-16s |", "performative"));
        seenByScenario.keySet().forEach(s -> sb.append(String.format(" %-9s |", s)));
        sb.append(" union |\n");
        for (String name : PerformativeCounter.canonicalNames()) {
            sb.append(String.format("| %-16s |", name));
            boolean any = false;
            for (Set<String> seen : seenByScenario.values()) {
                boolean hit = seen.contains(name);
                any |= hit;
                sb.append(String.format(" %-9s |", hit ? "yes" : "-"));
            }
            sb.append(String.format(" %-5s |%n", any ? "YES" : "NO"));
        }

        sb.append("\nChecklist coverage (what establishes each row — mirrored in docs/DEMO_CHECKLIST.md):\n");
        COVERAGE.forEach((row, how) -> sb.append(String.format("  %-42s %s%n", row, how)));
        sb.append("\nRows 1-9 have a GUI half that only a human can sign off. This runner does not\n"
                + "claim them, and DEMO_CHECKLIST.md records them as manual with a tester and a date.\n");

        if (failures.isEmpty()) {
            sb.append("\nRESULT: PASS — 10/10 performatives observed, all three scenarios ran clean.\n");
        } else {
            sb.append("\nRESULT: FAIL (").append(failures.size()).append(")\n");
            failures.forEach(f -> sb.append("  - ").append(f).append('\n'));
            if (!missing.isEmpty()) {
                sb.append("  missing performatives: ").append(missing).append('\n');
            }
        }
        System.out.println(sb);
    }

    private static List<String> sorted(Set<String> names) {
        return PerformativeCounter.canonicalNames().stream().filter(names::contains).toList();
    }

    /** The committed bounded sleep-poll pattern (CLAUDE.md rule 5): every wait has a deadline. */
    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // A leftover temp dir is not worth failing a smoke run over.
                }
            });
        } catch (Exception ignored) {
            // ditto
        }
    }
}
