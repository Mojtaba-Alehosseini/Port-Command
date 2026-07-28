package it.unige.portcommand.persistence;

import java.util.List;

import it.unige.portcommand.persistence.dto.HarbourMasterStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;

/**
 * The restore bundle the loader appends to the HarbourMaster's spawn args (task 22) —
 * a plain in-JVM carrier, never itself serialized. Pairs the HM's own persisted state
 * with the active-vessel DTOs, because the rebuilt HM must re-track every restored
 * vessel (by deterministic local name — the AIDs are rebuilt, not persisted) and
 * re-drive what the save interrupted: the tug Contract Net for tug-less AWAITING_TUG
 * vessels and the customs pre-clearance REQUESTs whose replies died with the old
 * container.
 */
public record HarbourMasterRestoreState(
        HarbourMasterStateDTO harbourMaster,
        List<VesselStateDTO> activeVessels) {
}
