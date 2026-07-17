package it.unige.portcommand.persistence.dto;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.IncomeEvent;

/**
 * The HarbourMaster's persisted coordination state (task 22). Wallet balance and
 * reputation live at the {@code GameState} ROOT (planning/22 §22.3) — this record carries
 * what only the HM knows:
 *
 * <ul>
 *   <li>{@code dayIncome}/{@code dayExpense} — the IN-PROGRESS day's ledger entries.
 *       Restored into the ledgers' history WITHOUT re-applying (the root balance already
 *       includes them); they are exactly what {@code IncomeRules.aggregateForDay}/
 *       {@code ExpenseRules.variableForDay} read at the loaded day's end-of-day, so the
 *       next EOD summary shows the money the day really made. Settled days are not
 *       persisted — nothing reads them back (dated note, planning/22).</li>
 *   <li>{@code performativeCounts} — today's FIPA tally by canonical display name, in
 *       canonical report order (a LinkedHashMap at snapshot — the determinism rule).</li>
 *   <li>{@code pendingCustomsChecks} — conversationId → vesselId for pre-clearance
 *       REQUESTs whose reply had not been processed at the save. The reply died with the
 *       old container; the rebuilt HM re-sends these, and because processing the reply IS
 *       the charge point, the €100 lands exactly once either way.</li>
 *   <li>{@code walkInSpawnCount} — the Poisson spawner's id counter, so post-load
 *       walk-ins continue {@code WALKIN-<n+1>...} instead of colliding with restored
 *       vessels' ids (and their derived RNG stream names).</li>
 * </ul>
 */
public record HarbourMasterStateDTO(
        List<IncomeEvent> dayIncome,
        List<ExpenseEvent> dayExpense,
        Map<String, Integer> performativeCounts,
        Map<String, String> pendingCustomsChecks,
        int walkInSpawnCount) {
}
