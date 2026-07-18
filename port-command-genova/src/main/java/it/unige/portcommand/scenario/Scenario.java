package it.unige.portcommand.scenario;

import java.util.List;

import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.scenario.events.ScriptedEvent;

/**
 * One scripted starting condition (task 23, PROJECT_DEFINITION §7.2): initial-state
 * overrides plus a timeline of {@link ScriptedEvent}s. Bound from
 * {@code data/scenarios/<key>.json} by {@link ScenarioLoader}; exactly 3 ship in v1
 * (tutorial, busy_day, storm — hard constraint, no freeform).
 *
 * <p><b>Deviations from the planning/23 §23.1 sketch (dated 2026-07-18):</b>
 * {@code settingsOverride} is a small {@link SettingsOverride} record rather than a
 * partial {@code core.Settings} — Settings' components are primitives with validating
 * ranges, so "null fields keep defaults" cannot bind onto it; {@code contracts} is new —
 * scenario-local {@link ServiceContract}s (registered into the HarbourMaster's map at
 * boot) so a scripted contracted spawn like the tutorial's {@code C-501} does not have
 * to ship in the global {@code contracts.json} (whose entries recur DAILY via
 * {@link ContractSchedule}; scenario-locals are day-1 script props and never recur).
 *
 * @param key                classpath key and save-file marker ({@code GameState.activeScenario})
 * @param displayName        shown by {@code NewGameDialog}
 * @param description        shown by {@code NewGameDialog}
 * @param targetDailyRevenue advisory figure for the NewGameDialog tooltip (this task's
 *                           values; §7.2 names no numbers)
 * @param randomSeed         nullable: null → generate a fresh seed at scenario start and
 *                           record it in the built {@code GameState}. 0 is a VALID pinned
 *                           seed, never a sentinel (hard constraint).
 * @param settingsOverride   nullable partial override of {@code Settings.load()}
 * @param initialState       day/time/wallet/reputation/weather/berths/tugs
 * @param contracts          scenario-local contracts (nullable → empty)
 * @param events             the scripted timeline, sorted by {@code simTimeSeconds}
 */
public record Scenario(
        String key,
        String displayName,
        String description,
        double targetDailyRevenue,
        Long randomSeed,
        SettingsOverride settingsOverride,
        InitialState initialState,
        List<ServiceContract> contracts,
        List<ScriptedEvent> events) {

    public Scenario {
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
        events = events == null ? List.of() : List.copyOf(events);
    }

    /**
     * The only Settings knobs a scenario may override; null fields keep
     * {@code Settings.load()} values. {@code realSecondsPerGameDay} exists because the
     * §3.15 sim-second timelines predate task 24's live clock — at the sandbox default
     * (300 s/day ≈ 288 sim-s per wall-s) a whole scripted wave would flash by in
     * seconds, so each scenario pins a humane pacing (reconciliation dated 2026-07-18
     * in planning/23). An explicit {@code --day-length} still wins (player intent).
     */
    public record SettingsOverride(String difficulty, Boolean autopilotEnabled,
                                   Long realSecondsPerGameDay) {
    }

    /**
     * The overridden opening state. Berth/tug lists are exactly 4 entries each
     * (validated); ids are the numeric 1..4 shorthand from the §3.15 sketch, mapped to
     * {@code berth_N}/{@code tug_N} by the loader.
     */
    public record InitialState(
            int startDay,
            String startTimeOfDay,      // "HH:MM"
            double wallet,
            int reputation,
            WeatherInit weatherOverride,
            List<BerthInit> berths,
            List<TugInit> tugs) {

        /**
         * Absolute sim-millis this scenario's clock starts at — the SAME formula
         * {@code GameStateBuilder.fromScenario} uses to set the initial {@code SimClock}
         * (single-sourced here, 2026-07-18 timing-bug fix). Deliberately recomputed, not
         * persisted: it is a pure function of the scenario's own declared day/time, so it
         * is identical across every boot AND every reload of the same scenario — exactly
         * the fixed reference point {@code ScriptedEventBehaviour} needs to translate a
         * scripted event's "sim-seconds since scenario start" into the engine's absolute
         * sim-clock seconds.
         */
        public long startSimMillis() {
            return (startDay() - 1L) * 86_400_000L
                    + Long.parseLong(startTimeOfDay().substring(0, 2)) * 3_600_000L
                    + Long.parseLong(startTimeOfDay().substring(3, 5)) * 60_000L;
        }
    }

    /**
     * Opening weather, in the canonical task-09 vocabulary ({@code WeatherSnapshot}):
     * category visibility {@code good|fair|poor}, Markov state {@code sunny|cloudy|stormy}.
     * (The §23.1 sketch's {@code visibilityNm} double predates that vocabulary — dated
     * reconciliation, 2026-07-18.)
     */
    public record WeatherInit(int windKn, String visibility, double swell, String state) {
    }

    /**
     * One berth's opening occupancy. {@code occupied=true} requires a {@code vessel}
     * block — the loader materialises it as a DOCKED active vessel (walk-in channel,
     * deal already closed pre-scenario, so no fee is re-earned) plus the matching
     * terminal occupancy; otherwise the berth loads FREE.
     */
    public record BerthInit(int berthId, boolean occupied, DockedVessel vessel) {
    }

    /** The pre-existing docked vessel occupying a berth at scenario start. */
    public record DockedVessel(String vesselId, String vesselType, double dealPrice, int dealHours) {
    }

    /**
     * One tug's opening state. {@code status} vocabulary is the §3.15 sketch's
     * {@code AVAILABLE|REFUELING}; the loader maps AVAILABLE → the engine's IDLE.
     */
    public record TugInit(int tugId, String status, double fuel) {
    }
}
