package it.unige.portcommand.prolog;

import java.util.Objects;

/**
 * A single tug's bid in the escort Contract-Net, as consumed by
 * {@link PrologQueries#selectBestBids(java.util.List, int)}.
 *
 * <p>Encoded into the Prolog term {@code bid(TugId, Cost, EtaMinutes, FuelState)}
 * — argument order matching {@code score_bid/2} in {@code rules_escort.pl}
 * (RULE R12). {@code fuelState} is a 0..1 readiness fraction (higher is better);
 * {@code cost} is euros; {@code etaMinutes} is time-to-station.
 *
 * @param tugId      the tug's agent id; must be a valid Prolog atom name
 * @param cost       bid cost in euros (penalised in the score)
 * @param etaMinutes minutes to reach the vessel (penalised in the score)
 * @param fuelState  fuel/readiness fraction 0..1 (rewarded in the score)
 */
public record TugBid(String tugId, double cost, double etaMinutes, double fuelState) {

    public TugBid {
        Objects.requireNonNull(tugId, "tugId");
        if (tugId.isBlank()) {
            throw new IllegalArgumentException("tugId must not be blank");
        }
    }
}
