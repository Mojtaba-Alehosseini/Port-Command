package it.unige.portcommand.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-22 session capstone, in one staged flow:
 * <ol>
 *   <li><b>Step 22.7:</b> a manual save during a live negotiation is refused;</li>
 *   <li><b>hidden beliefs verbatim:</b> a closed-deal walk-in's persisted beliefs equal its
 *       live beliefs after a load — never re-rolled;</li>
 *   <li><b>THE TRAP (task-20 review's prediction for this exact task):</b> after
 *       save → load → load, exactly ONE of each session-scoped bus subscriber is alive —
 *       one synthetic deal moves the wallet EXACTLY once, and the subscriber counts are
 *       structural proof (a stale LedgerCoordinator/DispatchPlayerCommand/Assistant plan
 *       would show as a second subscription);</li>
 *   <li><b>privacy:</b> after a load no negotiation resurrects — no
 *       {@code NegotiationOpenedEvent}, no PROPOSE traffic from the restored vessel;</li>
 *   <li><b>load-determinism:</b> two loads of one file restore identical facts (the
 *       stream-level guarantee is {@code RandomSourceSaveSeedTest}'s; a byte-level trace
 *       compare is deliberately NOT asserted here — the live wall-clock advancer drifts
 *       sim-time between loads, which changes timestamps but no seeded draw).</li>
 * </ol>
 */
@Tag("integration")
class SaveLoadSessionIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void saveGuard_verbatimBeliefs_exactlyOnceCharging_privacy_andLoadDeterminism() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        SimClock clock = boot.getSimClock();

        // --- Stage a ferry walk-in (tugsRequired == 0 — the 07b ferry path keeps CNP out). ---
        VesselSpec spec = new VesselSpec("WALKIN-T1", "ferry", 5.0, 100.0, 8_000,
                "general_cargo", 0L);
        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, clock, boot.getMarketHistoryArtifact(),
                boot.getRandomSource(),
                new RealNegotiationEngine(Settings.load().roundLimit(),
                        boot.getRandomSource().forStream("nego-WALKIN-T1")),
                session.directory());
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> true, 15_000),
                "the walk-in's opening offer must reach the player relay");

        // --- 22.7: while that negotiation is live, a manual save is REFUSED. ---
        assertThrows(SaveNotAllowedException.class, session::saveNow,
                "save during a live negotiation must be blocked with the polite message");

        // --- Close the deal through the REAL wire path (bus → dispatch → vessel → CONFIRM). ---
        boot.getEventBus().publish(new PlayerCommandEvent(PlayerCommandKind.ACCEPT, "WALKIN-T1", Map.of()));
        DealClosedEvent deal = awaitEvent(DealClosedEvent.class,
                e -> "WALKIN-T1".equals(e.deal().vesselId()), 15_000);
        assertNotNull(deal, "accepting must close the deal");
        BaseVesselAgent live = awaitVesselWithPhase("vessel_walkin_t1", session);
        VesselStateDTO beliefsBefore = live.snapshotDto();
        assertNotNull(beliefsBefore.minAcceptablePrice(), "the staged walk-in has hidden beliefs");

        // --- Save (negotiation closed → allowed), then load TWICE. ---
        Path saved = session.saveNow();
        GameState savedState = new SaveLoadManager(tempDir).load(saved);
        double walletAtSave = savedState.wallet();
        assertEquals(1, savedState.agents().activeVessels().size());

        session.loadGame(saved);
        VesselStateDTO afterFirstLoad = awaitVesselWithPhase("vessel_walkin_t1", session).snapshotDto();
        session.loadGame(saved);
        VesselStateDTO afterSecondLoad = awaitVesselWithPhase("vessel_walkin_t1", session).snapshotDto();
        int loadMarker = lastIndexOf(GameLoadedEvent.class);

        // --- Hidden beliefs: persisted and restored VERBATIM, identically on every load. ---
        for (VesselStateDTO restored : List.of(afterFirstLoad, afterSecondLoad)) {
            assertEquals(beliefsBefore.personality(), restored.personality());
            assertEquals(beliefsBefore.minAcceptablePrice(), restored.minAcceptablePrice());
            assertEquals(beliefsBefore.targetPrice(), restored.targetPrice());
            assertEquals(beliefsBefore.maxWaitMinutes(), restored.maxWaitMinutes());
            assertEquals(beliefsBefore.minDurationHours(), restored.minDurationHours());
            assertEquals(beliefsBefore.dealPrice(), restored.dealPrice());
            assertEquals(beliefsBefore.dealHours(), restored.dealHours());
        }

        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertEquals(walletAtSave, hm.walletLedger().balance(), 0.0001,
                "both loads restore the saved wallet exactly");

        // --- THE TRAP, structurally: after two teardown/rebuilds, ONE subscriber each. ---
        assertEquals(1, EventBusProbe.subscriberCount(boot.getEventBus(), DealClosedEvent.class),
                "exactly one LedgerCoordinator alive — a stale one would double-charge");
        assertEquals(1, EventBusProbe.subscriberCount(boot.getEventBus(), PlayerCommandEvent.class),
                "exactly one DispatchPlayerCommand subscription alive");
        assertEquals(1, EventBusProbe.subscriberCount(boot.getEventBus(), NegotiationOpenedEvent.class),
                "exactly one Assistant autopilot plan alive");
        assertEquals(1, EventBusProbe.subscriberCount(boot.getEventBus(), AutosaveRequestedEvent.class),
                "exactly one AutosaveCoordinator (session-scoped, survives loads)");

        // --- THE TRAP, behaviourally: one deal moves the wallet EXACTLY once. ---
        double synthPrice = 1_000.0;
        double expectedBase = IncomeRules.berthBase(null, 5, synthPrice);
        double expectedPremium = IncomeRules.premiumSurcharge(expectedBase,
                hm.reputationLedger().score());
        boot.getEventBus().publish(new DealClosedEvent(new Deal("deal-SYNTH-1", "SYNTH-1", "berth_1",
                synthPrice, 5, clock.nowSimMillis(), Deal.Outcome.DEAL)));
        double expected = walletAtSave + expectedBase + expectedPremium;
        assertTrue(waitUntil(() -> Math.abs(hm.walletLedger().balance() - expected) < 0.0001, 5_000),
                "one closed deal must move the wallet by its fee EXACTLY once (was: "
                        + hm.walletLedger().balance() + ", expected " + expected + ")");

        // --- Privacy across the round trip: nothing negotiation-shaped resurrects for the
        // RESTORED vessel (a fresh live-spawned walk-in opening its own negotiation is fine). ---
        List<Event> postLoad = EventBusProbe.published(boot.getEventBus());
        postLoad = postLoad.subList(loadMarker + 1, postLoad.size());
        assertTrue(postLoad.stream()
                        .filter(NegotiationOpenedEvent.class::isInstance)
                        .map(NegotiationOpenedEvent.class::cast)
                        .noneMatch(e -> "nego-WALKIN-T1".equals(e.dialogueId())),
                "a restored walk-in never re-opens its negotiation (hidden beliefs stay unplayed)");
        assertTrue(postLoad.stream()
                        .filter(CommLogEvent.class::isInstance).map(CommLogEvent.class::cast)
                        .noneMatch(e -> e.performative() == ACLMessage.PROPOSE
                                && "vessel_walkin_t1".equals(e.sender())),
                "a restored walk-in sends no PROPOSE — its beliefs are carried, never surfaced");
    }

    /**
     * Checkpoint-#6 F3 (fixed 2026-07-18): the ledgers publish only on mutation, so a fresh
     * boot rendered "Wallet: —" until the first money event. The window's one-time
     * {@code GuiReadyEvent} now triggers the same synthetic absolute refresh the load path
     * publishes — here fired from the test thread ({@code CALLER_THREAD} subscription, so the
     * refresh is synchronous) against a freshly started game.
     */
    @Test
    void guiReady_publishesLiveHudRefreshAtFreshBoot() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();

        boot.getEventBus().publish(new it.unige.portcommand.gui.events.GuiReadyEvent());

        List<Event> published = EventBusProbe.published(boot.getEventBus());
        assertTrue(published.stream()
                        .filter(it.unige.portcommand.gui.events.WalletChangedEvent.class::isInstance)
                        .map(it.unige.portcommand.gui.events.WalletChangedEvent.class::cast)
                        .anyMatch(e -> "gui_ready".equals(e.source())
                                && e.balance() == AgentRoster.STARTING_WALLET && e.delta() == 0.0),
                "GuiReadyEvent must publish a synthetic absolute wallet refresh with the "
                        + "starting balance — the HUD must never boot to the \"—\" placeholder");
        assertTrue(published.stream()
                        .filter(it.unige.portcommand.gui.events.ReputationChangedEvent.class::isInstance)
                        .map(it.unige.portcommand.gui.events.ReputationChangedEvent.class::cast)
                        .anyMatch(e -> "gui_ready".equals(e.reason())
                                && e.score() == AgentRoster.STARTING_REPUTATION),
                "GuiReadyEvent must publish the matching reputation refresh");
    }

    private BaseVesselAgent awaitVesselWithPhase(String localName, GameSession session)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var agent = session.directory().byName(localName)
                    .filter(BaseVesselAgent.class::isInstance)
                    .map(BaseVesselAgent.class::cast)
                    .filter(v -> v.flowPhase() != null);
            if (agent.isPresent()) {
                return agent.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError(localName + " never reported a flow phase");
    }

    private int lastIndexOf(Class<? extends Event> type) {
        List<Event> events = EventBusProbe.published(boot.getEventBus());
        for (int i = events.size() - 1; i >= 0; i--) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private <E extends Event> E awaitEvent(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Event event : EventBusProbe.published(boot.getEventBus())) {
                if (type.isInstance(event) && matcher.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
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
