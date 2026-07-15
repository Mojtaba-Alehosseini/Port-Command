package it.unige.portcommand.harbourmaster;

import java.util.Objects;

import it.unige.portcommand.ontology.Position;
import jade.core.AID;

/**
 * One tug-escort Contract Net request. {@code cfpId}/{@code replyBy} are assigned
 * inside the coordinator, not supplied by the caller — mirrors planning/15's own
 * documented shape, extended with {@code vesselAid} (to address the zero-bid hold
 * INFORM) and {@code berthPosition} (ADR-07: the HarbourMaster is authoritative for
 * the escort destination carried in the tug's ACCEPT).
 */
public record CnpRequest(
        String vesselId,
        AID vesselAid,
        Position vesselPosition,
        Position berthPosition,
        int tugsRequired) {

    public CnpRequest {
        Objects.requireNonNull(vesselId, "vesselId");
        Objects.requireNonNull(vesselAid, "vesselAid");
        Objects.requireNonNull(vesselPosition, "vesselPosition");
        Objects.requireNonNull(berthPosition, "berthPosition");
        if (tugsRequired < 1) {
            throw new IllegalArgumentException("tugsRequired must be >= 1, got " + tugsRequired);
        }
    }
}
