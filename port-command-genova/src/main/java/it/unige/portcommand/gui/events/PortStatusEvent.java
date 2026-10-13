package it.unige.portcommand.gui.events;

import java.util.List;

import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.util.Event;

/**
 * Who holds which berth, and who is waiting for one (S15): the snapshot the Port table shows.
 *
 * <p>Published by the HarbourMaster from its existing once-a-sim-minute tick
 * ({@code SimClockTickPublisherBehaviour}), built by the pure
 * {@code harbourmaster.PortStatusSnapshot} — and only when it differs from the last one
 * published, so a quiet port is a silent bus. It is READ-ONLY reporting: nothing decides
 * anything from it. It exists because the {@code PortStateArtifact} the map used to read knows
 * contracted vessels only (a berth held by a walk-in reads free there) and no queue at all.
 *
 * <p>Deliberately carries no timestamp: record equality IS the "did anything change" test.
 * {@code gameDay} is part of that key so midnight forces one republish, and so the GUI can
 * format an "until" time against the current day without tracking the clock itself.
 *
 * @param gameDay the 1-based game day the snapshot was taken on
 * @param berths  always the port's four berths, in berth-id order
 * @param queued  display names of the vessels waiting for a berth, in arrival order
 */
public record PortStatusEvent(int gameDay, List<BerthRow> berths, List<String> queued) implements Event {

    /**
     * One berth. A free berth has every vessel field {@code null}.
     *
     * @param vesselName     the occupant's display name (its id when it has none)
     * @param stage          the HarbourMaster's tracking stage — AWAITING_TUG … DEPARTING for a holder
     * @param untilSimMillis when a DOCKED vessel's service ends, in sim millis; {@code null} until
     *                       it has docked (and for a free berth)
     * @param weatherHeld    the vessel's approach is on a weather hold
     */
    public record BerthRow(String berthId, String vesselId, String vesselName,
                           VesselTracking.Channel channel, VesselTracking.Stage stage,
                           Long untilSimMillis, boolean weatherHeld) {

        public static BerthRow free(String berthId) {
            return new BerthRow(berthId, null, null, null, null, null, false);
        }

        public boolean isFree() {
            return vesselId == null;
        }
    }

    public PortStatusEvent {
        berths = berths == null ? List.of() : List.copyOf(berths);
        queued = queued == null ? List.of() : List.copyOf(queued);
    }

    public int queuedCount() {
        return queued.size();
    }
}
