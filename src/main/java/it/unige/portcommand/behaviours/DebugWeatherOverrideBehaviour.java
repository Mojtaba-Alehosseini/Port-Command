package it.unige.portcommand.behaviours;

import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import it.unige.portcommand.util.Subscription;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribe-once bridge from the Debug menu's {@link DebugWeatherEvent} to the
 * WeatherAgent's shared snapshot (task 24 — the on-demand storm for checkpoint
 * demos). Lives in the UNCOUNTED parent {@code behaviours} package (catalogue
 * stays 51).
 *
 * <p>The task-10 subscribe-once pattern, with the task-19 lesson applied: JADE
 * nulls {@code myAgent} after a OneShot's {@code action()}, so the handler
 * touches ONLY the final fields captured at construction (the snapshot cell,
 * the clock, the agent's name for logging) — never {@code myAgent}.
 */
public final class DebugWeatherOverrideBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DebugWeatherOverrideBehaviour.class);

    private final AtomicReference<WeatherSnapshot> current;
    private final SimClock simClock;
    private final EventBus eventBus;
    private final String agentName;
    /** Held so the owning WeatherAgent cancels on takedown (task 22): after a save/load
     * teardown-rebuild the old handler would keep writing the DEAD agent's snapshot cell
     * and pile one extra subscriber onto the shared bus per reload. */
    private volatile Subscription<DebugWeatherEvent> subscription;

    public DebugWeatherOverrideBehaviour(Agent agent, AtomicReference<WeatherSnapshot> current,
                                         SimClock simClock, EventBus eventBus) {
        super(agent);
        this.current = current;
        this.simClock = simClock;
        this.eventBus = eventBus;
        this.agentName = agent.getLocalName();
    }

    @Override
    public void action() {
        subscription = eventBus.subscribe(DebugWeatherEvent.class, this::onOverride, DeliveryMode.CALLER_THREAD);
    }

    /** Cancels the bus subscription; safe if {@link #action()} never ran. */
    public void cancelSubscription() {
        Subscription<DebugWeatherEvent> s = subscription;
        if (s != null) {
            s.cancel();
        }
    }

    /** Public so tests drive it directly (task-10 precedent). Safe off the agent thread. */
    public void onOverride(DebugWeatherEvent event) {
        WeatherSnapshot forced = new WeatherSnapshot(event.wind(), event.visibility(), event.swell(),
                event.state(), simClock.nowSimMillis());
        current.set(forced);
        log.info("{}: DEBUG weather override -> wind={} vis={} swell={} state={}",
                agentName, forced.wind(), forced.visibility(), forced.swell(), forced.state());
    }
}
