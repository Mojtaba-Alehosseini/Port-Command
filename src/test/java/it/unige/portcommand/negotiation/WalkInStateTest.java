package it.unige.portcommand.negotiation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalkInStateTest {

    private static WalkInState sample() {
        return WalkInState.initial("tanker", 4500.0, 7000.0, Personality.NEUTRAL, 20, 12);
    }

    @Test
    void initialStartsAtRoundOneWithNoOffers() {
        WalkInState s = sample();
        assertEquals(1, s.round());
        assertEquals(0L, s.negotiationStartedAtSimMillis());
        assertEquals(0.0, s.lastPlayerPrice());
        assertEquals(0.0, s.lastOwnOffer());
        assertEquals(12, s.dealHours());
    }

    @Test
    void markStartedStampsTimeAndPreservesBeliefs() {
        WalkInState s = sample().markStarted(123_000L);
        assertEquals(123_000L, s.negotiationStartedAtSimMillis());
        assertEquals(4500.0, s.minAcceptablePrice());
        assertEquals(7000.0, s.targetPrice());
        assertEquals(Personality.NEUTRAL, s.personality());
        assertEquals(1, s.round());
    }

    @Test
    void recordOwnOfferSetsLastOwnOffer() {
        WalkInState s = sample().recordOwnOffer(7700.0);
        assertEquals(7700.0, s.lastOwnOffer());
        assertEquals(0.0, s.lastPlayerPrice());
    }

    @Test
    void recordPlayerCounterAdvancesRoundAndStoresPrice() {
        WalkInState s = sample().recordOwnOffer(7700.0).recordPlayerCounter(5000.0);
        assertEquals(2, s.round());
        assertEquals(5000.0, s.lastPlayerPrice());
        assertEquals(7700.0, s.lastOwnOffer()); // preserved
    }
}
