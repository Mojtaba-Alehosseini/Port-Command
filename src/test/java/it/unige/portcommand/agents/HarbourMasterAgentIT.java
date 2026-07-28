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
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.gui.events.WithdrawalEvent;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.ontology.Deal;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase-2 wire gate for the HarbourMaster hub — the last agent task. Every binding
 * decision (berth ACCEPT/REFUSE, CNP award, weather hold) is proven Prolog-gated by
 * pairing a positive scenario with a NEGATIVE one that asserts the binding
 * performative is ABSENT (not just that a REFUSE/hold arrived), matching CLAUDE.md
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
        ServiceContract contract = new ServiceContract("CONTRACT-1", "C001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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
        ServiceContract contract = new ServiceContract("CONTRACT-CL1", "CL001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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
        // Order-insensitive on purpose (2026-07-17, task 20): the CFP's receiver list is built from
        // ServiceLocator's DF search, whose result order JADE does not guarantee — asserting
        // List.of("cl_tug1","cl_tug2") made this fail whenever the DF returned tug2 first, which is
        // a coupling the assertion never meant to have (its own next comment already concedes the
        // two tug threads race). The INTENT is unchanged and still fully pinned: exactly ONE CFP
        // event, carrying exactly BOTH tugs — not two separate sends.
        assertEquals(Set.of("cl_tug1", "cl_tug2"), Set.copyOf(commLog.get(3).receivers()),
                "4th event is the ONE CFP broadcast to both tugs (not two separate sends)");
        assertEquals(2, commLog.get(3).receivers().size(), "both tugs, each exactly once");

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
        ServiceContract contract = new ServiceContract("CONTRACT-I1", "I001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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
        ServiceContract contract = new ServiceContract("CONTRACT-F1", "F001", "ferry", "berth_4", 1800.0, 4, 0L);
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
        ServiceContract contract = new ServiceContract("CONTRACT-Z1", "Z001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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

        ServiceContract contract = new ServiceContract("CONTRACT-T1", "T001", "tanker", berthId, 9000.0, 10, 0L);
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

    /**
     * Audit A-01 (2026-07-27). The walk-in vessel's own REFUSE — {@code invalid_price} and
     * {@code berth_incompatible}, both from {@code EvaluateCounterOfferBehaviour} — is addressed
     * to the HarbourMaster on the {@code nego-} conversation with NO protocol tag. Before the fix
     * the HM's whole inbound template set matched PROPOSE|CONFIRM|INFORM only, so the REFUSE was
     * dropped on the floor: no chat reply, no comm-log line (receiveLogged only sees what a
     * template matched), no notification, no round change — the dialogue the player drives BY HAND
     * in front of the examiner simply froze until the vessel timed out.
     *
     * <p>The third instance of the BUG-01/BUG-03 class: a producer added (task 19) without a
     * hub-side consumer, invisible to unit tests because {@code WalkInVesselAgentIT} supplies its
     * own HarbourMaster stand-in and therefore receives the REFUSE itself.
     */
    @Test
    void walkInRefusedTheCounter_reachesThePlayerAndBurnsNoRound() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W020", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t26_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 4800.0, 6, "nego-W020"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-W020".equals(e.dialogueId()), 10_000));
        assertEquals(1, hm.negotiationRounds().get("nego-W020").get(), "fixture: the opening offer is round 1");

        vessel.outbox.put(negotiationRefuse(hm, "invalid_price", "nego-W020"));

        AssistantChatEvent shown = awaitEvent(AssistantChatEvent.class,
                e -> "nego-W020".equals(e.dialogueId()), 10_000);
        assertNotNull(shown, "a vessel-side REFUSE must reach the player's tab, not the floor");
        assertTrue(shown.text().contains("invalid_price"),
                "the player must be told WHY the counter bounced, got: " + shown.text());

        // The vessel deliberately does not burn a round on a malformed counter
        // (EvaluateCounterOfferBehaviour's price guard returns before recordPlayerCounter),
        // so the hub must not invent one either.
        assertEquals(1, hm.negotiationRounds().get("nego-W020").get(), "a bounced counter burns no round");
        assertNotNull(hm.negotiationBerths().get("nego-W020"), "the candidate berth survives a bounce");
    }

    /**
     * Audit A-01, second half. The vessel's ACCEPT_PROPOSAL (it took the player's counter) was
     * dropped by the same template gap. Functionally benign — the {@code deal_confirmed} CONFIRM
     * follows and IS matched — but it meant the vessel's acceptance never reached the comm log or
     * the performative tally, so the log showed only the HM accepting and never the vessel.
     */
    @Test
    void walkInAcceptedTheCounter_isLoggedRatherThanDropped() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W023", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t28_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 4800.0, 6, "nego-W023"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-W023".equals(e.dialogueId()), 10_000));

        ACLMessage accept = MessageFactory.create(ACLMessage.ACCEPT_PROPOSAL);
        accept.addReceiver(hm.getAID());
        accept.setConversationId("nego-W023");
        accept.setContent(TerminalJson.write(Map.of("intent", "accept", "price", 4700.0, "hours", 6)));
        vessel.outbox.put(accept);

        CommLogEvent logged = awaitEvent(CommLogEvent.class,
                e -> e.performative() == ACLMessage.ACCEPT_PROPOSAL && "t28_vessel".equals(e.sender()), 10_000);
        assertNotNull(logged, "the vessel's own ACCEPT-PROPOSAL must appear in the comm log");
    }

    /**
     * Audit A-02 (2026-07-27). {@code pickBerth} took {@code findCompatibleBerths(...).get(0)}
     * unconditionally, and R8's {@code findall} enumerates {@code instance_of/2} in clause order —
     * so the pick was DETERMINISTIC: every walk-in of a given class resolved to the same berth
     * (berth_1 for a cargo_vessel). {@code busy_day} scripts two walk-ins at t=300; both were
     * offered, and could both be granted, one berth between them. Nothing downstream catches it:
     * a walk-in sends the terminal no berth REQUEST at all (ADR-09), so {@code berth_busy} never
     * fires and {@code PortStateArtifact} never learns the berth is taken.
     */
    @Test
    void twoConcurrentWalkInsOfTheSameClass_getDifferentBerths() throws Exception {
        assertTrue(PrologQueries.findCompatibleBerths("cargo_vessel", 9.0, 150.0, 30000).size() >= 2,
                "fixture sanity: this class needs >= 2 compatible berths or the assertion is vacuous");
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec first = new VesselSpec("W021", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        VesselSpec second = new VesselSpec("W022", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vesselA = spawnProbe("t27_vessel_a", null);
        Probe vesselB = spawnProbe("t27_vessel_b", null);

        vesselA.outbox.put(openingOffer(hm, first, 4800.0, 6, "nego-W021"));
        NegotiationOpenedEvent openedA = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W021".equals(e.dialogueId()), 10_000);
        assertNotNull(openedA);

        vesselB.outbox.put(openingOffer(hm, second, 4800.0, 6, "nego-W022"));
        NegotiationOpenedEvent openedB = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W022".equals(e.dialogueId()), 10_000);
        assertNotNull(openedB);

        assertNotEquals(openedA.snapshot().berthId(), openedB.snapshot().berthId(),
                "a berth already held by a live walk-in must not be offered to a second one");
        assertTrue(PrologQueries.isCompatible(openedB.snapshot().berthId(), "cargo_vessel", 9.0, 150.0, 30000),
                "the alternative berth must still be Prolog-compatible");
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

    // ============ Checkpoint-#6 F1/F5 (2026-07-18): HM tracking stage lifecycle ============

    /**
     * The full walk-in lifecycle as the HarbourMaster's OWN tracking sees it (checkpoint-#6
     * F1/F5 regression): every stage the flow reports must land in {@code activeVessels}, in
     * order, ending in the terminal removal (INVARIANTS "terminal removal" rule). Pre-fix,
     * escort_complete was dropped silently ({@code event} field, not {@code intent}) and
     * service_complete fell into the "unrecognised intent" branch — vessels departed still
     * showing AWAITING_TUG/IN_TRANSIT and persisted a stage contradicting their own phase.
     */
    @Test
    void walkInFullLifecycle_trackingStageMonotone_endsTerminal() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W101", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t30_vessel", null);
        Probe tug = spawnProbe("t30_tug", "tug-escort");
        List<VesselTracking.Stage> observed = new java.util.ArrayList<>();

        vessel.outbox.put(openingOffer(hm, spec, 4800.0, 6, "nego-W101"));
        observed.add(assertStageReached(hm, "W101", VesselTracking.Stage.NEGOTIATING).stage());

        vessel.outbox.put(dealConfirmed(hm, 4900.0, 6, "nego-W101"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: berth grant must arrive");
        observed.add(assertStageReached(hm, "W101", VesselTracking.Stage.AWAITING_TUG).stage());

        ACLMessage cfp = pollUntil(tug.inbox, m -> true, 10_000);
        assertNotNull(cfp, "setup: this cargo_vessel fixture requires tugs — a CFP must go out");
        tug.outbox.put(bidReply(cfp, 100.0, 5.0, 0.9));
        assertNotNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: the lone bidder must be awarded");
        observed.add(assertStageReached(hm, "W101", VesselTracking.Stage.IN_TRANSIT).stage());

        // The tug's delivery report — exactly EscortToBerthBehaviour's message shape
        // ({"event": "escort_complete"}, CNP conversation id, no protocol tag).
        tug.outbox.put(flowReport(hm, Map.of("event", "escort_complete", "vessel_id", "W101"),
                cfp.getConversationId()));
        observed.add(assertStageReached(hm, "W101", VesselTracking.Stage.DOCKED).stage());

        // The vessel's service-end report — exactly DockAndServiceBehaviour's message shape.
        vessel.outbox.put(flowReport(hm, Map.of(
                "intent", "service_complete", "vessel_id", "W101", "berth_id", "berth_1"), null));
        observed.add(assertStageReached(hm, "W101", VesselTracking.Stage.DEPARTING).stage());

        vessel.outbox.put(flowReport(hm, Map.of("intent", "departed", "vessel_id", "W101"), null));
        long deadline = System.currentTimeMillis() + 10_000;
        while (hm.activeVessels().containsKey("W101") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(hm.activeVessels().containsKey("W101"),
                "departure must remove the tracking entry (terminal-removal invariant)");

        for (int i = 1; i < observed.size(); i++) {
            assertTrue(observed.get(i - 1).ordinal() < observed.get(i).ordinal(),
                    "stage sequence must be strictly monotone, got: " + observed);
        }
    }

    /**
     * The W9 case from checkpoint-#6 F1: a ferry needs no escort (RULE R11), so its grant
     * must track IN_TRANSIT immediately — it never awaits a tug — and the service/depart
     * reports carry it to DEPARTING and out.
     */
    @Test
    void zeroTugWalkInGrant_tracksInTransitImmediately_neverAwaitingTug() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W102", "ferry", 5.0, 120.0, 8000, "passengers", 0L);
        assertEquals(0, PrologQueries.tugsRequired("ferry", 8000, 120.0),
                "fixture sanity: a ferry must be tug-exempt (RULE R11)");
        Probe vessel = spawnProbe("t31_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 1500.0, 6, "nego-W102"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W102".equals(e.dialogueId()), 10_000), "setup: offer must be forwarded");

        vessel.outbox.put(dealConfirmed(hm, 1600.0, 6, "nego-W102"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: berth grant must arrive");
        VesselTracking granted = assertStageReached(hm, "W102", VesselTracking.Stage.IN_TRANSIT);
        assertNotEquals(VesselTracking.Stage.AWAITING_TUG, granted.stage(),
                "a 0-tug vessel never awaits a tug (checkpoint-#6 F1, the W9 stale-stage case)");

        vessel.outbox.put(flowReport(hm, Map.of(
                "intent", "service_complete", "vessel_id", "W102", "berth_id", "berth_1"), null));
        assertStageReached(hm, "W102", VesselTracking.Stage.DEPARTING);

        vessel.outbox.put(flowReport(hm, Map.of("intent", "departed", "vessel_id", "W102"), null));
        long deadline = System.currentTimeMillis() + 10_000;
        while (hm.activeVessels().containsKey("W102") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(hm.activeVessels().containsKey("W102"), "departure must close the tracking entry");
    }

    // ==================== Task 19: tab-close event publishers ====================

    @Test
    void walkInDealConfirmed_publishesDealClosedAndNegotiationClosedEvents() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec spec = new VesselSpec("W005", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vessel = spawnProbe("t15_vessel", null);

        vessel.outbox.put(openingOffer(hm, spec, 1500.0, 6, "nego-W005"));
        NegotiationOpenedEvent opened = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W005".equals(e.dialogueId()), 10_000);
        assertNotNull(opened, "setup: opening offer must be forwarded");
        String berthId = opened.snapshot().berthId();

        vessel.outbox.put(dealConfirmed(hm, 1600.0, 6, "nego-W005"));
        assertNotNull(pollUntil(vessel.inbox, m -> true, 10_000), "setup: berth grant must arrive");

        DealClosedEvent dealClosed = awaitEvent(DealClosedEvent.class,
                e -> "W005".equals(e.deal().vesselId()), 10_000);
        assertNotNull(dealClosed, "a granted deal must publish DealClosedEvent (task-19 gap closure)");
        assertEquals(Deal.Outcome.DEAL, dealClosed.deal().outcome());
        assertEquals(berthId, dealClosed.deal().berthId());
        assertEquals(1600.0, dealClosed.deal().finalPrice(), 0.001);
        assertEquals(6, dealClosed.deal().finalHours());

        NegotiationClosedEvent closed = awaitEvent(NegotiationClosedEvent.class,
                e -> "nego-W005".equals(e.dialogueId()), 10_000);
        assertNotNull(closed, "a granted deal must ALSO publish NegotiationClosedEvent (the chat tab's own bracket signal)");
        assertEquals("W005", closed.vesselId());
        assertEquals(Deal.Outcome.DEAL, closed.outcome());
    }

    @Test
    void walkInWithdraw_publishesWithdrawalAndNegotiationClosedEventsForEveryReason() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec overPriced = new VesselSpec("W006", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        VesselSpec timedOut = new VesselSpec("W007", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vesselA = spawnProbe("t16_vessel", null);
        Probe vesselB = spawnProbe("t17_vessel", null);

        vesselA.outbox.put(openingOffer(hm, overPriced, 1500.0, 6, "nego-W006"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-W006".equals(e.dialogueId()), 10_000));
        vesselA.outbox.put(withdrawMessage(hm, "over_priced", "nego-W006"));

        WithdrawalEvent withdrawal = awaitEvent(WithdrawalEvent.class, e -> "W006".equals(e.vesselId()), 10_000);
        assertNotNull(withdrawal, "an over_priced withdraw must publish WithdrawalEvent");
        assertEquals(Deal.Outcome.WITHDRAW_PRICE, withdrawal.outcome());
        NegotiationClosedEvent closedA = awaitEvent(NegotiationClosedEvent.class,
                e -> "nego-W006".equals(e.dialogueId()), 10_000);
        assertNotNull(closedA);
        assertEquals(Deal.Outcome.WITHDRAW_PRICE, closedA.outcome());

        vesselB.outbox.put(openingOffer(hm, timedOut, 1500.0, 6, "nego-W007"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-W007".equals(e.dialogueId()), 10_000));
        vesselB.outbox.put(withdrawMessage(hm, "timeout", "nego-W007"));

        NegotiationClosedEvent closedB = awaitEvent(NegotiationClosedEvent.class,
                e -> "nego-W007".equals(e.dialogueId()), 10_000);
        assertNotNull(closedB, "a timeout withdraw must ALSO publish NegotiationClosedEvent, mapped to a DIFFERENT outcome");
        assertEquals(Deal.Outcome.TIMEOUT, closedB.outcome());
    }

    @Test
    void walkInWithdraw_unrecognisedReasonNeverKillsTheRouterBehaviour() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        VesselSpec bogus = new VesselSpec("W008", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        VesselSpec after = new VesselSpec("W009", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        Probe vesselA = spawnProbe("t18_vessel", null);
        Probe vesselB = spawnProbe("t19_vessel", null);

        vesselA.outbox.put(openingOffer(hm, bogus, 1500.0, 6, "nego-W008"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-W008".equals(e.dialogueId()), 10_000));
        // A malformed/unknown reason must be swallowed (logged), not thrown from this CyclicBehaviour.
        vesselA.outbox.put(withdrawMessage(hm, "made_up_reason", "nego-W008"));
        assertEventNeverPublished(NegotiationClosedEvent.class, e -> "nego-W008".equals(e.dialogueId()), 500);

        // The behaviour's message loop must still be alive and processing OTHER vessels afterward.
        vesselB.outbox.put(openingOffer(hm, after, 1500.0, 6, "nego-W009"));
        NegotiationOpenedEvent stillWorks = awaitEvent(NegotiationOpenedEvent.class,
                e -> "nego-W009".equals(e.dialogueId()), 10_000);
        assertNotNull(stillWorks, "the router must keep processing walk-ins after an unrecognised withdraw reason");
    }

    // ==================== Player command dispatch ====================

    @Test
    void dispatchPlayerCommand_translatesEachKindFaithfully() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-P1", "P001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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

        // Each poll filters for the EXPECTED performative (task-22 session deflake): the staged
        // grant also fires a zero-tug Contract Net whose asynchronous tug_hold INFORM can land
        // between any two dispatches under load — an `m -> true` poll would swallow it as the
        // reply and fail on the performative. Filtering makes each step assert "the dispatch
        // produced this message", which is what the test means; the stray INFORM stays queued.
        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, "P001", Map.of()));
        assertNotNull(pollUntil(vessel.inbox,
                m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 5000));

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.REJECT, "P001", Map.of()));
        assertNotNull(pollUntil(vessel.inbox,
                m -> m.getPerformative() == ACLMessage.REJECT_PROPOSAL, 5000));

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.ASK, "P001", Map.of()));
        assertNotNull(pollUntil(vessel.inbox,
                m -> m.getPerformative() == ACLMessage.QUERY_REF, 5000));

        dispatch.onPlayerCommand(new PlayerCommandEvent(PlayerCommandKind.WITHDRAW, "P001", Map.of()));
        assertNotNull(pollUntil(vessel.inbox,
                m -> m.getPerformative() == ACLMessage.CANCEL, 5000));
    }

    /**
     * Regression test for a real bug found via manual play-testing (task 19), not by any prior
     * unit/integration test — including the one directly above, which (like every other existing
     * test of this class) never lets the behaviour's agent reference go null before invoking the
     * handler. In production, JADE's scheduler runs a {@code OneShotBehaviour}'s {@code action()}
     * once, then retires it — {@code Agent.removeBehaviour} calls {@code Behaviour.setAgent(null)}
     * (bytecode-verified against jade-4.6.0.jar) as part of that retirement. Exactly when JADE gets
     * around to that retirement is not on any bounded, test-friendly schedule (the real failure took
     * ~19 minutes of live play to surface) — so this test does not wait for it to happen naturally;
     * it calls the SAME public {@code setAgent(null)} JADE itself calls, deterministically
     * reproducing the exact precondition. The behaviour's {@code this::onPlayerCommand} method
     * reference stays subscribed on the EventBus indefinitely regardless, so any chat command
     * published after that point used to NPE inside the handler (it re-read the now-null
     * {@code myAgent}). The fix captures the agent in a plain field at construction instead.
     */
    @Test
    void dispatchPlayerCommand_stillWorksAfterItsAgentReferenceIsNulled() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-D1", "D001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
        VesselSpec spec = new VesselSpec("D001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-D1", contract), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe vessel = spawnProbe("t20_vessel", null);

        vessel.outbox.put(contractRequest(hm, "CONTRACT-D1", spec, "t20_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "setup: establishes activeVessels tracking with a real sender AID");

        // action() registers the real subscription exactly as JADE's scheduler would (this class
        // itself no longer reads myAgent inside action(), only the injected `hm` field, so calling
        // it directly here is faithful to the real wiring, not a workaround).
        DispatchPlayerCommandBehaviour dispatch = new DispatchPlayerCommandBehaviour(hm);
        dispatch.action();

        // Deterministically reproduce what JADE's own Agent.removeBehaviour does once the scheduler
        // retires a completed OneShotBehaviour, instead of waiting on its real (unbounded) timing.
        dispatch.setAgent(null);
        assertNull(dispatch.getAgent(), "setup sanity: the behaviour's agent reference must be null now");

        // The real path: publish through the bus (ASYNC), never a direct method call. Matched
        // specifically on ACCEPT_PROPOSAL (not "any message") -- the HM's own auto-flow can send a
        // contracted vessel other unrelated traffic on its own schedule, which must not be mistaken
        // for this dispatch succeeding.
        hm.eventBus().publish(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, "D001", Map.of()));

        ACLMessage reply = pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 5000);
        assertNotNull(reply, "a chat command published after the behaviour's agent reference is nulled "
                + "must still reach the vessel -- this is exactly what failed in the manual play-test");
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

    /**
     * Task 24's negative IT for the previously-permanent strand: hold under storm, then the
     * weather-clear signal, then the FULL re-dispatch cascade — fresh CFP (fresh cfpId), bid,
     * award, vessel resume — with tug charging exactly once per escort job and no phantom
     * extra-tug surcharge (same tug re-awarded).
     */
    @Test
    void weatherClear_redispatchesHeldVesselExactlyOnce() throws Exception {
        InTransitFixture scene = getVesselInTransit("t14");
        String vesselId = "V-t14";
        Probe weatherProbe = spawnProbe("t14_weather", "weather");

        // HOLD: a storm past every limit cancels the escort and marks the vessel weather-held.
        weatherProbe.outbox.put(thresholdAlert(scene.hm, 55, "poor", 6.0, "stormy"));
        assertNotNull(pollUntil(scene.tug.inbox, m -> m.getPerformative() == ACLMessage.CANCEL, 10_000),
                "the hold must CANCEL the in-transit tug");
        VesselTracking held = pollTracking(scene.hm, vesselId, VesselTracking::weatherHeld, 10_000);
        assertNotNull(held, "the hold must mark the tracking weatherHeld");
        assertEquals(VesselTracking.Stage.AWAITING_TUG, held.stage());
        assertTrue(held.assignedTugs().isEmpty(), "the cancelled escort must be dropped");
        ACLMessage holdInform = pollUntil(scene.vessel.inbox,
                m -> m.getPerformative() == ACLMessage.INFORM
                        && m.getContent() != null && m.getContent().contains("weather_hold"), 10_000);
        assertNotNull(holdInform, "the vessel must be told about the hold");

        // CLEAR: recovered conditions -> the HM re-runs the Prolog gate and re-initiates the CNP.
        weatherProbe.outbox.put(clearAlert(scene.hm, 12, "good", 0.5, "sunny"));
        ACLMessage cfp2 = pollUntil(scene.tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp2, "the weather-clear signal must re-initiate the tug CNP");
        scene.tug.outbox.put(bidReply(cfp2, 120.0, 6.0, 0.8));
        ACLMessage award2 = pollUntil(scene.tug.inbox,
                m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000);
        assertNotNull(award2, "the re-dispatched CNP must award the lone bidder");

        ACLMessage resume = pollUntil(scene.vessel.inbox,
                m -> m.getPerformative() == ACLMessage.INFORM
                        && m.getContent() != null && m.getContent().contains("weather_resume"), 10_000);
        assertNotNull(resume, "the vessel must be told the escort resumes");

        VesselTracking resumed = pollTracking(scene.hm, vesselId,
                t -> t.stage() == VesselTracking.Stage.IN_TRANSIT, 10_000);
        assertNotNull(resumed, "the award must move the vessel back to IN_TRANSIT");
        assertFalse(resumed.weatherHeld(), "the held flag must clear on re-dispatch");
        assertEquals(List.of("t14_tug"), resumed.assignedTugs());

        // Exactly-once charging: two awards (two genuine escort jobs) under two DISTINCT cfpIds
        // -> two (conversationId, tugId) ledger keys -> exactly two tug_job expenses; and the
        // SAME tug re-awarded means the vessel's distinct-tug set never grows past one -> no
        // extra-tug surcharge.
        List<TugJobAwardedEvent> awards = EventBusProbe.published(boot.getEventBus()).stream()
                .filter(TugJobAwardedEvent.class::isInstance).map(TugJobAwardedEvent.class::cast)
                .filter(e -> vesselId.equals(e.vesselId()))
                .toList();
        assertEquals(2, awards.size(), "one award per escort job, hold->clear = two jobs");
        assertNotEquals(awards.get(0).conversationId(), awards.get(1).conversationId(),
                "the re-dispatch must mint a FRESH cfpId — reusing the cancelled one would dedupe the charge");
        assertEquals(2, scene.hm.walletLedger().expenseHistory().stream()
                        .filter(e -> ExpenseRules.SOURCE_TUG_JOB.equals(e.source())).count(),
                "exactly one charge per escort job — never zero, never doubled");
        assertTrue(scene.hm.walletLedger().incomeHistory().stream()
                        .noneMatch(e -> IncomeRules.SOURCE_EXTRA_TUG.equals(e.source())),
                "re-awarding the same tug must not bill the vessel an extra-tug surcharge");

        // A second clear with nothing held left must be a no-op (the weatherHeld flag is the
        // exactly-once guard on the dispatch side).
        weatherProbe.outbox.put(clearAlert(scene.hm, 12, "good", 0.5, "sunny"));
        assertNeverArrives(scene.tug.inbox, ACLMessage.CFP, 3000);
    }

    /**
     * Checkpoint-#5 B1 (GHOST VESSELS): a departed vessel's tracking entry must die with the
     * agent — the observed run held three ALREADY-DEPARTED walk-ins on a storm and then
     * re-dispatched real (charged) escorts for all three on the clear.
     */
    @Test
    void departedVessel_isNeverHeldNorRedispatchedNorCharged() throws Exception {
        InTransitFixture scene = getVesselInTransit("t16");
        String vesselId = "V-t16";
        Probe weatherProbe = spawnProbe("t16_weather", "weather");
        long chargesAfterAward = scene.hm.walletLedger().expenseHistory().stream()
                .filter(e -> ExpenseRules.SOURCE_TUG_JOB.equals(e.source())).count();
        assertEquals(1, chargesAfterAward, "setup: the original escort award was charged once");

        // The vessel leaves (DepartBehaviour's wire shape) — the HM must close the tracking.
        ACLMessage departed = MessageFactory.create(ACLMessage.INFORM);
        departed.addReceiver(scene.hm.getAID());
        departed.setContent(TerminalJson.write(Map.of("intent", "departed", "vessel_id", vesselId)));
        scene.vessel.outbox.put(departed);
        long deadline = System.currentTimeMillis() + 10_000;
        while (scene.hm.activeVessels().containsKey(vesselId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20); // bounded poll — the documented no-Awaitility exception pattern
        }
        assertFalse(scene.hm.activeVessels().containsKey(vesselId),
                "departure must remove the tracking entry");

        // A storm now finds nothing to hold; a clear finds nothing to re-dispatch.
        weatherProbe.outbox.put(thresholdAlert(scene.hm, 55, "poor", 6.0, "stormy"));
        assertNeverArrives(scene.tug.inbox, ACLMessage.CANCEL, 3000);
        weatherProbe.outbox.put(clearAlert(scene.hm, 12, "good", 0.5, "sunny"));
        assertNeverArrives(scene.tug.inbox, ACLMessage.CFP, 3000);
        assertEquals(chargesAfterAward, scene.hm.walletLedger().expenseHistory().stream()
                        .filter(e -> ExpenseRules.SOURCE_TUG_JOB.equals(e.source())).count(),
                "a ghost must never generate a tug charge");
    }

    /**
     * Checkpoint-#5 B2 (CONCURRENT CNP TRIPLE-BOOKING): same-instant re-dispatches used to put
     * every CFP on the wire before any award, so the best tugs won every contract at once. The
     * coordinator now serializes sessions — the next CFP goes out only after the previous award,
     * so a busy tug can REFUSE and the assignments come out disjoint.
     */
    @Test
    void simultaneousRedispatches_serializeIntoDisjointTugAssignments() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_FRIENDLY_REAL_SECONDS_PER_DAY);
        Probe tugA = spawnProbe("t17_tug_a", "tug-escort");
        Probe tugB = spawnProbe("t17_tug_b", "tug-escort");
        Probe weatherProbe = spawnProbe("t17_weather", "weather");
        // 15,000 t cargo needs exactly ONE tug (R9 <20000 -> 1; cargo has no type minimum), so
        // each session awards one winner and the second session must find a DIFFERENT free tug.
        VesselSpec spec1 = new VesselSpec("T17V1", "cargo_vessel", 9.0, 150.0, 15000, "general_cargo", 0L);
        VesselSpec spec2 = new VesselSpec("T17V2", "cargo_vessel", 9.0, 150.0, 15000, "general_cargo", 0L);
        assertEquals(1, PrologQueries.tugsRequired("cargo_vessel", 15000, 150.0),
                "fixture sanity: one tug per vessel");
        // Two weather-held vessels (the sanctioned direct-seed seam) — one clear alert fires
        // both re-dispatches in the same behaviour pass, the exact triple-booking shape.
        hm.activeVessels().put("T17V1", VesselTracking
                .arriving("T17V1", spec1, VesselTracking.Channel.WALK_IN, hm.getAID())
                .withStage(VesselTracking.Stage.AWAITING_TUG).withBerth("berth_1").withWeatherHeld(true));
        hm.activeVessels().put("T17V2", VesselTracking
                .arriving("T17V2", spec2, VesselTracking.Channel.WALK_IN, hm.getAID())
                .withStage(VesselTracking.Stage.AWAITING_TUG).withBerth("berth_3").withWeatherHeld(true));

        weatherProbe.outbox.put(clearAlert(hm, 12, "good", 0.5, "sunny"));

        // Session 1: both tugs bid; A's cheaper bid wins (R13 score).
        ACLMessage cfp1atA = pollUntil(tugA.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        ACLMessage cfp1atB = pollUntil(tugB.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp1atA);
        assertNotNull(cfp1atB);
        assertEquals(cfp1atA.getConversationId(), cfp1atB.getConversationId(), "one session, one cfpId");
        tugA.outbox.put(bidReply(cfp1atA, 100.0, 5.0, 0.9));
        tugB.outbox.put(bidReply(cfp1atB, 200.0, 5.0, 0.9));

        // THE serialization assertion: tug A's NEXT message must be session 1's ACCEPT — before
        // the fix both CFPs raced out together, so the next message was session 2's CFP.
        ACLMessage nextAtA = pollUntil(tugA.inbox, m -> true, 10_000);
        assertNotNull(nextAtA);
        assertEquals(ACLMessage.ACCEPT_PROPOSAL, nextAtA.getPerformative(),
                "the award must land before any further CFP reaches the tug");
        assertEquals(cfp1atA.getConversationId(), nextAtA.getConversationId());

        // Session 2 only now goes out. A is busy (its real TugAgent would REFUSE on
        // currentJob != null — the probe simulates exactly that); B wins.
        ACLMessage cfp2atA = pollUntil(tugA.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp2atA, "the queued session must launch after the first award");
        assertNotEquals(cfp1atA.getConversationId(), cfp2atA.getConversationId(), "fresh cfpId per session");
        tugA.outbox.put(MessageFactory.reply(cfp2atA, ACLMessage.REFUSE));
        ACLMessage cfp2atB = pollUntil(tugB.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp2atB);
        tugB.outbox.put(bidReply(cfp2atB, 200.0, 5.0, 0.9));
        ACLMessage acceptAtB = pollUntil(tugB.inbox,
                m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000);
        assertNotNull(acceptAtB, "the free tug must win the queued session");
        assertEquals(cfp2atB.getConversationId(), acceptAtB.getConversationId());

        // Disjoint assignments, one per vessel; money matches assignments exactly. Which vessel
        // got which tug depends on the clear-sweep's ConcurrentHashMap iteration order — assert
        // order-insensitively (the task-20 DF-order lesson: never couple to an unordered
        // collection's iteration).
        VesselTracking v1 = pollTracking(hm, "T17V1",
                t -> t.stage() == VesselTracking.Stage.IN_TRANSIT, 10_000);
        VesselTracking v2 = pollTracking(hm, "T17V2",
                t -> t.stage() == VesselTracking.Stage.IN_TRANSIT, 10_000);
        assertNotNull(v1);
        assertNotNull(v2);
        assertEquals(1, v1.assignedTugs().size(), "one tug per vessel");
        assertEquals(1, v2.assignedTugs().size(), "one tug per vessel");
        assertEquals(Set.of("t17_tug_a", "t17_tug_b"),
                Set.of(v1.assignedTugs().get(0), v2.assignedTugs().get(0)),
                "disjoint single-tug assignments covering both vessels");
        List<TugJobAwardedEvent> awards = EventBusProbe.published(boot.getEventBus()).stream()
                .filter(TugJobAwardedEvent.class::isInstance).map(TugJobAwardedEvent.class::cast)
                .toList();
        assertEquals(2, awards.size(), "one award per vessel — never a double-booking");
        assertEquals(2, awards.stream().map(TugJobAwardedEvent::tugId).distinct().count());
        assertEquals(300.0, hm.walletLedger().expenseHistory().stream()
                        .filter(e -> ExpenseRules.SOURCE_TUG_JOB.equals(e.source()))
                        .mapToDouble(it.unige.portcommand.harbourmaster.ExpenseEvent::amount).sum(), 0.001,
                "charges match the two real assignments (100 + 200), nothing doubled");
    }

    // ==================== Customs (task-09's deferred note) ====================

    @Test
    void customsFlagged_marksBlockedAndNotifies() throws Exception {
        ServiceContract contract = new ServiceContract("CONTRACT-CU1", "CU1", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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
        // Task 24: this crossing is no longer wallet-silent — the HM-constructed
        // DayRolloverCoordinator settles the day synchronously (the ONE €5,200 fixed charge).
        // Detect-only still holds for the BEHAVIOUR (INVARIANTS, tasks 11/20): the only
        // mutations after a zero-deal day are the coordinator's five fixed lines.
        assertEquals(startingBalance - ExpenseRules.dailyFixed(), hm.walletLedger().balance(), 0.0001,
                "an empty day settles at exactly the fixed daily cost, nothing else");
        assertEquals(5, hm.walletLedger().expenseHistory().size(),
                "exactly the five fixed lines — no behaviour-attributed ledger writes");
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
        ServiceContract contract = new ServiceContract(contractId, vesselId, "cargo_vessel", "berth_1", 5200.0, 8, 0L);
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

    /** The vessel-side REFUSE {@code EvaluateCounterOfferBehaviour} sends on a malformed counter:
     * a reply on the {@code nego-} conversation with NO protocol tag (audit A-01). */
    private static ACLMessage negotiationRefuse(HarbourMasterAgent hm, String reason, String conversationId) {
        ACLMessage refuse = MessageFactory.create(ACLMessage.REFUSE);
        refuse.addReceiver(hm.getAID());
        refuse.setConversationId(conversationId);
        refuse.setContent(TerminalJson.write(Map.of("intent", "refuse", "reason", reason)));
        return refuse;
    }

    private static ACLMessage withdrawMessage(HarbourMasterAgent hm, String reason, String conversationId) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hm.getAID());
        inform.setConversationId(conversationId);
        inform.setContent(TerminalJson.write(Map.of("intent", "withdraw", "reason", reason)));
        return inform;
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

    /** The task-24 recovery signal — same protocol/shape, {@code event=weather_clear}, no priority. */
    private static ACLMessage clearAlert(HarbourMasterAgent hm, int windKnots, String visibility, double swell,
                                         String state) {
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.getAID());
        alert.setContent(TerminalJson.write(Map.of(
                "event", "weather_clear", "wind_knots", windKnots, "visibility", visibility,
                "swell", swell, "state", state)));
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        return alert;
    }

    /** Polls until the tracked stage EQUALS {@code stage}; fails the test with a diagnostic
     * (current entry, or "absent") if it never arrives. Returns the observed tracking. */
    private static VesselTracking assertStageReached(HarbourMasterAgent hm, String vesselId,
                                                     VesselTracking.Stage stage) throws InterruptedException {
        VesselTracking tracking = pollTracking(hm, vesselId, t -> t.stage() == stage, 10_000);
        if (tracking == null) {
            fail("tracking for " + vesselId + " never reached stage " + stage + " — currently: "
                    + hm.activeVessels().get(vesselId));
        }
        return tracking;
    }

    /** A vessel/tug flow report INFORM ({@code service_complete}/{@code departed}/
     * {@code escort_complete} shapes) — mirrors the production senders exactly:
     * plain INFORM on the standard envelope, no protocol tag. */
    private static ACLMessage flowReport(HarbourMasterAgent hm, Map<String, Object> content,
                                         String conversationId) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hm.getAID());
        if (conversationId != null) {
            inform.setConversationId(conversationId);
        }
        inform.setContent(TerminalJson.write(content));
        return inform;
    }

    /** Bounded poll on the HM's tracking map until {@code condition} holds for {@code vesselId}. */
    private static VesselTracking pollTracking(HarbourMasterAgent hm, String vesselId,
                                               Predicate<VesselTracking> condition, long totalMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            VesselTracking tracking = hm.activeVessels().get(vesselId);
            if (tracking != null && condition.test(tracking)) {
                return tracking;
            }
            Thread.sleep(20); // bounded poll — the documented no-Awaitility exception pattern
        }
        return null;
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
