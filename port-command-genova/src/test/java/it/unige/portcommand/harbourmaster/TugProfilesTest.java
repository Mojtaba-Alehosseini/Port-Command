package it.unige.portcommand.harbourmaster;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import it.unige.portcommand.agents.InitArgs;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.AgentRosterProbe;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19 (playtest 5, PT39): "all 4 tugs always bid about EUR 480 / ETA about 29 min". Each tug has
 * its own fare, fuel cost per km and top speed - and the fleet's averages are what every tug had,
 * so the economy's calibration holds.
 */
class TugProfilesTest {

    private static double average(java.util.function.ToDoubleFunction<TugProfiles.Profile> field) {
        return TugProfiles.all().values().stream().mapToDouble(field).average().orElseThrow();
    }

    @Test
    void everyTugIsItsOwnFromSmallCheapAndSlowToBigDearAndFast() {
        Map<String, TugProfiles.Profile> all = TugProfiles.all();
        assertEquals(Set.of("tug_1", "tug_2", "tug_3", "tug_4"), all.keySet());
        assertEquals(4, all.values().stream().map(TugProfiles.Profile::baseFare).collect(Collectors.toSet()).size(),
                "four fares");
        assertEquals(4, all.values().stream().map(TugProfiles.Profile::topSpeedKnots).collect(Collectors.toSet()).size(),
                "four speeds");
        TugProfiles.Profile small = TugProfiles.of("tug_1");
        TugProfiles.Profile big = TugProfiles.of("tug_4");
        assertTrue(small.baseFare() < big.baseFare() && small.fuelCostPerKm() < big.fuelCostPerKm()
                && small.topSpeedKnots() < big.topSpeedKnots(), "the small one is cheaper and slower");
    }

    @Test
    void theFleetsAveragesAreWhatEveryTugHad() {
        assertEquals(AgentRoster.TUG_BASE_FARE, average(TugProfiles.Profile::baseFare), 1e-9);
        assertEquals(AgentRoster.TUG_FUEL_COST_PER_KM, average(TugProfiles.Profile::fuelCostPerKm), 1e-9);
        assertEquals(AgentRoster.TUG_TOP_SPEED_KNOTS, average(TugProfiles.Profile::topSpeedKnots), 1e-9);
        assertEquals(ExpenseRules.marginalCostParts("tanker", 1).perTug(), average(TugProfiles.Profile::baseFare), 1e-9,
                "the Assistant's cost of a tug is the fleet's average fare, as before");
    }

    @Test
    void eachTugIsSpawnedWithItsOwnProfile() {
        for (String id : TugProfiles.all().keySet()) {
            InitArgs.TugInitArgs args = AgentRosterProbe.tugInitArgs(id);
            TugProfiles.Profile profile = TugProfiles.of(id);
            assertEquals(profile.baseFare(), args.baseFare(), 1e-9, id);
            assertEquals(profile.fuelCostPerKm(), args.fuelCostPerKm(), 1e-9, id);
            assertEquals(profile.topSpeedKnots(), args.topSpeedKnots(), 1e-9, id);
            assertEquals(TugBasePositions.position(id), args.basePosition(), id);
            assertEquals(1.0, args.initialFuel(), 1e-9, id);
        }
    }
}
