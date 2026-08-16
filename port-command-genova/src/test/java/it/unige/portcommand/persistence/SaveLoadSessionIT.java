package it.unige.portcommand.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
import it.unige.portcommand.nlp.PolicyParser;
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
        // S7 (audit P3-3): the expectation is a LITERAL, not an IncomeRules echo — berthBase is
        // a passthrough of the deal price (planning/20's table), and the reputation premium is
        // 0 below the 80-point unlock. The precondition pins the premium term at zero so the
        // literal cannot silently absorb a surcharge.
        assertTrue(hm.reputationLedger().score() < 80.0,
                "fixture: a fresh game starts below the premium threshold (50 < 80), score was "
                        + hm.reputationLedger().score());
        double synthPrice = 1_000.0;
        boot.getEventBus().publish(new DealClosedEvent(new Deal("deal-SYNTH-1", "SYNTH-1", "berth_1",
                synthPrice, 5, clock.nowSimMillis(), Deal.Outcome.DEAL)));
        double expected = walletAtSave + 1_000.0;
        assertTrue(waitUntil(() -> Math.abs(hm.walletLedger().balance() - expected) < 0.0001, 5_000),
                "one closed deal must move the wallet by its fee EXACTLY once (was: "
                        + hm.walletLedger().balance() + ", expected " + expected + ")");

        // --- Privacy across the round trip: nothing negotiation-shaped resurrects for the
        // RESTORED vessel (a fresh live-spawned walk-in opening its own negotiation is fine). ---
        List<Event> postLoad = publishedAfterLast(GameLoadedEvent.class);
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

        // --- S7 (audit P3-11): a STRAY negotiation message must not kill the restored vessel.
        // Historically the restore path bound NoOpNegotiationEngine, whose evaluate() THREW —
        // and JADE kills an agent whose behaviour throws. What this block can honestly pin
        // TODAY: (a) the stray PROPOSE moves no negotiation state (P-04 — the dialogue stays
        // concluded), and (b) the agent's JADE lifecycle survives it (state never AP_DELETED —
        // a DIRECT liveness witness, deliberately not a flow-phase one: at 300 s/day the ferry
        // may already be DOCKED here, which made a phase-progress witness vacuous, adversarial
        // finding 9). The RealNegotiationEngine binding itself is belt-and-braces — no restore
        // path attaches negotiation behaviours, so no test can reach evaluate() until a future
        // change re-attaches them; the binding exists so THAT change inherits a real engine
        // instead of a grenade (stated, not overclaimed — finding 10).
        BaseVesselAgent restored = awaitVesselWithPhase("vessel_walkin_t1", session);
        boot.getSpawner().spawn("stray_probe", it.unige.portcommand.agents.MessageProbe.class,
                new Object[] {"vessel_walkin_t1", ACLMessage.PROPOSE,
                        "{\"intent\": \"counter_offer\", \"price\": 9999.0, \"hours\": 5}", null});
        clock.advance(clock.simSecondsToWallMs(120));
        // Give delivery (and any hypothetical behaviour-throw kill) time to land, then check
        // the lifecycle: a killed agent is AP_DELETED. The negative must HOLD for the window,
        // so this waits out the full bound rather than returning early on the happy state.
        Thread.sleep(1_500); // bounded, explicit (design rule 5): the kill would be async
        assertTrue(restored.getState() != jade.core.Agent.AP_DELETED,
                "the restored vessel must survive a stray PROPOSE — AP_DELETED means a "
                        + "behaviour threw on it (the old NoOp-engine failure mode)");
        assertTrue(publishedAfterLast(GameLoadedEvent.class).stream()
                        .filter(NegotiationOpenedEvent.class::isInstance)
                        .map(NegotiationOpenedEvent.class::cast)
                        .noneMatch(e -> "nego-WALKIN-T1".equals(e.dialogueId())),
                "the stray PROPOSE must not re-open the concluded negotiation either");
    }

    /**
     * Audit C-01/D-02, decision taken 2026-07-28 — the demo's own scripted policy sentence, end to
     * end: typed → parsed → published → registered on the Assistant → persisted → still live after
     * a load.
     *
     * <p>Every link existed before and the chain was <b>unreachable</b>, because nothing called
     * {@code PolicyParser.parse(text, eventBus)} in production. DEMO_CHECKLIST Row 6 claimed this
     * was "covered indirectly by {@code SaveLoadSessionIT} (policy triggers survive a save/load)" —
     * it was not: before this test the string "polic" did not appear in this file at all. So the
     * round-trip {@code GameState.policyTriggers} advertises had no test either.
     *
     * <p>Drives the real {@code PolicyParser} against the real bus so the Assistant's own
     * {@code PolicyParseBehaviour} does the registering — the Settings field's exact code path,
     * minus the Swing widget ({@code SettingsFormTest} covers that half headlessly).
     */
    @Test
    void theScriptedDemoPolicyRegistersAndSurvivesASaveLoad() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();

        PolicyRegistryArtifact registry = boot.getPolicyRegistryArtifact();
        assertTrue(registry.all().isEmpty(), "a fresh game starts with no policies");

        // Exactly what DEMO_SCRIPT Beat 4 has the presenter type.
        Optional<PolicyRule> parsed = new PolicyParser().parse(
                PolicyParser.GRAMMAR_EXAMPLE, boot.getEventBus());
        assertTrue(parsed.isPresent(), "the demo's scripted sentence must parse");

        // PolicyParseBehaviour subscribes ASYNC, so wait for it rather than assuming.
        assertTrue(waitUntil(() -> registry.all().size() == 1, 15_000),
                "the Assistant's PolicyParseBehaviour must register the rule; registry="
                        + registry.all());
        assertEquals(PolicyParser.GRAMMAR_EXAMPLE, registry.all().get(0).trigger());

        // It must actually MATCH the vessel it scopes to, and not the ones it does not — otherwise
        // "registered" would be a claim about a lambda nobody ever calls.
        assertTrue(registry.firstMatching(snapshot("tanker", 2_400.0)).isPresent(),
                "a tanker asking 2400 is above the 2200 threshold");
        assertTrue(registry.firstMatching(snapshot("tanker", 2_100.0)).isEmpty(),
                "2100 is below the threshold — deferred to the player");
        assertTrue(registry.firstMatching(snapshot("cargo_vessel", 2_400.0)).isEmpty(),
                "the 'for tankers' scope must exclude other types");

        Path saved = session.saveNow();
        assertEquals(List.of(PolicyParser.GRAMMAR_EXAMPLE),
                new SaveLoadManager(tempDir).load(saved).policyTriggers(),
                "the trigger text is what the save carries — the predicate is a lambda");

        session.loadGame(saved);
        assertTrue(waitUntil(() -> boot.getPolicyRegistryArtifact().all().size() == 1, 15_000),
                "restorePolicies must re-parse the persisted trigger into a live rule");
        assertTrue(boot.getPolicyRegistryArtifact().firstMatching(snapshot("tanker", 2_400.0)).isPresent(),
                "the RESTORED rule must be a working predicate, not just a remembered string");
    }

    private static WalkInDialogueSnapshot snapshot(String vesselType, double vesselAsk) {
        VesselSpec spec = new VesselSpec("POL-1", vesselType, 9.0, 150.0, 30_000, "general_cargo", 0L);
        return WalkInDialogueSnapshot.of("POL-1", spec, "berth_1", 6, vesselAsk, 0.0, 1, 0.0, false);
    }

    /**
     * Checkpoint-#6 F3 (fixed 2026-07-18): the ledgers publish only on mutation, so a fresh boot
     * rendered "Wallet: —" until the first money event. The window's one-time
     * {@code GuiReadyEvent} triggers the same synthetic absolute refresh the load path publishes —
     * here fired from the test thread against a freshly started game.
     *
     * <p>The refresh is delivered {@code ASYNC} since 2026-08-10 (audit P1-14: the real publisher
     * is {@code MainWindow.windowOpened}, on the EDT, and the handler reads live agent state), so
     * these assertions MUST poll. Before that change the subscription was {@code CALLER_THREAD}
     * and this test read the audit log immediately after {@code publish} returned — correct then,
     * a race now.
     */
    @Test
    void guiReady_publishesLiveHudRefreshAtFreshBoot() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();

        boot.getEventBus().publish(new it.unige.portcommand.gui.events.GuiReadyEvent());

        assertTrue(waitUntil(() -> EventBusProbe.published(boot.getEventBus()).stream()
                        .filter(it.unige.portcommand.gui.events.WalletChangedEvent.class::isInstance)
                        .map(it.unige.portcommand.gui.events.WalletChangedEvent.class::cast)
                        .anyMatch(e -> "gui_ready".equals(e.source())
                                && e.balance() == AgentRoster.STARTING_WALLET && e.delta() == 0.0), 10_000),
                "GuiReadyEvent must publish a synthetic absolute wallet refresh with the "
                        + "starting balance — the HUD must never boot to the \"—\" placeholder");
        assertTrue(waitUntil(() -> EventBusProbe.published(boot.getEventBus()).stream()
                        .filter(it.unige.portcommand.gui.events.ReputationChangedEvent.class::isInstance)
                        .map(it.unige.portcommand.gui.events.ReputationChangedEvent.class::cast)
                        .anyMatch(e -> "gui_ready".equals(e.reason())
                                && e.score() == AgentRoster.STARTING_REPUTATION), 10_000),
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

    /**
     * The audit-log slice published after the last event of {@code markerType}, boundary re-derived
     * against the SAME snapshot it slices.
     *
     * <p>Replaces a {@code lastIndexOf} whose result was cached and then applied to a list fetched
     * seconds later. Since audit P1-2 the audit log is a bounded drop-oldest ring: a stale index
     * points past the real boundary once eviction shifts the list, and a marker that has itself
     * aged out used to return {@code -1}, whose {@code subList(0, size)} silently yielded the WHOLE
     * history — turning the "nothing resurrects after the load" assertions into pre-load noise.
     * Now it fails loudly instead of guessing.
     */
    private List<Event> publishedAfterLast(Class<? extends Event> markerType) {
        List<Event> events = EventBusProbe.published(boot.getEventBus());
        for (int i = events.size() - 1; i >= 0; i--) {
            if (markerType.isInstance(events.get(i))) {
                return events.subList(i + 1, events.size());
            }
        }
        throw new AssertionError("no " + markerType.getSimpleName() + " left in the " + events.size()
                + "-event audit log: the bounded ring evicted the boundary this test slices on");
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
