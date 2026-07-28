package it.unige.portcommand.negotiation;

/**
 * The hidden negotiation state of one walk-in vessel — the input to
 * {@link NegotiationEngine#evaluate}. Immutable; the agent holds a reference and
 * swaps it as the dialogue advances ({@link #markStarted}, {@link #recordOwnOffer},
 * {@link #recordPlayerCounter}).
 *
 * <p>The first five fields are <b>hidden beliefs</b>: they drive the decision but
 * must never appear in an outgoing ACL message (enforced by a grep test). They are
 * read only by the engine (same package) and the owning agent.
 *
 * <p><b>Task 19b — the duration dimension.</b> {@code minDurationHours} is the vessel's hard
 * cargo-handling floor (a physical need, not a preference) and joins the hidden-belief set
 * (P-04): the FIELD never goes on the wire. Its VALUE surfaces only as the hours term of a
 * {@code too_short} counter — the "we need at least Nh" pushback §7.3's bargaining is made of —
 * exactly like price counters approach the hidden {@code targetPrice} without naming it.
 * {@code dealHours} (the PREFERRED stay, announced in the opening PROPOSE) stays observable.
 * {@code lastOwnHours} is the vessel's standing hours term — what its last PROPOSE said after
 * "for" — and is the duration a bare "deal" closes at; it starts at {@code dealHours} and
 * follows the negotiation. {@code lastPlayerHours} mirrors {@code lastPlayerPrice} as pure
 * bookkeeping of the player's side.
 */
public record WalkInState(
        String vesselType,
        double minAcceptablePrice,
        double targetPrice,
        Personality personality,
        int maxWaitMinutes,
        int minDurationHours,
        int round,
        long negotiationStartedAtSimMillis,
        double lastPlayerPrice,
        double lastOwnOffer,
        int lastPlayerHours,
        int lastOwnHours,
        int dealHours) {

    /** Round 1, not yet started, no offers exchanged; the standing hours open at the
     * preferred stay (what the opening PROPOSE announces). */
    public static WalkInState initial(String vesselType, double minAcceptablePrice, double targetPrice,
                                      Personality personality, int maxWaitMinutes, int dealHours,
                                      int minDurationHours) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                minDurationHours, 1, 0L, 0.0, 0.0, 0, dealHours, dealHours);
    }

    /** Stamp the negotiation start (when the opening PROPOSE is sent) for the timeout ticker. */
    public WalkInState markStarted(long simMillis) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                minDurationHours, round, simMillis, lastPlayerPrice, lastOwnOffer,
                lastPlayerHours, lastOwnHours, dealHours);
    }

    /** Record the terms this vessel just proposed (opening or counter): price and hours. */
    public WalkInState recordOwnOffer(double offer, int hours) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                minDurationHours, round, negotiationStartedAtSimMillis, lastPlayerPrice, offer,
                lastPlayerHours, hours, dealHours);
    }

    /** Advance a round on receiving the player's counter, recording their price and hours. */
    public WalkInState recordPlayerCounter(double playerPrice, int playerHours) {
        return new WalkInState(vesselType, minAcceptablePrice, targetPrice, personality, maxWaitMinutes,
                minDurationHours, round + 1, negotiationStartedAtSimMillis, playerPrice, lastOwnOffer,
                playerHours, lastOwnHours, dealHours);
    }
}
