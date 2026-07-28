package it.unige.portcommand.gui.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.gui.events.VesselArrivedEvent;
import it.unige.portcommand.gui.events.VesselDepartedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.TugBasePositions;

/**
 * Pure rendering model for {@code MapPanel} — no Swing/AWT import, no thread assertion. EDT
 * discipline is the panel's responsibility (it decides when it is safe to call these mutators);
 * this class is a plain POJO so tests call the {@code applyXxx} methods directly from the JUnit
 * thread with no EDT involved at all.
 *
 * <p>Seeds all 4 canonical berths as {@link BerthOccupancy#free} from
 * {@link BerthPositions#all()} at construction, so a freshly-built model always has the full
 * known berth set present even before any terminal has ever published an occupancy update (
 * {@code PortStateArtifact.berthSnapshot()} may be empty at that point). Tugs are likewise seeded
 * at their home bases (from {@link TugBasePositions#all()}, status {@link TugStatus#IDLE}), so all
 * four tugs are visible at the pier from boot: {@code PortStateArtifact} has NO tug-position
 * snapshot ({@code tugSnapshot()} carries status only, verified by reading the artifact) and a
 * tug's one-time initial position broadcast fires during fleet spawn, BEFORE the GUI subscribes —
 * seeding from the static table closes that boot-ordering gap map-side (no agent change). Live
 * {@link PortStateUpdate.TugDelta}s then overwrite the seed by tug id as tugs move.
 */
public final class MapModel {

    private final Map<String, BerthOccupancy> berths = new LinkedHashMap<>();
    private final Map<String, PortStateUpdate.TugDelta> tugs = new LinkedHashMap<>();
    private final Map<String, VesselArrivedEvent> arrivedVessels = new LinkedHashMap<>();
    private WeatherChangeEvent weather;
    private Runnable onChange = () -> { };

    public MapModel() {
        for (String berthId : BerthPositions.all().keySet()) {
            berths.put(berthId, BerthOccupancy.free(berthId));
        }
        TugBasePositions.all().forEach((tugId, base) ->
                tugs.put(tugId, new PortStateUpdate.TugDelta(tugId, base, TugStatus.IDLE)));
    }

    /** Replaces the change listener (default a no-op). Invoked at the end of every mutator. */
    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public void applyBerthDelta(PortStateUpdate.BerthDelta delta) {
        berths.put(delta.berthId(), delta.occupancy());
        onChange.run();
    }

    public void applyTugDelta(PortStateUpdate.TugDelta delta) {
        tugs.put(delta.tugId(), delta);
        onChange.run();
    }

    public void applyWeather(WeatherChangeEvent event) {
        weather = event;
        onChange.run();
    }

    /** Vessels not (yet) resolved to a berth occupant — a lightweight arrival/departure chip list. */
    public void applyVesselArrived(VesselArrivedEvent event) {
        arrivedVessels.put(event.vesselId(), event);
        onChange.run();
    }

    public void applyVesselDeparted(VesselDepartedEvent event) {
        if (arrivedVessels.remove(event.vesselId()) != null) {
            onChange.run();
        }
    }

    /** Immutable snapshot: all 4 canonical berth ids are always present. */
    public Map<String, BerthOccupancy> berths() {
        return Map.copyOf(berths);
    }

    /** Immutable snapshot: only tugs that have published at least one delta are present. */
    public Map<String, PortStateUpdate.TugDelta> tugs() {
        return Map.copyOf(tugs);
    }

    public Optional<WeatherChangeEvent> weather() {
        return Optional.ofNullable(weather);
    }

    /** Insertion order (oldest arrival first). */
    public List<VesselArrivedEvent> arrivedVessels() {
        return List.copyOf(arrivedVessels.values());
    }
}
