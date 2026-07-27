package it.unige.portcommand.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.scenario.Scenario.BerthInit;
import it.unige.portcommand.scenario.Scenario.TugInit;
import it.unige.portcommand.scenario.events.BerthFloodEvent;
import it.unige.portcommand.scenario.events.NotificationScriptedEvent;
import it.unige.portcommand.scenario.events.ScriptedEvent;
import it.unige.portcommand.scenario.events.SpawnVesselEvent;
import it.unige.portcommand.scenario.events.TugRefuelCompleteEvent;
import it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent;
import it.unige.portcommand.scenario.events.WeatherChangeScriptedEvent;

/**
 * Reads and validates one scenario JSON (task 23). Parsing is strict Jackson (unknown
 * fields FAIL — a scenario file is authored by hand and a typo must not silently
 * vanish); the semantic invariants below are hand-coded per planning/23 §23.2, with
 * {@code data/scenarios/_schema.json} as human documentation only.
 *
 * <p>One parses, {@code ScriptedEventBehaviour} executes — this class never touches
 * the live world.
 */
public final class ScenarioLoader {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern TIME_OF_DAY = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Set<String> VISIBILITIES = Set.of("good", "fair", "poor");
    private static final Set<String> WEATHER_STATES = Set.of("sunny", "cloudy", "stormy");
    private static final Set<String> TUG_STATUSES = Set.of("AVAILABLE", "REFUELING");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "EMERGENCY");
    /** The four berths the port has ({@code _schema.json}'s berth_id enum). Task 26: BerthFlood. */
    private static final Set<String> BERTH_IDS = Set.of("berth_1", "berth_2", "berth_3", "berth_4");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "NORMAL", "HARD");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** Loads and validates a scenario from a file (tests, ad-hoc keys). */
    public Scenario load(Path file) throws IOException, ScenarioValidationException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, file.toString());
        }
    }

    /** Loads and validates a scenario from a stream ({@code ScenarioRegistry}'s classpath path). */
    public Scenario load(InputStream in, String sourceLabel)
            throws IOException, ScenarioValidationException {
        Scenario scenario;
        try {
            scenario = mapper.readValue(in, Scenario.class);
        } catch (IOException e) {
            throw new ScenarioValidationException(
                    "scenario " + sourceLabel + " failed to parse: " + e.getMessage());
        }
        validate(scenario);
        return scenario;
    }

    /** The shared mapper — the round-trip test serialises through the same modules. */
    public ObjectMapper mapper() {
        return mapper;
    }

    private void validate(Scenario s) throws ScenarioValidationException {
        require(s.key() != null && KEY.matcher(s.key()).matches(), "key must match [a-z][a-z0-9_]*");
        require(notBlank(s.displayName()), "displayName is required");
        require(notBlank(s.description()), "description is required");
        require(s.targetDailyRevenue() > 0, "targetDailyRevenue must be > 0");
        validateSettingsOverride(s);
        validateInitialState(s);
        Set<String> contractIds = validateContracts(s);
        validateEvents(s, contractIds);
    }

    private void validateSettingsOverride(Scenario s) throws ScenarioValidationException {
        if (s.settingsOverride() == null) {
            return;
        }
        String difficulty = s.settingsOverride().difficulty();
        require(difficulty == null || DIFFICULTIES.contains(difficulty),
                "settingsOverride.difficulty must be EASY/NORMAL/HARD, got " + difficulty);
        Long rate = s.settingsOverride().realSecondsPerGameDay();
        require(rate == null || rate > 0, "settingsOverride.realSecondsPerGameDay must be > 0");
    }

    private void validateInitialState(Scenario s) throws ScenarioValidationException {
        Scenario.InitialState init = s.initialState();
        require(init != null, "initialState is required");
        require(init.startDay() >= 1, "initialState.startDay must be >= 1");
        require(init.startTimeOfDay() != null && TIME_OF_DAY.matcher(init.startTimeOfDay()).matches(),
                "initialState.startTimeOfDay must be HH:MM, got " + init.startTimeOfDay());
        require(init.wallet() >= 0, "initialState.wallet must be >= 0");
        require(init.reputation() >= 0 && init.reputation() <= 100,
                "initialState.reputation must be in 0..100");
        validateWeather(init.weatherOverride(), "initialState.weatherOverride");

        require(init.berths() != null && init.berths().size() == 4, "exactly 4 berths required");
        Set<Integer> berthIds = new HashSet<>();
        for (BerthInit berth : init.berths()) {
            require(berth.berthId() >= 1 && berth.berthId() <= 4,
                    "berthId must be in 1..4, got " + berth.berthId());
            require(berthIds.add(berth.berthId()), "duplicate berthId " + berth.berthId());
            if (berth.occupied()) {
                Scenario.DockedVessel v = berth.vessel();
                require(v != null, "occupied berth " + berth.berthId() + " needs a vessel block");
                require(notBlank(v.vesselId()), "docked vessel at berth " + berth.berthId() + " needs a vesselId");
                require(VesselSpec.knownVesselTypes().contains(v.vesselType()),
                        "unknown vessel type '" + v.vesselType() + "' at berth " + berth.berthId());
                require(v.dealPrice() > 0, "docked vessel " + v.vesselId() + " needs dealPrice > 0");
                require(v.dealHours() >= 1 && v.dealHours() <= 24,
                        "docked vessel " + v.vesselId() + " dealHours must be 1..24");
            } else {
                require(berth.vessel() == null,
                        "free berth " + berth.berthId() + " must not carry a vessel block");
            }
        }

        require(init.tugs() != null && init.tugs().size() == 4, "exactly 4 tugs required");
        Set<Integer> tugIds = new HashSet<>();
        for (TugInit tug : init.tugs()) {
            require(tug.tugId() >= 1 && tug.tugId() <= 4, "tugId must be in 1..4, got " + tug.tugId());
            require(tugIds.add(tug.tugId()), "duplicate tugId " + tug.tugId());
            require(TUG_STATUSES.contains(tug.status()),
                    "tug " + tug.tugId() + " status must be AVAILABLE|REFUELING, got " + tug.status());
            require(tug.fuel() >= 0.0 && tug.fuel() <= 1.0, "tug " + tug.tugId() + " fuel must be 0..1");
        }
    }

    private void validateWeather(Scenario.WeatherInit weather, String where)
            throws ScenarioValidationException {
        require(weather != null, where + " is required");
        require(weather.windKn() >= 0, where + ".windKn must be >= 0");
        require(VISIBILITIES.contains(weather.visibility()),
                where + ".visibility must be good|fair|poor, got " + weather.visibility());
        require(weather.swell() >= 0, where + ".swell must be >= 0");
        require(WEATHER_STATES.contains(weather.state()),
                where + ".state must be sunny|cloudy|stormy, got " + weather.state());
    }

    /** @return every contract id resolvable by a contracted spawn (scenario-local ∪ global). */
    private Set<String> validateContracts(Scenario s) throws ScenarioValidationException {
        Map<String, ServiceContract> global = AgentRoster.loadContracts();
        Set<String> ids = new HashSet<>(global.keySet());
        for (ServiceContract contract : s.contracts()) {
            require(!global.containsKey(contract.contractId()),
                    "scenario contract " + contract.contractId() + " shadows a global contracts.json entry");
            require(ids.add(contract.contractId()), "duplicate contract id " + contract.contractId());
        }
        return ids;
    }

    private void validateEvents(Scenario s, Set<String> contractIds) throws ScenarioValidationException {
        require(s.events() != null, "events list is required (may be empty)");
        long previous = Long.MIN_VALUE;
        Set<String> walkInIds = new HashSet<>();
        for (int i = 0; i < s.events().size(); i++) {
            ScriptedEvent event = s.events().get(i);
            String at = "events[" + i + "]";
            require(event.simTimeSeconds() >= 0, at + ".simTimeSeconds must be >= 0");
            require(event.simTimeSeconds() >= previous,
                    "events must be sorted by simTimeSeconds (" + at + " is out of order)");
            previous = event.simTimeSeconds();
            switch (event) {
                case SpawnVesselEvent spawn -> validateSpawn(spawn, at, contractIds, walkInIds, s);
                case WeatherChangeScriptedEvent weather -> {
                    require(weather.windKn() >= 0, at + ".windKn must be >= 0");
                    require(VISIBILITIES.contains(weather.visibility()),
                            at + ".visibility must be good|fair|poor");
                    require(weather.swell() >= 0, at + ".swell must be >= 0");
                    require(WEATHER_STATES.contains(weather.state()), at + ".state must be sunny|cloudy|stormy");
                }
                case TugRefuelCompleteEvent refuel ->
                        require(refuel.tugId() >= 1 && refuel.tugId() <= 4, at + ".tugId must be in 1..4");
                case TutorialStepAdvanceEvent step -> {
                    require(step.step() >= 1 && step.step() <= TutorialStepAdvanceEvent.TOTAL_STEPS,
                            at + ".step must be in 1.." + TutorialStepAdvanceEvent.TOTAL_STEPS);
                    require(notBlank(step.text()), at + ".text is required");
                }
                case NotificationScriptedEvent notification -> {
                    require(SEVERITIES.contains(notification.severity()),
                            at + ".severity must be INFO|WARN|EMERGENCY");
                    require(notBlank(notification.text()), at + ".text is required");
                }
                case BerthFloodEvent flood ->
                        // The null test comes FIRST: BERTH_IDS is a Set.of(...), whose contains(null)
                        // throws NPE by contract rather than returning false. _schema.json does not
                        // list berthId as required, so {"type":"BerthFlood","simTimeSeconds":780} is
                        // schema-legal and must produce this message, not a stack trace. (The schema
                        // is documentation only — this method is the sole gate.)
                        require(flood.berthId() != null && BERTH_IDS.contains(flood.berthId()),
                                at + ".berthId must be berth_1|berth_2|berth_3|berth_4, got "
                                        + flood.berthId());
            }
        }
    }

    private void validateSpawn(SpawnVesselEvent spawn, String at, Set<String> contractIds,
                               Set<String> walkInIds, Scenario s) throws ScenarioValidationException {
        require(VesselSpec.knownVesselTypes().contains(spawn.vesselType()),
                at + ".vesselType must be one of the 5 canonical types, got " + spawn.vesselType());
        require(spawn.etaSimMillis() == null,
                at + ".etaSimMillis is reserved for the programmatic daily stream");
        if (SpawnVesselEvent.CHANNEL_CONTRACTED.equals(spawn.channel())) {
            require(spawn.contractRef() != null && contractIds.contains(spawn.contractRef()),
                    at + ".contractRef '" + spawn.contractRef()
                            + "' resolves to no scenario or contracts.json entry");
            require(spawn.vesselId() == null, at + ": contracted spawns take the contract's vessel id");
            require(spawn.personality() == null && spawn.minPrice() == null && spawn.targetPrice() == null,
                    at + ": belief pins are walk-in-only");
            ServiceContract contract = s.contracts().stream()
                    .filter(c -> c.contractId().equals(spawn.contractRef()))
                    .findFirst()
                    .orElseGet(() -> AgentRoster.loadContracts().get(spawn.contractRef()));
            require(contract.vesselType().equals(spawn.vesselType()),
                    at + ".vesselType " + spawn.vesselType() + " contradicts contract "
                            + spawn.contractRef() + "'s " + contract.vesselType());
        } else if (SpawnVesselEvent.CHANNEL_WALK_IN.equals(spawn.channel())) {
            require(notBlank(spawn.vesselId()), at + ": walk-in spawns need a vesselId");
            require(walkInIds.add(spawn.vesselId()), at + ": duplicate walk-in vesselId " + spawn.vesselId());
            require(spawn.contractRef() == null, at + ": walk-ins carry no contractRef");
            if (spawn.personality() != null) {
                try {
                    Personality.valueOf(spawn.personality());
                } catch (IllegalArgumentException e) {
                    throw new ScenarioValidationException(at + ".personality unknown: " + spawn.personality());
                }
            }
            require(spawn.minPrice() == null || spawn.minPrice() > 0, at + ".minPrice must be > 0");
            require(spawn.targetPrice() == null || spawn.targetPrice() > 0, at + ".targetPrice must be > 0");
            require(spawn.minPrice() == null || spawn.targetPrice() == null
                            || spawn.minPrice() < spawn.targetPrice(),
                    at + ": minPrice must be < targetPrice");
        } else {
            throw new ScenarioValidationException(at + ".channel must be contracted|walk_in, got "
                    + spawn.channel());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean condition, String message) throws ScenarioValidationException {
        if (!condition) {
            throw new ScenarioValidationException(message);
        }
    }
}
