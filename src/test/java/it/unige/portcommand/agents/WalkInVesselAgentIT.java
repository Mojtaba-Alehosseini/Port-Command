package it.unige.portcommand.agents;

import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.artifacts.DealRecord;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.negotiation.Decision;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase-2 wire gate for the walk-in negotiation: a Mockito {@link NegotiationEngine}
 * scripted COUNTER-then-ACCEPT drives PROPOSE → counter → ACCEPT_PROPOSAL → CONFIRM
 * against a scripted HarbourMaster, on a Prolog-verified compatible vessel+berth.
 * CRITICALLY, every outbound vessel message is checked to contain NO hidden belief.
 */
@Tag("integration")
class WalkInVesselAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void walkInNegotiatesCounterThenAcceptWithoutLeakingBeliefs() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W001";
        // cargo_vessel (beam 23, needs general_crane) fits berth_1/2/3 — a representative walk-in.
        // (Pre-07b a ferry couldn't berth anywhere; task 07b widened berth_4 to beam 26 so ferries
        //  now fit too — but this IT keeps cargo_vessel as the canonical case.)
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);

        // Prolog-verified compatible berth so the isCompatible guard passes and negotiation proceeds.
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: cargo_vessel must have a compatible berth");
        String berthId = berths.get(0);

        // Replicate the vessel's hidden beliefs (same master seed + sampling order) for the leak check.
        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = boot.getRandomSource().forStream("vessel-" + vesselId);
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);

        // Mocked engine: COUNTER on the first offer, ACCEPT on the second.
        NegotiationEngine engine = mock(NegotiationEngine.class);
        when(engine.evaluate(anyDouble(), any())).thenReturn(
                Decision.counter(6000.0, "test-counter"),
                Decision.accept("test-accept"));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, 5500.0, 10, berthId});

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        boolean opening = false;
        boolean counter = false;
        boolean accept = false;
        boolean confirm = false;
        for (int i = 0; i < 12 && !confirm; i++) {
            ACLMessage m = hmInbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            assertNoHiddenBeliefs(m, personality, min, target); // PRIVACY GATE on every vessel message
            String intent = intent(m);
            switch (m.getPerformative()) {
                case ACLMessage.PROPOSE -> {
                    if ("opening_offer".equals(intent)) {
                        assertEnvelope(m, ACLMessage.PROPOSE);
                        opening = true;
                    } else if ("counter_offer".equals(intent)) {
                        assertEnvelope(m, ACLMessage.PROPOSE);
                        counter = true;
                    }
                }
                case ACLMessage.ACCEPT_PROPOSAL -> {
                    assertEnvelope(m, ACLMessage.ACCEPT_PROPOSAL);
                    accept = true;
                }
                case ACLMessage.CONFIRM -> {
                    assertEnvelope(m, ACLMessage.CONFIRM);
                    assertEquals("deal_confirmed", intent);
                    confirm = true;
                }
                default -> { /* ignore */ }
            }
        }
        assertTrue(opening, "vessel sent opening PROPOSE");
        assertTrue(counter, "vessel sent a counter PROPOSE (engine COUNTER)");
        assertTrue(accept, "vessel sent ACCEPT_PROPOSAL (engine ACCEPT)");
        assertTrue(confirm, "vessel sent CONFIRM on deal close");

        verify(engine, times(2)).evaluate(anyDouble(), any());

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size(), "deal recorded");
        assertEquals(Deal.Outcome.DEAL, recorded.get(0).outcome());
    }

    /** The privacy invariant: NO hidden belief (field name OR value) in any outbound content. */
    private static void assertNoHiddenBeliefs(ACLMessage m, Personality personality, double min, double target) {
        String content = m.getContent() == null ? "" : m.getContent();
        String lower = content.toLowerCase();
        for (String token : List.of("minacceptable", "min_acceptable", "targetprice", "target_price",
                "maxwait", "max_wait", "personality", "roundsremaining", "rounds_remaining")) {
            assertFalse(lower.contains(token), "hidden-belief field name leaked: " + content);
        }
        assertFalse(content.contains(personality.name()), "personality value leaked: " + content);
        assertFalse(content.contains(String.valueOf(min)), "minAcceptablePrice value leaked: " + content);
        assertFalse(content.contains(String.valueOf(target)), "targetPrice value leaked: " + content);
    }

    private static void assertEnvelope(ACLMessage m, int performative) {
        assertEquals(performative, m.getPerformative(),
                "performative (got " + ACLMessage.getPerformative(m.getPerformative()) + ")");
        assertEquals("port_command_v1", m.getOntology());
        assertEquals("json", m.getLanguage());
    }

    private static String intent(ACLMessage m) throws Exception {
        JsonNode n = MAPPER.readTree(m.getContent());
        return n.has("intent") ? n.get("intent").asText() : null;
    }
}
