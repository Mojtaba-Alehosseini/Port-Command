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
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.negotiation.Decision;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        // Mocked engine: COUNTER on the first offer, ACCEPT on the second — echoing the hours it
        // was handed, like the real engine does for a workable stay (task 19b).
        NegotiationEngine engine = mock(NegotiationEngine.class);
        when(engine.evaluate(anyDouble(), anyInt(), any()))
                .thenAnswer(inv -> Decision.counter(6000.0, inv.getArgument(1), "test-counter"))
                .thenAnswer(inv -> Decision.accept(inv.getArgument(1), "test-accept"));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, 5500.0, 10, berthId});

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        boolean opening = false;
        boolean counter = false;
        boolean accept = false;
        boolean confirm = false;
        int openingHours = -1;
        int counterHours = -1;
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
                        openingHours = MAPPER.readTree(m.getContent()).get("hours").asInt();
                    } else if ("counter_offer".equals(intent)) {
                        assertEnvelope(m, ACLMessage.PROPOSE);
                        counter = true;
                        counterHours = MAPPER.readTree(m.getContent()).get("hours").asInt();
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

        // Task 19b: a PRESENT proposed duration (the fake HM sends "hours": 10) flows through the
        // engine and back on the counter — the counter now carries the ECHOED player hours, no
        // longer the vessel's fixed dealHours (that task-19 pinning is deliberately superseded;
        // the absent-duration case keeps its own regression test below). Never 0 either way.
        assertTrue(openingHours > 0, "opening carried a real duration");
        assertEquals(10, counterHours,
                "a present proposed duration must flow to the engine and back on the counter");

        verify(engine, times(2)).evaluate(anyDouble(), anyInt(), any());

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size(), "deal recorded");
        assertEquals(Deal.Outcome.DEAL, recorded.get(0).outcome());
    }

    /**
     * Task 15: the REAL engine (not a mock) drives an in-band constant counter-offer to an
     * eventual CONFIRM. Deterministic in execution (fixed seed, no flakiness) without pinning one
     * exact round count -- termination-by-ACCEPT within {@code roundLimit} rounds is guaranteed
     * for ANY seed by the engine's forced-accept-at-last-round rule.
     */
    @Test
    void walkInWithRealEngine_eventuallyAcceptsAnInBandCounterOffer() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W010";
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: cargo_vessel must have a compatible berth");
        String berthId = berths.get(0);

        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = boot.getRandomSource().forStream("vessel-" + vesselId);
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);
        double inBandCounter = (min + target) / 2.0;

        NegotiationEngine engine = new RealNegotiationEngine(
                Settings.load().roundLimit(), boot.getRandomSource().forStream("nego-" + vesselId));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        // hours=12: >= every possible cargo_vessel floor (min_duration_range [6,12], clamped to
        // the preferred stay), so the duration leg is workable on EVERY seed and this test still
        // pins exactly what it always pinned: price convergence within roundLimit.
        boot.getSpawner().spawn("fake_hm_accept", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, inBandCounter, 12, berthId});

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        boolean confirm = false;
        for (int i = 0; i < 12 && !confirm; i++) {
            ACLMessage m = hmInbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            assertNoHiddenBeliefs(m, personality, min, target);
            if (m.getPerformative() == ACLMessage.CONFIRM) {
                confirm = true;
            }
        }
        assertTrue(confirm, "vessel must eventually CONFIRM an in-band deal within roundLimit rounds");

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size(), "deal recorded");
        assertEquals(Deal.Outcome.DEAL, recorded.get(0).outcome());
    }

    /**
     * Task 19 (buyer-semantics rewrite): the REAL engine withdraws when the player's demanded
     * fee stays ABOVE the vessel's budget ({@code targetPrice}, its hard ceiling) through every
     * round — over_priced -> WITHDRAW_PRICE, meaning what it says: the player priced the vessel
     * out. (The pre-rewrite version of this test drove the withdrawal with a LOWBALL demand,
     * which under coherent buyer mechanics is an instant bargain-accept instead.)
     */
    @Test
    void walkInWithRealEngine_withdrawsOverPricedWhenPlayerDemandsAboveItsBudget() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W011";
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: cargo_vessel must have a compatible berth");
        String berthId = berths.get(0);

        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = boot.getRandomSource().forStream("vessel-" + vesselId);
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);
        double aboveBudgetDemand = target * 3.0; // far beyond the ceiling on every round

        NegotiationEngine engine = new RealNegotiationEngine(
                Settings.load().roundLimit(), boot.getRandomSource().forStream("nego-" + vesselId));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm_withdraw", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, aboveBudgetDemand, 10, berthId});

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        boolean withdrawInform = false;
        for (int i = 0; i < 12 && !withdrawInform; i++) {
            ACLMessage m = hmInbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            assertNoHiddenBeliefs(m, personality, min, target);
            if (m.getPerformative() == ACLMessage.INFORM) {
                JsonNode content = MAPPER.readTree(m.getContent());
                assertEquals("withdraw", content.get("intent").asText());
                assertEquals("over_priced", content.get("reason").asText());
                withdrawInform = true;
            }
        }
        assertTrue(withdrawInform, "vessel must withdraw (over_priced) when the player never meets its minimum");

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size(), "withdrawal recorded");
        assertEquals(Deal.Outcome.WITHDRAW_PRICE, recorded.get(0).outcome());
    }

    /**
     * Task 19b regression — the OLD BUG'S EXACT SHAPE. Pre-task-19, a player counter whose JSON
     * carried no {@code hours} key was decoded through a 0.0-defaulting reader, and the deal could
     * close at 0 hours. Task 19 killed that by ignoring the frame's duration entirely; 19b
     * re-opens the duration path, so this pins the rule that keeps the ghost dead: an ABSENT
     * duration resolves to the vessel's STANDING hours (its announced preferred stay — nothing
     * has countered it here), and the deal closes there. Never at 0.
     */
    @Test
    void walkInClosesAtItsStandingHoursWhenThePlayerNamesNoDuration() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W002";
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: cargo_vessel must have a compatible berth");
        String berthId = berths.get(0);

        // Accept-on-first-offer engine that echoes the hours it was handed — so whatever the
        // behaviour resolves the absent duration TO is exactly what the deal closes at.
        NegotiationEngine engine = mock(NegotiationEngine.class);
        when(engine.evaluate(anyDouble(), anyInt(), any()))
                .thenAnswer(inv -> Decision.accept(inv.getArgument(1), "test-accept"));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm_no_hours", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, 5500.0, null, berthId}); // null -> the hours key is OMITTED

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        int openingHours = -1;
        int acceptHours = -1;
        int confirmHours = -1;
        boolean confirm = false;
        for (int i = 0; i < 12 && !confirm; i++) {
            ACLMessage m = hmInbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            String intent = intent(m);
            if (m.getPerformative() == ACLMessage.PROPOSE && "opening_offer".equals(intent)) {
                openingHours = MAPPER.readTree(m.getContent()).get("hours").asInt();
            } else if (m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                acceptHours = MAPPER.readTree(m.getContent()).get("hours").asInt();
            } else if (m.getPerformative() == ACLMessage.CONFIRM) {
                confirmHours = MAPPER.readTree(m.getContent()).get("hours").asInt();
                confirm = true;
            }
        }
        assertTrue(confirm, "the duration-less counter must still close a deal");
        assertTrue(openingHours > 0, "sanity: the announced preferred stay is a real duration");
        assertEquals(openingHours, acceptHours,
                "an ABSENT duration keeps the vessel's preferred (standing) hours — the old bug closed at 0 here");
        assertEquals(openingHours, confirmHours, "the confirmed deal carries the same non-zero stay");

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size());
        assertEquals(openingHours, recorded.get(0).durationHours(),
                "the recorded deal's duration is the standing hours, never the decoder's 0 default");
    }

    /**
     * Post-19b hardening (adversarial review observation): a PROPOSE whose JSON carries NO
     * {@code price} key is producer garbage — the DCG fails a price-less counter at parse
     * time, so nothing legitimate emits one — and before the guard it decoded to 0.0, which
     * under buyer semantics is {@code <= lastOwnOffer} and would CLOSE A DEAL AT €0. The
     * vessel must REFUSE ({@code invalid_price}), never consult the engine, never close, and
     * leave the negotiation open (no withdrawal, no recorded outcome).
     */
    @Test
    void walkInRefusesAPricelessCounterInsteadOfClosingAtZero() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W003";
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: cargo_vessel must have a compatible berth");
        String berthId = berths.get(0);

        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = boot.getRandomSource().forStream("vessel-" + vesselId);
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);

        NegotiationEngine engine = mock(NegotiationEngine.class); // garbage must never reach the engine

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm_no_price", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, null, 8, berthId}); // null -> the price key is OMITTED

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        boolean refuse = false;
        for (int i = 0; i < 12 && !refuse; i++) {
            ACLMessage m = hmInbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            assertNoHiddenBeliefs(m, personality, min, target);
            if (m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL || m.getPerformative() == ACLMessage.CONFIRM) {
                throw new AssertionError("a price-less counter must NEVER close a deal (was: €0 close): "
                        + m.getContent());
            }
            if (m.getPerformative() == ACLMessage.REFUSE) {
                assertEquals("invalid_price", MAPPER.readTree(m.getContent()).get("reason").asText());
                refuse = true;
            }
        }
        assertTrue(refuse, "the vessel must REFUSE the malformed counter, loudly");

        // The dialogue survives the garbage: no engine consult, no close, no withdrawal recorded.
        verify(engine, never()).evaluate(anyDouble(), anyInt(), any());
        ACLMessage after = hmInbox.poll(2, TimeUnit.SECONDS);
        assertTrue(after == null || (after.getPerformative() != ACLMessage.CONFIRM
                        && after.getPerformative() != ACLMessage.ACCEPT_PROPOSAL),
                "nothing may close after the refuse");
        assertTrue(boot.getMarketHistoryArtifact().lastN(1).isEmpty(),
                "no outcome recorded — the negotiation is still open, not withdrawn");
    }

    /**
     * Task 19b, the duration dimension end-to-end with the REAL engine: an affordable fee with a
     * physically impossible stay (2h — below every cargo_vessel floor, {@code min_duration_range}
     * [6,12] clamped to the preferred stay, so floor >= 6 on EVERY seed). Each round the vessel
     * counters with its hours pushed back up ("we need at least Nh" — the floor ON the wire, by
     * design); when the rounds run out it withdraws {@code too_short}, recorded as the honest
     * {@code WITHDRAW_DURATION}, never {@code WITHDRAW_PRICE}.
     */
    @Test
    void walkInWithRealEngine_withdrawsAsDurationFailureWhenPlayerNeverGrantsTheFloor() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W012";
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: cargo_vessel must have a compatible berth");
        String berthId = berths.get(0);

        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = boot.getRandomSource().forStream("vessel-" + vesselId);
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);
        double inBandCounter = (min + target) / 2.0; // affordable — ONLY the hours block this deal

        NegotiationEngine engine = new RealNegotiationEngine(
                Settings.load().roundLimit(), boot.getRandomSource().forStream("nego-" + vesselId));

        BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("fake_hm_short_stay", CounterOfferHarbourMaster.class,
                new Object[] {hmInbox, inBandCounter, 2, berthId});

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        boolean withdrawInform = false;
        boolean sawFloorPushback = false;
        for (int i = 0; i < 16 && !withdrawInform; i++) {
            ACLMessage m = hmInbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            assertNoHiddenBeliefs(m, personality, min, target);
            if (m.getPerformative() == ACLMessage.PROPOSE && "counter_offer".equals(intent(m))) {
                int counterHours = MAPPER.readTree(m.getContent()).get("hours").asInt();
                assertTrue(counterHours >= 6,
                        "every counter must push the 2h proposal back up to the floor (>= 6 for cargo), got " + counterHours);
                sawFloorPushback = true;
            }
            if (m.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                throw new AssertionError("a below-floor stay must NEVER be accepted");
            }
            if (m.getPerformative() == ACLMessage.INFORM) {
                JsonNode content = MAPPER.readTree(m.getContent());
                assertEquals("withdraw", content.get("intent").asText());
                assertEquals("too_short", content.get("reason").asText(),
                        "an affordable fee with an impossible stay is a DURATION failure, not a price one");
                withdrawInform = true;
            }
        }
        assertTrue(sawFloorPushback, "the 'we need at least Nh' counter must appear on the wire");
        assertTrue(withdrawInform, "the vessel must withdraw when its floor is never granted");

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size(), "withdrawal recorded");
        assertEquals(Deal.Outcome.WITHDRAW_DURATION, recorded.get(0).outcome(),
                "the honest outcome: the player never granted the hours, the price was fine");
    }

    /**
     * Task 19 play-test fix: the player's "reject" (REJECT_PROPOSAL) must END the negotiation
     * with the canonical {@code player_refused} outcome. Before the fix, no vessel-side template
     * matched REJECT_PROPOSAL at all — the refusal was silently swallowed, the dialogue stayed
     * open (a later "deal" could still close it), and {@code Deal.Outcome.PLAYER_REFUSED} was
     * unreachable in production. The engine must never be consulted on a refusal.
     */
    @Test
    void walkInLeavesWithPlayerRefusedWhenThePlayerRejects() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));

        String vesselId = "W020";
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);

        BlockingQueue<ACLMessage> outbox = new LinkedBlockingQueue<>();
        BlockingQueue<ACLMessage> inbox = new LinkedBlockingQueue<>();
        boot.getSpawner().spawn("probe_hm_reject", HmProbeAgent.class,
                new Object[] {outbox, inbox, "harbour-master"});

        NegotiationEngine engine = mock(NegotiationEngine.class); // a refusal must never consult the engine

        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(),
                boot.getMarketHistoryArtifact(), boot.getRandomSource(), engine);

        ACLMessage openingMsg = null;
        for (int i = 0; i < 12 && openingMsg == null; i++) {
            ACLMessage m = inbox.poll(10, TimeUnit.SECONDS);
            if (m != null && m.getPerformative() == ACLMessage.PROPOSE) {
                openingMsg = m;
            }
        }
        assertNotNull(openingMsg, "setup: the walk-in must send its opening PROPOSE to the probe HM");

        ACLMessage reject = MessageFactory.reply(openingMsg, ACLMessage.REJECT_PROPOSAL);
        reject.setContent("{}"); // matches DispatchPlayerCommandBehaviour's plainMessage shape
        outbox.put(reject);

        boolean withdrawInform = false;
        for (int i = 0; i < 12 && !withdrawInform; i++) {
            ACLMessage m = inbox.poll(10, TimeUnit.SECONDS);
            if (m == null) {
                break;
            }
            if (m.getPerformative() == ACLMessage.INFORM) {
                JsonNode content = MAPPER.readTree(m.getContent());
                assertEquals("withdraw", content.get("intent").asText());
                assertEquals("player_refused", content.get("reason").asText(),
                        "a rejection must leave with player_refused, not a price/timeout reason");
                withdrawInform = true;
            }
        }
        assertTrue(withdrawInform, "the vessel must announce its player_refused departure");

        verify(engine, never()).evaluate(anyDouble(), anyInt(), any());

        List<DealRecord> recorded = boot.getMarketHistoryArtifact().lastN(1);
        assertEquals(1, recorded.size(), "the refusal must be recorded");
        assertEquals(Deal.Outcome.PLAYER_REFUSED, recorded.get(0).outcome(),
                "PLAYER_REFUSED (declared task 02, mapped task 07) is finally reachable in production");
    }

    /** The privacy invariant: NO hidden belief (field name OR value) in any outbound content. */
    private static void assertNoHiddenBeliefs(ACLMessage m, Personality personality, double min, double target) {
        String content = m.getContent() == null ? "" : m.getContent();
        String lower = content.toLowerCase();
        // 19b: minduration/min_duration guard the new hours-floor belief's FIELD name. There is
        // deliberately no floor-VALUE check: a 1–2-digit int collides with prices/rounds/other
        // hours, and the floor's value legitimately appears as the hours term of a too_short
        // counter ("we need at least Nh") — that reveal IS the §7.3 mechanic (INVARIANTS P-04).
        for (String token : List.of("minacceptable", "min_acceptable", "targetprice", "target_price",
                "maxwait", "max_wait", "personality", "roundsremaining", "rounds_remaining",
                "minduration", "min_duration")) {
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
