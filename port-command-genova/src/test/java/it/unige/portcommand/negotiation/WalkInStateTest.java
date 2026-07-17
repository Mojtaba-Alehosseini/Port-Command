package it.unige.portcommand.negotiation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalkInStateTest {

    private static WalkInState sample() {
        return WalkInState.initial("tanker", 4500.0, 7000.0, Personality.NEUTRAL, 20, 12, 9);
    }

    @Test
    void initialStartsAtRoundOneWithNoOffers() {
        WalkInState s = sample();
        assertEquals(1, s.round());
        assertEquals(0L, s.negotiationStartedAtSimMillis());
        assertEquals(0.0, s.lastPlayerPrice());
        assertEquals(0.0, s.lastOwnOffer());
        assertEquals(0, s.lastPlayerHours());
        assertEquals(12, s.dealHours());
        assertEquals(9, s.minDurationHours());
        assertEquals(12, s.lastOwnHours(),
                "the standing hours open at the preferred stay — what the opening PROPOSE announces");
    }

    @Test
    void markStartedStampsTimeAndPreservesBeliefs() {
        WalkInState s = sample().markStarted(123_000L);
        assertEquals(123_000L, s.negotiationStartedAtSimMillis());
        assertEquals(4500.0, s.minAcceptablePrice());
        assertEquals(7000.0, s.targetPrice());
        assertEquals(Personality.NEUTRAL, s.personality());
        assertEquals(9, s.minDurationHours());
        assertEquals(1, s.round());
    }

    @Test
    void recordOwnOfferSetsTheVesselsStandingTerms() {
        WalkInState s = sample().recordOwnOffer(7700.0, 10);
        assertEquals(7700.0, s.lastOwnOffer());
        assertEquals(10, s.lastOwnHours(), "an hours counter moves the standing hours term");
        assertEquals(0.0, s.lastPlayerPrice());
        assertEquals(12, s.dealHours(), "the preferred stay is immutable sampled data");
    }

    @Test
    void recordPlayerCounterAdvancesRoundAndStoresBothTerms() {
        WalkInState s = sample().recordOwnOffer(7700.0, 12).recordPlayerCounter(5000.0, 10);
        assertEquals(2, s.round());
        assertEquals(5000.0, s.lastPlayerPrice());
        assertEquals(10, s.lastPlayerHours());
        assertEquals(7700.0, s.lastOwnOffer()); // preserved
        assertEquals(12, s.lastOwnHours());     // preserved — the vessel's own term moves only on ITS offers
    }
}
