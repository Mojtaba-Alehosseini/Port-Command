package it.unige.portcommand.agents;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.assistant.AssistantWording;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.AutopilotOwnershipEvent;
import it.unige.portcommand.gui.events.HintButtonEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.events.TakeOverEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21, decision A1): the autopilot carries a negotiation to an END — a deal, an explicit
 * "no", or the vessel leaving — on the real wire: a real session, the real HarbourMaster and its
 * Prolog gate, a real walk-in with the real negotiation engine.
 *
 * <p>Born red, case by case, on the pre-S17a code — these are the reproductions of the three
 * stall causes the tree analysis named (and the note on the third):
 * <ol>
 *   <li>a "reject" decision dispatched NOTHING, so the vessel sat there until its patience ran
 *       out and the port paid a reputation point for a timeout;</li>
 *   <li>switching the autopilot on in the middle of a talk did nothing until the vessel spoke
 *       again — and the vessel was waiting for the port;</li>
 *   <li>a command the Prolog gate refused was never followed up. Since S16 took the weather out
 *       of the deal gate this cannot happen to an autopilot command in play (it never names a
 *       berth, and the HarbourMaster's candidate berth is always compatible), so it is pinned
 *       where a refusal can be staged: {@code AutopilotPlanTest}.</li>
 * </ol>
 *
 * <p>The game clock is PAUSED throughout: a pause freezes every vessel's patience, so no
 * negotiation here can end by timing out — if one ends, somebody ended it. Negotiation messages
 * are not game time and flow as usual. Every wait is bounded.
 */
@Tag("integration")
class AutopilotNegotiationIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;
    private GameSession session;

    @AfterEach
    void tearDown() {
        Settings.applyLive(Settings.classpathDefaults());
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    /** A1 (a): "reject" is an explicit no, at once — not silence until the vessel times out. */
    @Test
    @Timeout(120)
    void aRejectDecisionEndsTheNegotiationWithAnExplicitNo() throws Exception {
        startPausedSessionWithAssistantReady();
        new PolicyParser().parse("auto reject if price > 0", boot.getEventBus());
        assertTrue(waitUntil(() -> !boot.getPolicyRegistryArtifact().all().isEmpty(), 10_000),
                "fixture: the policy is registered");
        switchAutopilot(true);

        spawnWalkIn("WA1", "cargo_vessel");
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-WA1".equals(e.dialogueId()), 15_000),
                "fixture: the walk-in hails");

        NegotiationClosedEvent closed = awaitEvent(NegotiationClosedEvent.class,
                e -> "nego-WA1".equals(e.dialogueId()), 15_000);
        assertNotNull(closed, "the autopilot decided to reject and must SAY so; before S17a it sent nothing "
                + "and the vessel waited out its whole patience (a timeout, and -1 reputation)");
        assertEquals(Deal.Outcome.PLAYER_REFUSED, closed.outcome(), "an explicit no: no reputation is lost");
    }

    /** A1 (c): switched on in the middle of a talk, the autopilot answers the offer already waiting. */
    @Test
    @Timeout(120)
    void switchedOnMidTalkTheAutopilotAnswersTheWaitingOffer() throws Exception {
        startPausedSessionWithAssistantReady();
        spawnWalkIn("WC1", "cargo_vessel");
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-WC1".equals(e.dialogueId()), 15_000),
                "fixture: the walk-in hails while the autopilot is off");
        assertTrue(commandsFor("WC1").isEmpty(), "fixture: off means nobody has answered");

        switchAutopilot(true);

        assertTrue(waitUntil(() -> !commandsFor("WC1").isEmpty(), 15_000),
                "the offer was already on the table when the autopilot was switched on: it must be answered "
                        + "now (before S17a nothing happened until the vessel spoke again - and it was waiting)");
        NegotiationClosedEvent closed = awaitEvent(NegotiationClosedEvent.class,
                e -> "nego-WC1".equals(e.dialogueId()), 30_000);
        assertNotNull(closed, "and the autopilot then carries the talk to its end");
        assertNotEquals(Deal.Outcome.TIMEOUT, closed.outcome());
    }

    /** The whole of A1 from the player's seat: on from the start, every round answered, an end reached. */
    @Test
    @Timeout(120)
    void onFromTheStartTheAutopilotNegotiatesEveryRoundToAnEnd() throws Exception {
        startPausedSessionWithAssistantReady();
        switchAutopilot(true);

        for (String id : List.of("WE1", "WE2", "WE3")) {
            spawnWalkIn(id, "WE2".equals(id) ? "container_vessel" : "cargo_vessel");
            NegotiationClosedEvent closed = awaitEvent(NegotiationClosedEvent.class,
                    e -> ("nego-" + id).equals(e.dialogueId()), 30_000);
            assertNotNull(closed, id + ": the negotiation must reach an end with the clock paused - a deal, a no, "
                    + "or the vessel walking away over price; commands sent: " + commandsFor(id));
            assertNotEquals(Deal.Outcome.TIMEOUT, closed.outcome(), id);
            assertTrue(!commandsFor(id).isEmpty(), id + ": and the autopilot is who answered her");
            // A9, on the real wire: the HarbourMaster announces each move it sent, and the
            // autopilot's line in the tab is written from that announcement.
            assertNotNull(awaitEvent(AssistantChatEvent.class,
                            e -> ("nego-" + id).equals(e.dialogueId())
                                    && e.text().startsWith(AssistantWording.AUTOPILOT_PREFIX), 10_000),
                    id + ": the autopilot must say in the tab what it did, once it was done");
            assertEveryAutopilotLineFollowsADispatch("nego-" + id);
        }
    }

    /**
     * A9 in the bus's own order: at no point has the autopilot told more moves in {@code dialogueId}
     * than the HarbourMaster had announced as sent. Before S17a the line was published WITH the
     * command, ahead of the gate - one line told, none sent.
     */
    private void assertEveryAutopilotLineFollowsADispatch(String dialogueId) {
        int sent = 0;
        int told = 0;
        for (Event event : EventBusProbe.published(boot.getEventBus())) {
            if (event instanceof PlayerCommandDispatchedEvent d && dialogueId.equals(d.dialogueId())) {
                sent++;
            } else if (event instanceof AssistantChatEvent chat && dialogueId.equals(chat.dialogueId())
                    && chat.text().startsWith(AssistantWording.AUTOPILOT_PREFIX)) {
                told++;
                assertTrue(told <= sent, dialogueId + ": the autopilot told move " + told + " (" + chat.text()
                        + ") when the HarbourMaster had sent only " + sent);
            }
        }
        assertTrue(told >= 1, dialogueId + ": fixture - at least one move was told");
    }

    /**
     * Adversarial review: an exception out of a JADE behaviour ends the agent, and the chat tabs
     * take their lock from the Assistant alone - a dead Assistant would leave every tab locked
     * with nobody to answer "Take over". A step that fails must cost that step only. Here an
     * offer from a vessel type the calculation does not know makes the autopilot's step throw;
     * the walk-in that hails afterwards must still be negotiated to an end.
     */
    @Test
    @Timeout(120)
    void aStepThatFailsDoesNotTakeTheAssistantDown() throws Exception {
        startPausedSessionWithAssistantReady();
        switchAutopilot(true);

        boot.getEventBus().publish(new NegotiationOpenedEvent("nego-BOGUS",
                new it.unige.portcommand.assistant.WalkInDialogueSnapshot("BOGUS", "submarine", 9.0, 150.0, 30_000,
                        "general_cargo", "berth_1", 6, 1500.0, 0.0, 1, 0.0, false)));

        spawnWalkIn("WG1", "cargo_vessel");
        NegotiationClosedEvent closed = awaitEvent(NegotiationClosedEvent.class,
                e -> "nego-WG1".equals(e.dialogueId()), 30_000);
        assertNotNull(closed, "the Assistant must still be alive and negotiating after a step of its own failed; "
                + "commands sent: " + commandsFor("WG1"));
        assertNotEquals(Deal.Outcome.TIMEOUT, closed.outcome());
        assertTrue(commandsFor("BOGUS").isEmpty(), "fixture: the failing step sent nothing");
    }

    /**
     * PT12 and A2 with the REAL timer: a launched game's autopilot waits before it sends a move
     * (here 1.5 s, so the margins below are wide), "Take over" during that wait cancels the move
     * and hands the tab to the player, and a tab that was not taken over is answered when the wait
     * is up. The only test in the suite that sits through a pacing delay - everything else builds
     * its world with none.
     */
    @Test
    @Timeout(120)
    void aPacedMoveCanBeTakenOverBeforeItGoesOutAndIsSentOtherwise() throws Exception {
        startPausedSessionWithAssistantReady(1_500L);
        switchAutopilot(true);

        spawnWalkIn("WP1", "cargo_vessel");
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-WP1".equals(e.dialogueId()), 15_000),
                "fixture: the walk-in hails");
        assertTrue(commandsFor("WP1").isEmpty(),
                "PT12: the move is decided but not sent yet - before S17a the answer left in the same instant");

        boot.getEventBus().publish(new TakeOverEvent("nego-WP1"));
        AutopilotOwnershipEvent taken = awaitEvent(AutopilotOwnershipEvent.class,
                e -> e.takenOver().contains("nego-WP1"), 10_000);
        assertNotNull(taken, "the Assistant must state that the tab is the player's now");
        assertTrue(taken.autopilotOn(), "the switch itself stays on: one tab was taken over, not all");
        assertFalse(waitUntil(() -> !commandsFor("WP1").isEmpty(), 2_500),
                "the wait is long over: the cancelled move must never go out");

        spawnWalkIn("WP2", "cargo_vessel");
        assertNotNull(awaitEvent(NegotiationOpenedEvent.class, e -> "nego-WP2".equals(e.dialogueId()), 15_000),
                "fixture: a second walk-in hails");
        assertTrue(waitUntil(() -> !commandsFor("WP2").isEmpty(), 15_000),
                "a tab nobody took over is answered when its wait is up - the timer really fires");
        assertTrue(commandsFor("WP1").isEmpty(), "and the first tab is still the player's");
    }

    // ---- staging ---------------------------------------------------------------------------

    /**
     * A sandbox session with the clock paused, and the Assistant known to be listening: its four
     * plans subscribe on its own thread just after setup, so the test waits (bounded) until each
     * plan's event has a subscriber. (This used to press Hint until one was answered. With the
     * sidecar up - as it is for the gate - every press started a real model generation that kept
     * the machine busy for the next half minute, right under the one test here that measures a
     * 1.5-second window; adversarial review.)
     */
    private void startPausedSessionWithAssistantReady() throws Exception {
        startPausedSessionWithAssistantReady(0L);
    }

    /** @param autopilotDelayMillis the pacing the session's Assistant is given; 0 = none, as in every other test */
    private void startPausedSessionWithAssistantReady(long autopilotDelayMillis) throws Exception {
        boot = new JadeBootstrap();
        session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300, autopilotDelayMillis),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        boot.getSimClock().pause();
        assertTrue(waitUntil(() -> session.directory().byName("harbour_master").isPresent(), 15_000),
                "fixture: the HarbourMaster is up");
        assertTrue(waitUntil(() -> List.of(HintButtonEvent.class, NegotiationOpenedEvent.class, TakeOverEvent.class,
                                PlayerCommandDispatchedEvent.class,
                                it.unige.portcommand.gui.events.PolicyParsedEvent.class,
                                it.unige.portcommand.gui.events.ExplainRequestEvent.class).stream()
                        .allMatch(type -> EventBusProbe.subscriberCount(boot.getEventBus(), type) >= 1), 20_000),
                "fixture: the Assistant's four plans are listening");
    }

    /** The Settings dialog's Apply, as the bus sees it. */
    private void switchAutopilot(boolean on) {
        Settings.applyLive(Settings.load().withAutopilotEnabled(on));
        boot.getEventBus().publish(new SettingsChangedEvent("NORMAL",
                boot.getSimClock().realSecondsPerGameDay(), on, Settings.load().llmModel()));
    }

    private void spawnWalkIn(String vesselId, String type) {
        VesselSpec spec = "container_vessel".equals(type)
                ? new VesselSpec(vesselId, type, 12.0, 250.0, 60_000, "containerized_cargo", 0L)
                : new VesselSpec(vesselId, type, 9.0, 150.0, 30_000, "general_cargo", 0L);
        AgentRoster.spawnWalkIn(boot.getSpawner(), spec, boot.getSimClock(), boot.getMarketHistoryArtifact(),
                boot.getRandomSource(),
                new RealNegotiationEngine(Settings.load().roundLimit(),
                        boot.getRandomSource().forStream("nego-" + vesselId)),
                session.directory());
    }

    private List<PlayerCommandEvent> commandsFor(String vesselId) {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(PlayerCommandEvent.class::isInstance).map(PlayerCommandEvent.class::cast)
                .filter(e -> vesselId.equals(e.targetVesselId()))
                .toList();
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
