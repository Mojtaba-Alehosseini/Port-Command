package it.unige.portcommand.persistence.dto;

/**
 * The customs service's persisted state (task 22): the clearance-reference sequence, so
 * {@code CL-} refs stay unique across a save/load instead of restarting at 1. The
 * inspection RNG is a {@code RandomSource} sub-stream and follows the reseeded master.
 *
 * @param nextClearanceRef the next {@code CL-} sequence number to issue
 */
public record CustomsStateDTO(int nextClearanceRef) {
}
