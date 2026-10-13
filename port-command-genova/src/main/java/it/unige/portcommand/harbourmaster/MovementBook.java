package it.unige.portcommand.harbourmaster;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the HarbourMaster knows about ships that are waiting to move, beyond what
 * {@link VesselTracking} holds (S16, 2026-10-13, ADR-20).
 *
 * <p>Since S16 a ship moves only when the HarbourMaster tells it to, and it is told only when
 * nothing stands in its way. This is the notebook those decisions are made from:
 *
 * <ul>
 *   <li><b>on station</b> — which of a ship's assigned tugs have reached it, and on which
 *       Contract Net conversation each reported (the escort order goes back on that id, so a
 *       tug cannot mistake an old order for a new one);</li>
 *   <li><b>hold rules</b> — the rule ids of the last unsafe weather verdict for a ship that is
 *       held at the channel entry or kept at its berth, for the Port table;</li>
 *   <li><b>departure requests</b> — ships whose service is over and that have asked to leave;</li>
 *   <li><b>inspections</b> — ships whose customs clearance is an inspection in progress, with the
 *       rules customs cited;</li>
 *   <li><b>escort requests</b> — ships for which a tug Contract Net is in flight, queued, or
 *       waiting on a quick retry, so one ship is never tendered twice at once;</li>
 *   <li><b>resume pending</b> — ships released from a weather hold that have not been told yet
 *       (they hear {@code weather_resume} when their escort is actually hired);</li>
 *   <li><b>closed berths</b> — berths closed by a flood, with the instant each reopens.</li>
 * </ul>
 *
 * <p>Deliberately NOT part of {@code VesselTracking}: none of this is persisted per vessel (a
 * load rebuilds it — tugs on station report again, pending customs requests are re-sent, the
 * boot weather sweep re-derives the holds), and the record's nine fields are constructed in
 * dozens of places. Only the closed berths go into the save, through
 * {@code HarbourMasterStateDTO.closedBerths}.
 *
 * <p>Written on the HarbourMaster's thread only. The maps are concurrent because two readers
 * are not on it: the save snapshot ({@link #closedBerths()}) and tests.
 */
public final class MovementBook {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> onStation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> holdRules = new ConcurrentHashMap<>();
    private final Set<String> departureRequests = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, List<String>> inspections = new ConcurrentHashMap<>();
    private final Set<String> escortRequests = ConcurrentHashMap.newKeySet();
    private final Set<String> resumePending = ConcurrentHashMap.newKeySet();
    private final Set<String> tugWaitNotified = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> closedBerths = new ConcurrentHashMap<>();

    // ---- tugs on station -----------------------------------------------------------------

    /** Records that {@code tugId} reached {@code vesselId}, reporting on {@code conversationId}. */
    public void tugOnStation(String vesselId, String tugId, String conversationId) {
        onStation.computeIfAbsent(vesselId, id -> new ConcurrentHashMap<>())
                .put(tugId, conversationId == null ? "" : conversationId);
    }

    /** tugId → the conversation it reported on, for the tugs on station at {@code vesselId}. */
    public Map<String, String> onStation(String vesselId) {
        Map<String, String> station = onStation.get(vesselId);
        return station == null ? Map.of() : Map.copyOf(station);
    }

    /** Whether every tug in {@code assignedTugs} has reported on station at {@code vesselId}. */
    public boolean allOnStation(String vesselId, List<String> assignedTugs) {
        Map<String, String> station = onStation.get(vesselId);
        if (assignedTugs.isEmpty()) {
            return true;
        }
        return station != null && station.keySet().containsAll(assignedTugs);
    }

    /** The escort was sent off, or recalled: nobody is on station at {@code vesselId} any more. */
    public void clearStation(String vesselId) {
        onStation.remove(vesselId);
    }

    /** One tug left the escort (it declined, or was sent home). */
    public void tugLeft(String vesselId, String tugId) {
        Map<String, String> station = onStation.get(vesselId);
        if (station != null) {
            station.remove(tugId);
        }
    }

    // ---- weather holds -------------------------------------------------------------------

    /** @return {@code true} if this changed what is recorded for the vessel */
    public boolean setHoldRules(String vesselId, List<String> ruleIds) {
        List<String> rules = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        return !rules.equals(holdRules.put(vesselId, rules));
    }

    /** The rule ids of the vessel's current hold, or empty if it has none on record. */
    public List<String> holdRules(String vesselId) {
        return holdRules.getOrDefault(vesselId, List.of());
    }

    public boolean hasHoldRules(String vesselId) {
        return holdRules.containsKey(vesselId);
    }

    public void clearHoldRules(String vesselId) {
        holdRules.remove(vesselId);
    }

    // ---- departures ----------------------------------------------------------------------

    public void departureRequested(String vesselId) {
        departureRequests.add(vesselId);
    }

    public boolean isDepartureRequested(String vesselId) {
        return departureRequests.contains(vesselId);
    }

    public void departureCleared(String vesselId) {
        departureRequests.remove(vesselId);
    }

    /** The vessels waiting at their berth for clearance to leave; a snapshot. */
    public Set<String> departureRequests() {
        return Set.copyOf(departureRequests);
    }

    // ---- customs inspections -------------------------------------------------------------

    public void inspectionStarted(String vesselId, List<String> ruleIds) {
        inspections.put(vesselId, ruleIds == null ? List.of() : List.copyOf(ruleIds));
    }

    public void inspectionOver(String vesselId) {
        inspections.remove(vesselId);
    }

    /** The rules customs cited for the inspection in progress; empty when none is. */
    public List<String> inspectionRules(String vesselId) {
        return inspections.getOrDefault(vesselId, List.of());
    }

    // ---- escort requests -----------------------------------------------------------------

    /** @return {@code true} if no request was open for the vessel (the caller may tender) */
    public boolean openEscortRequest(String vesselId) {
        return escortRequests.add(vesselId);
    }

    public boolean hasEscortRequest(String vesselId) {
        return escortRequests.contains(vesselId);
    }

    public void closeEscortRequest(String vesselId) {
        escortRequests.remove(vesselId);
    }

    /** @return {@code true} the first time it is asked for a vessel since {@link #forget} */
    public boolean firstTugWaitNotice(String vesselId) {
        return tugWaitNotified.add(vesselId);
    }

    // ---- resume --------------------------------------------------------------------------

    public void resumeOwed(String vesselId) {
        resumePending.add(vesselId);
    }

    /** @return {@code true} if a resume was owed (it is no longer) */
    public boolean takeResumeOwed(String vesselId) {
        return resumePending.remove(vesselId);
    }

    // ---- closed berths -------------------------------------------------------------------

    public void closeBerth(String berthId, long reopenAtSimMillis) {
        closedBerths.put(berthId, reopenAtSimMillis);
    }

    /** Whether {@code berthId} is closed at {@code nowSimMillis}. */
    public boolean berthClosed(String berthId, long nowSimMillis) {
        Long reopenAt = berthId == null ? null : closedBerths.get(berthId);
        return reopenAt != null && nowSimMillis < reopenAt;
    }

    /** When {@code berthId} reopens, or {@code null} if it is not on the closed list. */
    public Long reopensAt(String berthId) {
        return closedBerths.get(berthId);
    }

    public void reopenBerth(String berthId) {
        closedBerths.remove(berthId);
    }

    /** berthId → reopening instant, sorted by berth id (the saved form). */
    public Map<String, Long> closedBerths() {
        return new TreeMap<>(closedBerths);
    }

    public void restoreClosedBerths(Map<String, Long> saved) {
        if (saved != null) {
            closedBerths.putAll(saved);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------

    /** The vessel left (departed, withdrew, was turned away): drop everything recorded for it. */
    public void forget(String vesselId) {
        onStation.remove(vesselId);
        holdRules.remove(vesselId);
        departureRequests.remove(vesselId);
        inspections.remove(vesselId);
        escortRequests.remove(vesselId);
        resumePending.remove(vesselId);
        tugWaitNotified.remove(vesselId);
    }
}
