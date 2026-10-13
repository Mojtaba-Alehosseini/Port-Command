package it.unige.portcommand.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.CustomsAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.TerminalAgent;
import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.WeatherAgent;
import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.lifecycle.GameOverGuard;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a {@link GameState} from the live world (task 22). <b>Must run on the
 * HarbourMaster's agent thread</b> — the autosave subscriber is already there
 * (CALLER_THREAD chain from the EOD settlement), and the manual save schedules a
 * one-shot behaviour to get there. On that thread no other HM behaviour can interleave,
 * so tracking, ledgers and counters are at a phase boundary by construction; the money
 * path cannot move (every money mutation happens on this same thread, task 24's
 * race-free-EOD argument). Tug/vessel threads still tick their cosmetic movement — their
 * volatile phase/position reads are individually coherent, and any lag replays a
 * money-free step after load.
 *
 * <h2>Open negotiations</h2>
 * A vessel whose tracking stage is NEGOTIATING (or whose vessel-side flow phase has not
 * been reported yet — the grant is still on the wire) has no clean resumable state.
 * The MANUAL save refuses ({@link SaveNotAllowedException}, planning/22 Step 22.7); the
 * AUTOSAVE — which cannot refuse, it is the EOD hook — DROPS those vessels from the
 * snapshot: in the saved timeline the walk-in never appeared. Berth reservations are
 * reconciled against the surviving vessel set at load ({@link #reconcileTerminals}), so
 * nothing leaks (decision dated 2026-07-17). One stated residual (S10 bug hunt, hunter A
 * finding 8): "never appeared" is not quite true for the WALLET — a grant whose income
 * landed on the HM thread before the vessel reported its phase is dropped vessel-side but
 * its fee stays in the persisted day history, so the restored day can carry money from a
 * vessel the timeline no longer contains. Accepted: the window is one grant round trip,
 * and clawing the entry back out of the append-only ledger would be worse than the drift.
 */
public final class GameStateBuilder {

    private static final Logger log = LoggerFactory.getLogger(GameStateBuilder.class);

    /** Everything the snapshot walk reads, bundled once by {@code GameSession}. */
    public record Sources(
            SimClock simClock,
            RandomSource randomSource,
            AgentDirectory directory,
            MarketHistoryArtifact marketHistory,
            PolicyRegistryArtifact policyRegistry,
            GameOverGuard gameOverGuard) {
    }

    private static final int MARKET_HISTORY_LIMIT = 200;

    private GameStateBuilder() {
    }

    /**
     * Builds the full save-file state. Call ON the HarbourMaster thread only.
     *
     * @param savedAt         ISO-8601 wall instant stamped into the file (audit field)
     * @param dropNegotiating {@code true} for the autosave (drop unfinished negotiations),
     *                        {@code false} for a manual save (refuse instead)
     */
    public static GameState build(Sources src, String savedAt, boolean dropNegotiating)
            throws SaveNotAllowedException {
        HarbourMasterAgent hm = (HarbourMasterAgent) src.directory().byName("harbour_master")
                .orElseThrow(() -> new IllegalStateException("harbour_master not in AgentDirectory"));

        List<VesselStateDTO> vessels = snapshotVessels(hm, src.directory(), dropNegotiating);

        List<TugStateDTO> tugs = src.directory().byType(TugAgent.class).stream()
                .map(TugAgent::snapshotDto)
                .sorted(Comparator.comparing(TugStateDTO::tugId))
                .toList();
        List<TerminalStateDTO> terminals = src.directory().byType(TerminalAgent.class).stream()
                .map(TerminalAgent::snapshotDto)
                .sorted(Comparator.comparing(TerminalStateDTO::terminalId))
                .toList();
        WeatherAgent weather = src.directory().byType(WeatherAgent.class).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("weather_agent not in AgentDirectory"));
        CustomsAgent customs = src.directory().byType(CustomsAgent.class).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("customs_agent not in AgentDirectory"));

        long simMillis = src.simClock().nowSimMillis();
        // Task 23: the scenario engine's persisted slice, read on this same HM thread. A
        // stub-harness HM without the engine snapshots sandbox semantics (null key, []).
        var scripted = hm.scriptedEvents();
        return new GameState(
                SaveLoadManager.SCHEMA_VERSION,
                savedAt,
                src.randomSource().saveSeedAt(simMillis),
                Settings.load(),
                new GameState.SimClockState(simMillis, src.simClock().realSecondsPerGameDay()),
                hm.walletLedger().balance(),
                (int) Math.round(hm.reputationLedger().score()),
                src.gameOverGuard() == null ? 0 : src.gameOverGuard().consecutiveDeepDebtDays(),
                src.gameOverGuard() == null ? 0 : src.gameOverGuard().lastDeepDebtDay(),
                src.marketHistory().lastN(MARKET_HISTORY_LIMIT),
                src.policyRegistry().all().stream().map(PolicyRule::trigger).toList(),
                scripted == null ? null : scripted.scenarioKey(),
                scripted == null ? List.of() : scripted.firedEventIdsSorted(),
                new GameState.AgentsState(
                        hm.snapshotDto(),
                        weather.snapshotDto(),
                        customs.snapshotDto(),
                        tugs,
                        terminals,
                        vessels));
    }

    /**
     * The active-vessel walk: HM tracking (authoritative for stage/tugs/holds) merged with
     * each vessel agent's own snapshot (authoritative for phase/deal/position/beliefs).
     * Sorted by vessel id (determinism rule).
     */
    private static List<VesselStateDTO> snapshotVessels(HarbourMasterAgent hm, AgentDirectory directory,
                                                        boolean dropNegotiating)
            throws SaveNotAllowedException {
        List<VesselStateDTO> out = new ArrayList<>();
        List<VesselTracking> tracked = hm.activeVessels().values().stream()
                .sorted(Comparator.comparing(VesselTracking::vesselId))
                .toList();
        for (VesselTracking tracking : tracked) {
            if (tracking.stage() == Stage.NEGOTIATING || tracking.stage() == Stage.ARRIVING) {
                if (!dropNegotiating) {
                    throw new SaveNotAllowedException(
                            "Finish the current negotiation with " + tracking.vesselId() + " before saving.");
                }
                log.info("autosave: dropping {} (stage {}) from the snapshot", tracking.vesselId(),
                        tracking.stage());
                continue;
            }
            BaseVesselAgent agent = directory.byName(AgentRoster.vesselLocalName(tracking.spec()))
                    .filter(BaseVesselAgent.class::isInstance)
                    .map(BaseVesselAgent.class::cast)
                    .orElse(null);
            if (agent == null || agent.flowPhase() == null) {
                // The grant is still on the wire (vessel never reported a phase) or the agent
                // is already gone: no resumable state. Manual saves refuse — this is the same
                // "no clean phase boundary" case as an open negotiation; autosaves drop.
                if (!dropNegotiating) {
                    throw new SaveNotAllowedException(
                            "Vessel " + tracking.vesselId() + " is between phases; try again in a moment.");
                }
                log.info("autosave: dropping {} (no reported flow phase) from the snapshot",
                        tracking.vesselId());
                continue;
            }
            if (tracking.stage().ordinal() >= Stage.AWAITING_TUG.ordinal()
                    && agent.flowPhase() == BaseVesselAgent.FlowPhase.AWAITING_BERTH) {
                // S10 (bug hunt, hunter B finding 4): the berth grant is still ON THE WIRE —
                // the HM staged AWAITING_TUG on send, the vessel has not consumed the ACCEPT.
                // Before S5's ADR-16 widened reportPhase this window was flowPhase==null and
                // the branch above correctly refused; AWAITING_BERTH silently re-opened it. A
                // save here restores a vessel waiting forever for an ACCEPT nobody re-sends
                // (requeueAwaitingBerth filters on the TRACKING stage, which says the grant
                // already happened) while redispatchTugless CNPs real tugs to an empty berth.
                // Same "no clean phase boundary" rule as the null case.
                if (!dropNegotiating) {
                    throw new SaveNotAllowedException(
                            "Vessel " + tracking.vesselId() + " is between phases; try again in a moment.");
                }
                log.info("autosave: dropping {} (berth grant still on the wire — stage {} vs "
                        + "phase {}) from the snapshot", tracking.vesselId(), tracking.stage(),
                        agent.flowPhase());
                continue;
            }
            if (tracking.stage() == Stage.IN_TRANSIT
                    && agent.flowPhase() == BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE) {
                // S16 (ADR-20, adversarial review): the ORDER TO PROCEED is still on the wire —
                // the HarbourMaster wrote IN_TRANSIT on send, the ship has not consumed it. The
                // same rule as the grant above, for the same reason: a save here restores a ship
                // that waits for an order the rebuilt HarbourMaster believes it already gave.
                if (!dropNegotiating) {
                    throw new SaveNotAllowedException(
                            "Vessel " + tracking.vesselId() + " is between phases; try again in a moment.");
                }
                log.info("autosave: dropping {} (order to proceed still on the wire) from the "
                        + "snapshot", tracking.vesselId());
                continue;
            }
            VesselStateDTO vesselSide = agent.snapshotDto();
            out.add(mergeTracking(vesselSide, tracking));
        }
        return out;
    }

    /** Fills the HM-side tracking fields into the vessel-side DTO (records are immutable). */
    private static VesselStateDTO mergeTracking(VesselStateDTO v, VesselTracking t) {
        return new VesselStateDTO(
                v.id(), v.vesselType(), v.channel(),
                v.draft(), v.length(), v.tonnage(), v.cargoClass(), v.etaArrivalSimMillis(),
                t.stage().name(), v.currentPhase(), v.phaseDeadlineSimMillis(),
                v.assignedBerth() != null ? v.assignedBerth() : t.assignedBerth(),
                v.dealPrice(), v.dealHours(),
                t.assignedTugs(), t.blocked(), t.weatherHeld(),
                v.posX(), v.posY(), v.posHeading(),
                v.contractRef(),
                v.personality(), v.minAcceptablePrice(), v.targetPrice(),
                v.maxWaitMinutes(), v.minDurationHours(), v.negotiationRound(),
                v.negotiationStartedAtSimMillis(),
                v.vesselName());
    }

    /**
     * Task 23: a scenario's opening {@link GameState} — <b>the same shape a save file
     * has</b>, so scenario start reuses the task-22 load path verbatim (validate →
     * teardown → rebuild; reconciliation dated 2026-07-18 — planning/23's §23.10
     * {@code Main.startScenario} sketch predates {@code GameSession}). Pre-docked berth
     * vessels materialise as restored DOCKED walk-ins whose deal closed pre-scenario
     * (no fee re-earned: {@code LedgerCoordinator} only credits on live events), with
     * dims from {@code ScenarioVessels}' pinned table.
     *
     * @param resolvedSeed          the pinned {@code randomSeed}, or the freshly generated
     *                              one when the scenario's field is null (0 is a valid pin)
     * @param realSecondsPerGameDay the already-resolved pacing (CLI {@code --day-length}
     *                              beats the scenario override beats the sandbox default)
     */
    public static GameState fromScenario(it.unige.portcommand.scenario.Scenario s, long resolvedSeed,
                                         long realSecondsPerGameDay, String savedAt) {
        it.unige.portcommand.scenario.Scenario.InitialState init = s.initialState();
        long simMillis = init.startSimMillis();

        List<VesselStateDTO> docked = new ArrayList<>();
        Map<String, List<BerthOccupancy>> occupanciesByTerminal = new java.util.LinkedHashMap<>();
        occupanciesByTerminal.put("terminal_container", new ArrayList<>());
        occupanciesByTerminal.put("terminal_general", new ArrayList<>());
        init.berths().stream()
                .sorted(Comparator.comparingInt(it.unige.portcommand.scenario.Scenario.BerthInit::berthId))
                .forEach(berth -> {
                    String berthId = "berth_" + berth.berthId();
                    String terminal = berth.berthId() == 2 ? "terminal_container" : "terminal_general";
                    if (berth.occupied()) {
                        var v = berth.vessel();
                        long freeAt = simMillis + v.dealHours() * 3_600_000L;
                        docked.add(dockedVessel(v, berthId, simMillis, freeAt));
                        occupanciesByTerminal.get(terminal).add(new BerthOccupancy(berthId,
                                v.vesselId(), simMillis, freeAt, 1, BerthOccupancy.Status.DOCKED));
                    } else {
                        occupanciesByTerminal.get(terminal).add(BerthOccupancy.free(berthId));
                    }
                });
        docked.sort(Comparator.comparing(VesselStateDTO::id));
        List<TerminalStateDTO> terminals = List.of(
                new TerminalStateDTO("terminal_container", occupanciesByTerminal.get("terminal_container")),
                new TerminalStateDTO("terminal_general", occupanciesByTerminal.get("terminal_general")));

        List<TugStateDTO> tugs = init.tugs().stream()
                .sorted(Comparator.comparingInt(it.unige.portcommand.scenario.Scenario.TugInit::tugId))
                .map(t -> new TugStateDTO("tug_" + t.tugId(),
                        // §3.15's AVAILABLE is the engine's IDLE; a REFUELING boot restores
                        // IDLE-with-low-tank and RefuelIfLowBehaviour re-triggers (task-22 rule).
                        "AVAILABLE".equals(t.status()) ? "IDLE" : t.status(),
                        null, t.fuel(), null, null))
                .toList();

        var weather = init.weatherOverride();
        return new GameState(
                SaveLoadManager.SCHEMA_VERSION,
                savedAt,
                resolvedSeed,
                mergedSettings(s, realSecondsPerGameDay),
                new GameState.SimClockState(simMillis, realSecondsPerGameDay),
                init.wallet(),
                init.reputation(),
                0, 0,
                List.of(), List.of(),
                s.key(),
                List.of(),
                new GameState.AgentsState(
                        new it.unige.portcommand.persistence.dto.HarbourMasterStateDTO(
                                List.of(), List.of(), new java.util.LinkedHashMap<>(),
                                // namePoolOffset -1: the "roll fresh at boot" sentinel (S8) —
                                // a scenario opening has no rolled offset yet; the HM draws one
                                // deterministically from the scenario's seed.
                                new java.util.LinkedHashMap<>(), 0, -1),
                        new it.unige.portcommand.persistence.dto.WeatherStateDTO(
                                new it.unige.portcommand.agents.WeatherSnapshot(weather.windKn(),
                                        weather.visibility(), weather.swell(), weather.state(), simMillis)),
                        new it.unige.portcommand.persistence.dto.CustomsStateDTO(1),
                        tugs, terminals, docked));
    }

    private static VesselStateDTO dockedVessel(it.unige.portcommand.scenario.Scenario.DockedVessel v,
                                               String berthId, long simMillis, long freeAt) {
        double[] dims = it.unige.portcommand.scenario.ScenarioVessels.pinnedDims(v.vesselType());
        var position = it.unige.portcommand.harbourmaster.BerthPositions.position(berthId);
        return new VesselStateDTO(
                v.vesselId(), v.vesselType(), VesselStateDTO.CHANNEL_WALK_IN,
                dims[0], dims[1], (int) dims[2],
                it.unige.portcommand.scenario.ScenarioVessels.cargoClassFor(v.vesselType()),
                simMillis,
                Stage.DOCKED.name(), "DOCKED", freeAt,
                berthId, v.dealPrice(), v.dealHours(),
                List.of(), false, false,
                position.x(), position.y(), 0.0,
                null,
                null, null, null, null, null, null, null,
                v.vesselName());
    }

    /** {@code Settings.load()} with the scenario's partial override applied (task 23). */
    private static Settings mergedSettings(it.unige.portcommand.scenario.Scenario s,
                                           long realSecondsPerGameDay) {
        // S9 (adversarial finding 4): the base is the CLASSPATH DEFAULTS, not Settings.load() —
        // load() became the session's live record, and using it here would leak a previous
        // world's applied settings (a loaded save's roundLimit=7, say) into every scenario
        // whose override doesn't name that knob. classpathDefaults() restores the exact
        // pre-S9 semantics: scenario = defaults + the scenario's own override, nothing else.
        Settings base = Settings.classpathDefaults();
        var override = s.settingsOverride();
        // With-ers, not a positional constructor (S14): a field added to Settings — gameSpeed was
        // the first — then needs no edit here, and a new scenario game starts from its default.
        Settings merged = base.withRealSecondsPerGameDay(realSecondsPerGameDay);
        if (override != null && override.autopilotEnabled() != null) {
            merged = merged.withAutopilotEnabled(override.autopilotEnabled());
        }
        if (override != null && override.difficulty() != null) {
            merged = merged.withDifficulty(override.difficulty());
        }
        return merged;
    }

    /**
     * S15 (2026-10-12): the opening {@link GameState} of a <b>Free play</b> game — the sandbox
     * with no scenario, built in the same save shape {@link #fromScenario} produces so that
     * {@code GameSession.startSandbox} can boot it through the task-22 load path (validate →
     * teardown → rebuild). That path is the only one that can REPLACE a running world, and the
     * only one that resets everything a new game must reset; {@code GameSession.startNewGame()}
     * (spawn straight into a fresh container) can do neither, which is why the New Game dialog's
     * Free play choice comes here instead.
     *
     * <p>Field by field, against {@link #fromScenario}:
     * <ul>
     *   <li><b>settings</b> — the classpath defaults (never {@code Settings.load()}: that is the
     *       live record and would leak the previous world's applied block, the S9 rule
     *       {@code mergedSettings} states) with the day length set to the clock's, so the
     *       settings block and the clock agree from the first save on. No override: there is
     *       no scenario to supply one.</li>
     *   <li><b>clock</b> — sim instant 0, i.e. Day 1 00:00 (scenarios open at 08:00), which is
     *       where {@code startNewGame()} starts too: the day's first contracted arrival is
     *       still some sim-hours away.</li>
     *   <li><b>wallet / reputation</b> — {@link AgentRoster#STARTING_WALLET} and
     *       {@link AgentRoster#STARTING_REPUTATION}, the same constants the directly-spawned
     *       sandbox feeds {@code HarbourMasterInitArgs}; no bankruptcy streak.</li>
     *   <li><b>market history, policies</b> — empty: a new game inherits neither the previous
     *       world's deal records nor its autopilot policies.</li>
     *   <li><b>activeScenario</b> — {@code null}, which is what makes every later save of the run
     *       a sandbox save ({@code GameSession.runtimeFor}); no fired ids yet, so the daily
     *       contracted stream starts from day 1.</li>
     *   <li><b>HarbourMaster</b> — an empty in-progress day, spawn counter 0 (walk-in ids start at
     *       {@code WALKIN-1}) and name-pool offset {@code -1}, the "roll fresh at boot" sentinel:
     *       the HarbourMaster draws the offset from {@code seed}, so two Free play games with
     *       different seeds meet differently named vessels.</li>
     *   <li><b>weather</b> — {@link AgentRoster#defaultWeather()}, the calm reading
     *       {@code startNewGame()} boots under; <b>customs</b> — clearance sequence 1.</li>
     *   <li><b>tugs</b> — the four, IDLE with a full tank and no position (a null position
     *       restores to the tug's base, {@code TugAgent.restoreFrom}).</li>
     *   <li><b>terminals</b> — both, with their four berths FREE. The occupancies are stated
     *       rather than omitted on purpose: each {@code TerminalAgent} pushes what it restores to
     *       the session-stable {@code PortStateArtifact}, which has no clear — so these four
     *       FREE records are what erases the replaced world's bookings.</li>
     *   <li><b>vessels</b> — none.</li>
     * </ul>
     *
     * @param seed                  the game's master seed — recorded here, hence in every save of
     *                              the run; any {@code long} is valid, {@code 0} included
     * @param realSecondsPerGameDay the ×1 pace ({@code BootstrapConfig.realSecondsPerGameDay()},
     *                              i.e. the default or an explicit {@code --day-length})
     * @param savedAt               ISO-8601 wall instant (the audit field)
     */
    public static GameState freshSandbox(long seed, long realSecondsPerGameDay, String savedAt) {
        List<TugStateDTO> tugs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            tugs.add(new TugStateDTO("tug_" + i, "IDLE", null, 1.0, null, null));
        }
        // The berth-to-terminal split is AgentRoster's (terminal_container manages berth_2,
        // terminal_general the other three) — the same one fromScenario derives from the berth id.
        List<TerminalStateDTO> terminals = List.of(
                new TerminalStateDTO("terminal_container", List.of(BerthOccupancy.free("berth_2"))),
                new TerminalStateDTO("terminal_general", List.of(
                        BerthOccupancy.free("berth_1"),
                        BerthOccupancy.free("berth_3"),
                        BerthOccupancy.free("berth_4"))));
        return new GameState(
                SaveLoadManager.SCHEMA_VERSION,
                savedAt,
                seed,
                Settings.classpathDefaults().withRealSecondsPerGameDay(realSecondsPerGameDay),
                new GameState.SimClockState(0L, realSecondsPerGameDay),
                AgentRoster.STARTING_WALLET,
                (int) Math.round(AgentRoster.STARTING_REPUTATION),
                0, 0,
                List.of(), List.of(),
                null,
                List.of(),
                new GameState.AgentsState(
                        new it.unige.portcommand.persistence.dto.HarbourMasterStateDTO(
                                List.of(), List.of(), new java.util.LinkedHashMap<>(),
                                new java.util.LinkedHashMap<>(), 0, -1),
                        new it.unige.portcommand.persistence.dto.WeatherStateDTO(
                                AgentRoster.defaultWeather()),
                        new it.unige.portcommand.persistence.dto.CustomsStateDTO(1),
                        List.copyOf(tugs), terminals, List.of()));
    }

    /**
     * Load-side reconciliation (task 22): berth occupancy that references a vessel absent
     * from the restored set (dropped negotiation, grant-limbo drop) loads as FREE. Keeps
     * "a berth is never leaked to a ghost" true by construction.
     */
    /**
     * Load-side reconciliation (S16, ADR-20): a ship that is WAITING for its escort keeps in
     * {@code assignedTugs} only the tugs that really hold a job for it in this same save.
     *
     * <p>The HarbourMaster lists a tug the moment it awards it; the tug takes the job when the
     * ACCEPT reaches it. A save between the two carries a ship "with" a tug that is saved idle,
     * and since S16 a ship waits for every assigned tug to report on station - that one never
     * would. Dropping it here makes the ship short again, so the restore sweep tenders for it.
     * (The lost award's charge stays in the ledger: a cancelled tug job is not refunded.) Ships
     * under way or moored are left alone - their list is history, not a condition.
     */
    public static List<VesselStateDTO> reconcileEscorts(GameState state) {
        java.util.Map<String, Set<String>> tugsWithAJobFor = new java.util.HashMap<>();
        for (it.unige.portcommand.persistence.dto.TugStateDTO tug : state.agents().tugs()) {
            if (tug.job() != null && tug.job().vesselId() != null) {
                tugsWithAJobFor.computeIfAbsent(tug.job().vesselId(), k -> new HashSet<>()).add(tug.tugId());
            }
        }
        List<VesselStateDTO> out = new ArrayList<>();
        for (VesselStateDTO vessel : state.agents().activeVessels()) {
            if (!Stage.AWAITING_TUG.name().equals(vessel.stage()) || vessel.assignedTugs().isEmpty()) {
                out.add(vessel);
                continue;
            }
            Set<String> real = tugsWithAJobFor.getOrDefault(vessel.id(), Set.of());
            List<String> kept = vessel.assignedTugs().stream().filter(real::contains).toList();
            if (kept.size() != vessel.assignedTugs().size()) {
                log.warn("load: {} lists tug(s) {} but only {} hold a job for it in this save - "
                        + "the rest are dropped and tendered for again", vessel.id(),
                        vessel.assignedTugs(), kept);
            }
            out.add(kept.size() == vessel.assignedTugs().size() ? vessel : vessel.withAssignedTugs(kept));
        }
        return out;
    }

    public static List<TerminalStateDTO> reconcileTerminals(GameState state) {
        Set<String> restoredIds = new HashSet<>();
        for (VesselStateDTO v : state.agents().activeVessels()) {
            restoredIds.add(v.id());
        }
        List<TerminalStateDTO> out = new ArrayList<>();
        for (TerminalStateDTO terminal : state.agents().terminals()) {
            List<BerthOccupancy> reconciled = terminal.occupancies().stream()
                    .map(o -> o.vesselId() == null || restoredIds.contains(o.vesselId())
                            ? o
                            : BerthOccupancy.free(o.berthId()))
                    .toList();
            out.add(new TerminalStateDTO(terminal.terminalId(), reconciled));
        }
        return out;
    }
}
