package it.unige.portcommand.agents;

import java.util.concurrent.atomic.AtomicBoolean;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.assistant.AutopilotPacing;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.RecommendationCache;
import it.unige.portcommand.behaviours.assistant.AutopilotExecuteBehaviour;
import it.unige.portcommand.behaviours.assistant.ExplainEventBehaviour;
import it.unige.portcommand.behaviours.assistant.PolicyParseBehaviour;
import it.unige.portcommand.behaviours.assistant.RecommendOnDemandBehaviour;
import it.unige.portcommand.gui.events.SettingsChangedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.harbourmaster.MovementGate;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The ChatBDI-pattern assistant (one instance) — the project centerpiece. Full v1.1
 * implementation (task 10): four plans, each a {@code OneShotBehaviour} that subscribes once to
 * its {@link EventBus} event and reacts on every subsequent firing —
 * {@link RecommendOnDemandBehaviour} (Hint button), {@link AutopilotExecuteBehaviour}
 * (autopilot), {@link PolicyParseBehaviour} (policy registration), {@link ExplainEventBehaviour}
 * ("Why?"). Sends no ACL messages and makes no Swing calls of its own — every effect is an
 * {@link EventBus} event (planning/10's "No GUI calls" hard constraint).
 *
 * <p>Args: {@code [MarketHistoryArtifact, PolicyRegistryArtifact, LLMBridge, EventBus]}, then —
 * found by type, wherever they sit — the game's {@code ConversationClock} (required), an
 * optional opening {@link MovementGate.Reading} and an optional {@link AutopilotPacing}.
 *
 * <p><b>S16 (2026-10-13, decision B10): the Assistant knows the weather.</b> It hears
 * {@link WeatherChangeEvent} on the bus — the same event the top bar shows — and keeps the last
 * reading, so that a Hint can say when the weather would keep that vessel waiting outside after
 * a deal ({@code assistant.WeatherNote}). It learns it from the bus and from nowhere else: no
 * call into the weather agent or the HarbourMaster, and nothing added to the dialogue snapshot,
 * which stays the list of what the PLAYER can see about the negotiation.
 *
 * <p><b>S17a (ADR-21): the Assistant says who is negotiating where.</b> The Settings switch is
 * still the one global "autopilot on/off", and this agent still mirrors it into a flag — but a
 * flip is now also handed to the autopilot plan on this agent's thread, which answers the offers
 * that were waiting and announces the new state to the chat tabs
 * ({@code AutopilotOwnershipEvent}). The tabs used to mirror the Settings event themselves, which
 * is how a loaded autopilot-ON game came to show an unlocked tab (R-07).
 */
public final class AssistantAgent extends PortCommandAgent {

    // S9: seeded from the session's effective settings, not hardcoded false — a restored
    // (or scenario-merged) autopilotEnabled now takes effect at agent boot; the task-21
    // SettingsChangedEvent subscription below still flips it live afterwards.
    private final AtomicBoolean autopilotEnabled =
            new AtomicBoolean(it.unige.portcommand.core.Settings.load().autopilotEnabled());

    /** S9, test seam: the construction-time autopilot value — SettingsRoundTripIT reads it off
     * the RESPAWNED agent to prove the saved settings were applied BEFORE agents booted. */
    public boolean autopilotOn() {
        return autopilotEnabled.get();
    }

    /** S17a, test seam: the pacing this agent was spawned with (none = no wait). A session IT reads
     * it off the spawned and the RESPAWNED agent to prove the boot configuration reached both. */
    private volatile AutopilotPacing autopilotPacing = AutopilotPacing.NONE;

    public AutopilotPacing autopilotPacing() {
        return autopilotPacing;
    }

    private RecommendOnDemandBehaviour recommendPlan;
    private volatile AutopilotExecuteBehaviour autopilotPlan;
    private PolicyParseBehaviour policyPlan;
    private ExplainEventBehaviour explainPlan;
    /** Task 21: the Settings screen's live autopilot toggle, delivered over the bus (never a
     * direct GUI→agent call — INVARIANTS: GUI ↔ agents only through EventBus). */
    private Subscription<SettingsChangedEvent> settingsSubscription;

    /**
     * S16 (B10): the last weather reading heard, {@code null} until the first. Volatile: written
     * by whichever thread publishes the event (the weather agent's, the HarbourMaster's for a
     * scripted change, the loader's at boot), read on this agent's thread when a Hint runs. A new
     * world gets a new Assistant, so a reading never outlives the world it described.
     */
    private volatile MovementGate.Reading lastWeather;
    private Subscription<WeatherChangeEvent> weatherSubscription;

    /** S16, test seam: the weather the next Hint would be checked against; {@code null} if none yet. */
    public MovementGate.Reading lastWeather() {
        return lastWeather;
    }

    @Override
    protected void registerServices() {
        registerDfService("assistant", getLocalName());
    }

    @Override
    protected void onSetup() {
        MarketHistoryArtifact marketHistory = argAt(0, MarketHistoryArtifact.class);
        PolicyRegistryArtifact policyRegistry = argAt(1, PolicyRegistryArtifact.class);
        LLMBridge llmBridge = argAt(2, LLMBridge.class);
        EventBus eventBus = argAt(3, EventBus.class);

        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(marketHistory);
        RecommendationCache cache = new RecommendationCache();

        // S14 (ADR-18): the game's conversation clock — found by type (its position after the
        // nullable AgentDirectory does not matter) and REQUIRED. A private stand-in clock would
        // make the Hint freeze silently inert: nothing would fail, the vessel's counter would
        // just keep running while the model thinks (adversarial review, S14).
        it.unige.portcommand.util.ConversationClock conversationClock =
                optionalArgOfType(it.unige.portcommand.util.ConversationClock.class);
        if (conversationClock == null) {
            throw new IllegalStateException("AssistantAgent " + getLocalName()
                    + " requires the game's ConversationClock among its args (SimClock.conversationClock())");
        }
        // S16 (B10): the weather, for the Hint's note. Subscribed BEFORE the plans exist, so no
        // reading published from here on can be missed. CALLER_THREAD like the settings toggle
        // below: the handler only stores four values in a volatile field, so it needs no hop to
        // this agent's thread, and it must not wait for one — a Hint pressed in the same instant
        // should already see the reading. An opening reading among the spawn args (found by
        // type, absent in every harness that does not care) covers the one case the bus cannot:
        // GameSession announces the boot weather once the HarbourMaster is up, which may be
        // before this agent has subscribed — and the next broadcast is fifteen game minutes off.
        lastWeather = optionalArgOfType(MovementGate.Reading.class);
        weatherSubscription = eventBus.subscribe(WeatherChangeEvent.class,
                e -> lastWeather = new MovementGate.Reading(e.windKnots(), e.visibility(), e.swell(), e.state()),
                DeliveryMode.CALLER_THREAD);

        recommendPlan = new RecommendOnDemandBehaviour(this, algorithm, llmBridge, eventBus, cache,
                conversationClock, () -> lastWeather);
        // S17a (PT12): how long a decided move waits before it is sent. Found by type; absent in
        // every harness that builds the agent by hand, and then there is no wait at all.
        AutopilotPacing pacing = optionalArgOfType(AutopilotPacing.class);
        if (pacing != null) {
            autopilotPacing = pacing;
        }
        autopilotPlan = new AutopilotExecuteBehaviour(this, algorithm, policyRegistry, eventBus, cache,
                autopilotEnabled, autopilotPacing, null, conversationClock); // S19 review: the clock at the move
        policyPlan = new PolicyParseBehaviour(this, policyRegistry, eventBus);
        explainPlan = new ExplainEventBehaviour(this, cache, llmBridge, eventBus);
        addBehaviour(recommendPlan);
        addBehaviour(autopilotPlan);
        addBehaviour(policyPlan);
        addBehaviour(explainPlan);

        // Task 21: the Settings screen flips Channel-B autopilot live. CALLER_THREAD delivery
        // runs the toggle synchronously on the publisher's thread (the EDT) — the target is a
        // thread-safe AtomicBoolean AutopilotExecuteBehaviour re-reads, so no JADE-thread hop is
        // needed and no Swing is touched. Subscribed here (not in a behaviour) because the handler
        // needs no myAgent — sidestepping the OneShot-nulls-myAgent trap the four plans work
        // around; cancelled in onTakeDown so an Assistant respawn (load teardown/rebuild) does not
        // leave a dead lambda writing a garbage-collected agent's flag.
        //
        // S17a (A1, A10): the flag is still flipped right here — a move waiting out its pacing
        // delay must see "off" at once — and a CHANGE is then handed to the autopilot plan on this
        // agent's own thread, where its state lives: switched on, it answers every offer that was
        // already waiting; either way it announces who is negotiating now. An Apply that leaves
        // the switch where it was (a speed change, say) hands over nothing.
        settingsSubscription = eventBus.subscribe(SettingsChangedEvent.class,
                e -> switchAutopilot(e.autopilotEnabled()), DeliveryMode.CALLER_THREAD);
    }

    /** Flips the shared flag and, if that changed anything, tells the autopilot plan on this agent's thread. */
    private void switchAutopilot(boolean enabled) {
        if (autopilotEnabled.getAndSet(enabled) == enabled) {
            return;
        }
        AutopilotExecuteBehaviour plan = autopilotPlan;
        if (plan == null) {
            return;
        }
        addBehaviour(new jade.core.behaviours.OneShotBehaviour(this) {
            @Override
            public void action() {
                // The flag as it is NOW, not as it was when this was queued: two quick flips must
                // end in the state the player left the switch in. Guarded like every step of the
                // plan: an exception here must not end the agent the tabs take their lock from.
                AutopilotExecuteBehaviour.guarded(() -> plan.onAutopilotSwitched(autopilotEnabled.get()));
            }
        });
    }

    /**
     * Unsubscribes the four subscribe-once plans (task 22). Load-bearing, same rationale as
     * {@code HarbourMasterAgent.onTakeDown}: the bus outlives the agent, so an Assistant
     * respawn (save/load teardown-rebuild) would otherwise double-fire every Hint reply,
     * autopilot action, policy registration and explanation.
     */
    @Override
    protected void onTakeDown() {
        if (recommendPlan != null) {
            recommendPlan.cancelSubscription();
        }
        if (autopilotPlan != null) {
            autopilotPlan.cancelSubscription();
        }
        if (policyPlan != null) {
            policyPlan.cancelSubscription();
        }
        if (explainPlan != null) {
            explainPlan.cancelSubscription();
        }
        if (settingsSubscription != null) {
            settingsSubscription.cancel();
        }
        // S16: same reason as the settings subscription — the bus outlives this agent, and a
        // dead lambda would go on writing a torn-down world's weather field.
        if (weatherSubscription != null) {
            weatherSubscription.cancel();
        }
    }

    /**
     * Test/ops seam: flips the autopilot toggle the way the Settings screen's event does
     * (S17a: including the hand-over to the autopilot plan).
     */
    public void setAutopilotEnabled(boolean enabled) {
        switchAutopilot(enabled);
    }

    private <T> T argAt(int index, Class<T> type) {
        Object[] a = getArguments();
        if (a == null || a.length <= index || a[index] == null) {
            throw new IllegalStateException("AssistantAgent " + getLocalName() + " requires "
                    + type.getSimpleName() + " at args[" + index + "]");
        }
        return type.cast(a[index]);
    }
}
