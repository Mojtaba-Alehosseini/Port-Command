package it.unige.portcommand.scenario;

import it.unige.portcommand.ontology.ServiceContract;

/**
 * The daily contracted-arrival schedule (task 23 — the boxed spawner spec filed by
 * task 24): every {@code contracts.json} entry becomes one {@code spawn_vessel}
 * scripted event per game day, timed on the LIVE sim clock a couple of sim-hours
 * before the contract's ETA so the vessel's arrival {@code WakerBehaviour} (which
 * converts the sim ETA to a wall delay at construction) fires on schedule.
 *
 * <p><b>Recurrence is DAILY (decided 2026-07-18):</b> Channel A is ~70% of designed
 * traffic; a one-shot schedule would revert every day after the first to the
 * REQUEST:0 state the checkpoint flagged. Daily recurrence forces day-qualification
 * everywhere, because two idempotency keys in the engine are contract-scoped:
 * <ul>
 *   <li><b>{@code LedgerCoordinator} dedupes contracted fees by {@code contractId}</b>
 *       — respawning raw {@code CONTRACT-1} on day 2 would earn €0. Each day gets a
 *       registered clone ({@code CONTRACT-1-D2}) whose fee therefore lands exactly
 *       once per day.</li>
 *   <li><b>JADE local names derive from vessel ids</b> — day-qualified vessel ids
 *       ({@code C001-D2}) can never collide with a previous day's vessel that is
 *       still alive in port.</li>
 * </ul>
 * The scripted-event id ({@link #eventId}) is day-qualified the same way, which is
 * what makes {@code GameState.firedEventIds} idempotency hold PER DAY across
 * save/load. Scenario-local contracts never enter this schedule (day-1 script props);
 * in a scenario boot the dailies start at day 2 — day 1 belongs to the script.
 */
public final class ContractSchedule {

    /** Spawn this many sim-millis before the contract ETA (the "few sim-hours" lead). */
    public static final long SPAWN_LEAD_SIM_MILLIS = 2L * 3_600_000L;

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private ContractSchedule() {
    }

    /** The scripted-event id for {@code base} on 1-based {@code day} — the firedEventIds key. */
    public static String eventId(ServiceContract base, int day) {
        return "contract:" + base.contractId() + ":d" + day;
    }

    /**
     * The day-qualified clone registered into the HarbourMaster's contract map for one
     * day's arrival: {@code CONTRACT-1-D2} carrying vessel {@code C001-D2}, ETA at the
     * base contract's time-of-day on {@code day} (absolute sim-millis).
     */
    public static ServiceContract dailyContract(ServiceContract base, int day) {
        long timeOfDay = Math.floorMod(base.expectedArrivalSimMillis(), MILLIS_PER_DAY);
        long absoluteEta = (day - 1L) * MILLIS_PER_DAY + timeOfDay;
        return new ServiceContract(
                base.contractId() + "-D" + day,
                base.vesselId() + "-D" + day,
                base.vesselType(), base.berthId(), base.contractedFee(), base.contractedHours(),
                absoluteEta);
    }

    /** The sim-second the spawn event is due ({@code SPAWN_LEAD_SIM_MILLIS} before ETA). */
    public static long spawnSimSeconds(ServiceContract dailyContract) {
        return Math.max(0L, (dailyContract.expectedArrivalSimMillis() - SPAWN_LEAD_SIM_MILLIS) / 1000L);
    }
}
