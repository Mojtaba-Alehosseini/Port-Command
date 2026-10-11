package it.unige.portcommand.behaviours;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.agents.HmProbeAgent;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.negotiation.Decision;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.WalkInBeliefOverrides;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.util.ConversationClock;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S14 (ADR-18): a walk-in's patience is CONVERSATION time — the real seconds the player has had
 * the offer in front of them — and not game time. Before S14 the timeout was a sim-time ticker
 * against the hidden {@code max_wait_minutes} belief, so on a clock that runs a day in minutes a
 * ferry left 29-50 real seconds after hailing and a faster game made every vessel less patient.
 *
 * <p>The REAL {@code WalkInVesselAgent} (opening proposal, counter evaluation, timeout poll) runs
 * against a stand-in HarbourMaster the test drives by hand — {@link HmProbeAgent} under the
 * {@code harbour-master} DF type, the {@code WalkInVesselAgentIT} harness. Nothing advances time
 * but the test: no wall-clock advancer runs under a bare {@code JadeBootstrap}, so every figure
 * read off the conversation clock is exact. Personality is pinned through
 * {@link WalkInBeliefOverrides} (the scenario channel) because it shifts the budget.
 *
 * <p>Lives in the uncounted parent {@code behaviours} test package (same reason as
 * {@code PoissonSpawnBehaviourIT}: a test class in a catalogue-counted sub-package would shadow
 * {@code BehaviourCatalogueTest}'s scan). Waits are the committed bounded sleep-poll (the design rules
 * rule 5); the one NEGATIVE wait ("it did not withdraw") is bounded too and says what bounds it.
 */
@Tag("integration")
class WalkInPatienceIT {

    private static final int TEST_PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A game day in ten real seconds — far faster than any pace the game ships — so that a few
     * real seconds of {@code SimClock.advance} are whole game DAYS: enough game time to end any
     * dialogue many times over under the old sim-minute patience, and almost no conversation time.
     */
    private static final long FAST_REAL_SECONDS_PER_GAME_DAY = 10L;
    private static final long MILLIS_PER_GAME_DAY = 86_400_000L;

    /**
     * How long "the vessel did NOT withdraw" is watched for. {@code TimeoutWithdrawalBehaviour}
     * looks at the conversation clock every 250 wall-ms, so this is six looks: a vessel whose
     * patience had (wrongly) run out would have said so within the first one or two.
     */
    private static final long NO_WITHDRAWAL_WINDOW_MS = 1_500L;
    private static final long MESSAGE_TIMEOUT_MS = 15_000L;
    private static final String STAND_IN_HM = "probe_hm_patience";

    private final BlockingQueue<ACLMessage> hmOutbox = new LinkedBlockingQueue<>();
    private final BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
    /** What the stand-in HarbourMaster has received and no wait has claimed yet — kept, so a
     * withdrawal that arrives while the test is waiting for something else is never lost. */
    private final List<ACLMessage> unclaimed = new ArrayList<>();

    private JadeBootstrap boot;
    private SimClock clock;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    /**
     * THE S14 claim. Three whole game days pass and the vessel is still there, because only
     * 30 of its 120 seconds of patience have; the rest of those seconds then end the dialogue
     * with the same {@code timeout} withdrawal as before. On the pre-S14 code the three days
     * (4 320 sim-minutes against a ferry's 140-240) withdrew the vessel at once.
     */
    @Test
    void gameTimeAlone_neverTimesOutAWalkIn() throws Exception {
        bootWithStandInHarbourMaster();
        openNegotiation(ferry("WP01"), Personality.NEUTRAL, mock(NegotiationEngine.class));
        assertEquals(120_000L, patience("WP01").remainingMillis(),
                "fixture: a neutral ferry opens with 120 s of patience");
        assertEquals(0L, patience("WP01").elapsedMillis(), "fixture: nothing has run yet");

        long simBefore = clock.nowSimMillis();
        clock.advance(30_000L);

        assertEquals(3 * MILLIS_PER_GAME_DAY, clock.nowSimMillis() - simBefore,
                "fixture: at 10 s per game day, 30 real seconds are three whole game days");
        assertEquals(30_000L, patience("WP01").elapsedMillis(),
                "the conversation ran for the 30 REAL seconds, whatever the game clock did with them");
        assertEquals(90_000L, patience("WP01").remainingMillis(), "30 of the 120 seconds are spent");
        assertNoWithdrawal("three game days are only 30 s of conversation: the ferry must still be waiting");

        clock.advance(100_000L);

        assertTimesOut("WP01", "130 s of conversation time is past the ferry's 120 s");
        assertTrue(clock.conversationClock().view("nego-WP01").isEmpty(),
                "the dialogue is over, so its counter must be gone from the clock");
        assertTrue(waitUntil(() -> !boot.getMarketHistoryArtifact().lastN(1).isEmpty(), MESSAGE_TIMEOUT_MS),
                "the withdrawal must still be recorded in the market history");
        assertEquals(Deal.Outcome.TIMEOUT, boot.getMarketHistoryArtifact().lastN(1).get(0).outcome(),
                "what ends the wait changed; the outcome it produces did not");
    }

    /** A paused game freezes patience: the player may stop and think for as long as they like. */
    @Test
    void aPausedGameFreezesPatience() throws Exception {
        bootWithStandInHarbourMaster();
        openNegotiation(ferry("WP02"), Personality.NEUTRAL, mock(NegotiationEngine.class));
        clock.advance(20_000L);
        ConversationClock.View atPause = patience("WP02");
        assertEquals(100_000L, atPause.remainingMillis(), "fixture: 20 of the 120 seconds are spent");
        long simAtPause = clock.nowSimMillis();

        clock.pause();
        clock.advance(1_000_000L);

        assertEquals(atPause, patience("WP02"),
                "a paused game must pass NOTHING on to the conversation clock — not elapsed, not remaining");
        assertEquals(simAtPause, clock.nowSimMillis(), "fixture: a paused clock does not move game time either");
        assertNoWithdrawal("1 000 real seconds went by with the game paused: the ferry must still be waiting");

        clock.resume();
        clock.advance(200_000L);

        assertTimesOut("WP02", "resumed, 200 more seconds are far past what the ferry had left");
    }

    /** The speed control changes how fast the PORT runs. It never reaches a conversation. */
    @Test
    void speedDoesNotChangePatience() throws Exception {
        bootWithStandInHarbourMaster();
        openNegotiation(ferry("WP03"), Personality.NEUTRAL, mock(NegotiationEngine.class));
        long gameTimeAtNormalSpeed = SimClock.simMillisDelta(30_000L, FAST_REAL_SECONDS_PER_GAME_DAY);

        clock.setSpeedMultiplier(4);
        long simBefore = clock.nowSimMillis();
        clock.advance(30_000L);

        assertEquals(4 * gameTimeAtNormalSpeed, clock.nowSimMillis() - simBefore,
                "fixture: at x4 the same 30 real seconds move game time four times as far");
        assertEquals(30_000L, patience("WP03").elapsedMillis(),
                "the conversation must see the 30 real seconds unscaled — not 120 000");
        assertEquals(90_000L, patience("WP03").remainingMillis(),
                "at x4 the ferry must still have 90 of its 120 seconds");
        assertNoWithdrawal("x4 game speed must not make the ferry less patient");

        clock.advance(90_000L);

        assertTimesOut("WP03", "the full 120 real seconds have now passed, at any speed");
    }

    /**
     * Every message of the player's that reaches the vessel buys 30 more seconds — a counter and
     * a plain status question alike — and the time already spent stays spent.
     */
    @Test
    void eachPlayerMessageBuysThirtySeconds() throws Exception {
        bootWithStandInHarbourMaster();
        VesselSpec spec = ferry("WP04");
        String berthId = compatibleBerth(spec);
        ACLMessage opening = openNegotiation(spec, Personality.NEUTRAL, engineThatAlwaysCounters());

        clock.advance(60_000L);
        assertEquals(60_000L, patience("WP04").remainingMillis(), "fixture: half of the 120 seconds are spent");

        // The vessel's reply is the barrier: the bonus is credited on arrival, before any reply
        // is built, so once the reply is here the clock already shows it.
        sendCounter(opening, 1_500.0, 6, berthId);
        assertNotNull(awaitMessage(WalkInPatienceIT::isCounterOffer, MESSAGE_TIMEOUT_MS),
                "the vessel must answer the player's counter (the engine here always counters)");
        assertEquals(90_000L, patience("WP04").remainingMillis(), "a counter must buy 30 more seconds: 60 -> 90");
        assertEquals(60_000L, patience("WP04").elapsedMillis(), "a bonus adds patience; it does not rewind the counter");
        assertEquals(150_000L, patience("WP04").totalMillis(), "the visible total grows with the bonus: 120 -> 150");

        sendStatusQuery(opening);
        assertNotNull(awaitMessage(m -> m.getPerformative() == ACLMessage.INFORM
                        && "status_report".equals(field(m, "intent")), MESSAGE_TIMEOUT_MS),
                "the vessel must answer a status question");
        assertEquals(120_000L, patience("WP04").remainingMillis(),
                "a question is a message too — it reached the vessel, so it buys its 30 seconds: 90 -> 120");
    }

    /**
     * No bonus ever leaves more than five minutes on the clock. A desperate cargo vessel opens
     * with 270 s, so its first message lands exactly on the cap and the second one must add nothing.
     */
    @Test
    void aReplyNeverLeavesMoreThanFiveMinutesOnTheClock() throws Exception {
        bootWithStandInHarbourMaster();
        VesselSpec spec = cargoVessel("WP05");
        String berthId = compatibleBerth(spec);
        ACLMessage opening = openNegotiation(spec, Personality.DESPERATE, engineThatAlwaysCounters());
        assertEquals(270_000L, patience("WP05").remainingMillis(),
                "fixture: a desperate cargo vessel opens with 240 + 30 seconds");

        sendCounter(opening, 1_600.0, 8, berthId);
        assertNotNull(awaitMessage(WalkInPatienceIT::isCounterOffer, MESSAGE_TIMEOUT_MS),
                "the vessel must answer the first counter");
        assertEquals(300_000L, patience("WP05").remainingMillis(), "270 + 30 lands exactly on the 300 s cap");

        sendCounter(opening, 1_650.0, 8, berthId);
        assertNotNull(awaitMessage(WalkInPatienceIT::isCounterOffer, MESSAGE_TIMEOUT_MS),
                "the vessel must answer the second counter");
        assertEquals(300_000L, patience("WP05").remainingMillis(),
                "a second message must not push the remaining time past the cap (uncapped: 330 000)");
        assertEquals(0L, patience("WP05").elapsedMillis(), "fixture: no time passed in this case");
    }

    /** An aggressive vessel walks sooner, a desperate one waits longer: 30 s either side of 120. */
    @Test
    void personalityShiftsTheOpeningBudget() throws Exception {
        bootWithStandInHarbourMaster();
        openNegotiation(ferry("WP06"), Personality.AGGRESSIVE, mock(NegotiationEngine.class));
        openNegotiation(ferry("WP07"), Personality.DESPERATE, mock(NegotiationEngine.class));

        assertEquals(90_000L, patience("WP06").remainingMillis(), "an aggressive ferry opens with 120 - 30 seconds");
        assertEquals(150_000L, patience("WP07").remainingMillis(), "a desperate ferry opens with 120 + 30 seconds");
        assertEquals(0L, patience("WP06").elapsedMillis(), "the budget is read at the opening, before any time passes");
        assertEquals(0L, patience("WP07").elapsedMillis(), "the budget is read at the opening, before any time passes");
    }

    // ---- harness ----

    /** Boots JADE at the fast game pace with the stand-in HarbourMaster. No {@code WallClockAdvancer}
     * exists under a bare {@code JadeBootstrap}: the clock moves only when a test advances it. */
    private void bootWithStandInHarbourMaster() throws InterruptedException {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", FAST_REAL_SECONDS_PER_GAME_DAY));
        clock = boot.getSimClock();
        boot.getSpawner().spawn(STAND_IN_HM, HmProbeAgent.class,
                new Object[] {hmOutbox, hmInbox, "harbour-master"});

        // Barrier before any vessel spawns: the probe's message loop only starts once its setup()
        // — the blocking DF registration — has returned, so a note it posts to itself and hands
        // back proves a walk-in's harbour-master lookup will find it.
        ACLMessage ready = MessageFactory.create(ACLMessage.INFORM);
        ready.addReceiver(new AID(STAND_IN_HM, AID.ISLOCALNAME));
        ready.setConversationId("stand-in-ready");
        hmOutbox.put(ready);
        assertNotNull(awaitMessage(m -> "stand-in-ready".equals(m.getConversationId()), MESSAGE_TIMEOUT_MS),
                "setup: the stand-in HarbourMaster must be up and registered in the DF");
    }

    /**
     * Spawns a REAL walk-in with its personality pinned and waits for its opening PROPOSE at the
     * stand-in HarbourMaster — by then the vessel has opened its conversation, which
     * {@code OpeningProposalBehaviour} does before the offer leaves.
     */
    private ACLMessage openNegotiation(VesselSpec spec, Personality personality, NegotiationEngine engine)
            throws InterruptedException {
        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, clock, boot.getMarketHistoryArtifact(),
                boot.getRandomSource(), engine, null, new WalkInBeliefOverrides(personality, null, null));
        String conversationId = "nego-" + spec.vesselId();
        ACLMessage opening = awaitMessage(m -> m.getPerformative() == ACLMessage.PROPOSE
                && conversationId.equals(m.getConversationId())
                && "opening_offer".equals(field(m, "intent")), MESSAGE_TIMEOUT_MS);
        assertNotNull(opening, "setup: " + spec.vesselId()
                + " must send its opening PROPOSE to the stand-in HarbourMaster");
        return opening;
    }

    /** Keeps the dialogue open however many counters the test sends: every offer is countered,
     * echoing the hours it was handed like the real engine does for a workable stay. */
    private static NegotiationEngine engineThatAlwaysCounters() {
        NegotiationEngine engine = mock(NegotiationEngine.class);
        when(engine.evaluate(anyDouble(), anyInt(), any()))
                .thenAnswer(inv -> Decision.counter(1_800.0, inv.getArgument(1), "test-counter"));
        return engine;
    }

    /** The ferry fixture {@code HarbourMasterAgentIT}'s contracted-ferry case uses (fits berth_4). */
    private static VesselSpec ferry(String vesselId) {
        return new VesselSpec(vesselId, "ferry", 5.0, 110.0, 9000, "general_cargo", 0L);
    }

    /** {@code WalkInVesselAgentIT}'s canonical cargo vessel. */
    private static VesselSpec cargoVessel(String vesselId) {
        return new VesselSpec(vesselId, "cargo_vessel", 8.0, 150.0, 30000, "general_cargo", 0L);
    }

    /** A Prolog-verified compatible berth, so the vessel's own R7 guard lets the counter through. */
    private static String compatibleBerth(VesselSpec spec) {
        List<String> berths = PrologQueries.findCompatibleBerths(
                spec.vesselType(), spec.draft(), spec.length(), spec.tonnage());
        assertFalse(berths.isEmpty(), "precondition: " + spec.vesselType() + " must have a compatible berth");
        return berths.get(0);
    }

    /** The player's counter as the HarbourMaster relays it: a PROPOSE on the dialogue's
     * conversation carrying {@code {price, hours, berth_id}} ({@code CounterOfferHarbourMaster}'s shape). */
    private void sendCounter(ACLMessage opening, double price, int hours, String berthId)
            throws InterruptedException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("price", price);
        content.put("hours", hours);
        content.put("berth_id", berthId);
        ACLMessage counter = MessageFactory.create(ACLMessage.PROPOSE);
        counter.addReceiver(opening.getSender());
        counter.setConversationId(opening.getConversationId());
        counter.setContent(TerminalJson.write(content));
        hmOutbox.put(counter);
    }

    /** The player's "ask": a QUERY_REF with empty content ({@code DispatchPlayerCommandBehaviour}'s shape). */
    private void sendStatusQuery(ACLMessage opening) throws InterruptedException {
        ACLMessage ask = MessageFactory.create(ACLMessage.QUERY_REF);
        ask.addReceiver(opening.getSender());
        ask.setConversationId(opening.getConversationId());
        ask.setContent("{}");
        hmOutbox.put(ask);
    }

    /** The conversation clock's view of one vessel's dialogue; fails if there is none. */
    private ConversationClock.View patience(String vesselId) {
        return clock.conversationClock().view("nego-" + vesselId)
                .orElseThrow(() -> new AssertionError("no conversation on the clock for nego-" + vesselId));
    }

    /** Bounded NEGATIVE wait (see {@link #NO_WITHDRAWAL_WINDOW_MS}): no withdraw INFORM may arrive. */
    private void assertNoWithdrawal(String why) throws InterruptedException {
        ACLMessage withdrawal = awaitMessage(WalkInPatienceIT::isWithdrawal, NO_WITHDRAWAL_WINDOW_MS);
        assertNull(withdrawal, why + " — but it withdrew: "
                + (withdrawal == null ? "" : withdrawal.getContent()));
    }

    /** Bounded wait for the vessel's own withdraw INFORM, which must name {@code timeout}. */
    private void assertTimesOut(String vesselId, String why) throws InterruptedException {
        String conversationId = "nego-" + vesselId;
        ACLMessage withdrawal = awaitMessage(
                m -> isWithdrawal(m) && conversationId.equals(m.getConversationId()), MESSAGE_TIMEOUT_MS);
        assertNotNull(withdrawal, why + ", so the vessel must withdraw — conversation still on the clock: "
                + clock.conversationClock().view(conversationId));
        assertEquals("timeout", field(withdrawal, "reason"),
                "patience running out is the timeout withdrawal, not a price or refusal one");
    }

    private static boolean isWithdrawal(ACLMessage m) {
        return m.getPerformative() == ACLMessage.INFORM && "withdraw".equals(field(m, "intent"));
    }

    private static boolean isCounterOffer(ACLMessage m) {
        return m.getPerformative() == ACLMessage.PROPOSE && "counter_offer".equals(field(m, "intent"));
    }

    /** One top-level field of a message's JSON content, or {@code null} when it has none. */
    private static String field(ACLMessage m, String name) {
        if (m.getContent() == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(m.getContent());
            return node != null && node.has(name) ? node.get(name).asText() : null;
        } catch (Exception e) {
            throw new AssertionError("unparseable content from " + m.getSender() + ": " + m.getContent(), e);
        }
    }

    /**
     * Bounded wait for a message at the stand-in HarbourMaster. Messages that do not match are
     * kept in {@link #unclaimed} for a later wait instead of being dropped.
     */
    private ACLMessage awaitMessage(Predicate<ACLMessage> match, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            for (Iterator<ACLMessage> waiting = unclaimed.iterator(); waiting.hasNext();) {
                ACLMessage m = waiting.next();
                if (match.test(m)) {
                    waiting.remove();
                    return m;
                }
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            ACLMessage next = hmInbox.poll(Math.min(left, 100L), TimeUnit.MILLISECONDS);
            if (next != null) {
                unclaimed.add(next);
            }
        }
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
