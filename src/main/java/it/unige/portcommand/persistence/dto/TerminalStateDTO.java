package it.unige.portcommand.persistence.dto;

import java.util.List;

import it.unige.portcommand.agents.BerthOccupancy;

/**
 * One terminal's persisted berth bookkeeping (task 22). Occupancies are sorted by
 * {@code berthId} at snapshot (the serialized-collection determinism rule) and are
 * reconciled by the loader against the restored vessel set — an entry whose vessel was
 * dropped (an open negotiation, a grant that never reached its vessel) loads as FREE, so
 * a berth can never be leaked to a ghost.
 *
 * @param terminalId  {@code terminal_container} | {@code terminal_general}
 * @param occupancies the managed berths' occupancy records ({@link BerthOccupancy} already
 *                    carries snake_case wire keys from task 06)
 */
public record TerminalStateDTO(String terminalId, List<BerthOccupancy> occupancies) {
}
