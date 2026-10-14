package it.unige.portcommand.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.behaviours.MovementControl;
import it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleEmergencyBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.behaviours.coordination.TransitToVesselBehaviour;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.MovementSafetyEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S16 acceptance (ADR-20): a ship never moves while it is unsafe, or without its required tugs
 * and customs clearance, and every movement goes through the one gate.
 *
 * <p>The HarbourMaster is real; every other agent is an {@link HmProbeAgent} puppet (the
 * {@code HarbourMasterAgentIT} idiom), so each message that makes a ship move — or keeps it
 * where it is — is put on the wire by the test and can be withheld. Each case names the decision
 * (B1, B2, B4, C1–C5, C9 of the S16 decision files) it pins, and every one of them fails on the
 * pre-S16 code: there, a berth grant started the ship, an award marked it "in transit", nothing
 * asked the weather before hiring, and a ship whose tugs were all busy sailed in alone.
 *
 * <p>Time: a bare {@code JadeBootstrap} runs no wall-clock advancer, so game time stands still
 * unless a case advances it; Contract Net windows and retries are wall wakers sized from the
 * pace, which is why the Contract Net cases run at {@link #CNP_PACE} (a 5-sim-second bid window
 * is half a real second there). Every wait has a deadline; "never arrives" is a bounded drain.
 */
@Tag("integration")
class MovementControlIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 10 game seconds per wall second: bid window 500 ms, retry gap 3 s. */
    private static final long CNP_PACE = 8_640L;
    /** 288 game seconds per wall second: the sim-minute tick is ~208 ms (the port-status pace). */
    private static final long TICK_PACE = 300L;

    private static final VesselSpec CARGO = new VesselSpec("C001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
    private static final ServiceContract CARGO_CONTRACT =
            new ServiceContract("CONTRACT-1", "C001", "cargo_vessel", "berth_1", 5200.0, 8, 0L);
    private static final VesselSpec FERRY = new VesselSpec("F001", "ferry", 5.0, 110.0, 9000, "general_cargo", 0L);
    private static final ServiceContract FERRY_CONTRACT =
            new ServiceContract("CONTRACT-F1", "F001", "ferry", "berth_4", 1800.0, 4, 0L);

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

    // ===================================================================================== B1

    /**
     * B1 + C2: the award leaves the ship where it is; one tug on station is not an escort; the
     * second one is — and then ONE action orders both tugs to begin and the ship to proceed.
     */
    @Test
    @Timeout(60)
    void aShipIsSentOnlyWhenItsWholeEscortIsOnStation() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("b1_vessel", null);
        Probe tug1 = spawnProbe("b1_tug1", "tug-escort");
        Probe tug2 = spawnProbe("b1_tug2", "tug-escort");
        assertEquals(2, PrologQueries.tugsRequired("cargo_vessel", 30000, 150.0), "fixture: R11 says 2");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", CARGO, "b1_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "the berth is granted");
        ACLMessage cfp1 = pollUntil(tug1.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        ACLMessage cfp2 = pollUntil(tug2.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp1);
        assertNotNull(cfp2);
        tug1.outbox.put(bidReply(cfp1, 100.0, 5.0, 0.9));
        tug2.outbox.put(bidReply(cfp2, 110.0, 6.0, 0.9));
        assertNotNull(pollUntil(tug1.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));
        assertNotNull(pollUntil(tug2.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));

        VesselTracking hired = pollTracking(hm, "C001", t -> t.assignedTugs().size() == 2, 10_000);
        assertNotNull(hired, "both tugs are assigned");
        assertEquals(Stage.AWAITING_TUG, hired.stage(),
                "hired is not arrived: the ship is still at the channel entry (pre-S16: IN_TRANSIT here)");

        tug1.outbox.put(onStation(hm, "C001", cfp1.getConversationId()));
        assertTrue(pollUntilTrue(() -> hm.movement().onStation("C001").containsKey("b1_tug1"), 10_000));
        assertNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_500),
                "one tug of two on station: the ship is not sent");
        assertEquals(Stage.AWAITING_TUG, hm.activeVessels().get("C001").stage());

        tug2.outbox.put(onStation(hm, "C001", cfp2.getConversationId()));
        ACLMessage proceed = pollUntil(vessel.inbox, MovementControlIT::isProceed, 10_000);
        assertNotNull(proceed, "the whole escort is on station: the ship is ordered to proceed");
        assertEquals(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL, proceed.getProtocol());
        // A literal, not the code's own answer: channel entry (700,200) to berth_1 (400,150) is
        // 304 px = 5.07 km at the S16 scale, 13.7 game minutes at the tugs' 12 kn (ChannelLegTest
        // pins all four berths). Pre-S16 the ship made a fixed 120-second leg on its own.
        assertEquals(821_107L, content(proceed).get("transit_sim_millis").asLong(), 1_000L,
                "the ship's leg lasts as long as the escort's (same distance, same speed)");
        for (Probe tug : List.of(tug1, tug2)) {
            ACLMessage order = pollUntil(tug.inbox,
                    m -> TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL.equals(m.getProtocol()), 10_000);
            assertNotNull(order, "each tug is ordered to begin the escort");
            assertEquals("begin_escort", content(order).get("event").asText());
            assertEquals(cfp1.getConversationId(), order.getConversationId(),
                    "on the Contract Net conversation it was hired on, so an old order can never be mistaken for it");
        }
        assertNotNull(pollTracking(hm, "C001", t -> t.stage() == Stage.IN_TRANSIT, 10_000),
                "IN_TRANSIT now means the ship is moving");

        vessel.outbox.put(flowReport(hm, Map.of("intent", "docked", "vessel_id", "C001", "berth_id", "berth_1")));
        assertNotNull(pollTracking(hm, "C001", t -> t.stage() == Stage.DOCKED, 10_000),
                "the ship's own arrival report docks it");
    }

    /** B1: a ship that needs no tug proceeds at its grant — and is DOCKED by its own report. */
    @Test
    @Timeout(60)
    void aShipThatNeedsNoTugProceedsAtOnce_andItsOwnArrivalDocksIt() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-F1", FERRY_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("b1f_vessel", null);
        Probe tug = spawnProbe("b1f_tug", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-F1", FERRY, "b1f_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));
        assertNotNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 10_000),
                "no escort to wait for, calm weather, no customs office here: proceed");
        assertNotNull(pollTracking(hm, "F001", t -> t.stage() == Stage.IN_TRANSIT, 10_000));
        assertNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 1_500),
                "a ferry is never tendered for");

        vessel.outbox.put(flowReport(hm, Map.of("intent", "docked", "vessel_id", "F001", "berth_id", "berth_4")));
        assertNotNull(pollTracking(hm, "F001", t -> t.stage() == Stage.DOCKED, 10_000),
                "a ferry is DOCKED in the tracking (pre-S16 it read IN_TRANSIT for its whole stay)");
    }

    // ===================================================================================== B2 / B4

    /**
     * B2 (grant, tug hiring) + B4 (resume only once tugs are hired): a berth granted in a storm
     * is reserved and the ship is held at once, with its rules, and no tug is tendered for; the
     * clear tenders, and the ship hears {@code weather_resume} at the award — not before.
     */
    @Test
    @Timeout(60)
    void aGrantInAStormHoldsTheShipAndHiresNobody_andTheClearHiresBeforeItResumes() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("b2_vessel", null);
        Probe tug1 = spawnProbe("b2_tug1", "tug-escort");
        Probe tug2 = spawnProbe("b2_tug2", "tug-escort");
        Probe weather = spawnProbe("b2_weather", "weather");

        weather.outbox.put(weatherAlert(hm, "weather_threshold", 32, "poor", 4.0, "stormy"));
        assertTrue(pollUntilTrue(() -> "stormy".equals(hm.lastWeather().state()), 10_000));

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", CARGO, "b2_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "the berth is still granted — a grant reserves, it does not move anything");
        ACLMessage hold = pollUntil(vessel.inbox, m -> isOrder(m, "weather_hold"), 10_000);
        assertNotNull(hold, "and the ship is held at once");
        assertTrue(content(hold).get("reason").asText().endsWith("[R16,R19]"),
                "naming the rules that refused it (32 kn is inside a cargo vessel's 45 kn limit; poor "
                        + "visibility and the stormy sea state are not) — got " + content(hold).get("reason"));
        VesselTracking held = pollTracking(hm, "C001", VesselTracking::weatherHeld, 10_000);
        assertNotNull(held);
        assertEquals(Stage.AWAITING_TUG, held.stage());
        assertEquals("berth_1", held.assignedBerth(), "the berth stays reserved");
        assertEquals(List.of("R16", "R19"), hm.movement().holdRules("C001"));
        // S17b (E1): the score's "blocked" - the gate refused this movement, and says with which rules.
        MovementSafetyEvent blocked = awaitEvent(MovementSafetyEvent.class,
                e -> e.kind() == MovementSafetyEvent.Kind.BLOCKED && "C001".equals(e.vesselId()), 5_000);
        assertNotNull(blocked, "a hold is announced as a blocked movement");
        assertEquals(List.of("R16", "R19"), blocked.ruleIds());
        assertNull(pollUntil(tug1.inbox, m -> m.getPerformative() == ACLMessage.CFP, 2_000),
                "no tug is tendered for into the storm (pre-S16: the CFP went out at the grant)");
        // The storm goes on: more weather messages are still the SAME hold.
        weather.outbox.put(weatherAlert(hm, "weather_threshold", 34, "poor", 4.5, "stormy"));
        assertTrue(pollUntilTrue(() -> hm.lastWeather().windKnots() == 34, 10_000));
        assertEquals(1, safetyEvents(MovementSafetyEvent.Kind.BLOCKED, "C001"),
                "once per ship per hold, not once per weather message");

        weather.outbox.put(weatherAlert(hm, "weather_clear", 20, "good", 1.0, "sunny"));
        ACLMessage cfp1 = pollUntil(tug1.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        ACLMessage cfp2 = pollUntil(tug2.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp1, "the clear tenders for the escort");
        assertNotNull(cfp2);
        assertFalse(hm.activeVessels().get("C001").weatherHeld(), "the hold is lifted");
        assertNull(pollUntil(vessel.inbox, m -> isOrder(m, "weather_resume"), 300),
                "released, but not told yet: nobody is hired");
        tug1.outbox.put(bidReply(cfp1, 100.0, 5.0, 0.9));
        tug2.outbox.put(bidReply(cfp2, 110.0, 6.0, 0.9));
        assertNotNull(pollUntil(vessel.inbox, m -> isOrder(m, "weather_resume"), 10_000),
                "weather_resume reaches the ship once its tugs are actually hired");
        assertEquals(Stage.AWAITING_TUG, hm.activeVessels().get("C001").stage(),
                "and it still waits for them to reach it");
        assertEquals(1, safetyEvents(MovementSafetyEvent.Kind.BLOCKED, "C001"), "the release blocks nothing");
        assertEquals(0, safetyEvents(MovementSafetyEvent.Kind.UNSAFE_MOVE, null), "and nothing unsafe was ordered");
    }

    /**
     * S17b (decision E1): the score's "unsafe moves carried out" is counted where an order to
     * move is written, by asking the gate once more at that moment. Here the reading turns
     * unsafe by ANOTHER road than a weather message - no sweep runs, nothing is held yet - and a
     * ferry (no tug to wait for) is granted its berth. The grant asks the gate: the ship is held,
     * that is one blocked movement, and no order goes out. If a later change ever let the order
     * through, {@code auditMoveOrder} is what would see it: this test then fails on its first
     * assertion with one unsafe move counted (proved by reverting both checks at once).
     */
    @Test
    @Timeout(60)
    void anOrderToMoveIsNeverWrittenUnderAnUnsafeReading() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-F1", FERRY_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("e1_vessel", null);
        hm.recordWeather(new HarbourMasterAgent.WeatherReading(38, "poor", 3.5, "stormy"));

        vessel.outbox.put(contractRequest(hm, "CONTRACT-F1", FERRY, "e1_vessel"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "fixture: the berth is granted - a grant reserves, it does not move anything");
        assertNotNull(pollTracking(hm, "F001", t -> t.assignedBerth() != null, 10_000));
        // A bounded wait for something that must NOT come: the order to proceed.
        ACLMessage proceed = pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_500);

        assertEquals(0, safetyEvents(MovementSafetyEvent.Kind.UNSAFE_MOVE, null),
                "no order to move may be written while the gate says the move is unsafe");
        assertNull(proceed, "and none was");
        assertTrue(hm.activeVessels().get("F001").weatherHeld(), "the ship is held instead");
        assertEquals(1, safetyEvents(MovementSafetyEvent.Kind.BLOCKED, "F001"), "one blocked movement");

        // The audit itself, asked directly with the reading no order can reach it with: it must
        // name the ship and the rules, and the day's score must lose its five points for it.
        MovementControl.auditMoveOrder(hm, hm.activeVessels().get("F001"));
        MovementSafetyEvent unsafe = awaitEvent(MovementSafetyEvent.class,
                e -> e.kind() == MovementSafetyEvent.Kind.UNSAFE_MOVE, 5_000);
        assertNotNull(unsafe, "an order written under this reading is an unsafe move");
        assertEquals("F001", unsafe.vesselId());
        assertEquals(List.of("R15", "R16", "R19"), unsafe.ruleIds(), "38 kn is over a ferry's 35 kn limit");
        it.unige.portcommand.gui.events.ScoreChangedEvent scored = awaitEvent(
                it.unige.portcommand.gui.events.ScoreChangedEvent.class, e -> e.score().unsafeMoves() == 1, 5_000);
        assertNotNull(scored, "and it reaches the score");
        assertEquals(20.0, scored.score().safetyPoints(), 1e-9);
        assertEquals(1, scored.score().blockedMoves());
    }

    /**
     * B2 (tug hiring): the weather turns between the tender and the award — the ship, which is
     * only WAITING, is held by the sweep, and the award then rejects the bids: nobody is hired or
     * charged. (What rejects them is the award's "held" check. Its own weather verdict, a few
     * lines further down, is a backstop that cannot be reached while every weather message runs
     * the sweep first; it is kept for a reading that changes by another road.)
     */
    @Test
    @Timeout(60)
    void aStormBetweenTenderAndAwardRejectsTheBidsAndHoldsTheShip() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT), 86_400L, false);
        Probe vessel = spawnProbe("b2a_vessel", null);
        Probe tug = spawnProbe("b2a_tug", "tug-escort");
        Probe weather = spawnProbe("b2a_weather", "weather");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", CARGO, "b2a_vessel"));
        ACLMessage cfp = pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp, "tendered in calm weather");
        // The bid window is 5 real seconds at this pace: the storm lands inside it.
        weather.outbox.put(weatherAlert(hm, "weather_threshold", 46, "poor", 4.5, "stormy"));
        assertNotNull(pollTracking(hm, "C001", VesselTracking::weatherHeld, 10_000),
                "the hold sweep catches a ship that is only WAITING (pre-S16: only 'in transit with tugs')");
        tug.outbox.put(bidReply(cfp, 100.0, 5.0, 0.9));
        ACLMessage answer = pollUntil(tug.inbox,
                m -> m.getPerformative() == ACLMessage.REJECT_PROPOSAL
                        || m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 15_000);
        assertNotNull(answer, "the bidder is answered");
        assertEquals(ACLMessage.REJECT_PROPOSAL, answer.getPerformative(), "nobody is hired into the storm");
        assertNull(awaitEvent(TugJobAwardedEvent.class, e -> "C001".equals(e.vesselId()), 500),
                "and nobody is charged");
        assertEquals(List.of("R15", "R16", "R17", "R19"), hm.movement().holdRules("C001"),
                "46 kn is over a cargo vessel's limit too, and 4.5 m is over the swell limit");
    }

    /** B4: a held tanker (limit 30 kn) is not released at 29 or 28 kn — at 27 it is. */
    @Test
    @Timeout(60)
    void aHeldShipIsReleasedOnlyThreeKnotsUnderItsLimit() throws Exception {
        ServiceContract contract = new ServiceContract("CT-T1", "T001", "tanker", "berth_1", 9000.0, 10, 0L);
        VesselSpec tanker = new VesselSpec("T001", "tanker", 11.0, 200.0, 60000, "hazmat_class_3", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CT-T1", contract), CNP_PACE, false);
        Probe vessel = spawnProbe("b4_vessel", null);
        Probe tug = spawnProbe("b4_tug", "tug-escort");
        Probe weather = spawnProbe("b4_weather", "weather");

        weather.outbox.put(weatherAlert(hm, "weather_threshold", 31, "fair", 1.5, "cloudy"));
        assertTrue(pollUntilTrue(() -> hm.lastWeather().windKnots() == 31.0, 10_000));
        vessel.outbox.put(contractRequest(hm, "CT-T1", tanker, "b4_vessel"));
        assertNotNull(pollTracking(hm, "T001", VesselTracking::weatherHeld, 10_000),
                "31 kn is over a tanker's 30 kn limit: held [R15]");
        assertEquals(List.of("R15"), hm.movement().holdRules("T001"));

        for (int wind : new int[] {29, 28}) {
            weather.outbox.put(weatherAlert(hm, "weather_periodic", wind, "fair", 1.5, "cloudy"));
            assertTrue(pollUntilTrue(() -> hm.lastWeather().windKnots() == wind, 10_000));
            assertNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 1_200),
                    wind + " kn is under the limit but not by 3: still held, nobody tendered for "
                            + "(pre-S16: released at 29, held again at 30, an escort hired and cancelled each time)");
            assertTrue(hm.activeVessels().get("T001").weatherHeld());
        }
        weather.outbox.put(weatherAlert(hm, "weather_periodic", 27, "fair", 1.5, "cloudy"));
        assertNotNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000),
                "at 27 kn the tanker is released and its escort tendered for");
        assertFalse(hm.activeVessels().get("T001").weatherHeld());
    }

    /**
     * B2 (departure): a served ship asks to leave; in a storm it stays at its berth — which stays
     * occupied — and is cleared when the gate allows.
     */
    @Test
    @Timeout(60)
    void aDepartureWaitsAtTheBerthInAStorm() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-F1", FERRY_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("b2d_vessel", null);
        Probe weather = spawnProbe("b2d_weather", "weather");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-F1", FERRY, "b2d_vessel"));
        assertNotNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 10_000));
        vessel.outbox.put(flowReport(hm, Map.of("intent", "docked", "vessel_id", "F001", "berth_id", "berth_4")));
        assertNotNull(pollTracking(hm, "F001", t -> t.stage() == Stage.DOCKED, 10_000));

        weather.outbox.put(weatherAlert(hm, "weather_threshold", 38, "poor", 3.5, "stormy"));
        assertTrue(pollUntilTrue(() -> "stormy".equals(hm.lastWeather().state()), 10_000));
        assertFalse(hm.activeVessels().get("F001").weatherHeld(), "a docked ship is not 'held outside'");

        vessel.outbox.put(flowReport(hm, Map.of("intent", "service_complete", "vessel_id", "F001", "berth_id", "berth_4")));
        assertNull(pollUntil(vessel.inbox, m -> isOrder(m, "cleared_to_depart"), 2_000),
                "not cleared to sail into a storm (pre-S16: the ship left on its own, unasked)");
        assertEquals(Stage.DOCKED, hm.activeVessels().get("F001").stage(), "the berth stays occupied");
        assertEquals(List.of("R15", "R16", "R19"), hm.movement().holdRules("F001"),
                "38 kn is over a ferry's 35 kn limit");
        assertNotNull(awaitEvent(NotificationEvent.class,
                e -> e.text().contains("F001") && e.text().contains("stays at its berth")
                        && NotificationEvent.holdTopic("F001").equals(e.topic()), 5_000),
                "and the player is told, under the ship's hold topic (S18b, PT10)");
        // S17b (E1): a served ship kept at its berth is a blocked movement - once, however long
        // the storm lasts and however many weather messages ask again.
        assertNotNull(awaitEvent(MovementSafetyEvent.class,
                e -> e.kind() == MovementSafetyEvent.Kind.BLOCKED && "F001".equals(e.vesselId()), 5_000));
        weather.outbox.put(weatherAlert(hm, "weather_threshold", 41, "poor", 3.5, "stormy"));
        assertTrue(pollUntilTrue(() -> hm.lastWeather().windKnots() == 41, 10_000));
        assertEquals(1, safetyEvents(MovementSafetyEvent.Kind.BLOCKED, "F001"),
                "asked again by the next weather message: still the same hold");

        weather.outbox.put(weatherAlert(hm, "weather_clear", 18, "good", 1.0, "sunny"));
        assertNotNull(pollUntil(vessel.inbox, m -> isOrder(m, "cleared_to_depart"), 10_000),
                "cleared when the weather allows");
        assertNotNull(pollTracking(hm, "F001", t -> t.stage() == Stage.DEPARTING, 10_000));
        assertEquals(1, safetyEvents(MovementSafetyEvent.Kind.BLOCKED, "F001"));
        assertEquals(0, safetyEvents(MovementSafetyEvent.Kind.UNSAFE_MOVE, null));
    }

    // ===================================================================================== C1 / C2

    /**
     * C1: every tug is busy. After the quick retries the ship is NOT sent in alone — it keeps
     * waiting, the player is told once, and the next tug to report itself free is tendered to.
     */
    @Test
    @Timeout(90)
    void aShipWhoseTugsAreAllBusyKeepsWaiting_andIsTenderedAgainWhenATugFrees() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("c1_vessel", null);
        Probe tug = spawnProbe("c1_tug", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", CARGO, "c1_vessel"));
        // The first tender and three quick retries, none answered (the tug is "busy").
        for (int round = 1; round <= 4; round++) {
            assertNotNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 15_000),
                    "CFP round " + round);
        }
        assertNotNull(awaitEvent(NotificationEvent.class,
                e -> e.text().contains("C001") && e.text().contains("waiting for a free tug"), 15_000),
                "the quick retries are spent: the player is told the ship is waiting");
        assertNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_500),
                "and the ship is NOT sent in unescorted (pre-S16: it had sailed at the grant and "
                        + "docked for free)");
        VesselTracking waiting = hm.activeVessels().get("C001");
        assertEquals(Stage.AWAITING_TUG, waiting.stage());
        assertTrue(waiting.assignedTugs().isEmpty());
        assertNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 4_000),
                "no fifth CFP on its own: it waits for a tug to free");

        tug.outbox.put(flowReport(hm, Map.of("event", "tug_available", "tug_id", "c1_tug")));
        assertNotNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000),
                "a tug reports itself free: the ship is tendered for again");
    }

    /**
     * C2 (backlog A-08): a two-tug ship that finds one bidder is awarded that one — and waits.
     * The missing tug is tendered for again; the ship moves when both are on station.
     */
    @Test
    @Timeout(90)
    void anUnderStrengthEscortIsToppedUpBeforeTheShipMoves() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("c2_vessel", null);
        Probe tug1 = spawnProbe("c2_tug1", "tug-escort");
        Probe tug2 = spawnProbe("c2_tug2", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", CARGO, "c2_vessel"));
        ACLMessage cfp1 = pollUntil(tug1.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(pollUntil(tug2.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000));
        assertNotNull(cfp1);
        tug1.outbox.put(bidReply(cfp1, 100.0, 5.0, 0.9)); // tug2 stays silent: "busy"
        assertNotNull(pollUntil(tug1.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));
        assertNotNull(pollTracking(hm, "C001", t -> t.assignedTugs().equals(List.of("c2_tug1")), 10_000));

        tug1.outbox.put(onStation(hm, "C001", cfp1.getConversationId()));
        assertTrue(pollUntilTrue(() -> hm.movement().onStation("C001").containsKey("c2_tug1"), 10_000));
        assertNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_500),
                "one tug where R11 requires two: the ship waits (pre-S16: it sailed with one)");
        assertEquals(Stage.AWAITING_TUG, hm.activeVessels().get("C001").stage());

        ACLMessage topUp = pollUntil(tug2.inbox, m -> m.getPerformative() == ACLMessage.CFP, 15_000);
        assertNotNull(topUp, "the missing tug is tendered for again");
        tug2.outbox.put(bidReply(topUp, 110.0, 6.0, 0.9));
        assertNotNull(pollUntil(tug2.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));
        assertNotNull(pollTracking(hm, "C001", t -> t.assignedTugs().size() == 2, 10_000),
                "the second tug joins the first — the escort is merged, not replaced");
        assertNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_000),
                "hired, not yet arrived: still waiting");

        tug2.outbox.put(onStation(hm, "C001", topUp.getConversationId()));
        assertNotNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 10_000),
                "both on station: proceed");
        ACLMessage order1 = pollUntil(tug1.inbox,
                m -> TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL.equals(m.getProtocol()), 10_000);
        ACLMessage order2 = pollUntil(tug2.inbox,
                m -> TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL.equals(m.getProtocol()), 10_000);
        assertNotNull(order1);
        assertNotNull(order2);
        assertEquals(cfp1.getConversationId(), order1.getConversationId(), "each on its own hiring conversation");
        assertEquals(topUp.getConversationId(), order2.getConversationId());

        // S17b (decision E11) - born red. The ship has a two-tug escort; it was hired over two
        // tenders of one tug each, and the surcharge used to be counted per tender: nothing.
        List<it.unige.portcommand.harbourmaster.IncomeEvent> surcharges = hm.walletLedger().incomeHistory().stream()
                .filter(e -> IncomeRules.SOURCE_EXTRA_TUG.equals(e.source())).toList();
        assertEquals(1, surcharges.size(), "one tug beyond the first, whichever tender hired it: " + surcharges);
        assertEquals(500.0, surcharges.get(0).amount(), 1e-9);
        assertEquals("C001", surcharges.get(0).vesselId());
        assertEquals(2, hm.walletLedger().expenseHistory().stream()
                .filter(e -> ExpenseRules.SOURCE_TUG_JOB.equals(e.source())).count(), "and two jobs paid");
    }

    /**
     * At most ONE ship at a time holds an under-strength escort (S16 adversarial review).
     *
     * <p>A partial award keeps a tug on station until the rest arrives. If every waiting ship
     * were allowed one, four two-tug ships could each sit on one of the port's four tugs with
     * none able to move. So the second ship's partial award is refused — its bidder is rejected
     * and it waits for a whole escort — and the tug it would have parked goes to the first ship.
     */
    @Test
    @Timeout(90)
    void onlyOneShipAtATimeHoldsAnUnderStrengthEscort() throws Exception {
        ServiceContract secondContract =
                new ServiceContract("CONTRACT-3", "C003", "cargo_vessel", "berth_3", 4800.0, 8, 0L);
        VesselSpec secondSpec = new VesselSpec("C003", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT, "CONTRACT-3", secondContract),
                CNP_PACE, false);
        Probe first = spawnProbe("p1_vessel", null);
        Probe second = spawnProbe("p2_vessel", null);
        Probe tugA = spawnProbe("p_tug_a", "tug-escort");
        Probe tugB = spawnProbe("p_tug_b", "tug-escort");

        // C001 finds one bidder: an under-strength escort, and the ship keeps it.
        first.outbox.put(contractRequest(hm, "CONTRACT-1", CARGO, "p1_vessel"));
        ACLMessage cfpA = cfpFor(tugA, "C001", 10_000);
        assertNotNull(cfpA);
        assertNotNull(cfpFor(tugB, "C001", 10_000));
        tugA.outbox.put(bidReply(cfpA, 100.0, 5.0, 0.9)); // tug B stays silent: "busy"
        assertNotNull(pollUntil(tugA.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));
        assertNotNull(pollTracking(hm, "C001", t -> t.assignedTugs().equals(List.of("p_tug_a")), 10_000),
                "fixture: C001 holds one tug of two");

        // C003 is granted and tendered for; now tug B is free and is the only bidder.
        second.outbox.put(contractRequest(hm, "CONTRACT-3", secondSpec, "p2_vessel"));
        assertNotNull(pollUntil(second.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "fixture: the second ship is granted its berth");
        ACLMessage cfpSecond = cfpFor(tugB, "C003", 15_000);
        assertNotNull(cfpSecond, "and tendered for");
        tugB.outbox.put(bidReply(cfpSecond, 110.0, 6.0, 0.9));
        ACLMessage answer = pollUntil(tugB.inbox,
                m -> cfpSecond.getConversationId().equals(m.getConversationId())
                        && (m.getPerformative() == ACLMessage.REJECT_PROPOSAL
                        || m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL), 15_000);
        assertNotNull(answer, "the bidder is answered");
        assertEquals(ACLMessage.REJECT_PROPOSAL, answer.getPerformative(),
                "one tug of two while another ship already holds a partial escort: not awarded");
        ACLMessage told = pollUntil(second.inbox, m -> m.getPerformative() == ACLMessage.INFORM
                && "tug_hold".equals(eventOf(m)), 10_000);
        assertNotNull(told, "the ship is told it is waiting for tugs");
        assertEquals("not_enough_tugs", content(told).get("reason").asText());
        assertTrue(hm.activeVessels().get("C003").assignedTugs().isEmpty(),
                "and holds no tug: " + hm.activeVessels().get("C003"));
        assertNull(awaitEvent(TugJobAwardedEvent.class, e -> "C003".equals(e.vesselId()), 300),
                "nobody is charged for a tug that was not hired");

        // The free tug goes to the ship that is one short - and completes its escort.
        ACLMessage topUp = cfpFor(tugB, "C001", 15_000);
        assertNotNull(topUp, "the first ship's missing tug is tendered for again");
        tugB.outbox.put(bidReply(topUp, 110.0, 6.0, 0.9));
        assertNotNull(pollTracking(hm, "C001", t -> t.assignedTugs().size() == 2, 10_000),
                "the ship that was closest to a full escort gets it");
    }

    /**
     * A ship's own report that it is moored is the truth, even when a hold crossed it on the
     * wire (S16 adversarial review). A weather message handled while the ship was on the last
     * tick of its leg holds an entry whose ship then docks and ignores the hold. The report used
     * to be refused as stale: the moored ship stayed "held outside", and the clearing hired and
     * paid a fresh escort for it.
     */
    @Test
    @Timeout(60)
    void aShipThatDocksWhileAHoldIsOnItsWayToItIsMoored_notHeld() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-F1", FERRY_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("dk_vessel", null);
        Probe tug = spawnProbe("dk_tug", "tug-escort");
        Probe weather = spawnProbe("dk_weather", "weather");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-F1", FERRY, "dk_vessel"));
        assertNotNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 10_000), "fixture: under way");
        assertNotNull(pollTracking(hm, "F001", t -> t.stage() == Stage.IN_TRANSIT, 10_000));

        weather.outbox.put(weatherAlert(hm, "weather_threshold", 36, "poor", 3.0, "stormy"));
        assertNotNull(pollUntil(vessel.inbox, m -> isOrder(m, "weather_hold"), 10_000),
                "fixture: the storm holds the moving ferry (35 kn is its limit)");
        assertNotNull(pollTracking(hm, "F001", VesselTracking::weatherHeld, 10_000));

        // It was on the last tick of its leg: it is alongside, and says so.
        vessel.outbox.put(flowReport(hm, Map.of("intent", "docked", "vessel_id", "F001", "berth_id", "berth_4")));
        VesselTracking moored = pollTracking(hm, "F001", t -> t.stage() == Stage.DOCKED, 10_000);
        assertNotNull(moored, "the ship's own report docks it - tracking=" + hm.activeVessels().get("F001"));
        assertFalse(moored.weatherHeld(), "a moored ship is not 'held outside'");
        assertTrue(hm.movement().holdRules("F001").isEmpty(), "and no hold rule is shown for it");

        drain(vessel.inbox);
        weather.outbox.put(weatherAlert(hm, "weather_clear", 20, "good", 1.0, "sunny"));
        assertTrue(pollUntilTrue(() -> "sunny".equals(hm.lastWeather().state()), 10_000));
        assertNull(pollUntil(vessel.inbox, m -> isProceed(m) || isOrder(m, "weather_resume"), 1_500),
                "the clearing has nothing to release: no resume, no second order to proceed");
        assertNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 500),
                "and no escort is tendered for a ship at its berth");
        assertEquals(Stage.DOCKED, hm.activeVessels().get("F001").stage());
    }

    /**
     * A tug that reports on station for a ship ALREADY under way is given its order again
     * (S16 adversarial review). That is what a save taken between the order and the tug reading
     * it restores: the ship moving, the tug waiting. Sending the tug home, as for any other
     * report nobody was waiting for, left the ship to finish its leg unescorted.
     */
    @Test
    @Timeout(60)
    void aTugOnStationForAShipAlreadyUnderWayIsOrderedToEscortAgain() throws Exception {
        VesselSpec oneTug = new VesselSpec("C001", "cargo_vessel", 9.0, 150.0, 15000, "general_cargo", 0L);
        assertEquals(1, PrologQueries.tugsRequired("cargo_vessel", 15000, 150.0), "fixture: R11 says 1");
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-1", CARGO_CONTRACT), CNP_PACE, false);
        Probe vessel = spawnProbe("re_vessel", null);
        Probe tug = spawnProbe("re_tug", "tug-escort");

        vessel.outbox.put(contractRequest(hm, "CONTRACT-1", oneTug, "re_vessel"));
        ACLMessage cfp = pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.CFP, 10_000);
        assertNotNull(cfp);
        tug.outbox.put(bidReply(cfp, 100.0, 5.0, 0.9));
        assertNotNull(pollUntil(tug.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000));
        tug.outbox.put(onStation(hm, "C001", cfp.getConversationId()));
        assertNotNull(pollUntil(tug.inbox,
                m -> TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL.equals(m.getProtocol()), 10_000),
                "fixture: the first order");
        assertNotNull(pollTracking(hm, "C001", t -> t.stage() == Stage.IN_TRANSIT, 10_000));

        tug.outbox.put(onStation(hm, "C001", cfp.getConversationId())); // the restored tug reports again
        ACLMessage again = pollUntil(tug.inbox,
                m -> TransitToVesselBehaviour.ESCORT_ORDER_PROTOCOL.equals(m.getProtocol())
                        || m.getPerformative() == ACLMessage.CANCEL, 10_000);
        assertNotNull(again, "the report is answered");
        assertEquals(ACLMessage.INFORM, again.getPerformative(), "with the order, not with a CANCEL");
        assertEquals("begin_escort", content(again).get("event").asText());
        assertEquals(cfp.getConversationId(), again.getConversationId());
        assertEquals(List.of("re_tug"), hm.activeVessels().get("C001").assignedTugs(), "the escort is intact");
    }

    // ===================================================================================== C5

    /**
     * C5: a walk-in is cleared by customs like any arrival, and the order to proceed waits for
     * the clearance. While customs inspects, the Port table says so, with the rules.
     */
    @Test
    @Timeout(60)
    void aWalkInWaitsForCustoms_andAnInspectionShowsInThePortTable() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of(), CNP_PACE, true);
        Probe vessel = spawnProbe("c5_vessel", null);
        Probe customs = spawnProbe("c5_customs", "customs");
        VesselSpec ferry = new VesselSpec("WF1", "ferry", 5.0, 110.0, 9000, "general_cargo", 0L);

        vessel.outbox.put(openingOffer(hm, ferry, 1500.0, 4, "nego-WF1"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-WF1".equals(e.dialogueId()), 10_000));
        vessel.outbox.put(dealConfirmed(hm, 1500.0, 4, "nego-WF1"));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000),
                "the berth is granted at the deal");

        ACLMessage request = pollUntil(customs.inbox, m -> m.getPerformative() == ACLMessage.REQUEST, 15_000);
        assertNotNull(request, "a WALK-IN is sent to customs (pre-S16: contracted arrivals only)");
        assertEquals("WF1", content(request).get("vessel_id").asText());
        assertNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_500),
                "a ferry needs no tug and the weather is calm — but customs has not answered");
        assertEquals(PortStatusEvent.Wait.CUSTOMS,
                MovementControl.waitOf(hm, hm.activeVessels().get("WF1")).waiting());

        ACLMessage started = MessageFactory.reply(request, ACLMessage.INFORM);
        started.setContent(TerminalJson.write(Map.of("event", "inspection_started",
                "reason", "inspected [R23]", "inspection_sim_seconds", 2700)));
        customs.outbox.put(started);
        assertTrue(pollUntilTrue(() -> List.of("R23").equals(hm.movement().inspectionRules("WF1")), 10_000),
                "the inspection and its rule are on record");
        assertEquals(List.of("R23"), MovementControl.waitOf(hm, hm.activeVessels().get("WF1")).ruleIds());
        assertNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 1_000), "inspecting: still waiting");

        ACLMessage cleared = MessageFactory.reply(request, ACLMessage.CONFIRM);
        cleared.setContent(TerminalJson.write(Map.of("ref", "CL-2026-1", "reason", "inspected [R23]")));
        customs.outbox.put(cleared);
        assertNotNull(pollUntil(vessel.inbox, MovementControlIT::isProceed, 10_000),
                "cleared: the ship proceeds");
        assertTrue(hm.movement().inspectionRules("WF1").isEmpty());
    }

    // ===================================================================================== C3 / C4 / C9

    /**
     * C3: a flood closes the berth for an hour of game time — nobody is granted it, a ship
     * granted it waits, and the Port table says "closed"; then it reopens and the ship proceeds.
     */
    @Test
    @Timeout(60)
    void aFloodedBerthIsClosedForAnHour() throws Exception {
        HarbourMasterAgent hm = spawnHm(Map.of("CONTRACT-F1", FERRY_CONTRACT), TICK_PACE, false);
        Probe vessel = spawnProbe("c3_vessel", null);
        Probe terminal = spawnProbe("c3_terminal", "terminal");

        ACLMessage retract = MessageFactory.create(ACLMessage.DISCONFIRM);
        retract.addReceiver(hm.getAID());
        retract.setProtocol(AutoFlowDispatcherBehaviour.FLOOD_RETRACT_PROTOCOL);
        retract.setContent(TerminalJson.write(Map.of("notice", "retracted", "berth_id", "berth_4")));
        terminal.outbox.put(retract);
        assertTrue(pollUntilTrue(() -> hm.movement().berthClosed("berth_4", boot.getSimClock().nowSimMillis()),
                10_000), "the retraction closes the berth (pre-S16 it changed nothing on this side)");
        assertNotNull(awaitEvent(NotificationEvent.class,
                e -> e.text().startsWith("Berth 4 flooded — closed until")
                        && NotificationEvent.berthTopic("berth_4").equals(e.topic()), 5_000),
                "S18b (PT10): the flood and the reopening share the berth's topic");
        PortStatusEvent closed = awaitEvent(PortStatusEvent.class,
                e -> e.berths().stream().anyMatch(b -> "berth_4".equals(b.berthId()) && b.closed() && b.isFree()),
                10_000);
        assertNotNull(closed, "the Port table is told: berth 4 is free and CLOSED");

        // A contracted ferry pinned to the closed berth queues instead of being granted it.
        vessel.outbox.put(contractRequest(hm, "CONTRACT-F1", FERRY, "c3_vessel"));
        assertNotNull(pollTracking(hm, "F001", t -> t.stage() == Stage.AWAITING_BERTH, 10_000),
                "the pinned berth is closed: the contract queues");
        assertNotNull(awaitEvent(NotificationEvent.class,
                e -> e.text().contains("F001") && e.text().contains("queued") && e.text().contains("closed"), 5_000),
                "and — C4 — a queued contract is announced, as a queued walk-in always was");
        assertNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 1_000));

        // 59 game minutes later it is STILL closed: several sim-minute ticks pass (one is ~208
        // real ms here) and nothing is granted. A closure of any length up to an hour would
        // otherwise pass this case.
        boot.getSimClock().advance(boot.getSimClock().simSecondsToWallMs(59 * 60L));
        assertNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 1_500),
                "59 minutes in: the berth is still closed and the contract still queued");
        assertTrue(hm.movement().berthClosed("berth_4", boot.getSimClock().nowSimMillis()));
        // Two more: the closure has run out; the next sim-minute tick reopens it.
        boot.getSimClock().advance(boot.getSimClock().simSecondsToWallMs(2 * 60L));
        assertNotNull(pollUntil(vessel.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 15_000),
                "reopened: the queued contract is granted");
        assertFalse(hm.movement().berthClosed("berth_4", boot.getSimClock().nowSimMillis()));
        assertNotNull(awaitEvent(NotificationEvent.class, e -> "Berth 4 reopened".equals(e.text())
                && NotificationEvent.berthTopic("berth_4").equals(e.topic()), 5_000),
                "S18b (PT10): the reopening replaces the flood's chip");
    }

    /**
     * C4 + C9: a contracted tanker and a walk-in tanker wait for the same berth, the walk-in
     * FIRST. When it frees, the contract gets it — walk-ins rank below scheduled arrivals — and
     * its terminal booking starts at the grant, not at the ETA it had before it queued.
     */
    @Test
    @Timeout(60)
    void aQueuedContractOutranksAWalkInThatArrivedEarlier_andIsBookedFromItsGrant() throws Exception {
        ServiceContract holderContract = new ServiceContract("CT-H", "TH1", "tanker", "berth_1", 9000.0, 10, 0L);
        ServiceContract lateContract = new ServiceContract("CT-L", "TL1", "tanker", "berth_1", 9000.0, 10, 0L);
        HarbourMasterAgent hm = spawnHm(Map.of("CT-H", holderContract, "CT-L", lateContract), CNP_PACE, false);
        Probe holder = spawnProbe("c4_holder", null);
        Probe walkIn = spawnProbe("c4_walkin", null);
        Probe late = spawnProbe("c4_late", null);
        Probe terminal = spawnProbe("c4_terminal", "terminal");

        VesselSpec holderSpec = new VesselSpec("TH1", "tanker", 11.0, 200.0, 60000, "hazmat_class_3", 0L);
        holder.outbox.put(contractRequest(hm, "CT-H", holderSpec, "c4_holder"));
        assertNotNull(pollTracking(hm, "TH1", t -> "berth_1".equals(t.assignedBerth()), 10_000),
                "fixture: the first tanker holds berth 1");

        VesselSpec walkInSpec = new VesselSpec("WT1", "tanker", 11.0, 200.0, 60000, "hazmat_class_3", 0L);
        walkIn.outbox.put(openingOffer(hm, walkInSpec, 6000.0, 8, "nego-WT1"));
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-WT1".equals(e.dialogueId()), 10_000));
        walkIn.outbox.put(dealConfirmed(hm, 6100.0, 8, "nego-WT1"));
        assertNotNull(pollTracking(hm, "WT1", t -> t.stage() == Stage.AWAITING_BERTH, 10_000),
                "fixture: the walk-in queues FIRST");

        VesselSpec lateSpec = new VesselSpec("TL1", "tanker", 11.0, 200.0, 60000, "hazmat_class_3", 1_000L);
        late.outbox.put(contractRequest(hm, "CT-L", lateSpec, "c4_late"));
        assertNotNull(pollTracking(hm, "TL1", t -> t.stage() == Stage.AWAITING_BERTH, 10_000),
                "fixture: the contract queues SECOND");

        // Some game time passes while they queue, then the holder leaves.
        boot.getSimClock().advance(boot.getSimClock().simSecondsToWallMs(2 * 3_600L));
        long grantedAt = boot.getSimClock().nowSimMillis();
        drain(terminal.inbox);
        holder.outbox.put(flowReport(hm, Map.of("intent", "departed", "vessel_id", "TH1")));

        ACLMessage grant = pollUntil(late.inbox, m -> m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL, 10_000);
        assertNotNull(grant, "the CONTRACT is granted the freed berth, although the walk-in queued first "
                + "(pre-S16 the two tied on class and arrival order decided)");
        assertEquals("after_queue scheduled_arrival rank 4 [R25,R26,R27,R30]", content(grant).get("reason").asText());
        assertEquals(Stage.AWAITING_BERTH, hm.activeVessels().get("WT1").stage(), "the walk-in still waits");
        assertEquals(MovementControl.WALK_IN_CLASS,
                MovementControl.priorityOf(hm, hm.activeVessels().get("WT1")).priorityClass());
        assertEquals(5, MovementControl.priorityOf(hm, hm.activeVessels().get("WT1")).rank());

        ACLMessage booking = pollUntil(terminal.inbox, m -> m.getPerformative() == ACLMessage.REQUEST, 10_000);
        assertNotNull(booking, "the released contract is booked with the terminal");
        assertTrue(content(booking).get("eta_sim").asLong() >= grantedAt,
                "C9: from its GRANT (" + grantedAt + "), not from the ETA it announced with (1000) — got "
                        + content(booking).get("eta_sim"));
    }

    // ===================================================================================== harness

    private record Probe(BlockingQueue<ACLMessage> outbox, BlockingQueue<ACLMessage> inbox) {
    }

    private HarbourMasterAgent spawnHm(Map<String, ServiceContract> contracts, long realSecondsPerGameDay,
                                       boolean customsOffice) throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", realSecondsPerGameDay));
        CompletableFuture<HarbourMasterAgent> selfRef = new CompletableFuture<>();
        boot.getSpawner().spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(10_000.0, 70.0, new WeatherSnapshot(12, "good", 0.5, "sunny", 0L),
                        customsOffice),
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

    /** A tug's report that it has reached its ship, on the conversation it was hired on. */
    private static ACLMessage onStation(HarbourMasterAgent hm, String vesselId, String conversationId) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hm.getAID());
        inform.setConversationId(conversationId);
        inform.setContent(TerminalJson.write(Map.of("event", "tug_on_station", "vessel_id", vesselId)));
        return inform;
    }

    /** A vessel or tug flow report: a plain INFORM on the standard envelope, no protocol tag. */
    private static ACLMessage flowReport(HarbourMasterAgent hm, Map<String, Object> content) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hm.getAID());
        inform.setContent(TerminalJson.write(content));
        return inform;
    }

    /** A weather message in the shape {@code PeriodicWeatherBroadcastBehaviour} sends. */
    private static ACLMessage weatherAlert(HarbourMasterAgent hm, String event, int windKnots,
                                           String visibility, double swell, String state) {
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.getAID());
        alert.setContent(TerminalJson.write(Map.of(
                "event", event, "wind_knots", windKnots, "visibility", visibility,
                "swell", swell, "state", state)));
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        return alert;
    }

    /** The next CFP in {@code tug}'s inbox that is for {@code vesselId}, or null at the deadline. */
    private static ACLMessage cfpFor(Probe tug, String vesselId, long totalMillis) throws InterruptedException {
        return pollUntil(tug.inbox, m -> {
            if (m.getPerformative() != ACLMessage.CFP) {
                return false;
            }
            try {
                return vesselId.equals(content(m).path("target_vessel_id").asText());
            } catch (Exception e) {
                return false;
            }
        }, totalMillis);
    }

    /** The {@code event} of a message's JSON content, or "" when it has none. */
    private static String eventOf(ACLMessage m) {
        try {
            return content(m).path("event").asText();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isProceed(ACLMessage m) {
        return isOrder(m, "proceed_to_berth");
    }

    /** A movement order to a vessel: the protocol tag plus the event name. */
    private static boolean isOrder(ACLMessage m, String event) {
        if (!HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL.equals(m.getProtocol())) {
            return false;
        }
        try {
            return event.equals(content(m).path("event").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonNode content(ACLMessage m) throws Exception {
        return MAPPER.readTree(m.getContent());
    }

    private static void drain(BlockingQueue<ACLMessage> q) {
        q.clear();
    }

    /** The first message matching {@code match} within {@code totalMillis}, or null; others are consumed. */
    private static ACLMessage pollUntil(BlockingQueue<ACLMessage> q, Predicate<ACLMessage> match, long totalMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            ACLMessage m = q.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && match.test(m)) {
                return m;
            }
        }
        return null;
    }

    private static boolean pollUntilTrue(BooleanSupplier condition, long totalMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static VesselTracking pollTracking(HarbourMasterAgent hm, String vesselId,
                                               Predicate<VesselTracking> condition, long totalMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            VesselTracking tracking = hm.activeVessels().get(vesselId);
            if (tracking != null && condition.test(tracking)) {
                return tracking;
            }
            Thread.sleep(20);
        }
        if (hm.activeVessels().get(vesselId) == null) {
            return null;
        }
        return null;
    }

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

    @SuppressWarnings("unused")
    /** How many movement-safety events of {@code kind} were published, for one ship or (null) all. */
    private long safetyEvents(MovementSafetyEvent.Kind kind, String vesselId) {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(MovementSafetyEvent.class::isInstance).map(MovementSafetyEvent.class::cast)
                .filter(e -> e.kind() == kind && (vesselId == null || vesselId.equals(e.vesselId())))
                .count();
    }

    private static void unreachable() {
        fail("unused helper anchor");
    }
}
