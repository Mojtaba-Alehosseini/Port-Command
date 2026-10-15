package it.unige.portcommand.behaviours;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import it.unige.portcommand.agents.HmProbeAgent;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14: a vessel's ETA is a GAME-time instant, and its arrival waker now waits for it as one
 * ({@code BaseVesselAgent} on {@code SimDeadlineWait}). Until S14 the waker was armed once, for
 * the wall delay worked out at spawn, so a contracted vessel spawned two sim-hours ahead simply
 * arrived that many wall-seconds later whatever the game had done in between: in the middle of a
 * pause, or — after a switch to ×4 — hours of game time late. With a 60-minute day that approach
 * is a five-minute wall wait, and the player may now sit in a pause and type.
 *
 * <p>Real {@code ContractedVesselAgent}s against the stand-in HarbourMaster {@code
 * WalkInPatienceIT} uses; no {@code WallClockAdvancer} under a bare {@code JadeBootstrap}, so
 * game time moves only when a test advances it. The witness is the vessel's arrival announcement
 * (the first thing {@code onArrival} does). In the uncounted parent {@code behaviours} test
 * package, for the reason {@code PoissonSpawnBehaviourIT} gives. Waits are bounded polls; the two
 * NEGATIVE waits are bounded too and say what bounds them.
 */
@Tag("integration")
class ArrivalWaitIT {

    private static final int TEST_PORT = 18099;
    private static final long MESSAGE_TIMEOUT_MS = 15_000L;
    private static final String STAND_IN_HM = "probe_hm_arrival";

    private final BlockingQueue<ACLMessage> hmOutbox = new LinkedBlockingQueue<>();
    private final BlockingQueue<ACLMessage> hmInbox = new LinkedBlockingQueue<>();
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
     * A pause holds an approaching vessel: it must not arrive while game time stands still short
     * of its ETA, and must arrive once game time gets there.
     */
    @Test
    void aPauseHoldsAnApproachingVesselUntilGameTimeReachesItsEta() throws Exception {
        bootWithStandInHarbourMaster(300L);
        // 432 game seconds ahead: at 300 s/day the pre-S14 one-shot waker would have fired
        // exactly 1.5 wall-seconds after the spawn.
        long etaSimMillis = clock.nowSimMillis() + 432_000L;
        assertEquals(1_500L, clock.simSecondsToWallMs(432L), "fixture: the old one-shot delay");
        VesselSpec spec = approaching("AW01", etaSimMillis);

        clock.pause();
        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "C-AW01", clock,
                boot.getMarketHistoryArtifact());

        // Twice the old delay, with the game paused the whole time. The old waker announced at
        // 1.5 s; the new one re-checks every 250 ms and must keep holding.
        assertNull(awaitMessage(arrivalOf(spec), 3_000L),
                "a paused game must not let a vessel arrive before its ETA");
        assertTrue(clock.nowSimMillis() < etaSimMillis, "fixture: game time never moved");

        clock.resume();
        clock.advance(1_500L); // 432 game seconds: the ETA is reached

        assertNotNull(awaitMessage(arrivalOf(spec), MESSAGE_TIMEOUT_MS),
                "resumed, and with game time at the ETA, the vessel must announce its arrival");
    }

    /**
     * A speed-up brings the arrival forward with game time. The vessel is two game hours out on
     * a 60-minute day — a 300-second wall wait at ×1. At ×4 those two hours pass in 75 real
     * seconds, and the pre-S14 waker would still have been asleep for another 225.
     */
    @Test
    void aSpeedUpBringsTheArrivalForwardWithGameTime() throws Exception {
        bootWithStandInHarbourMaster(3_600L);
        long etaSimMillis = clock.nowSimMillis() + 7_200_000L;
        assertEquals(300_000L, clock.simSecondsToWallMs(7_200L), "fixture: five real minutes at x1");
        VesselSpec spec = approaching("AW02", etaSimMillis);
        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "C-AW02", clock,
                boot.getMarketHistoryArtifact());

        // Not yet: nothing has moved game time, and the wait is five minutes long. Three wall
        // seconds is three of the waker's one-second chunks.
        assertNull(awaitMessage(arrivalOf(spec), 3_000L), "two game hours out, the vessel is still approaching");

        // Game time first, the speed-up second. The waker re-derives its wait only when the pace
        // changes (SimDeadlineWait's epoch), and a hand jump is not a pace change: a waker boundary
        // landing between a speed-up and a LATER jump re-derives from the game time before the jump
        // and then sleeps the jump out (74 s). The second S19 gate failed on exactly that, and the old
        // order, run alone, failed 2 runs in 10: the 3-s wait above puts these lines on the waker's
        // third boundary. A real game never jumps.
        clock.advance(300_000L); // two game hours at x1, by hand
        clock.setSpeedMultiplier(4);

        assertTrue(clock.nowSimMillis() >= etaSimMillis, "fixture: game time is at the ETA");
        assertNotNull(awaitMessage(arrivalOf(spec), MESSAGE_TIMEOUT_MS),
                "the arrival must follow game time, not the wall delay armed at x1 (still ~295 s away)");
    }

    /** An ETA that is already due is not held by a pause — a walk-in spawned while paused arrives. */
    @Test
    void aVesselAlreadyDueArrivesEvenWhilePaused() throws Exception {
        bootWithStandInHarbourMaster(300L);
        clock.pause();
        VesselSpec spec = approaching("AW03", clock.nowSimMillis());

        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "C-AW03", clock,
                boot.getMarketHistoryArtifact());

        assertNotNull(awaitMessage(arrivalOf(spec), MESSAGE_TIMEOUT_MS),
                "a pause holds a vessel short of its ETA, never one that is already there");
    }

    // ---- harness (the WalkInPatienceIT stand-in) ----

    private static VesselSpec approaching(String vesselId, long etaSimMillis) {
        return new VesselSpec(vesselId, "cargo_vessel", 9.0, 150.0, 30_000, "general_cargo", etaSimMillis);
    }

    /** The vessel's arrival announcement: its first message on its berth conversation. */
    private static Predicate<ACLMessage> arrivalOf(VesselSpec spec) {
        String conversationId = "berth-" + AgentRoster.vesselLocalName(spec);
        return m -> conversationId.equals(m.getConversationId());
    }

    private void bootWithStandInHarbourMaster(long realSecondsPerGameDay) throws InterruptedException {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", realSecondsPerGameDay));
        clock = boot.getSimClock();
        boot.getSpawner().spawn(STAND_IN_HM, HmProbeAgent.class,
                new Object[] {hmOutbox, hmInbox, "harbour-master"});

        // Barrier before any vessel spawns: the probe's message loop only starts once its
        // blocking DF registration has returned, so a note it posts to itself and hands back
        // proves a vessel's harbour-master lookup will find it.
        ACLMessage ready = MessageFactory.create(ACLMessage.INFORM);
        ready.addReceiver(new AID(STAND_IN_HM, AID.ISLOCALNAME));
        ready.setConversationId("stand-in-ready");
        hmOutbox.put(ready);
        assertNotNull(awaitMessage(m -> "stand-in-ready".equals(m.getConversationId()), MESSAGE_TIMEOUT_MS),
                "setup: the stand-in HarbourMaster must be up and registered in the DF");
    }

    /** Bounded wait for a message at the stand-in HarbourMaster; non-matching ones are kept. */
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
}
