package it.unige.portcommand.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson serialize/deserialize round-trip for every task-02 domain record,
 * plus a spot-check that the compact-constructor validation rejects bad input.
 */
class RecordRoundtripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> void roundtrip(T value, Class<T> type) throws Exception {
        String json = mapper.writeValueAsString(value);
        T back = mapper.readValue(json, type);
        assertEquals(value, back, "round-trip mismatch via JSON: " + json);
    }

    @Test
    void vesselSpecRoundtrips() throws Exception {
        roundtrip(new VesselSpec("v-1", "tanker", 14.0, 140.0, 50_000, "hazmat_class_3", 1_700_000_000L),
                VesselSpec.class);
    }

    @Test
    void positionRoundtrips() throws Exception {
        roundtrip(new Position(123.5, -42.0, 270.0), Position.class);
    }

    @Test
    void berthSpecRoundtrips() throws Exception {
        roundtrip(new BerthSpec("berth_1", "deep_water_berth", 22.0, 350.0, true), BerthSpec.class);
    }

    @Test
    void serviceContractRoundtrips() throws Exception {
        roundtrip(new ServiceContract("c-1", "v-1", "berth_2", 5200.0, 12, 1_700_000_000L),
                ServiceContract.class);
    }

    @Test
    void offerRoundtrips() throws Exception {
        roundtrip(new Offer(450.0, 8, "berth_3", "vessel_x", "harbour_master", 1_700_000_123L), Offer.class);
    }

    @Test
    void dealRoundtripsForEveryOutcome() throws Exception {
        for (Deal.Outcome outcome : Deal.Outcome.values()) {
            roundtrip(new Deal("d-" + outcome, "v-1", "berth_4", 470.0, 6, 1_700_000_456L, outcome),
                    Deal.class);
        }
    }

    /**
     * Proves the {@code @JsonProperty} snake_case contract — a plain round-trip
     * would pass even with missing annotations (camelCase serialises and parses
     * back fine), so assert the wire keys explicitly.
     */
    @Test
    void jsonUsesSnakeCaseKeys() throws Exception {
        assertJsonHasKeys(new VesselSpec("v", "tanker", 1.0, 1.0, 1, "general_cargo", 0L),
                "vessel_id", "vessel_type", "cargo_class", "eta_arrival_sim_millis");
        assertJsonHasKeys(new Position(1.0, 2.0, 3.0), "heading_deg");
        assertJsonHasKeys(new BerthSpec("berth_1", "deep_water_berth", 1.0, 1.0, true),
                "berth_id", "berth_type", "max_draft", "has_crane");
        assertJsonHasKeys(new ServiceContract("c", "v", "berth_1", 1.0, 1, 0L),
                "contract_id", "contracted_fee", "expected_arrival_sim_millis");
        assertJsonHasKeys(new Offer(1.0, 1, "berth_1", "a", "b", 0L),
                "duration_hours", "from_agent", "to_agent");
        assertJsonHasKeys(new Deal("d", "v", "berth_1", 1.0, 1, 0L, Deal.Outcome.DEAL),
                "deal_id", "final_price", "closed_at_sim_millis", "outcome");
    }

    private void assertJsonHasKeys(Object value, String... keys) throws Exception {
        String json = mapper.writeValueAsString(value);
        for (String key : keys) {
            assertTrue(json.contains("\"" + key + "\""), "JSON " + json + " missing key " + key);
        }
    }

    @Test
    void vesselSpecRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> new VesselSpec("v-2", "submarine", 5.0, 30.0, 100, "general_cargo", 0L));
    }

    @Test
    void vesselSpecRejectsNonPositiveDraft() {
        assertThrows(IllegalArgumentException.class,
                () -> new VesselSpec("v-3", "ferry", 0.0, 30.0, 100, "general_cargo", 0L));
    }

    @Test
    void berthSpecRejectsBadBerthId() {
        assertThrows(IllegalArgumentException.class,
                () -> new BerthSpec("berth_9", "deep_water_berth", 22.0, 350.0, true));
    }

    @Test
    void offerRejectsOutOfRangeDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new Offer(100.0, 25, "berth_1", "a", "b", 0L));
    }

    @Test
    void dealRejectsNullOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> new Deal("d-x", "v-1", "berth_1", 100.0, 6, 0L, null));
    }
}
