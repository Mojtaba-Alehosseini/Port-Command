package it.unige.portcommand.negotiation;

/**
 * The hidden negotiation state of one walk-in vessel — the input to
 * {@link NegotiationEngine#evaluate}. Immutable; the agent holds a reference and
 * swaps it as the dialogue advances ({@link #markStarted}, {@link #recordOwnOffer},
 * {@link #recordPlayerCounter}).
 *
 * <p>The first four fields are <b>hidden beliefs</b>: they drive the decision but
 * must never appear in an outgoing ACL message (enforced by a grep test). They are
 * read only by the engine (same package) and the owning agent.
 */
public record WalkInState(
        String vesselType,
        double minAcceptablePrice,
        double targetPrice,
        Personality personality,
        int maxWaitMinutes,
        int round,
        long negotiationStartedAtSimMillis,
        double lastPlayerPrice,
        double lastOwnOffer,
        int dealHours) {

    /** Round 1, not yet started, no offers exchanged. */
    public static WalkInState initial(String vesselType, double minAcceptablePrice, double targetPrice,
                                      Personality personality, int maxWaitMinutes, int dealHours) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                1, 0L, 0.0, 0.0, dealHours);
    }

    /** Stamp the negotiation start (when the opening PROPOSE is sent) for the timeout ticker. */
    public WalkInState markStarted(long simMillis) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                round, simMillis, lastPlayerPrice, lastOwnOffer, dealHours);
    }

    /** Record the price this vessel just proposed (opening or counter). */
    public WalkInState recordOwnOffer(double offer) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                round, negotiationStartedAtSimMillis, lastPlayerPrice, offer, dealHours);
    }

    /** Advance a round on receiving the player's counter, recording their price. */
    public WalkInState recordPlayerCounter(double playerPrice) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                round + 1, negotiationStartedAtSimMillis, playerPrice, lastOwnOffer, dealHours);
    }
}
