package it.unige.portcommand.artifacts;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-JVM coverage of the market-history store: query window, ±20% band, stats, lastN/restore. */
class MarketHistoryArtifactTest {

    private static final long DAY = 86_400_000L;

    private static DealRecord deal(String type, int hours, double price, long simTime, Deal.Outcome outcome) {
        return new DealRecord(type, hours, price, simTime, outcome);
    }

    @Test
    void sevenDayWindowKeepsAllWhenLargerThanFiftyCap() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        for (int i = 0; i < 60; i++) {
            m.record(deal("cargo_vessel", 5, 1000 + i, i * 1000L, Deal.Outcome.DEAL)); // 60 within ~1 minute
        }
        MarketStats s = m.queryStats("cargo_vessel", 5);
        assertEquals(60, s.sampleCount(), "7-day window (60) > 50-cap");
        assertFalse(s.lowConfidence());
    }

    @Test
    void fiftyCapKeptWhenLargerThanSevenDaySet() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        long base = 100 * DAY;
        for (int i = 0; i < 30; i++) {
            m.record(deal("cargo_vessel", 5, 1000, base - 30 * DAY + i, Deal.Outcome.DEAL)); // older than 7 days
        }
        for (int i = 0; i < 30; i++) {
            m.record(deal("cargo_vessel", 5, 1000, base + i, Deal.Outcome.DEAL)); // recent
        }
        MarketStats s = m.queryStats("cargo_vessel", 5);
        assertEquals(50, s.sampleCount(), "50-cap (50) > within-7-days (30)");
    }

    @Test
    void durationBandFiltersBeforeWindow() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        long t = 0;
        for (int i = 0; i < 20; i++) {
            m.record(deal("cargo_vessel", 5, 500, t++, Deal.Outcome.DEAL)); // in band [4,6]
        }
        for (int i = 0; i < 10; i++) {
            m.record(deal("cargo_vessel", 3, 999, t++, Deal.Outcome.DEAL)); // out (3 < 4)
        }
        for (int i = 0; i < 10; i++) {
            m.record(deal("cargo_vessel", 7, 999, t++, Deal.Outcome.DEAL)); // out (7 > 6)
        }
        MarketStats s = m.queryStats("cargo_vessel", 5);
        assertEquals(20, s.sampleCount(), "only the 20 in-band [4,6] deals count");
        assertEquals(500.0, s.mean(), 1e-9, "3h/7h excluded from mean");
    }

    @Test
    void lowConfidenceBelowTenSamples() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        for (int i = 0; i < 5; i++) {
            m.record(deal("ferry", 4, 200, i, Deal.Outcome.DEAL));
        }
        MarketStats s = m.queryStats("ferry", 4);
        assertEquals(5, s.sampleCount());
        assertTrue(s.lowConfidence());
    }

    @Test
    void meanStddevDealRateComputed() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        m.record(deal("tanker", 10, 100, 0, Deal.Outcome.DEAL));
        m.record(deal("tanker", 10, 200, 1, Deal.Outcome.DEAL));
        m.record(deal("tanker", 10, 300, 2, Deal.Outcome.WITHDRAW_PRICE));
        MarketStats s = m.queryStats("tanker", 10);
        assertEquals(3, s.sampleCount());
        assertEquals(200.0, s.mean(), 1e-9);
        assertEquals(100.0, s.min(), 1e-9);
        assertEquals(300.0, s.max(), 1e-9);
        assertEquals(Math.sqrt(20000.0 / 3), s.stddev(), 1e-6, "population stddev of {100,200,300}");
        assertEquals(2.0 / 3, s.dealRate(), 1e-9, "2 DEAL of 3 records");
    }

    @Test
    void emptyQueryIsZeroLowConfidence() {
        MarketStats s = new MarketHistoryArtifact().queryStats("cruise_ship", 8);
        assertEquals(0, s.sampleCount());
        assertTrue(s.lowConfidence());
    }

    @Test
    void lastNReturnsMostRecentInInsertionOrder() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        for (int i = 0; i < 5; i++) {
            m.record(deal("ferry", 4, i, i, Deal.Outcome.DEAL));
        }
        List<DealRecord> last3 = m.lastN(3);
        assertEquals(3, last3.size());
        assertEquals(2.0, last3.get(0).price(), 1e-9);
        assertEquals(4.0, last3.get(2).price(), 1e-9);
        assertEquals(5, m.lastN(100).size(), "n > size returns all");
    }

    @Test
    void restoreReplacesStore() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        m.record(deal("ferry", 4, 1, 1, Deal.Outcome.DEAL));
        m.restore(List.of(deal("tanker", 10, 50, 0, Deal.Outcome.DEAL)));
        assertEquals(1, m.lastN(10).size());
        assertEquals("tanker", m.lastN(10).get(0).vesselType());
    }

    @Test
    void subscriberNotifiedOnRecord() {
        MarketHistoryArtifact m = new MarketHistoryArtifact();
        List<DealRecord> seen = new ArrayList<>();
        m.subscribe(seen::add);
        m.record(deal("ferry", 4, 1, 1, Deal.Outcome.DEAL));
        assertEquals(1, seen.size());
    }
}
