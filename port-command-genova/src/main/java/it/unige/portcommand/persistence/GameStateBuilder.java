package it.unige.portcommand.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 * nothing leaks (decision dated 2026-07-17).
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
                v.negotiationStartedAtSimMillis());
    }

    /**
     * Load-side reconciliation (task 22): berth occupancy that references a vessel absent
     * from the restored set (dropped negotiation, grant-limbo drop) loads as FREE. Keeps
     * "a berth is never leaked to a ghost" true by construction.
     */
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
