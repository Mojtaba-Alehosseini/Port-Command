package it.unige.portcommand.harbourmaster;

import java.util.Objects;

import it.unige.portcommand.ontology.Position;
import jade.core.AID;

/**
 * One tug-escort Contract Net request. {@code cfpId}/{@code replyBy} are assigned
 * inside the coordinator, not supplied by the caller — mirrors planning/15's own
 * documented shape, extended with {@code vesselAid} (to address the zero-bid hold
 * INFORM), {@code berthPosition} (ADR-07: the HarbourMaster is authoritative for
 * the escort destination carried in the tug's ACCEPT), and — S5 — {@code vesselType},
 * so the coordinator's priority dequeue (R25-R30 via {@code effective_priority}) can
 * rank queued sessions without reaching back into HM tracking.
 */
public record CnpRequest(
        String vesselId,
        AID vesselAid,
        Position vesselPosition,
        Position berthPosition,
        int tugsRequired,
        String vesselType) {

    public CnpRequest {
        Objects.requireNonNull(vesselId, "vesselId");
        Objects.requireNonNull(vesselAid, "vesselAid");
        Objects.requireNonNull(vesselPosition, "vesselPosition");
        Objects.requireNonNull(berthPosition, "berthPosition");
        Objects.requireNonNull(vesselType, "vesselType");
        if (tugsRequired < 1) {
            throw new IllegalArgumentException("tugsRequired must be >= 1, got " + tugsRequired);
        }
    }
}
