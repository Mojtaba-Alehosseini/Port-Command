package it.unige.portcommand.scenario;

import java.util.Set;

/**
 * The scenario-engine bundle a boot threads into the HarbourMaster's spawn args
 * (found by type, task-22 pattern). Present on EVERY production boot — fresh sandbox,
 * scenario start, and load — because the daily contracted stream
 * ({@link ContractSchedule}) runs in sandbox play too; only bare stub harnesses omit
 * it (and get no {@link ScriptedEventBehaviour}).
 *
 * @param scenario      the active scenario, or null for sandbox play
 * @param firedEventIds already-fired scripted-event ids from the save being restored
 *                      (empty on any fresh boot) — the replay guard
 * @param restoredVesselTraces every vessel id the restored save can VOUCH for: the
 *                      restored active vessels plus the in-progress day's income/expense
 *                      vessel ids. A fired contracted-spawn id whose vessel left NO trace
 *                      was caught in its spawned-but-not-yet-granted window by the save —
 *                      the engine un-fires it so the arrival (and its fee) is recovered
 *                      instead of silently lost (2026-07-18 adversarial-review finding 1)
 */
public record ScenarioRuntime(Scenario scenario, Set<String> firedEventIds,
                              Set<String> restoredVesselTraces) {

    public ScenarioRuntime {
        firedEventIds = firedEventIds == null ? Set.of() : Set.copyOf(firedEventIds);
        restoredVesselTraces = restoredVesselTraces == null ? Set.of()
                : Set.copyOf(restoredVesselTraces);
    }

    /** Fresh-boot / test convenience: no restored traces (nothing to vouch for). */
    public ScenarioRuntime(Scenario scenario, Set<String> firedEventIds) {
        this(scenario, firedEventIds, Set.of());
    }

    /** Sandbox boot: no scenario, dailies from day 1, replay guard from the save (or empty). */
    public static ScenarioRuntime sandbox(Set<String> firedEventIds) {
        return new ScenarioRuntime(null, firedEventIds, Set.of());
    }

    /** Sandbox load: replay guard + vessel traces from the save. */
    public static ScenarioRuntime sandbox(Set<String> firedEventIds, Set<String> restoredVesselTraces) {
        return new ScenarioRuntime(null, firedEventIds, restoredVesselTraces);
    }
}
