package it.unige.portcommand.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.behaviours.assistant.AutopilotExecuteBehaviour;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.AutopilotOwnershipEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandDispatchedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.gui.events.TakeOverEvent;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import jade.core.Agent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21): the autopilot plan, one decision at a time — {@link AutopilotExecuteBehaviour}'s
 * handlers called directly, on this thread, with what it put on the bus read back from the bus's
 * own log. No JADE container, no HarbourMaster: the verdicts the HarbourMaster would send
 * ({@link PlayerCommandDispatchedEvent}, {@link PlayerCommandRefusedEvent}) are handed in by the
 * test, which is also the only place a REFUSAL can be staged at all (since S16 no autopilot move
 * is refused in play — see {@code AutopilotNegotiationIT}, the same decisions on the real wire).
 *
 * <p>The expected-value path uses the real {@link RecommendationAlgorithm}, which asks Prolog
 * whether the vessel fits the berth (R7), so the engine is loaded as in {@code WeatherNoteTest}.
 * Each fixture that relies on what the algorithm recommends says so with an assertion of its own,
 * so a change in the negotiation math fails as "fixture", not as a wrong autopilot.
 *
 * <p>Pacing: every test but the PT12 block builds the plan with no delay, where a move is sent in
 * the step that decided it. The PT12 block hands in a timer it fires by hand — nothing here
 * sleeps or waits.
 */
class AutopilotPlanTest {

    private static final String PREFIX = "🤖 Assistant (autopilot): ";

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    /** A {@code MoveTimer} whose tasks run when the test says so. */
    private static final class ManualTimer implements AutopilotExecuteBehaviour.MoveTimer {
        private final List<Runnable> waiting = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void after(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            waiting.add(task);
        }

        void fire() {
            List<Runnable> due = List.copyOf(waiting);
            waiting.clear();
            due.forEach(Runnable::run);
        }
    }

    private EventBus bus;
    private AtomicBoolean on;
    private PolicyRegistryArtifact policies;
    private RecommendationAlgorithm algorithm;
    private RecommendationCache cache;

    @BeforeEach
    void world() {
        bus = new EventBus();
        on = new AtomicBoolean(true);
        policies = new PolicyRegistryArtifact();
        algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        cache = new RecommendationCache();
    }

    private AutopilotExecuteBehaviour plan() {
        return new AutopilotExecuteBehaviour(new Agent(), algorithm, policies, bus, cache, on);
    }

    private AutopilotExecuteBehaviour pacedPlan(ManualTimer timer) {
        return new AutopilotExecuteBehaviour(new Agent(), algorithm, policies, bus, cache, on,
                new AutopilotPacing(AutopilotPacing.GAME_MOVE_DELAY_MILLIS), timer);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static String dialogueOf(String vesselId) {
        return "nego-" + vesselId;
    }

    /** A tanker at the centre of her fee band with ten seconds left on her clock: the calculation
     * keeps its caution there and ACCEPTS (EV 6000 - 1100). S19 (PT38a): with rounds and time left
     * it counters instead - RecommendationAlgorithmTest - and these tests are about the plan. */
    private static WalkInDialogueSnapshot tanker(String vesselId, double offer, int round) {
        return new WalkInDialogueSnapshot(vesselId, "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, offer, 0.0, round, 0.0, false).withTime(170.0, 180.0);
    }

    /** A tanker offering far below her fee band (4000-8000), early in the talk: the calculation
     * COUNTERS at the band floor, EUR 4000. */
    private static WalkInDialogueSnapshot lowTanker(String vesselId) {
        return new WalkInDialogueSnapshot(vesselId, "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, 1000.0, 0.0, 1, 0.0, false);
    }

    /** The playtest's own case (PT13): 17 hours for EUR 1477 costs more than it pays - the calculation says NO. */
    private static WalkInDialogueSnapshot longCheapCargo(String vesselId) {
        return new WalkInDialogueSnapshot(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo",
                "berth_1", 17, 1477.0, 0.0, 1, 0.0, false);
    }

    private void policy(String text) {
        policies.register(new PolicyParser().parse(text, new EventBus()).orElseThrow());
    }

    private List<PlayerCommandEvent> commands() {
        return events(PlayerCommandEvent.class);
    }

    private List<String> chatLines() {
        return events(AssistantChatEvent.class).stream().map(AssistantChatEvent::text).toList();
    }

    private List<AutopilotOwnershipEvent> ownership() {
        return events(AutopilotOwnershipEvent.class);
    }

    private <E extends Event> List<E> events(Class<E> type) {
        return EventBusProbe.published(bus).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static PlayerCommandDispatchedEvent dispatched(PlayerCommandEvent cmd) {
        return new PlayerCommandDispatchedEvent(dialogueOf(cmd.targetVesselId()), cmd.kind(), cmd.commandId());
    }

    private static PlayerCommandRefusedEvent refused(PlayerCommandEvent cmd) {
        return new PlayerCommandRefusedEvent(dialogueOf(cmd.targetVesselId()), cmd.kind(), cmd.commandId(), 0.0,
                "berth_1 cannot take her [R8]");
    }

    // =============================================================================================
    // A1 (a): "reject" is an explicit no
    // =============================================================================================

    @Test
    void aPolicysRejectIsAnExplicitNoOnTheWire() {
        policy("auto reject if price > 0");
        AutopilotExecuteBehaviour plan = plan();

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        assertEquals(1, commands().size(), "before S17a a reject dispatched NOTHING and the vessel timed out");
        PlayerCommandEvent no = commands().get(0);
        assertEquals(PlayerCommandKind.REJECT, no.kind());
        assertEquals("v1", no.targetVesselId());
        assertEquals(Map.of("conversation_id", "nego-v1"), no.content(), "a no names the talk and carries no price");
        assertNotEquals(PlayerCommandEvent.NO_COMMAND_ID, no.commandId());

        plan.onCommandDispatched(dispatched(no));
        assertEquals(List.of(PREFIX + "said no (your policy: auto reject if price > 0)."), chatLines());
    }

    @Test
    void theCalculationsRejectIsAnExplicitNoToo() {
        WalkInDialogueSnapshot snapshot = longCheapCargo("v2");
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.REJECT_SILENT, rec.action(), "fixture: this offer does not pay");
        assertEquals("compatible", rec.prologStatus(), "fixture: and not because of the berth");
        AutopilotExecuteBehaviour plan = plan();

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v2", snapshot));

        assertEquals(1, commands().size());
        assertEquals(PlayerCommandKind.REJECT, commands().get(0).kind());
        plan.onCommandDispatched(dispatched(commands().get(0)));
        assertEquals(List.of(PREFIX + "said no — costs (berth time, tugs, customs) are higher than this offer."),
                chatLines());
    }

    @Test
    void aShipThatDoesNotFitTheBerthIsToldNoAndTheLineSaysWhy() {
        // A tanker at the container berth: R7 fails, so no move that prices a deal there may be made.
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("v3", "tanker", 14.0, 140.0, 20000,
                "liquid_bulk", "berth_2", 6, 6000.0, 0.0, 1, 0.0, false);
        assertEquals("incompatible", algorithm.run(snapshot).prologStatus(), "fixture: R7 refuses this pair");
        AutopilotExecuteBehaviour plan = plan();

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v3", snapshot));

        assertEquals(PlayerCommandKind.REJECT, commands().get(0).kind());
        plan.onCommandDispatched(dispatched(commands().get(0)));
        assertEquals(List.of(PREFIX + "said no — this ship does not fit berth 2."), chatLines());
    }

    @Test
    void anAcceptAndACounterGoOutAsBeforeWithTheirPriceAndTheTalksId() {
        AutopilotExecuteBehaviour plan = plan();
        assertEquals(RecommendationCandidate.COUNTER, algorithm.run(lowTanker("v5")).action(),
                "fixture: far below her band, the calculation counters");

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v4", tanker("v4", 6000.0, 1)));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v5", lowTanker("v5")));

        assertEquals(2, commands().size());
        PlayerCommandEvent accept = commands().get(0);
        assertEquals(PlayerCommandKind.ACCEPT, accept.kind());
        assertEquals(Map.of("price", 6000.0, "conversation_id", "nego-v4"), accept.content());
        PlayerCommandEvent counter = commands().get(1);
        assertEquals(PlayerCommandKind.PROPOSE, counter.kind());
        assertEquals(Map.of("price", 4000.0, "conversation_id", "nego-v5"), counter.content());
        assertNotEquals(accept.commandId(), counter.commandId(), "every move has its own id");
    }

    // =============================================================================================
    // A9: the line is written after the gate, not before
    // =============================================================================================

    @Test
    void aMoveIsNarratedOnlyOnceTheHarbourMasterHasSentIt() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        PlayerCommandEvent accept = commands().get(0);

        assertEquals(List.of(), chatLines(),
                "before S17a the line was published WITH the command, before the Prolog gate had judged it");

        plan.onCommandDispatched(new PlayerCommandDispatchedEvent("nego-v1", PlayerCommandKind.ACCEPT,
                accept.commandId() + 1_000));
        assertEquals(List.of(), chatLines(), "somebody else's command is not this move");

        plan.onCommandDispatched(dispatched(accept));
        assertEquals(List.of(PREFIX + "accepted their offer of €6000."), chatLines());

        plan.onCommandDispatched(dispatched(accept));
        assertEquals(1, chatLines().size(), "said once");
    }

    @Test
    void aRefusedMoveIsNeverNarratedAsMade() {
        policy("auto counter +15% if price > 0");
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        PlayerCommandEvent counter = commands().get(0);

        plan.onCommandRefused(refused(counter));
        plan.onCommandDispatched(dispatched(counter)); // cannot happen after a refusal; even so:

        assertEquals(List.of(), chatLines(), "the counter never left the port, and the follow-up to it "
                + "has not been sent by the HarbourMaster yet: nothing has happened that could be told");
    }

    // =============================================================================================
    // A1 (b): a move the gate refused is followed up
    // =============================================================================================

    @Test
    void aRefusedPolicyMoveIsRetriedOnceFromTheCalculation() {
        policy("auto counter +15% if price > 0");
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        PlayerCommandEvent counter = commands().get(0);
        assertEquals(PlayerCommandKind.PROPOSE, counter.kind(), "fixture: the policy's counter went out first");
        assertEquals(6900.0, (Double) counter.content().get("price"), 0.001);

        plan.onCommandRefused(refused(counter));

        assertEquals(2, commands().size(), "before S17a a refusal was the end: the vessel was never answered");
        PlayerCommandEvent retry = commands().get(1);
        assertEquals(PlayerCommandKind.ACCEPT, retry.kind(), "the calculation's own move, the policy set aside");
        assertEquals(Map.of("price", 6000.0, "conversation_id", "nego-v1"), retry.content());
        assertNotEquals(counter.commandId(), retry.commandId());

        plan.onCommandDispatched(dispatched(retry));
        assertEquals(List.of(PREFIX + "accepted their offer of €6000."), chatLines(),
                "and the line names the move that was made, not the policy that was refused");
    }

    @Test
    void aSecondRefusalEndsInAnExplicitNo() {
        policy("auto counter +15% if price > 0");
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        plan.onCommandRefused(refused(commands().get(0)));
        plan.onCommandRefused(refused(commands().get(1)));

        assertEquals(3, commands().size(), "one retry, then the no - never a third attempt at a deal");
        PlayerCommandEvent no = commands().get(2);
        assertEquals(PlayerCommandKind.REJECT, no.kind());
        assertEquals(Map.of("conversation_id", "nego-v1"), no.content());

        plan.onCommandDispatched(dispatched(no));
        assertEquals(List.of(PREFIX + "said no — the port's rules refused the move."), chatLines());

        plan.onCommandRefused(refused(no));
        assertEquals(3, commands().size(), "a no is the last word, whatever becomes of it");
    }

    @Test
    void aRefusedMoveTheCalculationWouldOnlyRepeatGoesStraightToNo() {
        AutopilotExecuteBehaviour plan = plan(); // no policy: the first move IS the calculation's
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        assertEquals(PlayerCommandKind.ACCEPT, commands().get(0).kind());

        plan.onCommandRefused(refused(commands().get(0)));

        assertEquals(2, commands().size());
        assertEquals(PlayerCommandKind.REJECT, commands().get(1).kind(),
                "sending the refused accept again would be refused again");
    }

    @Test
    void aRefusalOfSomebodyElsesCommandChangesNothing() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        long own = commands().get(0).commandId();

        // The player's own typed counter, refused: its id is not one the autopilot gave out.
        plan.onCommandRefused(new PlayerCommandRefusedEvent("nego-v1", PlayerCommandKind.PROPOSE, own + 1_000, 0.0, "R8"));
        plan.onCommandRefused(new PlayerCommandRefusedEvent("nego-v1", PlayerCommandKind.PROPOSE,
                PlayerCommandEvent.NO_COMMAND_ID, 0.0, "R8"));

        assertEquals(1, commands().size());
    }

    @Test
    void afterARefusalNothingIsSentForATalkThePlayerHasTakenOver() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        plan.onTakeOver(new TakeOverEvent("nego-v1"));

        plan.onCommandRefused(refused(commands().get(0)));

        assertEquals(1, commands().size(), "the talk is the player's now; the follow-up is theirs to make");
    }

    // =============================================================================================
    // A1 (c): switched on, it answers what was waiting
    // =============================================================================================

    @Test
    void switchedOnItAnswersEveryOfferThatWasWaiting() {
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v2", tanker("v2", 6200.0, 1)));
        assertEquals(List.of(), commands(), "fixture: off, nobody is answered");

        on.set(true);
        plan.onAutopilotSwitched(true);

        assertEquals(Set.of("v1", "v2"),
                Set.copyOf(commands().stream().map(PlayerCommandEvent::targetVesselId).toList()),
                "before S17a nothing happened until a vessel spoke again - and they were waiting for the port");
        assertEquals(2, commands().size());
    }

    @Test
    void switchedOnItAnswersTheLatestOfferNotAnOlderOne() {
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6300.0, 2)));

        on.set(true);
        plan.onAutopilotSwitched(true);

        assertEquals(1, commands().size());
        assertEquals(6300.0, (Double) commands().get(0).content().get("price"), 0.001);
    }

    @Test
    void switchedOnItLeavesAloneWhatIsTakenOverEndedOrAlreadyAnswered() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1))); // answered at once
        assertEquals(1, commands().size(), "fixture: on, v1's offer is answered");

        on.set(false);
        plan.onAutopilotSwitched(false);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v2", tanker("v2", 6000.0, 1)));
        plan.onTakeOver(new TakeOverEvent("nego-v2")); // the player's
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v3", tanker("v3", 6000.0, 1)));
        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v3", "v3", Deal.Outcome.TIMEOUT)); // gone

        on.set(true);
        plan.onAutopilotSwitched(true);

        assertEquals(1, commands().size(),
                "v1's offer already has its answer (the gate's verdict is still out), v2 is the player's, v3 left");

        // ... and once the vessel speaks again, v1 is answered again.
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6100.0, 2)));
        assertEquals(2, commands().size());
    }

    @Test
    void whileOffNothingIsSent() {
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        assertEquals(List.of(), EventBusProbe.published(bus), "off: no command, no line, nothing");
    }

    // =============================================================================================
    // A2 / A10: who is negotiating where
    // =============================================================================================

    @Test
    void theWholeOwnershipStateIsAnnouncedOnEveryChange() {
        AutopilotExecuteBehaviour plan = plan();

        plan.publishOwnership(); // what the plan does when it starts, and when a window or a load asks
        assertEquals(new AutopilotOwnershipEvent(true, Set.of()), ownership().get(0));
        assertTrue(ownership().get(0).assistantOwns("nego-anything"));

        plan.onTakeOver(new TakeOverEvent("nego-v1"));
        assertEquals(new AutopilotOwnershipEvent(true, Set.of("nego-v1")), ownership().get(1));
        assertFalse(ownership().get(1).assistantOwns("nego-v1"), "that tab is the player's");
        assertTrue(ownership().get(1).assistantOwns("nego-v2"), "every other tab is still the Assistant's");

        plan.onTakeOver(new TakeOverEvent("nego-v1"));
        assertEquals(2, ownership().size(), "a second click changes nothing and announces nothing");

        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.DEAL));
        assertEquals(new AutopilotOwnershipEvent(true, Set.of()), ownership().get(2),
                "the talk ended: nothing is left to own, and the set does not grow for ever");

        on.set(false);
        plan.onAutopilotSwitched(false);
        assertEquals(new AutopilotOwnershipEvent(false, Set.of()), ownership().get(3));
        assertFalse(ownership().get(3).assistantOwns("nego-v2"), "off: the Assistant negotiates nowhere");
    }

    @Test
    void thePlanStatesItsWorldsOwnershipWhenItStartsAndLetsGoOfTheBusWhenTold() {
        // A new game, a scenario and a load each spawn a new Assistant, whose plan starts here:
        // this first statement is how a loaded autopilot-ON world locks its tabs (R-07).
        AutopilotExecuteBehaviour plan = plan();

        plan.action();

        assertEquals(List.of(new AutopilotOwnershipEvent(true, Set.of())), ownership());
        List<Class<? extends Event>> heard = List.of(NegotiationOpenedEvent.class, NegotiationClosedEvent.class,
                TakeOverEvent.class, PlayerCommandDispatchedEvent.class, PlayerCommandRefusedEvent.class,
                DealClosedEvent.class);
        for (Class<? extends Event> type : heard) {
            assertEquals(1, EventBusProbe.subscriberCount(bus, type), type.getSimpleName());
        }

        plan.cancelSubscription(); // the agent's takedown: the bus outlives it
        for (Class<? extends Event> type : heard) {
            assertEquals(0, EventBusProbe.subscriberCount(bus, type), type.getSimpleName() + " after takedown");
        }
    }

    @Test
    void anEndedTalkThatWasNeverTakenOverAnnouncesNothing() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.DEAL));

        assertEquals(List.of(), ownership());
    }

    @Test
    void aTakenOverTalkIsThePlayersUntilItEnds() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        assertEquals(1, commands().size(), "fixture: the Assistant made the first move");

        plan.onTakeOver(new TakeOverEvent("nego-v1"));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6100.0, 2)));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6200.0, 3)));
        assertEquals(1, commands().size(), "the vessel's counters are the player's to answer now");

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v2", tanker("v2", 6000.0, 1)));
        assertEquals(2, commands().size(), "taking over ONE tab leaves the autopilot on everywhere else");
        assertEquals("v2", commands().get(1).targetVesselId());
    }

    @Test
    void takeOverIsHonouredForATalkThePlanHasNotHeardOf() {
        // The click is the player's; a tab that asked and got no answer would stay locked for good.
        AutopilotExecuteBehaviour plan = plan();

        plan.onTakeOver(new TakeOverEvent("nego-unknown"));

        assertEquals(List.of(new AutopilotOwnershipEvent(true, Set.of("nego-unknown"))), ownership());
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-unknown", tanker("unknown", 6000.0, 1)));
        assertEquals(List.of(), commands());
    }

    // =============================================================================================
    // Events that arrive late (the bus delivers each event type on its own, in no common order)
    // =============================================================================================

    @Test
    void anOfferThatArrivesAfterItsOwnTalkEndedIsNotAnswered() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        assertEquals(1, commands().size(), "fixture: the first offer was answered");

        // Her counter and her leaving were published back to back; the close was handled first.
        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.TIMEOUT));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6100.0, 2)));

        assertEquals(1, commands().size(), "nobody is there to answer");

        // ... and the finished talk is not back among the open ones: a later switch-on finds nothing.
        on.set(false);
        plan.onAutopilotSwitched(false);
        on.set(true);
        plan.onAutopilotSwitched(true);
        assertEquals(1, commands().size());
    }

    @Test
    void anOfferNeverHeardBeforeItsCloseIsNotAnsweredEither() {
        AutopilotExecuteBehaviour plan = plan();

        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.TIMEOUT));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        assertEquals(List.of(), commands());
    }

    @Test
    void aTakeOverClickedAsTheTalkEndedIsNotKept() {
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.DEAL));

        plan.onTakeOver(new TakeOverEvent("nego-v1")); // the window had not seen the close yet

        assertEquals(List.of(), ownership(), "a closed tab is nobody's: nothing changed, nothing is announced");
        plan.publishOwnership();
        assertEquals(Set.of(), ownership().get(0).takenOver(), "and the set does not carry it for the rest of the game");
    }

    @Test
    void aRefusalThatArrivesAfterTheVesselHasSpokenAgainDoesNotAnswerHerTwice() {
        policy("auto counter +15% if price > 0");
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        timer.fire();
        PlayerCommandEvent first = commands().get(0);
        // Her next offer arrives, and its move is waiting out its delay ...
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6100.0, 2)));
        // ... when the verdict on the FIRST move comes in late.
        plan.onCommandRefused(refused(first));
        assertEquals(1, commands().size(), "no follow-up: the new offer already has its own move coming");

        timer.fire();
        assertEquals(2, commands().size(), "one answer to the new offer, not two");
    }

    @Test
    void aStepThatThrowsIsLoggedAndDoesNotEscape() {
        // What every queued step of the plan is run under: an exception out of a JADE behaviour
        // ends the agent, and the tabs take their lock from this agent alone.
        AutopilotExecuteBehaviour.guarded(() -> {
            throw new IllegalStateException("a step that fails");
        });

        boolean[] ran = new boolean[1];
        AutopilotExecuteBehaviour.guarded(() -> ran[0] = true);
        assertTrue(ran[0], "and an ordinary step simply runs");
    }

    // =============================================================================================
    // PT12: a move can be seen coming
    // =============================================================================================

    @Test
    void aPacedMoveWaitsForItsDelayAndIsSentWhenTheTimerFires() {
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);

        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        assertEquals(List.of(), commands(), "before S17a the answer left in the same instant the offer arrived");
        assertEquals(List.of(2_000L), timer.delays, "about two seconds, real time, per move");

        timer.fire();
        assertEquals(1, commands().size());
        assertEquals(PlayerCommandKind.ACCEPT, commands().get(0).kind());
    }

    @Test
    void takeOverCancelsAMoveStillWaitingToBeSent() {
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        plan.onTakeOver(new TakeOverEvent("nego-v1"));
        timer.fire();

        assertEquals(List.of(), commands(), "the player pressed Take over in time: the move must not go out");
    }

    @Test
    void switchingOffCancelsAMoveStillWaitingToBeSent() {
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        on.set(false);
        plan.onAutopilotSwitched(false);
        timer.fire();
        assertEquals(List.of(), commands());

        // Off and on again within the delay: the old move stays cancelled, a fresh one is scheduled.
        on.set(true);
        plan.onAutopilotSwitched(true);
        assertEquals(List.of(), commands(), "still paced");
        timer.fire();
        assertEquals(1, commands().size(), "one answer, not two");
    }

    @Test
    void aNewerOfferReplacesAMoveStillWaiting() {
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6300.0, 2)));

        timer.fire(); // both timers are due

        assertEquals(1, commands().size(), "one answer, to the offer that stands");
        assertEquals(6300.0, (Double) commands().get(0).content().get("price"), 0.001);
    }

    @Test
    void aVesselThatLeftIsNotAnsweredWhenTheTimerFires() {
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));

        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.TIMEOUT));
        timer.fire();

        assertEquals(List.of(), commands());
    }

    @Test
    void aRetryAfterARefusalIsNotDelayedAgain() {
        // The player has already watched this offer wait once; the follow-up is the same turn.
        policy("auto counter +15% if price > 0");
        ManualTimer timer = new ManualTimer();
        AutopilotExecuteBehaviour plan = pacedPlan(timer);
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        timer.fire();

        plan.onCommandRefused(refused(commands().get(0)));

        assertEquals(2, commands().size());
        assertEquals(1, timer.delays.size());
    }

    // =============================================================================================
    // PT14: "Accepted at once"
    // =============================================================================================

    private static DealClosedEvent deal(String vesselId, double price) {
        return new DealClosedEvent(new Deal("deal-" + vesselId, vesselId, "berth_1", price, 6, 0L, Deal.Outcome.DEAL));
    }

    private static WalkInDialogueSnapshot cargoOffering(String vesselId, double offer, int round) {
        return new WalkInDialogueSnapshot(vesselId, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo",
                "berth_1", 6, offer, 0.0, round, 0.0, false);
    }

    @Test
    void aDealAtThePlayersFirstPriceGetsTheNote() {
        on.set(false); // the player is negotiating
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", cargoOffering("v1", 1436.0, 1)));

        plan.onDealClosed(deal("v1", 1900.0));

        List<AssistantChatEvent> notes = events(AssistantChatEvent.class);
        assertEquals(1, notes.size());
        assertEquals("nego-v1", notes.get(0).dialogueId(), "in that vessel's tab");
        assertEquals("Accepted at once — you might have asked for more.", notes.get(0).text());
        assertFalse(notes.get(0).isHint(), "an ordinary line");
    }

    @Test
    void theNoteStillComesWhenTheCloseWasHeardBeforeTheDeal() {
        // The two events are delivered on a shared pool; either may reach the plan first.
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", cargoOffering("v1", 1436.0, 1)));
        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.DEAL));

        plan.onDealClosed(deal("v1", 1900.0));

        assertEquals(List.of("Accepted at once — you might have asked for more."), chatLines());
    }

    @Test
    void noNoteWhenThePlayerSimplyAcceptedHerOffer() {
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", cargoOffering("v1", 1436.0, 1)));

        plan.onDealClosed(deal("v1", 1436.0));

        assertEquals(List.of(), chatLines());
    }

    @Test
    void noNoteOnceTheVesselHasCountered() {
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", cargoOffering("v1", 1436.0, 1)));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", cargoOffering("v1", 1600.0, 2)));

        plan.onDealClosed(deal("v1", 1750.0));

        assertEquals(List.of(), chatLines(), "she bargained: the price was not simply taken");
    }

    @Test
    void noNoteForATalkTheAssistantNegotiatedItself() {
        // A POLICY's counter: nothing about it is in the Hint cache, so only "the Assistant made
        // this move" can keep the note away (an expected-value counter would be recognised as
        // the Hint's own price as well, and prove nothing here).
        policy("auto counter +15% if price > 0");
        AutopilotExecuteBehaviour plan = plan(); // autopilot on
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v5", tanker("v5", 6000.0, 1)));
        PlayerCommandEvent counter = commands().get(0);
        assertEquals(PlayerCommandKind.PROPOSE, counter.kind(), "fixture: the autopilot countered");

        plan.onDealClosed(deal("v5", 6900.0));
        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v5", "v5", Deal.Outcome.DEAL));

        assertEquals(List.of(), chatLines(), "there is no player to tell");
    }

    @Test
    void noNoteForAPriceTheAssistantPutEvenIfThePlayerHoldsTheTabByThen() {
        // A policy's counter (so nothing about it is in the Hint cache), then Take over, then she
        // takes that counter: the price was the Assistant's, and the player asked for nothing.
        policy("auto counter +15% if price > 0");
        AutopilotExecuteBehaviour plan = plan();
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", tanker("v1", 6000.0, 1)));
        assertEquals(PlayerCommandKind.PROPOSE, commands().get(0).kind(), "fixture: the autopilot countered");
        plan.onTakeOver(new TakeOverEvent("nego-v1"));

        plan.onDealClosed(deal("v1", 6900.0));
        assertEquals(List.of(), chatLines());

        // The same when the switch was turned off instead, and the close was heard first.
        AutopilotExecuteBehaviour other = plan();
        other.onNegotiationOpened(new NegotiationOpenedEvent("nego-v2", tanker("v2", 6000.0, 1)));
        on.set(false);
        other.onAutopilotSwitched(false);
        other.onNegotiationClosed(new NegotiationClosedEvent("nego-v2", "v2", Deal.Outcome.DEAL));
        other.onDealClosed(deal("v2", 6900.0));
        assertEquals(List.of(), chatLines());
    }

    @Test
    void theNoteIsForThePlayerWhoTookOverToo() {
        AutopilotExecuteBehaviour plan = plan(); // autopilot on
        plan.onTakeOver(new TakeOverEvent("nego-v1"));
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v1", cargoOffering("v1", 1436.0, 1)));

        plan.onNegotiationClosed(new NegotiationClosedEvent("nego-v1", "v1", Deal.Outcome.DEAL));
        plan.onDealClosed(deal("v1", 1900.0));

        assertEquals(List.of("Accepted at once — you might have asked for more."), chatLines(),
                "who owned the talk is remembered from when it ended, not looked up after it was forgotten");
    }

    @Test
    void noNoteWhenThePriceWasTheHintsOwnCounter() {
        // "Use this" sends the card's whole-euro price. The Assistant does not tell the player
        // they should have asked for more than it told them to ask.
        on.set(false);
        AutopilotExecuteBehaviour plan = plan();
        WalkInDialogueSnapshot snapshot = lowTanker("v5");
        Recommendation hint = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.COUNTER, hint.action(), "fixture: the Hint says counter");
        cache.put("nego-v5-r1", hint); // what RecommendOnDemandBehaviour stores for a Hint in round 1
        plan.onNegotiationOpened(new NegotiationOpenedEvent("nego-v5", snapshot));

        plan.onDealClosed(deal("v5", Math.round(hint.price())));
        assertEquals(List.of(), chatLines());

        // ... but a price of the player's own, in a talk a Hint was asked for, still gets it.
        AutopilotExecuteBehaviour other = plan();
        other.onNegotiationOpened(new NegotiationOpenedEvent("nego-v5", snapshot));
        other.onDealClosed(deal("v5", hint.price() + 150.0));
        assertEquals(List.of("Accepted at once — you might have asked for more."), chatLines());
    }

    @Test
    void aDealNobodyToldThePlanAboutIsIgnored() {
        AutopilotExecuteBehaviour plan = plan();

        plan.onDealClosed(deal("contracted-7", 4200.0)); // a contracted vessel: no walk-in talk at all
        plan.onDealClosed(new DealClosedEvent(null));

        assertEquals(List.of(), chatLines());
    }
}
