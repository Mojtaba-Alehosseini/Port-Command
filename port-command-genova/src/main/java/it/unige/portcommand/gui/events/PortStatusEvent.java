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
     * Why a vessel that holds a berth is not moving (S16, ADR-20). A ship waits at the channel
     * entry until every one of these is out of the way, and waits at its berth when it may not
     * leave; the Port table says which. When several apply, the HarbourMaster reports the first
     * in THIS order — the one the player can do least about comes first.
     */
    public enum Wait {
        /** Nothing: moving, docked, or about to be told to proceed. */
        NONE,
        /** Held by the movement gate; {@code ruleIds} names the rules (R15, R16, R17, R19). */
        WEATHER,
        /** Its berth is closed (a flood). */
        BERTH_CLOSED,
        /** Customs has not cleared it; {@code ruleIds} is non-empty while an inspection runs. */
        CUSTOMS,
        /** Its escort is not complete, or has not reached it yet. */
        TUGS,
        /** Served and ready to leave, but the gate will not let it sail; {@code ruleIds} as WEATHER. */
        DEPARTURE_WEATHER
    }

    /**
     * One berth. A free berth has every vessel field {@code null}.
     *
     * @param vesselName     the occupant's display name (its id when it has none)
     * @param stage          the HarbourMaster's tracking stage — AWAITING_TUG … DEPARTING for a holder
     * @param untilSimMillis when a DOCKED vessel's service ends, in sim millis; {@code null} until
     *                       it has docked (and for a free berth)
     * @param waiting        why the holder is not moving ({@link Wait#NONE} for a free berth)
     * @param ruleIds        the rule ids behind {@code waiting}, e.g. {@code [R15, R19]}; empty if none
     * @param closed         the berth itself is closed (S16: a flood) — nobody is granted it until
     *                       it reopens, whether or not a ship is moored there
     */
    public record BerthRow(String berthId, String vesselId, String vesselName,
                           VesselTracking.Channel channel, VesselTracking.Stage stage,
                           Long untilSimMillis, Wait waiting, List<String> ruleIds, boolean closed) {
        public BerthRow {
            waiting = waiting == null ? Wait.NONE : waiting;
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        }

        /** Pre-S16 shape (a weather hold with no rule ids, berth open). Kept for fixtures. */
        public BerthRow(String berthId, String vesselId, String vesselName,
                        VesselTracking.Channel channel, VesselTracking.Stage stage,
                        Long untilSimMillis, boolean weatherHeld) {
            this(berthId, vesselId, vesselName, channel, stage, untilSimMillis,
                    weatherHeld ? Wait.WEATHER : Wait.NONE, List.of(), false);
        }

        public static BerthRow free(String berthId) {
            return new BerthRow(berthId, null, null, null, null, null, Wait.NONE, List.of(), false);
        }

        /** A berth nobody holds that is closed (S16: flood) — shown as "closed (flood)". */
        public static BerthRow closedFree(String berthId) {
            return new BerthRow(berthId, null, null, null, null, null, Wait.NONE, List.of(), true);
        }

        public boolean isFree() {
            return vesselId == null;
        }

        /** True for {@link Wait#WEATHER} — the pre-S16 flag, still what a weather hold means. */
        public boolean weatherHeld() {
            return waiting == Wait.WEATHER;
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
