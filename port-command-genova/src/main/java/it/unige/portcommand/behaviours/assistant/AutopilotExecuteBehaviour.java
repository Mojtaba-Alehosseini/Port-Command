package it.unige.portcommand.behaviours.assistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.artifacts.PolicyRule.PolicyAction;
import it.unige.portcommand.assistant.AssistantWording;
import it.unige.portcommand.assistant.AutopilotPacing;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
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
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The autopilot plan (planning/10 §10.7; rebuilt in S17a, ADR-21). While the autopilot is switched
 * on, the Assistant negotiates every walk-in dialogue the player has not taken over: for each
 * offer a vessel makes it checks the player's policies ({@link PolicyRegistryArtifact#firstMatching}),
 * falls back to {@link RecommendationAlgorithm}, and dispatches the chosen move exactly as if it
 * came from the player's own chat ({@link PlayerCommandEvent}) — so every binding move passes the
 * HarbourMaster's Prolog gate, like a typed one.
 *
 * <p><b>What S17a changed, and why</b> (tree-analysis decisions A1, A2, A9; playtest PT12, PT14):
 * <ul>
 *   <li><b>It always answers.</b> A "reject" decision used to dispatch NOTHING ("the round simply
 *       passes"): the vessel waited out its whole patience and the port lost reputation for a
 *       timeout. It is now an explicit no — a REJECT, which costs no reputation.</li>
 *   <li><b>It answers an offer that was already waiting</b> when it is switched on. It used to
 *       react to {@link NegotiationOpenedEvent} only, so a talk in which the vessel had spoken
 *       last stood still. It now remembers each open dialogue's latest snapshot, whether it is
 *       on or off.</li>
 *   <li><b>It follows up a move the gate refused.</b> Once, from the expected-value calculation
 *       (the refused move may have been a policy's); if that is refused too — or would be the
 *       same move — it says no. A refusal can no longer leave a vessel unanswered. (Since S16
 *       took the weather out of the deal gate no autopilot move is refused in play: it never
 *       names a berth and the HarbourMaster's candidate berth always fits. This is the net under
 *       that.)</li>
 *   <li><b>It says what it did after it was done</b> (A9). The chat line used to be published
 *       with the command, before the gate had judged it. Each move now carries a command id and
 *       the line is written when {@link PlayerCommandDispatchedEvent} names it.</li>
 *   <li><b>It can be watched, and stopped</b> (PT12, A2). A move is sent {@link AutopilotPacing}
 *       after it was decided, and {@link TakeOverEvent} hands a dialogue to the player for the
 *       rest of that negotiation — cancelling a move still waiting to be sent.</li>
 *   <li><b>It says who is negotiating where</b> (A2, A10): {@link AutopilotOwnershipEvent}, the
 *       whole state on every change. The chat tabs lock and unlock from it and from nothing
 *       else.</li>
 * </ul>
 *
 * <p><b>Threading.</b> All state here belongs to the Assistant's agent thread. The bus handlers
 * only enqueue onto it (the {@code DispatchPlayerCommandBehaviour} hop — and the algorithm runs
 * Prolog, which the bus forbids on its shared ASYNC pool); tests call the public {@code on…}
 * handlers directly, on one thread. With no pacing a move is sent in the step that decided it,
 * so those tests need no timer.
 *
 * <p><b>A step that throws does not take the Assistant with it</b> (adversarial review). JADE ends
 * an agent whose behaviour throws; the chat tabs lock and unlock from this plan's statements and
 * from nothing else, so a dead Assistant would leave every tab locked with nobody to answer "Take
 * over". Every step queued here - a bus event, a timer firing - is therefore run under a guard
 * that logs the failure and lets the agent live. The public handlers themselves still throw, to a
 * test that calls them directly.
 */
public final class AutopilotExecuteBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AutopilotExecuteBehaviour.class);

    /** How many finished dialogues are remembered (for the first-round note, and to drop late events). */
    private static final int RECENTLY_CLOSED_LIMIT = 32;

    /** The note for a deal the vessel took at the player's first asking price (playtest PT14). */
    public static final String ACCEPTED_AT_ONCE = "Accepted at once — you might have asked for more.";

    /** Runs {@code task} on the agent's thread after a real-time delay. */
    @FunctionalInterface
    public interface MoveTimer {
        void after(long delayMillis, Runnable task);
    }

    private final RecommendationAlgorithm algorithm;
    private final PolicyRegistryArtifact policyRegistry;
    private final EventBus eventBus;
    private final RecommendationCache cache;
    private final AtomicBoolean autopilotEnabled;
    /** Captured at construction, NEVER read from {@code myAgent} in the handlers — JADE nulls
     * {@code myAgent} once a OneShot completes (the DispatchPlayerCommandBehaviour task-19
     * lesson), while the bus subscriptions live on. */
    private final Agent agent;
    private final AutopilotPacing pacing;
    private final MoveTimer timer;

    // ---- state: the Assistant's agent thread only ----------------------------------------------

    /** Every open dialogue's latest snapshot — kept whether the autopilot is on or off. */
    private final Map<String, WalkInDialogueSnapshot> open = new LinkedHashMap<>();
    /** Dialogues the player took over; each stays the player's until it ends. */
    private final Set<String> takenOver = new LinkedHashSet<>();
    /** dialogue → the sequence number of the move waiting out its pacing delay there. */
    private final Map<String, Long> scheduled = new HashMap<>();
    /** Dialogues whose current offer the autopilot has already answered (cleared by the next offer). */
    private final Set<String> answered = new HashSet<>();
    /** Dialogues in which the Assistant has made a move of its own (until they end): a deal there
     * may be at ITS price, whoever holds the tab by the time it closes. */
    private final Set<String> movedIn = new HashSet<>();
    /** command id → the move that was published under it and is waiting for the gate's verdict. */
    private final Map<Long, SentMove> sent = new HashMap<>();
    /**
     * The last finished dialogues. Two uses: a {@link DealClosedEvent} that arrives after the
     * close still finds the offer it was about; and an offer or a take-over that arrives AFTER
     * its own dialogue's close - the bus delivers each event type on its own, with no order
     * between them - is recognised as late and dropped, instead of bringing a finished dialogue
     * back to life (a dialogue id is never used twice in one world).
     */
    private final LinkedHashMap<String, Closed> recentlyClosed = new LinkedHashMap<>();
    private long sequence;
    /** S19 review (PT38a): the tabs' clock, read at the move; {@code null} in a plan built by hand
     * (then the stored snapshot's time stands). */
    private final it.unige.portcommand.util.ConversationClock conversationClock;

    /** One decided move. {@code rec} is {@code null} for a policy's move, {@code policy} for an EV one. */
    private record Decision(String action, double price, String policy, Recommendation rec) {
        boolean sameMoveAs(Decision other) {
            return action.equals(other.action) && Double.compare(price, other.price) == 0;
        }
    }

    private record SentMove(String dialogueId, Decision decision, boolean afterRefusal, String chatLine) {
    }

    /**
     * @param snapshot       the last offer heard, or {@code null} if the plan never heard one
     * @param assistantsTalk the Assistant had made a move in it
     */
    private record Closed(WalkInDialogueSnapshot snapshot, boolean assistantsTalk) {
    }

    /** No pacing: a move is sent in the step that decided it (the form every pre-S17a caller used). */
    public AutopilotExecuteBehaviour(Agent agent, RecommendationAlgorithm algorithm,
                                     PolicyRegistryArtifact policyRegistry, EventBus eventBus,
                                     RecommendationCache cache, AtomicBoolean autopilotEnabled) {
        this(agent, algorithm, policyRegistry, eventBus, cache, autopilotEnabled, AutopilotPacing.NONE, null);
    }

    /**
     * @param pacing how long a decided move waits before it is sent
     * @param timer  what runs the wait; {@code null} for the production one, a JADE waker on
     *               {@code agent} (tests hand in a manual one and run its tasks themselves)
     */
    public AutopilotExecuteBehaviour(Agent agent, RecommendationAlgorithm algorithm,
                                     PolicyRegistryArtifact policyRegistry, EventBus eventBus,
                                     RecommendationCache cache, AtomicBoolean autopilotEnabled,
                                     AutopilotPacing pacing, MoveTimer timer) {
        this(agent, algorithm, policyRegistry, eventBus, cache, autopilotEnabled, pacing, timer, null);
    }

    /**
     * @param conversationClock S19 review (PT38a): the game's conversation clock, so a move asks
     *                          "more than 30 s left?" of the tab's clock as it reads at the move;
     *                          {@code null} and the snapshot's own time stands
     */
    public AutopilotExecuteBehaviour(Agent agent, RecommendationAlgorithm algorithm,
                                     PolicyRegistryArtifact policyRegistry, EventBus eventBus,
                                     RecommendationCache cache, AtomicBoolean autopilotEnabled,
                                     AutopilotPacing pacing, MoveTimer timer,
                                     it.unige.portcommand.util.ConversationClock conversationClock) {
        super(agent);
        this.conversationClock = conversationClock;
        this.agent = agent;
        this.algorithm = algorithm;
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus;
        this.cache = cache;
        this.autopilotEnabled = autopilotEnabled;
        this.pacing = Objects.requireNonNull(pacing, "pacing");
        this.timer = timer != null ? timer : this::wakeAfter;
    }

    /** Held so AssistantAgent cancels on takedown (task 22): the bus outlives the agent, and a
     * respawn would otherwise leave these handlers subscribed alongside the new agent's,
     * double-firing every reaction. Written on the agent thread, read by {@code onTakeDown}. */
    private final List<Subscription<? extends Event>> subscriptions =
            java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void action() {
        listen(NegotiationOpenedEvent.class, this::onNegotiationOpened);
        listen(NegotiationClosedEvent.class, this::onNegotiationClosed);
        listen(TakeOverEvent.class, this::onTakeOver);
        listen(PlayerCommandDispatchedEvent.class, this::onCommandDispatched);
        listen(PlayerCommandRefusedEvent.class, this::onCommandRefused);
        listen(DealClosedEvent.class, this::onDealClosed);
        log.debug("autopilot plan subscribed");
        // The state this world starts in — "on" after loading an autopilot-ON save. A new game, a
        // scenario and a load each spawn a new Assistant, so each new world states its own; a
        // window built AFTER the world (a --scenario or --load launch) missed this, and starts
        // from the same place this agent did: the session's autopilot setting, nothing taken over.
        publishOwnership();
    }

    /** ASYNC-pool half (S10 bug hunt, hunter C finding 5): the handlers run
     * {@link RecommendationAlgorithm} — JPL under the process-wide engine lock — which the
     * EventBus contract forbids on the shared ASYNC pool, and they share this plan's state. The
     * bus handler only enqueues onto the Assistant's own agent thread. */
    private <E extends Event> void listen(Class<E> type, java.util.function.Consumer<E> handler) {
        subscriptions.add(eventBus.subscribe(type, event -> enqueue(() -> handler.accept(event)),
                DeliveryMode.ASYNC));
    }

    private void enqueue(Runnable step) {
        agent.addBehaviour(new OneShotBehaviour(agent) {
            @Override
            public void action() {
                guarded(step);
            }
        });
    }

    /** The production {@link MoveTimer}: a JADE waker on the Assistant's own thread. */
    private void wakeAfter(long delayMillis, Runnable task) {
        agent.addBehaviour(new WakerBehaviour(agent, delayMillis) {
            @Override
            protected void onWake() {
                guarded(task);
            }
        });
    }

    /** Runs one queued step; a failure is logged and ends with the step, not with the agent (see the class comment). */
    public static void guarded(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("an Assistant step failed; the Assistant carries on", e);
        }
    }

    /** Cancels the bus subscriptions; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        synchronized (subscriptions) {
            subscriptions.forEach(Subscription::cancel);
            subscriptions.clear();
        }
    }

    // =============================================================================================
    // What the vessels and the player do
    // =============================================================================================

    /** A vessel opened a dialogue or countered: the offer now on the table is hers. */
    public void onNegotiationOpened(NegotiationOpenedEvent event) {
        String dialogueId = event.dialogueId();
        if (recentlyClosed.containsKey(dialogueId)) {
            return; // her last offer, overtaken by the news that she left: there is nobody to answer
        }
        open.put(dialogueId, event.snapshot());
        answered.remove(dialogueId); // a new offer: nobody has answered THIS one
        consider(dialogueId);
    }

    /** A dialogue ended, however it ended: everything kept about it goes. */
    public void onNegotiationClosed(NegotiationClosedEvent event) {
        String dialogueId = event.dialogueId();
        WalkInDialogueSnapshot last = open.remove(dialogueId);
        recentlyClosed.put(dialogueId, new Closed(last, assistantsTalk(dialogueId)));
        while (recentlyClosed.size() > RECENTLY_CLOSED_LIMIT) {
            recentlyClosed.remove(recentlyClosed.keySet().iterator().next());
        }
        scheduled.remove(dialogueId);
        answered.remove(dialogueId);
        movedIn.remove(dialogueId);
        sent.values().removeIf(move -> move.dialogueId().equals(dialogueId));
        if (takenOver.remove(dialogueId)) {
            publishOwnership();
        }
    }

    /**
     * The switch changed (the Settings dialog; the agent has already flipped the shared flag).
     * Switched ON, every offer that is waiting for an answer gets one — decision A1: before S17a
     * the autopilot did nothing until the vessel spoke again, and the vessel was waiting for the
     * port. Switched OFF, a move still waiting out its pacing delay is not sent.
     */
    public void onAutopilotSwitched(boolean on) {
        publishOwnership();
        if (!on) {
            scheduled.clear();
            return;
        }
        for (String dialogueId : List.copyOf(open.keySet())) {
            if (!scheduled.containsKey(dialogueId) && !answered.contains(dialogueId) && !awaitingVerdict(dialogueId)) {
                consider(dialogueId);
            }
        }
    }

    /** "Take over": the dialogue is the player's from here to its end; a pending move is dropped. */
    public void onTakeOver(TakeOverEvent event) {
        String dialogueId = event.dialogueId();
        if (recentlyClosed.containsKey(dialogueId)) {
            return; // clicked as the talk ended: a closed tab is nobody's, and the set must not keep it
        }
        // Honoured even for a dialogue this plan has not heard of: the click is the player's, and
        // a tab that asked to be taken over and got no answer would stay locked for good.
        if (!takenOver.add(dialogueId)) {
            return; // already the player's — nothing changes
        }
        scheduled.remove(dialogueId);
        log.info("{}: taken over by the player", dialogueId);
        publishOwnership();
    }

    // =============================================================================================
    // A move
    // =============================================================================================

    /** Decides and sends (or schedules) the answer to {@code dialogueId}'s standing offer, if it is ours to give. */
    private void consider(String dialogueId) {
        if (!assistantOwns(dialogueId) || !open.containsKey(dialogueId)) {
            return;
        }
        long seq = ++sequence;
        scheduled.put(dialogueId, seq);
        Runnable move = () -> {
            // Re-read everything: between the decision to move and the move, the player may have
            // taken over, the switch may be off, the vessel may have left or spoken again (a newer
            // offer has its own, newer, scheduled move).
            if (!Long.valueOf(seq).equals(scheduled.get(dialogueId))) {
                return;
            }
            scheduled.remove(dialogueId);
            WalkInDialogueSnapshot snapshot = open.get(dialogueId);
            if (snapshot == null || !assistantOwns(dialogueId)) {
                return;
            }
            send(dialogueId, snapshot, decide(dialogueId, snapshot, true), false);
        };
        if (pacing.paced()) {
            timer.after(pacing.moveDelayMillis(), move);
        } else {
            move.run();
        }
    }

    /**
     * The move for {@code snapshot}: the player's first matching policy, else the expected-value
     * calculation. {@code policiesToo} is false for the one retry after a refusal.
     */
    private Decision decide(String dialogueId, WalkInDialogueSnapshot stored, boolean policiesToo) {
        WalkInDialogueSnapshot snapshot = asItReadsNow(dialogueId, stored);
        Optional<PolicyRule> matched = policiesToo ? policyRegistry.firstMatching(snapshot) : Optional.empty();
        if (matched.isPresent()) {
            PolicyAction policyAction = matched.get().action();
            log.info("{}: policy '{}' matched -> {}", dialogueId, matched.get().trigger(), policyAction.kind());
            return new Decision(policyAction.kind(), priceFor(policyAction, snapshot), matched.get().trigger(), null);
        }
        Recommendation rec = algorithm.run(snapshot);
        cache.put(RecommendOnDemandBehaviour.decisionId(dialogueId, snapshot), rec);
        return new Decision(rec.action(), rec.price(), null, rec);
    }

    /**
     * S19 review (PT38a): the snapshot with the tab's counter as it reads NOW. The stored one's time
     * dates from the vessel's message; a move waits for its pacing, a plan switched on answers an
     * offer that has waited, and a retry follows a refusal - and PT38a's "more than 30 s left" is
     * asked at the move, as the Hint asks it at the click ({@code DialogueTabView.onHint}).
     */
    private WalkInDialogueSnapshot asItReadsNow(String dialogueId, WalkInDialogueSnapshot stored) {
        if (conversationClock == null) {
            return stored;
        }
        return conversationClock.view(dialogueId)
                .map(view -> stored.withTime(view.elapsedMillis() / 1000.0, view.totalMillis() / 1000.0))
                .orElse(stored);
    }

    private static double priceFor(PolicyAction action, WalkInDialogueSnapshot snapshot) {
        return switch (action.kind()) {
            case RecommendationCandidate.COUNTER -> snapshot.lastVesselOffer() * (1.0 + action.counterPct());
            // Mirrors RecommendationAlgorithm.scoreRejectSilent: no price is on the table.
            case RecommendationCandidate.REJECT_SILENT -> 0.0;
            default -> snapshot.lastVesselOffer(); // accept: take the vessel's own standing offer
        };
    }

    /**
     * Publishes {@code decision} as a player-equivalent command under a fresh command id, and
     * remembers it until the HarbourMaster says what became of it. A "reject" is a REJECT on the
     * wire — the explicit no; before S17a it was nothing at all.
     */
    private void send(String dialogueId, WalkInDialogueSnapshot snapshot, Decision decision, boolean afterRefusal) {
        send(dialogueId, snapshot, decision, afterRefusal,
                AssistantWording.autopilotMove(decision.action(), decision.price(), decision.policy(), decision.rec()));
    }

    private void send(String dialogueId, WalkInDialogueSnapshot snapshot, Decision decision, boolean afterRefusal,
                      String chatLine) {
        PlayerCommandKind kind = switch (decision.action()) {
            case RecommendationCandidate.ACCEPT -> PlayerCommandKind.ACCEPT;
            case RecommendationCandidate.COUNTER -> PlayerCommandKind.PROPOSE;
            case RecommendationCandidate.REJECT_SILENT -> PlayerCommandKind.REJECT;
            default -> throw new IllegalStateException("unknown autopilot action: " + decision.action());
        };
        // "conversation_id" lets HM (task 11) address the real ACL reply to the right standing
        // negotiation — dialogueId is that negotiation's FIPA conversationId. A no carries no price.
        Map<String, Object> content = kind == PlayerCommandKind.REJECT
                ? Map.of("conversation_id", dialogueId)
                : Map.of("price", decision.price(), "conversation_id", dialogueId);
        long commandId = PlayerCommandEvent.nextCommandId();
        sent.put(commandId, new SentMove(dialogueId, decision, afterRefusal, chatLine));
        answered.add(dialogueId);
        movedIn.add(dialogueId);
        eventBus.publish(new PlayerCommandEvent(kind, snapshot.vesselId(), content, commandId));
    }

    /** The HarbourMaster sent one of our moves: now — and only now — the tab is told (A9). */
    public void onCommandDispatched(PlayerCommandDispatchedEvent event) {
        SentMove move = sent.remove(event.commandId());
        if (move != null) {
            eventBus.publish(new AssistantChatEvent(move.dialogueId(), move.chatLine()));
        }
    }

    /**
     * The Prolog gate refused one of our moves (the HarbourMaster has already told the player
     * why, with the rule id). The offer is unanswered again, so: one retry from the
     * expected-value calculation; and when that is the same move, or was refused as well, an
     * explicit no. The vessel is never left waiting on a move that did not go out.
     */
    public void onCommandRefused(PlayerCommandRefusedEvent event) {
        SentMove move = sent.remove(event.commandId());
        if (move == null) {
            return; // not ours (the player's own send), or already settled
        }
        String dialogueId = move.dialogueId();
        if (RecommendationCandidate.REJECT_SILENT.equals(move.decision().action())) {
            // A no is not a binding move and the gate does not judge one; should that ever
            // change, there is nothing gentler left to say, and repeating it would never end.
            log.warn("{}: the autopilot's no was refused ({}) - nothing further is sent", dialogueId, event.reason());
            return;
        }
        if (scheduled.containsKey(dialogueId)) {
            // The vessel has spoken again since, and that offer has its own move on the way: a
            // follow-up to the old one would answer the new offer a second time.
            return;
        }
        answered.remove(dialogueId);
        WalkInDialogueSnapshot snapshot = open.get(dialogueId);
        if (snapshot == null || !assistantOwns(dialogueId)) {
            return;
        }
        Decision retry = move.afterRefusal() ? null : decide(dialogueId, snapshot, false);
        if (retry != null && !retry.sameMoveAs(move.decision())) {
            log.info("{}: {} refused ({}) -> retrying once with {}", dialogueId, move.decision().action(),
                    event.reason(), retry.action());
            send(dialogueId, snapshot, retry, true);
            return;
        }
        log.info("{}: {} refused ({}) -> saying no", dialogueId, move.decision().action(), event.reason());
        send(dialogueId, snapshot, new Decision(RecommendationCandidate.REJECT_SILENT, 0.0, null, null), true,
                AssistantWording.autopilotGaveUp());
    }

    // =============================================================================================
    // The first-round note (PT14)
    // =============================================================================================

    /**
     * A deal closed. If the vessel took the PLAYER's first demand on the spot — the dialogue
     * never went past its opening round and the price is not the one she had offered — the
     * player asked for less than she would have paid, and nothing on screen says so: a vessel
     * accepts any demand at or below what she is prepared to pay. One line says it. Not for a
     * dialogue the Assistant has made a move in (the price may be its own, even if the player has
     * taken the tab over since - and while it holds a tab only it can move there), not when the
     * player simply accepted her offer, and not when the price was the Assistant's own Hint.
     */
    public void onDealClosed(DealClosedEvent event) {
        Deal deal = event.deal();
        if (deal == null || deal.vesselId() == null) {
            return;
        }
        String dialogueId = "nego-" + deal.vesselId();
        WalkInDialogueSnapshot snapshot = open.get(dialogueId);
        boolean assistantsTalk = assistantsTalk(dialogueId);
        if (snapshot == null) {
            Closed closed = recentlyClosed.get(dialogueId);
            if (closed == null || closed.snapshot() == null) {
                return;
            }
            snapshot = closed.snapshot();
            assistantsTalk = closed.assistantsTalk();
        }
        boolean firstRound = snapshot.roundsUsed() <= 1;
        boolean herOwnPrice = Math.abs(deal.finalPrice() - snapshot.lastVesselOffer()) < 0.5;
        if (firstRound && !herOwnPrice && !assistantsTalk && !wasTheHintsCounter(dialogueId, snapshot, deal)) {
            eventBus.publish(new AssistantChatEvent(dialogueId, ACCEPTED_AT_ONCE));
        }
    }

    /**
     * Whether the price the vessel took is the counter a Hint recommended for that very offer
     * (the Hint plan and this one share the cache; "Use this" sends the card's whole-euro price).
     * The Assistant does not tell the player they should have asked for more than it told them
     * to ask.
     */
    private boolean wasTheHintsCounter(String dialogueId, WalkInDialogueSnapshot snapshot, Deal deal) {
        return cache.get(RecommendOnDemandBehaviour.decisionId(dialogueId, snapshot))
                .filter(rec -> RecommendationCandidate.COUNTER.equals(rec.action()))
                .filter(rec -> Math.round(rec.price()) == Math.round(deal.finalPrice()))
                .isPresent();
    }

    // =============================================================================================
    // Who is negotiating where
    // =============================================================================================

    private boolean assistantOwns(String dialogueId) {
        return autopilotEnabled.get() && !takenOver.contains(dialogueId);
    }

    /** Whether a deal in {@code dialogueId} is the Assistant's doing rather than the player's. */
    private boolean assistantsTalk(String dialogueId) {
        return movedIn.contains(dialogueId);
    }

    private boolean awaitingVerdict(String dialogueId) {
        return sent.values().stream().anyMatch(move -> move.dialogueId().equals(dialogueId));
    }

    /** The whole state, every time: the switch, and the dialogues the player took over. */
    public void publishOwnership() {
        eventBus.publish(new AutopilotOwnershipEvent(autopilotEnabled.get(), Set.copyOf(takenOver)));
    }
}
