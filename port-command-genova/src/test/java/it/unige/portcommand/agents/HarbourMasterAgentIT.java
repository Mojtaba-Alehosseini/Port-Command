package it.unige.portcommand.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.behaviours.coordination.DispatchPlayerCommandBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.prolog.TugBid;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase-2 wire gate for the HarbourMaster hub — the last agent task. Every binding
 * decision (berth ACCEPT/REFUSE, CNP award, weather hold) is proven Prolog-gated by
 * pairing a positive scenario with a NEGATIVE one that asserts the binding
 * performative is ABSENT (not just that a REFUSE/hold arrived), matching the design rules
 * rule 4. Real {@link TerminalAgent}/{@link CustomsAgent} are exercised where the
 * scenario is about HM's outbound message shape; {@link HmProbeAgent} stands in for
 * vessel/tug/weather/customs where the scenario needs exact, scripted content on the
 * other side of the wire. Hermetic — no Rasa/Flask/GUI.
 */
@Tag("integration")
class HarbourMasterAgentIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 1 real-second == 1 sim-second, so the CNP's 5-sim-second reply_by window is a
    // comfortable 5 real seconds — the task-03/15-default 300 (5-min sim-day) would
    // squeeze it to ~17ms, too tight for a probe round-trip.
    private static final long CNP_FRIENDLY_REAL_SECONDS_PER_DAY = 86_400L;

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    // ==================== Channel A: contracted auto-flow ====================

    @Test
    void contractedVesselCompatible_acceptsAndDispatchesDownstream() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-1", "C001", "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec("C001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t1_vessel", null);
        Probe terminal = spawnProbe("t1_terminal", "terminal");
        Probe tug1 = spawnProbe("t1_tug1", "tug-escort");
        Probe tug2 = spawnProbe("t1_tug2", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", spec, "t1_vessel"));

        ACLMessage accept = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(accept, "vessel must receive a reply");
        assertEnvelope(accept, ACLMessage.ACCEPT_PROPOSAL);
        JsonNode acceptContent = content(accept);
        assertEquals("berth_1", acceptContent.get("berth_id").asText());
        assertEquals(5200.0, acceptContent.get("price").asDouble());
        assertEquals(8, acceptContent.get("hours").asInt());

        ACLMessage termReq = pollUntil(terminal.inbox, m -> true, 5000);
        assertNotNull(termReq, "terminal must receive the 9-field BerthRequest");
        assertEnvelope(termReq, ACLMessage.REQUEST);
        JsonNode termContent = content(termReq);
        assertEquals("C001", termContent.get("vessel_id").asText());
        assertEquals("cargo_vessel", termContent.get("vessel_type").asText());
        assertEquals("berth_1", termContent.get("berth_id").asText());
        assertEquals(9.0, termContent.get("draft").asDouble());
        assertEquals(150.0, termContent.get("length").asDouble());
        assertEquals(30000, termContent.get("tonnage").asInt());
        assertEquals(8, termContent.get("duration_hours").asInt());
        assertEquals("general_cargo", termContent.get("cargo_class").asText());

        ACLMessage cfp1 = pollUntil(tug1.inbox, m -> true, 5000);
        ACLMessage cfp2 = pollUntil(tug2.inbox, m -> true, 5000);
        assertNotNull(cfp1, "tug1 must receive the CFP");
        assertNotNull(cfp2, "tug2 must receive the CFP");
        assertEquals(ACLMessage.CFP, cfp1.getPerformative());
        assertEquals(cfp1.getConversationId(), cfp2.getConversationId(), "both tugs bid on the SAME CNP session");

        int tugsRequired = PrologQueries.tugsRequired("cargo_vessel", 30000, 150.0);
        tug1.outbox.put(bidReply(cfp1, 100.0, 5.0, 0.9));
        tug2.outbox.put(bidReply(cfp2, 200.0, 10.0, 0.5));

        ACLMessage r1 = pollUntil(tug1.inbox, m -> true, 10_000);
        ACLMessage r2 = pollUntil(tug2.inbox, m -> true, 10_000);
        assertNotNull(r1);
        assertNotNull(r2);
        long acceptCount = List.of(r1, r2).stream().filter(m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL).count();
        assertEquals(Math.min(tugsRequired, 2), acceptCount, "exactly tugsRequired winner(s) among the 2 bids");
    }

    /**
     * Task 17 §BUILD 4's gate: runs the same contracted auto-flow as
     * {@link #contractedVesselCompatible_acceptsAndDispatchesDownstream} (a real terminal
     * puppet that never replies, so no extra terminal-CONFIRM traffic muddies the count) and
     * asserts the {@link CommLogEvent} sequence the HarbourMaster's instrumented
     * send/receive path ({@code HarbourMasterAgent.sendLogged}/{@code .receiveLogged})
     * produces: performative order for the deterministic prefix, every paraphrase non-blank,
     * and the concurrent tug-bid tail present as an order-independent multiset (two real
     * agent threads race to bid; the bus makes no cross-subscriber ordering promise either —
     * EventBus's own javadoc).
     */
    @Test
    void contractedAutoFlow_publishesCommLogEventSequence() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-CL1", "CL001", "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec("CL001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-CL1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("cl_vessel", null);
        Probe terminal = spawnProbe("cl_terminal", "terminal");
        Probe tug1 = spawnProbe("cl_tug1", "tug-escort");
        Probe tug2 = spawnProbe("cl_tug2", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-CL1", spec, "cl_vessel"));

        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: vessel must be accepted");
        assertNotNull(pollUntil(terminal.inbox, m -> true, 5000), "setup: terminal must receive the berth REQUEST");
        ACLMessage cfp1 = pollUntil(tug1.inbox, m -> true, 5000);
        ACLMessage cfp2 = pollUntil(tug2.inbox, m -> true, 5000);
        assertNotNull(cfp1, "setup: tug1 must receive the CFP");
        assertNotNull(cfp2, "setup: tug2 must receive the CFP");

        tug1.outbox.put(bidReply(cfp1, 100.0, 5.0, 0.9));
        tug2.outbox.put(bidReply(cfp2, 200.0, 10.0, 0.5));
        assertNotNull(pollUntil(tug1.inbox, m -> true, 10_000), "setup: tug1 must receive an award decision");
        assertNotNull(pollUntil(tug2.inbox, m -> true, 10_000), "setup: tug2 must receive an award decision");

        // Scoped to this test's own participants (adversarial review finding): the HM also runs
        // PoissonSpawnBehaviour in real time, so an unrelated walk-in could in principle land its
        // own CommLogEvents on the same shared bus mid-test — filtering by sender/receiver keeps
        // this assertion robust to that, the same way the ACL-reply assertions above are already
        // scoped to this test's own probes rather than reading a raw global count.
        Set<String> participants = Set.of("cl_vessel", "cl_terminal", "cl_tug1", "cl_tug2");
        List<CommLogEvent> commLog = EventBusProbe.published(boot.getEventBus()).stream()
                .filter(CommLogEvent.class::isInstance)
                .map(CommLogEvent.class::cast)
                .filter(e -> participants.contains(e.sender()) || e.receivers().stream().anyMatch(participants::contains))
                .toList();

        assertEquals(8, commLog.size(), "REQUEST, ACCEPT_PROPOSAL, REQUEST, CFP, 2x PROPOSE, 2x award decision: "
                + commLog);
        for (CommLogEvent e : commLog) {
            assertNotNull(e.paraphrase(), "every CommLogEvent must carry a paraphrase: " + e);
            assertFalse(e.paraphrase().isBlank(), "every CommLogEvent's paraphrase must be non-blank: " + e);
        }

        List<Integer> performatives = commLog.stream().map(CommLogEvent::performative).toList();
        assertEquals(List.of(ACLMessage.REQUEST, ACLMessage.ACCEPT_PROPOSAL, ACLMessage.REQUEST, ACLMessage.CFP),
                performatives.subList(0, 4),
                "deterministic prefix: vessel's REQUEST received, HM's grant sent, "
                        + "HM's terminal REQUEST sent, HM's tug CFP sent (in that order)");
        assertEquals("cl_vessel", commLog.get(0).sender(), "1st event is the vessel's inbound REQUEST");
        assertTrue(commLog.get(1).paraphrase().contains("berth_1"), "the berth grant paraphrase names the berth");
        assertEquals(List.of("cl_terminal"), commLog.get(2).receivers(), "3rd event addresses the terminal puppet");
        assertEquals(List.of("cl_tug1", "cl_tug2"), commLog.get(3).receivers(),
                "4th event is the ONE CFP broadcast to both tugs (not two separate sends)");

        // Tail: two PROPOSE receipts then two award sends, tug order not guaranteed (two real
        // agent threads race to bid) but each award must reuse its own bid's performative.
        List<CommLogEvent> tail = commLog.subList(4, 8);
        long proposeReceived = tail.subList(0, 2).stream().filter(e -> e.performative() == ACLMessage.PROPOSE).count();
        assertEquals(2, proposeReceived, "events 5-6 must both be received bids: " + tail);
        long awardsSent = tail.subList(2, 4).stream()
                .filter(e -> e.performative() == ACLMessage.ACCEPT_PROPOSAL || e.performative() == ACLMessage.REJECT_PROPOSAL)
                .count();
        assertEquals(2, awardsSent, "events 7-8 must both be award decisions: " + tail);
        // Same fixture as contractedVesselCompatible_acceptsAndDispatchesDownstream — reuse its
        // own computed (not guessed) expectation rather than hardcoding a rule-kernel value here.
        int tugsRequired = PrologQueries.tugsRequired("cargo_vessel", 30000, 150.0);
        long acceptsSent = tail.subList(2, 4).stream().filter(e -> e.performative() == ACLMessage.ACCEPT_PROPOSAL).count();
        assertEquals(Math.min(tugsRequired, 2), acceptsSent, "award count must match Prolog's tugsRequired");
    }

    @Test
    void contractedVesselIncompatible_refusedNeverAccepted() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-I1", "I001", "berth_1", 5200.0, 8, 0L);
        // 50 m draft: no berth in this harbour is remotely that deep.
        VesselSpec spec = new VesselSpec("I001", "cargo_vessel", 50.0, 150.0, 30000, "general_cargo", 0L);
        assertFalse(PrologQueries.isCompatible("berth_1", "cargo_vessel", 50.0, 150.0, 30000),
                "fixture sanity: a 50 m draft must not fit any berth");

        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-I1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t2_vessel", null);

        vessel.outbox.put(contractRequest(hm, "CONTRACT-I1", spec, "t2_vessel"));
        ACLMessage refuse = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(refuse);
        assertEnvelope(refuse, ACLMessage.REFUSE);
        assertEquals("incompatible", content(refuse).get("reason").asText());

        assertNeverArrives(vessel.inbox, ACLMessage.ACCEPT_PROPOSAL, 2000);
    }

    @Test
    void ferryContract_skipsCnpEntirely() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-F1", "F001", "berth_4", 1800.0, 4, 0L);
        VesselSpec spec = new VesselSpec("F001", "ferry", 5.0, 110.0, 9000, "general_cargo", 0L);
        assertEquals(0, PrologQueries.tugsRequired("ferry", 9000, 110.0), "fixture sanity: ferry is always 0 tugs (07b)");

        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-F1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t3_vessel", null);
        Probe tug = spawnProbe("t3_tug", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-F1", spec, "t3_vessel"));
        ACLMessage accept = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(accept);
        assertEnvelope(accept, ACLMessage.ACCEPT_PROPOSAL);

        assertNeverArrives(tug.inbox, ACLMessage.CFP, 3000);
    }

    @Test
    void cnpZeroBids_holdsVesselNeverAccepts() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-Z1", "Z001", "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec("Z001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-Z1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t4_vessel", null);
        Probe tug = spawnProbe("t4_tug", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-Z1", spec, "t4_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));

        ACLMessage cfp = pollUntil(tug.inbox, m -> true, 10_000);
        assertNotNull(cfp, "tug must receive the CFP");
        // tug stays silent -> zero bids

        ACLMessage hold = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(hold, "vessel must be INFORMed to hold when the CFP window closes with no bids");
        assertEnvelope(hold, ACLMessage.INFORM);
        assertEquals("tug_hold", content(hold).get("event").asText());

        assertNeverArrives(tug.inbox, ACLMessage.ACCEPT_PROPOSAL, 2000);
    }

    @Test
    void cnpTankerTwoTugs_awardsPrologSelectedWinners() throws Exception {
        double draft = 13.5;
        double length = 210.0;
        int tonnage = 85_000;
        List<String> compatibleBerths = PrologQueries.findCompatibleBerths("tanker", draft, length, tonnage);
        assertFalse(compatibleBerths.isEmpty(), "fixture sanity: mid-range tanker dims must fit >=1 berth");
        String berthId = compatibleBerths.get(0);
        assertEquals(2, PrologQueries.tugsRequired("tanker", tonnage, length),
                "PROJECT_DEFINITION §5.2: tanker is always 2 tugs");

        ServiceContract contract = new ServiceContract("CONTRACT-T1", "T001", berthId, 9000.0, 10, 0L);
        VesselSpec spec = new VesselSpec("T001", "tanker", draft, length, tonnage, "liquid_bulk", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-T1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t5_vessel", null);
        Map<String, Probe> tugs = new LinkedHashMap<>();
        tugs.put("t5_tuga", spawnProbe("t5_tuga", "tug-escort"));
        tugs.put("t5_tugb", spawnProbe("t5_tugb", "tug-escort"));
        tugs.put("t5_tugc", spawnProbe("t5_tugc", "tug-escort"));
        tugs.put("t5_tugd", spawnProbe("t5_tugd", "tug-escort"));

        vessel.outbox.put(contractRequest(hm, "CONTRACT-T1", spec, "t5_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));

        Map<String, ACLMessage> cfps = new LinkedHashMap<>();
        for (Map.Entry<String, Probe> e : tugs.entrySet()) {
            ACLMessage cfp = pollUntil(e.getValue().inbox, m -> true, 10_000);
            assertNotNull(cfp, e.getKey() + " must receive the CFP");
            cfps.put(e.getKey(), cfp);
        }

        // Distinct, non-tied bid qualities: tuga dominates, tugd beats tugc on every axis.
        List<TugBid> bids = List.of(
                new TugBid("t5_tuga", 100.0, 5.0, 0.95),
                new TugBid("t5_tugb", 150.0, 8.0, 0.80),
                new TugBid("t5_tugc", 500.0, 30.0, 0.10),
                new TugBid("t5_tugd", 300.0, 20.0, 0.30));
        for (TugBid bid : bids) {
            tugs.get(bid.tugId()).outbox.put(bidReply(cfps.get(bid.tugId()), bid.cost(), bid.etaMinutes(), bid.fuelState()));
        }
        List<String> expectedWinners = PrologQueries.selectBestBids(bids, 2);
        assertEquals(2, expectedWinners.size());

        int acceptCount = 0;
        int rejectCount = 0;
        for (Map.Entry<String, Probe> e : tugs.entrySet()) {
            ACLMessage reply = pollUntil(e.getValue().inbox, m -> true, 10_000);
            assertNotNull(reply, e.getKey() + " must receive an award decision");
            if (reply.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                acceptCount++;
                assertTrue(expectedWinners.contains(e.getKey()), e.getKey() + " accepted but Prolog didn't pick it");
                assertEquals(cfps.get(e.getKey()).getConversationId(), reply.getConversationId(),
                        "ACCEPT must reuse the CFP's conversation id (tug correlates its remembered bid by it)");
            } else {
                assertEquals(ACLMessage.REJECT_PROPOSAL, reply.getPerformative());
                rejectCount++;
            }
        }
        assertEquals(2, acceptCount);
        assertEquals(2, rejectCount);
    }

    // ==================== Channel B: walk-in relay ====================

    @Test
    void walkInOpeningOffer_publishesNegotiationOpenedEventWithCompatibleBerth() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t6_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 4800.0, 6, "nego-W001"));

        NegotiationOpenedEvent event = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W001".equals(e.dialogueId()), 10_000);
        assertNotNull(event, "opening offer must publish a NegotiationOpenedEvent");
        assertEquals("W001", event.snapshot().vesselId());
        assertEquals(1, event.snapshot().roundsUsed());
        assertTrue(PrologQueries.isCompatible(event.snapshot().berthId(), "cargo_vessel", 9.0, 150.0, 30000),
                "the berth HM picked must itself be Prolog-compatible");
    }

    @Test
    void walkInNoCompatibleBerth_refusedNeverPublishesEvent() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        // 350 m cruise ship: the known negative-control fixture from 07b's spawnability suite.
        VesselSpec spec = new VesselSpec("W002", "cruise_ship", 9.0, 350.0, 90000, "general_cargo", 0L);
        assertTrue(PrologQueries.findCompatibleBerths("cruise_ship", 9.0, 350.0, 90000).isEmpty(),
                "fixture sanity: a 350 m cruise ship must have zero compatible berths (07b)");
        Probe vessel = spawnProbe("t7_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 8000.0, 10, "nego-W002"));

        ACLMessage refuse = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(refuse, "vessel must be REFUSEd when no berth fits");
        assertEnvelope(refuse, ACLMessage.REFUSE);
        assertEquals("no_compatible_berth", content(refuse).get("reason").asText());

        assertEventNeverPublished(NegotiationOpenedEvent.class, ne -> "nego-W002".equals(ne.dialogueId()), 500);
    }

    @Test
    void walkInDealConfirmed_grantsBerthPrologGated() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W003", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t8_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 4800.0, 6, "nego-W003"));
        NegotiationOpenedEvent opened = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W003".equals(e.dialogueId()), 10_000);
        assertNotNull(opened);
        String berthId = opened.snapshot().berthId();

        vessel.outbox.put(dealConfirmed(hm, 4900.0, 6, "nego-W003"));
        ACLMessage grant = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(grant, "deal_confirmed must produce a berth grant");
        assertEnvelope(grant, ACLMessage.ACCEPT_PROPOSAL);
        assertEquals("berth-t8_vessel", grant.getConversationId(), "must match AwaitBerthBehaviour's listening conversation");
        JsonNode grantContent = content(grant);
        assertEquals(berthId, grantContent.get("berth_id").asText());
        assertEquals(4900.0, grantContent.get("price").asDouble());
    }

    @Test
    void walkInDealConfirmed_dispatchesTugCnpWhenRequired() throws Exception {
        // Task 15: closes the walk-in CNP-dispatch gap ADR-08 left deliberately open --
        // reuses the exact cargo_vessel fixture (draft 9.0/length 150/tonnage 30000) the
        // contracted-path test already proves has tugsRequired>0.
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W004", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t14_vessel", null);
        Probe tug = spawnProbe("t14_tug", "tug-escort");

        vessel.outbox.put(openingOffer(hm, spec, 4800.0, 6, "nego-W004"));
        NegotiationOpenedEvent opened = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W004".equals(e.dialogueId()), 10_000);
        assertNotNull(opened);

        vessel.outbox.put(dealConfirmed(hm, 4900.0, 6, "nego-W004"));
        ACLMessage grant = pollUntil(vessel.inbox, m -> true, 10_000);
        assertNotNull(grant, "deal_confirmed must produce a berth grant");
        assertEnvelope(grant, ACLMessage.ACCEPT_PROPOSAL);

        int tugsRequired = PrologQueries.tugsRequired("cargo_vessel", 30000, 150.0);
        assertTrue(tugsRequired > 0, "fixture sanity: this cargo_vessel spec must require >=1 tug");

        ACLMessage cfp = pollUntil(tug.inbox, m -> true, 10_000);
        assertNotNull(cfp, "walk-in deal-confirm must now dispatch a tug CNP (task 15 closes ADR-08's gap)");
        assertEquals(ACLMessage.CFP, cfp.getPerformative());

        tug.outbox.put(bidReply(cfp, 100.0, 5.0, 0.9));
        ACLMessage award = pollUntil(tug.inbox, m -> true, 10_000);
        assertNotNull(award, "the lone bidder must be awarded -- vessel ends fully engaged in the CNP/escort pipeline");
        assertEquals(ACLMessage.ACCEPT_PROPOSAL, award.getPerformative());
    }

    // ==================== Player command dispatch ====================

    @Test
    void dispatchPlayerCommand_translatesEachKindFaithfully() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-P1", "P001", "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec("P001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-P1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t9_vessel", null);

        vessel.outbox.put(contractRequest(hm, "CONTRACT-P1", spec, "t9_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: establishes activeVessels tracking with a real sender AID");

        // Call the public handler directly rather than publishing through the (now real, ASYNC)
        // bus, so this test drives it deterministically on the calling thread instead of racing
        // an async delivery — exactly like AssistantAgentIT does for the Assistant's OneShot
        // behaviours. This standalone instance is never added to the agent (no addBehaviour), so
        // its action() never runs and it never actually subscribes on the shared bus either.
        DispatchPlayerCommandBehaviour dispatch = new DispatchPlayerCommandBehaviour(hm);

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.PROPOSE, "P001",
                Map.of("price", 4800.0, "berth_id", "berth_1")));
        ACLMessage propose = pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.PROPOSE, 5000);
        assertNotNull(propose);
        assertEquals("nego-P001", propose.getConversationId());
        assertEquals(4800.0, content(propose).get("price").asDouble());

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, "P001", Map.of()));
        assertEquals(ACLMessage.ACCEPT_PROPOSAL,
                pollUntil(vessel.inbox, m -> true, 5000).getPerformative());

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.REJECT, "P001", Map.of()));
        assertEquals(ACLMessage.REJECT_PROPOSAL,
                pollUntil(vessel.inbox, m -> true, 5000).getPerformative());

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.ASK, "P001", Map.of()));
        assertEquals(ACLMessage.QUERY_REF,
                pollUntil(vessel.inbox, m -> true, 5000).getPerformative());

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.WITHDRAW, "P001", Map.of()));
        assertEquals(ACLMessage.CANCEL,
                pollUntil(vessel.inbox, m -> true, 5000).getPerformative());
    }

    // ==================== Weather safety gate (v1.1: R15/16 + R17 + R19) ====================

    @Test
    void weatherUnsafeSwell_cancelsInTransitTugAndHoldsVessel() throws Exception {
        InTransitFixture scene = getVesselInTransit("t10");

        Probe weatherProbe = spawnProbe("t10_weather", "weather");
        assertTrue(PrologQueries.swellWithinLimit(4.0) && !PrologQueries.swellWithinLimit(6.0),
                "fixture sanity: 6.0 m must violate the R17 4.0 m limit");
        weatherProbe.outbox.put(thresholdAlert(scene.hm, 10, "good", 6.0, "cloudy"));

        ACLMessage cancel = pollUntil(scene.tug.inbox, m -> true, 10_000);
        assertNotNull(cancel, "R17 (swell) must CANCEL the in-transit tug");
        assertEnvelope(cancel, ACLMessage.CANCEL);

        ACLMessage hold = pollUntil(scene.vessel.inbox, m -> true, 10_000);
        assertNotNull(hold, "vessel must be INFORMed of the weather hold");
        assertEnvelope(hold, ACLMessage.INFORM);
        assertEquals("weather_hold", content(hold).get("event").asText());
    }

    @Test
    void weatherUnsafeStormyState_cancelsInTransitTug() throws Exception {
        InTransitFixture scene = getVesselInTransit("t11");

        Probe weatherProbe = spawnProbe("t11_weather", "weather");
        assertTrue(PrologQueries.weatherStateUnsafe("stormy") && !PrologQueries.weatherStateUnsafe("cloudy"),
                "fixture sanity: only 'stormy' trips R19");
        // Wind/visibility/swell all nominal -> ONLY R19 (state) can be the cause.
        weatherProbe.outbox.put(thresholdAlert(scene.hm, 10, "good", 1.0, "stormy"));

        ACLMessage cancel = pollUntil(scene.tug.inbox, m -> true, 10_000);
        assertNotNull(cancel, "R19 (stormy state) must CANCEL the in-transit tug even with safe numeric readings");
        assertEnvelope(cancel, ACLMessage.CANCEL);
    }

    @Test
    void weatherSafe_noCancelSent() throws Exception {
        InTransitFixture scene = getVesselInTransit("t12");

        Probe weatherProbe = spawnProbe("t12_weather", "weather");
        weatherProbe.outbox.put(thresholdAlert(scene.hm, 10, "good", 1.0, "sunny"));

        assertNeverArrives(scene.tug.inbox, ACLMessage.CANCEL, 3000);
    }

    // ==================== Customs (task-09's deferred note) ====================

    @Test
    void customsFlagged_marksBlockedAndNotifies() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-CU1", "CU1", "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec("CU1", "cargo_vessel", 9.0, 150.0, 30000, "hazmat_class_1", 0L);
        assertTrue(PrologQueries.isHazmat("hazmat_class_1"), "fixture sanity: hazmat_class_1 must be hazmat");
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-CU1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t13_vessel", null);
        Probe customs = spawnProbe("t13_customs", "customs");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-CU1", spec, "t13_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));

        ACLMessage custReq = pollUntil(customs.inbox, m -> true, 10_000);
        assertNotNull(custReq, "hazmat cargo must trigger a customs pre-clearance REQUEST");
        assertEnvelope(custReq, ACLMessage.REQUEST);
        JsonNode custContent = content(custReq);
        assertEquals("cargo_vessel", custContent.get("vessel_type").asText());
        assertEquals("hazmat_class_1", custContent.get("cargo_class").asText());

        ACLMessage flagged = MessageFactory.reply(custReq, ACLMessage.INFORM);
        flagged.setContent(TerminalJson.write(Map.of("event", "flagged", "reason", "blacklisted")));
        flagged.addUserDefinedParameter("priority", "high");
        customs.outbox.put(flagged);

        NotificationEvent notice = awaitEvent(NotificationEvent.class, e -> e.text().contains("CU1"), 10_000);
        assertNotNull(notice, "a customs flag must publish a NotificationEvent");
        assertEquals(NotificationEvent.Severity.WARNING, notice.severity());
    }

    // ==================== End of day (detect-only) ====================

    @Test
    void endOfDay_publishesExactlyOnceOnMidnightCrossing() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), BootstrapConfig.DEFAULT_REAL_SECONDS_PER_GAME_DAY);
        double startingBalance = hm.walletLedger().balance();

        boot.getSimClock().advanceToNextDay();

        EndOfDayEvent event = awaitEvent(EndOfDayEvent.class, e -> true, 10_000);
        assertNotNull(event, "EndOfDayEvent must be published on a midnight crossing");
        assertEquals(1, event.gameDay());

        // Keep watching for a further window (a few more ~208ms ticker periods here) —
        // the count must never climb past one for this single crossing.
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            long count = EventBusProbe.published(boot.getEventBus()).stream()
                    .filter(e -> e instanceof EndOfDayEvent).count();
            assertEquals(1, count, "exactly once per midnight crossing");
            Thread.sleep(20);
        }
        assertEquals(startingBalance, hm.walletLedger().balance(), 0.0001,
                "detect-only: the HarbourMaster must never touch the wallet itself");
    }

    // ==================== shared fixtures / helpers ====================

    private record Probe(BlockingQueue<ACLMessage> outbox, BlockingQueue<ACLMessage> inbox) {
    }

    private record InTransitFixture(HarbourMasterAgent hm, Probe vessel, Probe tug) {
    }

    /** Runs the contracted flow through CNP award so the vessel is IN_TRANSIT with one assigned tug. */
    private InTransitFixture getVesselInTransit(String prefix) throws Exception {
        String contractId = "CONTRACT-" + prefix;
        String vesselId = "V-" + prefix;
        ServiceContract contract = new ServiceContract(contractId, vesselId, "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of(contractId, contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe(prefix + "_vessel", null);
        Probe tug = spawnProbe(prefix + "_tug", "tug-escort");

        vessel.outbox.put(contractRequest(hm, contractId, spec, prefix + "_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: vessel must be accepted");
        ACLMessage cfp = pollUntil(tug.inbox, m -> true, 10_000);
        assertNotNull(cfp, "setup: tug must receive the CFP");
        tug.outbox.put(bidReply(cfp, 100.0, 5.0, 0.9));
        ACLMessage award = pollUntil(tug.inbox, m -> true, 10_000);
        assertNotNull(award, "setup: tug must be awarded");
        assertEquals(ACLMessage.ACCEPT_PROPOSAL, award.getPerformative(), "setup: the lone bidder must win");

        return new InTransitFixture(hm, vessel, tug);
    }

    private HarbourMasterAgent spawnHm(Map<String, ServiceContract> contracts, long realSecondsPerGameDay)
            throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", realSecondsPerGameDay));
        CompletableFuture<HarbourMasterAgent> selfRef = new CompletableFuture<>();
        boot.getSpawner().spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(10_000.0, 70.0),
                boot.getSimClock(), boot.getRandomSource(), boot.getEventBus(),
                contracts, boot.getSpawner(), boot.getMarketHistoryArtifact(), selfRef});
        return selfRef.get(10, TimeUnit.SECONDS);
    }

    private Probe spawnProbe(String localName, String dfServiceType) {
        BlockingQueue<ACLMessage> outbox = new LinkedBlockingQueue<>();
        BlockingQueue<ACLMessage> inbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn(localName, HmProbeAgent.class, new Object[] {outbox, inbox, dfServiceType});
        return new Probe(outbox, inbox);
    }

    private static ACLMessage contractRequest(HarbourMasterAgent hm, String contractId, VesselSpec spec,
                                              String senderLocalName) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("intent", "request_berth");
        content.put("contract", contractId);
        content.put("vessel_spec", spec);
        ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
        request.addReceiver(hm.getAID());
        request.setConversationId("berth-" + senderLocalName);
        request.setContent(TerminalJson.write(content));
        return request;
    }

    private static ACLMessage openingOffer(HarbourMasterAgent hm, VesselSpec spec, double price, int hours,
                                           String conversationId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("intent", "opening_offer");
        content.put("price", price);
        content.put("hours", hours);
        content.put("vessel_spec", spec);
        ACLMessage propose = MessageFactory.create(ACLMessage.PROPOSE);
        propose.addReceiver(hm.getAID());
        propose.setConversationId(conversationId);
        propose.setContent(TerminalJson.write(content));
        return propose;
    }

    private static ACLMessage dealConfirmed(HarbourMasterAgent hm, double price, int hours, String conversationId) {
        ACLMessage confirm = MessageFactory.create(ACLMessage.CONFIRM);
        confirm.addReceiver(hm.getAID());
        confirm.setConversationId(conversationId);
        confirm.setContent(TerminalJson.write(Map.of("intent", "deal_confirmed", "price", price, "hours", hours)));
        return confirm;
    }

    private static ACLMessage bidReply(ACLMessage cfp, double cost, double etaMinutes, double fuelState) {
        ACLMessage propose = MessageFactory.reply(cfp, ACLMessage.PROPOSE);
        propose.setContent(TerminalJson.write(
                Map.of("cost", cost, "eta_minutes", etaMinutes, "fuel_state", fuelState)));
        return propose;
    }

    private static ACLMessage thresholdAlert(HarbourMasterAgent hm, int windKnots, String visibility, double swell,
                                             String state) {
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.getAID());
        alert.setContent(TerminalJson.write(Map.of(
                "event", "weather_threshold", "wind_knots", windKnots, "visibility", visibility,
                "swell", swell, "state", state)));
        alert.addUserDefinedParameter("priority", "high");
        // Matches HandleWeatherAlertBehaviour's protocol-tag routing (this test hand-crafts
        // the alert directly rather than going through PeriodicWeatherBroadcastBehaviour).
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        return alert;
    }

    private static void assertEnvelope(ACLMessage m, int performative) {
        assertEquals(performative, m.getPerformative(),
                "performative (got " + ACLMessage.getPerformative(m.getPerformative()) + ")");
        assertEquals("port_command_v1", m.getOntology());
        assertEquals("json", m.getLanguage());
    }

    private static JsonNode content(ACLMessage m) throws Exception {
        return MAPPER.readTree(m.getContent());
    }

    /** Polls {@code q} up to {@code totalMillis}, returning the first message matching {@code match} (ignoring others). */
    private static ACLMessage pollUntil(BlockingQueue<ACLMessage> q, Predicate<ACLMessage> match, long totalMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = q.poll(200, TimeUnit.MILLISECONDS);
            if (m != null && match.test(m)) {
                return m;
            }
        }
        return null;
    }

    /** Drains {@code q} for {@code millis}, failing if a message with {@code performative} ever appears. */
    private static void assertNeverArrives(BlockingQueue<ACLMessage> q, int performative, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = q.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && m.getPerformative() == performative) {
                fail("unexpected " + ACLMessage.getPerformative(performative) + " arrived: " + m.getContent());
            }
        }
    }

    /**
     * Polls the EventBus's recorded publish list until a matching event appears or
     * {@code millis} elapses. No Awaitility dependency exists in this project (checked)
     * and the task-03 EventBus stub has no blocking observation API — publish() only
     * appends to a plain queue, it never notifies. This is the narrowest possible
     * bounded-retry substitute; every ACL-reply wait elsewhere in this file blocks on
     * a real BlockingQueue instead, per the established IT pattern (ContractedVesselAgentIT
     * et al.) — flagged for the adversarial review.
     */
    private <T extends Event> T awaitEvent(Class<T> type, Predicate<T> match, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            for (Event e : EventBusProbe.published(boot.getEventBus())) {
                if (type.isInstance(e) && match.test(type.cast(e))) {
                    return type.cast(e);
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** The {@link #awaitEvent} negative counterpart — fails fast if a match ever appears within {@code millis}. */
    private <T extends Event> void assertEventNeverPublished(Class<T> type, Predicate<T> match, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            for (Event e : EventBusProbe.published(boot.getEventBus())) {
                if (type.isInstance(e) && match.test(type.cast(e))) {
                    fail("unexpected " + type.getSimpleName() + " published: " + e);
                }
            }
            Thread.sleep(20);
        }
    }
}
