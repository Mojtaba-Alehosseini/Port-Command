package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.util.SimClock;

/**
 * Pure rendering model for {@code PortPanel} (S15, 2026-10-12) — the Port table that replaced the
 * map: four berth lines, then one line each for the berth queue, the tugs and the weather. No
 * Swing/AWT import and no thread assertion; EDT discipline is the panel's job, so tests call the
 * mutators straight from the JUnit thread (the same split as {@code HUDModel}).
 *
 * <p><b>Where each line comes from.</b> Berths and queue are the HarbourMaster's
 * {@link PortStatusEvent} — the only source that knows walk-ins and the queue (the
 * {@code PortStateArtifact} the map read sees contracted vessels only). Tugs are the artifact's
 * statuses (snapshot at construction and on a world change, deltas in between). Weather is the
 * last {@link WeatherChangeEvent}.
 *
 * <p><b>Unknown is not "free".</b> Until a status has arrived — no world yet, or a world was just
 * replaced — every berth and the queue read {@value #UNKNOWN}. Showing "free" there would be a
 * claim about a port nobody has reported on.
 *
 * <p><b>Every mutator fires the change listener only when something really changed.</b> The tugs
 * republish their status on every transit tick, and a re-announced status is by design often equal
 * to the one already shown; neither may cost a re-render.
 *
 * <p>All numbers are formatted with {@link Locale#ROOT}: the table must read the same on a German
 * or a Thai desktop as in CI.
 */
public final class PortTableModel {

    /** Shown wherever nothing is known yet. */
    public static final String UNKNOWN = "—";
    /** The occupant cell of a berth nobody holds. */
    public static final String FREE = "free";
    /** Joins the parts of one line: {@code 3 free · 1 busy}. */
    public static final String SEPARATOR = " · ";

    public static final String QUEUE_LABEL = "Queue";
    public static final String TUGS_LABEL = "Tugs";
    public static final String WEATHER_LABEL = "Weather";

    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    /**
     * One berth as the table shows it.
     *
     * @param berthId  the raw id ({@code berth_1})
     * @param label    {@code Berth 1}
     * @param occupant the vessel's name, {@value PortTableModel#FREE}, or {@value PortTableModel#UNKNOWN}
     * @param state    {@code until 14:30} or a state word; empty for a free or unknown berth
     */
    public record BerthLine(String berthId, String label, String occupant, String state) {

        /** Nothing has been reported about this berth yet. */
        public boolean isUnknown() {
            return UNKNOWN.equals(occupant);
        }

        /** The whole line in one string — what a tooltip shows when the column is too narrow. */
        public String fullText() {
            return state.isEmpty() ? label + SEPARATOR + occupant : label + SEPARATOR + occupant + SEPARATOR + state;
        }
    }

    /**
     * One of the three lines under the berths.
     *
     * @param label  {@code Queue}, {@code Tugs} or {@code Weather}
     * @param value  the headline ({@code 2 waiting}), or {@value PortTableModel#UNKNOWN}
     * @param detail what backs the headline (the queued vessels' names); empty when there is none
     */
    public record InfoLine(String label, String value, String detail) {

        /** Nothing has been reported for this line yet. */
        public boolean isUnknown() {
            return UNKNOWN.equals(value);
        }

        /** What the value cell shows: the headline, then the detail when there is one. */
        public String text() {
            return detail.isEmpty() ? value : value + SEPARATOR + detail;
        }

        /** The whole line in one string — what a tooltip shows when the column is too narrow. */
        public String fullText() {
            return label + SEPARATOR + text();
        }
    }

    /** The port's berth ids in id order — the table's fixed four lines. */
    private final List<String> berthIds;
    /** Sorted by id only so two models fed the same deltas compare equal; the line shows counts. */
    private final Map<String, TugStatus> tugs = new TreeMap<>();
    /** {@code null} until the HarbourMaster has reported, and again after {@link #clearStatus()}. */
    private PortStatusEvent status;
    private WeatherChangeEvent weather;
    private Runnable onChange = () -> { };

    public PortTableModel() {
        // BerthPositions is the single canonical berth list (the HarbourMaster's snapshot builder
        // sorts the same keys), but it is an unordered Map.of — hence the sort.
        this.berthIds = BerthPositions.all().keySet().stream().sorted().toList();
    }

    /** Replaces the change listener (default a no-op). Runs after every mutation that changed something. */
    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    // --- mutators --------------------------------------------------------------------------------

    /**
     * Takes the HarbourMaster's latest snapshot. An equal one fires no change: the HarbourMaster
     * re-announces on request (a window that opened late, a world that was just replaced), and
     * record equality is the event's own "did anything change" test.
     */
    public void applyStatus(PortStatusEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.equals(status)) {
            return;
        }
        status = event;
        onChange.run();
    }

    /**
     * One tug's status. A {@code null} id or status is ignored, and so is a repeat of the status
     * already held — a moving tug republishes on every transit tick.
     */
    public void applyTug(String tugId, TugStatus tugStatus) {
        if (tugId == null || tugStatus == null) {
            return;
        }
        if (tugStatus == tugs.put(tugId, tugStatus)) {
            return;
        }
        onChange.run();
    }

    /**
     * REPLACES the fleet with a snapshot ({@code PortStateArtifact.tugSnapshot()}): at
     * construction, because a tug's first status push happens during fleet spawn, before any
     * window subscribes; and on a world change, so a tug of the old world cannot linger.
     */
    public void seedTugs(Map<String, TugStatus> snapshot) {
        Map<String, TugStatus> seeded = new TreeMap<>();
        if (snapshot != null) {
            snapshot.forEach((tugId, tugStatus) -> {
                if (tugId != null && tugStatus != null) {
                    seeded.put(tugId, tugStatus);
                }
            });
        }
        if (seeded.equals(tugs)) {
            return;
        }
        tugs.clear();
        tugs.putAll(seeded);
        onChange.run();
    }

    public void applyWeather(WeatherChangeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.equals(weather)) {
            return;
        }
        weather = event;
        onChange.run();
    }

    /**
     * Forgets the berth and queue status — the world it described is gone. Tugs and weather are
     * kept: the tugs are reseeded by the panel from the artifact, and the weather line keeps the
     * last reading until the new world's weather agent reports (as the map's chip did).
     */
    public void clearStatus() {
        if (status == null) {
            return;
        }
        status = null;
        onChange.run();
    }

    // --- readers ---------------------------------------------------------------------------------

    /** Always the port's four berths, {@code berth_1} to {@code berth_4}, whatever is known. */
    public List<BerthLine> berthRows() {
        List<BerthLine> lines = new ArrayList<>(berthIds.size());
        for (String berthId : berthIds) {
            lines.add(berthLine(berthId));
        }
        return List.copyOf(lines);
    }

    public InfoLine queueRow() {
        if (status == null) {
            return new InfoLine(QUEUE_LABEL, UNKNOWN, "");
        }
        return new InfoLine(QUEUE_LABEL, queueText(status.queuedCount()), String.join(", ", status.queued()));
    }

    public InfoLine tugRow() {
        return new InfoLine(TUGS_LABEL, tugSummary(tugs.values()), "");
    }

    public InfoLine weatherRow() {
        return new InfoLine(WEATHER_LABEL, weatherText(weather), "");
    }

    private BerthLine berthLine(String berthId) {
        String label = berthLabel(berthId);
        PortStatusEvent.BerthRow row = status == null ? null : rowFor(berthId);
        if (row == null) {
            // No status at all, or one that says nothing about this berth: unknown, never "free".
            return new BerthLine(berthId, label, UNKNOWN, "");
        }
        if (row.isFree()) {
            return new BerthLine(berthId, label, FREE, "");
        }
        return new BerthLine(berthId, label, occupantText(row), stateText(row, status.gameDay()));
    }

    private PortStatusEvent.BerthRow rowFor(String berthId) {
        for (PortStatusEvent.BerthRow row : status.berths()) {
            if (berthId.equals(row.berthId())) {
                return row;
            }
        }
        return null;
    }

    /** The event already falls back to the id for a nameless (contracted) vessel; this only guards a blank. */
    private static String occupantText(PortStatusEvent.BerthRow row) {
        String name = row.vesselName();
        return name == null || name.isBlank() ? row.vesselId() : name;
    }

    // --- texts -----------------------------------------------------------------------------------

    /** {@code berth_1} becomes {@code Berth 1}. */
    public static String berthLabel(String berthId) {
        if (berthId == null || berthId.isBlank()) {
            return UNKNOWN;
        }
        String spaced = berthId.trim().replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    /**
     * {@code until 14:30}, or {@code until D2 02:15} when that instant falls on another game day
     * than {@code gameDay}. Minutes are floored, as the HUD clock's are.
     */
    public static String untilText(long untilSimMillis, int gameDay) {
        long until = Math.max(0L, untilSimMillis);
        int day = SimClock.gameDayOf(until);
        long hour = (until / MILLIS_PER_HOUR) % 24;
        long minute = (until / MILLIS_PER_MINUTE) % 60;
        String clock = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        return day == gameDay ? "until " + clock : "until D" + day + " " + clock;
    }

    /**
     * What the state cell says about the vessel holding a berth; empty for a free berth.
     *
     * <p>A docked vessel shows until when it stays. Before it has docked there is no such time —
     * deliberately: a weather hold slides the transit deadline every tick, and a word does not
     * flicker — so the cell names the stage instead, and a weather hold overrides that word.
     */
    public static String stateText(PortStatusEvent.BerthRow row, int gameDay) {
        if (row == null || row.isFree()) {
            return "";
        }
        if (row.untilSimMillis() != null) {
            return untilText(row.untilSimMillis(), gameDay);
        }
        if (row.weatherHeld()) {
            return "weather hold";
        }
        VesselTracking.Stage stage = row.stage();
        if (stage == null) {
            return "arriving";
        }
        return switch (stage) {
            case DEPARTING -> "leaving";
            case DOCKED -> "docked";
            case AWAITING_TUG -> "awaiting tugs";
            default -> "arriving";
        };
    }

    /** {@code none}, {@code 1 waiting}, {@code 2 waiting}. */
    public static String queueText(int queuedCount) {
        return queuedCount <= 0 ? "none" : queuedCount + " waiting";
    }

    /**
     * {@code 3 free · 1 busy}, or {@value #UNKNOWN} when no tug has reported. IDLE and BIDDING
     * both count as free: a tug bids for a second or two in every Contract Net round and is still
     * at its base, so counting it busy would make the line flicker on each CFP.
     */
    public static String tugSummary(Collection<TugStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return UNKNOWN;
        }
        int free = 0;
        int busy = 0;
        for (TugStatus tugStatus : statuses) {
            if (tugStatus == TugStatus.IDLE || tugStatus == TugStatus.BIDDING) {
                free++;
            } else {
                busy++;
            }
        }
        return free + " free" + SEPARATOR + busy + " busy";
    }

    /**
     * {@code 18 kn · sunny · visibility good}, from the event's own fields (the weather agent's
     * vocabulary, unchanged), or {@value #UNKNOWN} before the first reading.
     */
    public static String weatherText(WeatherChangeEvent weather) {
        if (weather == null) {
            return UNKNOWN;
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.0f kn", weather.windKnots()));
        if (weather.state() != null && !weather.state().isBlank()) {
            text.append(SEPARATOR).append(weather.state().trim());
        }
        if (weather.visibility() != null && !weather.visibility().isBlank()) {
            text.append(SEPARATOR).append("visibility ").append(weather.visibility().trim());
        }
        return text.toString();
    }
}
