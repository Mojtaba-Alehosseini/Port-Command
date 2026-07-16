package it.unige.portcommand.gui.model;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.gui.events.VesselArrivedEvent;
import it.unige.portcommand.gui.events.VesselDepartedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.TugBasePositions;
import it.unige.portcommand.ontology.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapModel} is a plain POJO (no Swing/AWT import) — every test here runs on the JUnit
 * thread with no EDT involvement at all, which is exactly what "headless-testable model" means.
 */
class MapModelTest {

    @Test
    void constructionSeedsAllFourCanonicalBerthsAsFree() {
        MapModel model = new MapModel();

        Set<String> berthIds = model.berths().keySet();
        assertEquals(BerthPositions.all().keySet(), berthIds, "must seed exactly the canonical berth set");
        for (BerthOccupancy occupancy : model.berths().values()) {
            assertEquals(BerthOccupancy.Status.FREE, occupancy.status());
        }
    }

    @Test
    void tugsSeededWithAllFourBasesAtIdleOnConstruction() {
        MapModel model = new MapModel();

        assertEquals(TugBasePositions.all().keySet(), model.tugs().keySet(),
                "all 4 tug home bases must be seeded at boot so idle tugs are visible before any move");
        for (PortStateUpdate.TugDelta delta : model.tugs().values()) {
            assertEquals(TugStatus.IDLE, delta.status());
        }
        assertEquals(TugBasePositions.position("tug_1"), model.tugs().get("tug_1").position(),
                "seeded tug must sit at its canonical base position");
    }

    @Test
    void aDeltaForATugIdNotInTheSeededFleetIsStillAdded() {
        MapModel model = new MapModel();

        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_probe", new Position(300, 300, 0), TugStatus.BIDDING));

        assertEquals(5, model.tugs().size(), "an unseen tug id must be added, not dropped");
        assertEquals(TugStatus.BIDDING, model.tugs().get("tug_probe").status());
    }

    @Test
    void applyBerthDeltaOverwritesThatBerthsOccupancy() {
        MapModel model = new MapModel();
        BerthOccupancy docked = new BerthOccupancy("berth_1", "V001", 1000L, 5000L, 1, BerthOccupancy.Status.DOCKED);

        model.applyBerthDelta(new PortStateUpdate.BerthDelta("berth_1", docked));

        assertEquals(docked, model.berths().get("berth_1"));
        // other 3 berths untouched
        assertEquals(BerthOccupancy.Status.FREE, model.berths().get("berth_2").status());
    }

    @Test
    void applyTugDeltaTwiceForTheSameTugKeepsOnlyTheLatestPositionAndNeverAddsEntries() {
        MapModel model = new MapModel();

        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_1", new Position(10, 10, 0), TugStatus.EN_ROUTE_TO_VESSEL));
        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_1", new Position(50, 60, 90), TugStatus.ESCORTING));

        Map<String, PortStateUpdate.TugDelta> tugs = model.tugs();
        assertEquals(4, tugs.size(), "the seeded fleet stays 4; repeated deltas for one tug overwrite, never accumulate");
        assertEquals(new Position(50, 60, 90), tugs.get("tug_1").position());
        assertEquals(TugStatus.ESCORTING, tugs.get("tug_1").status());
    }

    @Test
    void deltasForDifferentTugsOverwriteTheirSeedsIndependently() {
        MapModel model = new MapModel();

        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_1", new Position(10, 10, 0), TugStatus.EN_ROUTE_TO_VESSEL));
        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_2", new Position(20, 20, 0), TugStatus.BIDDING));

        assertEquals(4, model.tugs().size());
        assertEquals(TugStatus.EN_ROUTE_TO_VESSEL, model.tugs().get("tug_1").status());
        assertEquals(TugStatus.BIDDING, model.tugs().get("tug_2").status());
        // tug_3 / tug_4 untouched -- still at their IDLE seeds
        assertEquals(TugStatus.IDLE, model.tugs().get("tug_3").status());
    }

    @Test
    void weatherStartsEmptyThenReflectsTheLatestEvent() {
        MapModel model = new MapModel();
        assertTrue(model.weather().isEmpty());

        model.applyWeather(new WeatherChangeEvent(18.0, "good", 0.5, "sunny", false));
        assertEquals(18.0, model.weather().orElseThrow().windKnots(), 0.0001);

        model.applyWeather(new WeatherChangeEvent(40.0, "poor", 3.0, "stormy", true));
        assertEquals(40.0, model.weather().orElseThrow().windKnots(), 0.0001, "latest event wins, not accumulated");
    }

    @Test
    void vesselArrivedThenDepartedRemovesTheChip() {
        MapModel model = new MapModel();
        model.applyVesselArrived(new VesselArrivedEvent("V1", "cargo_vessel", "contracted"));
        assertEquals(1, model.arrivedVessels().size());

        model.applyVesselDeparted(new VesselDepartedEvent("V1"));
        assertTrue(model.arrivedVessels().isEmpty());
    }

    @Test
    void duplicateArrivalForTheSameVesselIdDoesNotDuplicateTheChip() {
        MapModel model = new MapModel();
        model.applyVesselArrived(new VesselArrivedEvent("V1", "cargo_vessel", "contracted"));
        model.applyVesselArrived(new VesselArrivedEvent("V1", "cargo_vessel", "contracted"));

        assertEquals(1, model.arrivedVessels().size());
    }

    @Test
    void departingAnUnknownVesselIsANoOpAndDoesNotFireOnChange() {
        MapModel model = new MapModel();
        AtomicInteger changes = new AtomicInteger();
        model.setOnChange(changes::incrementAndGet);

        model.applyVesselDeparted(new VesselDepartedEvent("never-arrived"));

        assertTrue(model.arrivedVessels().isEmpty());
        assertEquals(0, changes.get(), "a departure for a vessel never recorded as arrived must not notify listeners");
    }

    @Test
    void onChangeFiresOnEveryMutatingApplyCall() {
        MapModel model = new MapModel();
        AtomicInteger changes = new AtomicInteger();
        model.setOnChange(changes::incrementAndGet);

        model.applyBerthDelta(new PortStateUpdate.BerthDelta("berth_1", BerthOccupancy.free("berth_1")));
        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_1", new Position(1, 1, 0), TugStatus.IDLE));
        model.applyWeather(new WeatherChangeEvent(10.0, "good", 0.2, "sunny", false));
        model.applyVesselArrived(new VesselArrivedEvent("V1", "ferry", "walk_in"));

        assertEquals(4, changes.get());
    }

    @Test
    void berthsAndTugsSnapshotsAreImmutable() {
        MapModel model = new MapModel();

        assertTrue(isImmutable(() -> model.berths().put("berth_1", BerthOccupancy.free("berth_1"))));
        assertTrue(isImmutable(() -> model.tugs()
                .put("tug_1", new PortStateUpdate.TugDelta("tug_1", new Position(0, 0, 0), TugStatus.IDLE))));
    }

    private static boolean isImmutable(Runnable mutation) {
        try {
            mutation.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }
}
