package it.unige.portcommand.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.scenario.events.ScriptedEvent;
import it.unige.portcommand.scenario.events.SpawnVesselEvent;
import it.unige.portcommand.util.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scenario engine's clock hand (task 23): a sim-driven ticker on the HarbourMaster
 * that fires due {@link ScriptedEvent}s exactly once, and materialises the DAILY
 * contracted-arrival stream ({@link ContractSchedule}) each new game day.
 *
 * <p><b>Owned by this task, NOT part of the ADR-01 51-behaviour agent-stub set</b> —
 * it lives in the (uncounted) {@code scenario} package; {@code BehaviourCatalogueTest}
 * scans only the 5 {@code behaviours.*} sub-packages. INVARIANTS.md pre-authorizes
 * exactly this placement.
 *
 * <p><b>Exactly-once, replay-proof.</b> Every fired event id lands in {@link #fired},
 * which the save snapshot persists as {@code GameState.firedEventIds}; a rebuild
 * (save/load teardown) reconstructs the pending set as (declared ∪ materialised) MINUS
 * fired, so a reloaded mid-scenario game never replays — and a daily spawn the save
 * caught pre-grant is recovered rather than lost ({@link #unfireLostSpawns}, which also
 * documents the accepted wall-millisecond residual for scenario spawns). Scenario ids
 * are position-derived ({@code key#i} over the validated-sorted list); daily ids are
 * day-qualified ({@code contract:CONTRACT-1:d2}).
 *
 * <p><b>Poisson suppression.</b> {@link #scenarioWaveActive()} is true while any of
 * the SCENARIO's own events (never the background dailies) is still pending;
 * {@code PoissonSpawnBehaviour.canSpawnNow} gates on it, which resumes walk-in traffic
 * after the last scripted event — and, because pending is derived from
 * events-minus-fired, the suppression window survives a mid-scenario save/load.
 *
 * <p>All state is touched only on the HarbourMaster thread: ticks fire here, and both
 * snapshot paths (autosave inline, manual save via one-shot) run on the same thread.
 * Events apply inside a catch-all so one failing event cannot kill the timeline.
 *
 * <p><b>Scenario event times are relative to scenario start, not midnight</b> (fixed
 * 2026-07-18, found by an actual play-through: every packaged scenario boots at 08:00 —
 * 28,800 absolute sim-seconds — which is already past every event's declared
 * {@code simTimeSeconds} (max 1,500), so ALL of them fired in the very first tick,
 * defeating the tutorial's 5-step pacing and the storm's hold→clear reaction window).
 * {@link #scenarioStartSeconds} is {@code scenario.initialState().startSimMillis()/1000}
 * — a pure function of the scenario's own declared day/time, added to a scenario event's
 * {@code simTimeSeconds} when it is queued. Daily-stream events are unaffected: their
 * {@code simTimeSeconds} ({@code ContractSchedule.spawnSimSeconds}) is already absolute.
 */
public final class ScriptedEventBehaviour extends SimTickerBehaviour {

    /** 30 sim-seconds — well under the tightest scripted spacing (±1 tick tolerance). */
    static final long TICK_SIM_MILLIS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(ScriptedEventBehaviour.class);

    private record Pending(String id, long simTimeSeconds, int seq, boolean scenarioEvent,
                           ScriptedEvent event) {
    }

    private final GameContext ctx;
    private final Scenario scenario;                 // null in sandbox play
    private final long scenarioStartSeconds;          // 0 in sandbox; unused there
    private final List<ServiceContract> dailyBase;   // pristine contracts.json set
    private final Set<String> fired = new LinkedHashSet<>();
    private final PriorityQueue<Pending> pending = new PriorityQueue<>(
            Comparator.comparingLong(Pending::simTimeSeconds).thenComparingInt(Pending::seq));
    private int pendingScenarioEvents;
    private int lastMaterializedDay;
    private int seq;

    public ScriptedEventBehaviour(jade.core.Agent agent, GameContext ctx, ScenarioRuntime runtime,
                                  List<ServiceContract> dailyBase) {
        super(agent, ctx.simClock(), TICK_SIM_MILLIS);
        this.ctx = ctx;
        this.scenario = runtime.scenario();
        this.scenarioStartSeconds = scenario == null ? 0L
                : scenario.initialState().startSimMillis() / 1000L;
        this.dailyBase = dailyBase.stream()
                .sorted(Comparator.comparing(ServiceContract::contractId))
                .toList();
        this.fired.addAll(runtime.firedEventIds());
        unfireLostSpawns(runtime);
        // Dailies materialise from the boot day forward — a past day's unfired spawn is
        // dead history, not a burst of late arrivals (its fired ids, if any, stay kept).
        this.lastMaterializedDay = SimClock.gameDayOf(ctx.simClock().nowSimMillis()) - 1;
        if (scenario != null) {
            for (int i = 0; i < scenario.events().size(); i++) {
                ScriptedEvent event = scenario.events().get(i);
                String id = scenario.key() + "#" + i;
                if (!fired.contains(id)) {
                    pending.add(new Pending(id, scenarioStartSeconds + event.simTimeSeconds(),
                            seq++, true, event));
                    pendingScenarioEvents++;
                }
            }
            log.info("scenario '{}' armed: {} pending / {} already fired", scenario.key(),
                    pendingScenarioEvents, scenario.events().size() - pendingScenarioEvents);
        }
    }

    @Override
    protected void onSimTick() {
        if (simClock().isPaused()) {
            return;
        }
        materialiseDailies();
        drainDue();
    }

    /**
     * Recovers DAILY spawns a save caught in their spawned-but-not-yet-granted window
     * (2026-07-18 adversarial-review finding 1): the spawn event is marked fired at
     * agent creation, but a contracted vessel only becomes save-visible at the GRANT
     * ({@code activeVessels} entry + fee income, both in one HM behaviour action) — and
     * the daily stream's 2-sim-hour spawn lead makes that window ~1/12 of each day. A
     * save inside it carries the fired id but NOT the vessel, and the reload would
     * otherwise drop the arrival (and its €) forever. Un-firing is gated on the restored
     * world VOUCHING for the vessel ({@link ScenarioRuntime#restoredVesselTraces} —
     * tracked, or already in today's income/expense): a traced vessel stays fired.
     *
     * <p>Scope is deliberately TODAY's dailies only. A past day's fee is not in the
     * restored day history and its vessel is legitimately gone — un-firing there would
     * re-credit a settled day's income through the rebuilt (empty-dedupe) ledger.
     * Scenario spawns are never un-fired: a scripted spawn arrives at its own event time
     * (eta = now → immediate announce), so its pre-grant window is the announce round
     * trip — wall-milliseconds, an accepted residual (documented in INVARIANTS) — while
     * un-firing one after a cross-midnight save WOULD re-credit; and a scripted walk-in
     * additionally follows task 22's drop semantics (dropped mid-negotiation = "never
     * appeared in the saved timeline").
     */
    private void unfireLostSpawns(ScenarioRuntime runtime) {
        if (fired.isEmpty()) {
            return;
        }
        Set<String> traces = runtime.restoredVesselTraces();
        int today = SimClock.gameDayOf(ctx.simClock().nowSimMillis());
        for (ServiceContract base : dailyBase) {
            String id = ContractSchedule.eventId(base, today);
            if (fired.contains(id)
                    && !traces.contains(ContractSchedule.dailyContract(base, today).vesselId())) {
                fired.remove(id); // materialiseDailies re-registers + re-queues it
                log.warn("recovering lost daily spawn {} — the save caught it pre-grant", id);
            }
        }
    }

    /**
     * One {@link SpawnVesselEvent} per {@code contracts.json} entry per game day, from
     * the boot day (sandbox) or day 2 (scenario — day 1 belongs to the script) onward.
     * The day-qualified contract clone is registered into the HarbourMaster's live map
     * BEFORE its event is queued, so the announce REQUEST always resolves.
     */
    private void materialiseDailies() {
        int today = SimClock.gameDayOf(simClock().nowSimMillis());
        for (int day = lastMaterializedDay + 1; day <= today; day++) {
            if (scenario != null && day < 2) {
                continue;
            }
            for (ServiceContract base : dailyBase) {
                String id = ContractSchedule.eventId(base, day);
                if (fired.contains(id)) {
                    continue;
                }
                ServiceContract daily = ContractSchedule.dailyContract(base, day);
                ctx.contracts().putIfAbsent(daily.contractId(), daily);
                pending.add(new Pending(id, ContractSchedule.spawnSimSeconds(daily), seq++, false,
                        new SpawnVesselEvent(ContractSchedule.spawnSimSeconds(daily),
                                daily.vesselType(), SpawnVesselEvent.CHANNEL_CONTRACTED, null,
                                daily.contractId(), null, null, null,
                                daily.expectedArrivalSimMillis())));
            }
        }
        lastMaterializedDay = Math.max(lastMaterializedDay, today);
    }

    private void drainDue() {
        long nowSeconds = simClock().nowSimMillis() / 1000L;
        while (!pending.isEmpty() && pending.peek().simTimeSeconds() <= nowSeconds) {
            Pending due = pending.poll();
            if (!fired.add(due.id())) {
                continue; // double-enqueue guard; enqueue paths already filter on fired
            }
            if (due.scenarioEvent()) {
                pendingScenarioEvents--;
                if (pendingScenarioEvents == 0) {
                    log.info("scenario '{}' script complete — Poisson walk-in traffic resumes",
                            scenario.key());
                }
            }
            try {
                due.event().apply(ctx);
            } catch (RuntimeException e) {
                log.error("scripted event {} failed — timeline continues", due.id(), e);
            }
        }
    }

    /** True while the scenario's own scripted wave is still firing (Poisson gate). */
    public boolean scenarioWaveActive() {
        return scenario != null && pendingScenarioEvents > 0;
    }

    /** The persisted replay guard — read by the save snapshot on this same agent thread. */
    public List<String> firedEventIdsSorted() {
        List<String> ids = new ArrayList<>(fired);
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** The active scenario key for {@code GameState.activeScenario}; null in sandbox. */
    public String scenarioKey() {
        return scenario == null ? null : scenario.key();
    }
}
