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
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.util.SimClock;

/**
 * Pure rendering model for {@code PortPanel} (S15, 2026-10-12) — the Port table that replaced the
 * map: four berth lines, then one line each for the berth queue and the tugs. No Swing/AWT import
 * and no thread assertion; EDT discipline is the panel's job, so tests call the mutators straight
 * from the JUnit thread (the same split as {@code HUDModel}).
 *
 * <p><b>Where each line comes from.</b> Berths and queue are the HarbourMaster's
 * {@link PortStatusEvent} — the only source that knows walk-ins and the queue (the
 * {@code PortStateArtifact} the map read sees contracted vessels only). Tugs are the artifact's
 * statuses (snapshot at construction and on a world change, deltas in between).
 *
 * <p><b>S16 (2026-10-13): a berth line says WHY a ship is not moving.</b> A granted ship now waits
 * outside until the weather, customs, its escort and its berth all allow the movement, and may be
 * held at its berth when it is ready to leave. The HarbourMaster reports which of these it is
 * ({@code BerthRow.waiting()}, with the rule ids behind it), and the state cell prints it:
 * {@code held (weather) [R15,R19]}, {@code waiting: tugs}, {@code waiting: customs inspection
 * [R22,R24]}, {@code waiting: berth closed (flood)}, {@code departure held (weather) [R19]}; a
 * free berth that is closed reads {@code closed (flood)}. Before S16 the only such word was
 * "weather hold", with no rule behind it. See {@link #stateText}.
 *
 * <p><b>S16: the weather is no longer a line of this table.</b> It moved to the top bar
 * ({@code HudWeatherModel}), where it is on screen whichever column the player is reading.
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
    /** S16: the occupant cell of a berth nobody holds and nobody can be given — it is closed. */
    public static final String CLOSED = "closed (flood)";
    /** Joins the parts of one line: {@code 3 free · 1 busy}. */
    public static final String SEPARATOR = " · ";

    public static final String QUEUE_LABEL = "Queue";
    public static final String TUGS_LABEL = "Tugs";

    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    /**
     * One berth as the table shows it.
     *
     * @param berthId  the raw id ({@code berth_1})
     * @param label    {@code Berth 1}
     * @param occupant the vessel's name, {@value PortTableModel#FREE}, {@value PortTableModel#CLOSED}
     *                 or {@value PortTableModel#UNKNOWN}
     * @param state    what follows the occupant: {@code until 14:30}, a stage word, or (S16) the
     *                 reason the ship is not moving; empty for a berth nobody holds and an unknown one
     * @param waiting  S16: {@code state} is such a reason. It is the long kind of state — up to
     *                 {@code waiting: customs inspection [R22,R24]} — so the panel gives it a line
     *                 of its own under the name instead of squeezing the name out of a narrow column
     */
    public record BerthLine(String berthId, String label, String occupant, String state, boolean waiting) {

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
     * One of the two lines under the berths.
     *
     * @param label  {@code Queue} or {@code Tugs}
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

    /**
     * Forgets the berth and queue status — the world it described is gone. The tugs are kept:
     * the panel reseeds them from the artifact.
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

    private BerthLine berthLine(String berthId) {
        String label = berthLabel(berthId);
        PortStatusEvent.BerthRow row = status == null ? null : rowFor(berthId);
        if (row == null) {
            // No status at all, or one that says nothing about this berth: unknown, never "free".
            return new BerthLine(berthId, label, UNKNOWN, "", false);
        }
        if (row.isFree()) {
            // S16: a berth nobody holds is either open to the next grant or closed to it.
            return new BerthLine(berthId, label, row.closed() ? CLOSED : FREE, "", false);
        }
        return new BerthLine(berthId, label, occupantText(row), stateText(row, status.gameDay()), showsWait(row));
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
     * <p>S16 (2026-10-13). First, the reason it is not moving, when the HarbourMaster reports one
     * and the row is in a position to show it ({@link #showsWait}, {@link #waitText}). Otherwise a
     * docked vessel shows until when it stays, and before it has docked — deliberately, a time
     * there would republish the status every tick — a word for the stage: {@code leaving},
     * {@code docked}, {@code arriving}. The pre-S16 word "awaiting tugs" is gone: a granted ship
     * that is not moving now says what it is waiting for, and one with nothing in its way is
     * simply arriving.
     */
    public static String stateText(PortStatusEvent.BerthRow row, int gameDay) {
        if (row == null || row.isFree()) {
            return "";
        }
        if (showsWait(row)) {
            return waitText(row);
        }
        if (row.untilSimMillis() != null) {
            return untilText(row.untilSimMillis(), gameDay);
        }
        VesselTracking.Stage stage = row.stage();
        if (stage == null) {
            return "arriving";
        }
        return switch (stage) {
            case DEPARTING -> "leaving";
            case DOCKED -> "docked";
            default -> "arriving";
        };
    }

    /**
     * Whether the state cell of {@code row} is the reason its ship is not moving (S16).
     *
     * <p>A reported wait is shown, with one exception that keeps the S15 rule "a hold never hides
     * an until": the four waits of the way IN (weather, closed berth, customs, tugs) cannot be
     * true of a ship that has an "until" — that time is the vessel's own docked-phase deadline, so
     * the ship is moored and being served, and the HarbourMaster's tracking is a tick behind it.
     * The departure hold is the opposite case: it is only ever reported for a ship that HAS been
     * served, so it wins over whatever "until" is still on the row.
     */
    public static boolean showsWait(PortStatusEvent.BerthRow row) {
        if (row == null || row.isFree()) {
            return false;
        }
        return switch (row.waiting()) {
            case NONE -> false;
            case DEPARTURE_WEATHER -> true;
            default -> row.untilSimMillis() == null;
        };
    }

    /**
     * The reason a ship is not moving, in the words of the S16 contract: {@code held (weather)
     * [R15,R19]}, {@code departure held (weather) [R19]}, {@code waiting: customs inspection
     * [R22,R24]} (plain {@code waiting: customs} while no rule is cited — the request is out and
     * no inspection has been ordered), {@code waiting: tugs}, {@code waiting: berth closed
     * (flood)}. Empty when the row reports no wait.
     *
     * <p>The rule ids are printed in the bracket form the comm log uses, and only when the
     * HarbourMaster sent some: a reason never shows an empty {@code []}.
     */
    public static String waitText(PortStatusEvent.BerthRow row) {
        if (row == null) {
            return "";
        }
        String tag = ruleTag(row.ruleIds());
        String reason = switch (row.waiting()) {
            case NONE -> "";
            case WEATHER -> "held (weather)";
            case DEPARTURE_WEATHER -> "departure held (weather)";
            case BERTH_CLOSED -> "waiting: berth closed (flood)";
            case CUSTOMS -> tag.isEmpty() ? "waiting: customs" : "waiting: customs inspection";
            case TUGS -> "waiting: tugs";
        };
        return reason.isEmpty() || tag.isEmpty() ? reason : reason + " " + tag;
    }

    /** {@code [R15,R19]}, or the empty string for no ids. */
    public static String ruleTag(List<String> ruleIds) {
        return ruleIds == null || ruleIds.isEmpty() ? "" : "[" + String.join(",", ruleIds) + "]";
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
}
