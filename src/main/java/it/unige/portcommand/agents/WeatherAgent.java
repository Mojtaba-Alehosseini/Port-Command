package it.unige.portcommand.agents;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.InitArgs.WeatherInitArgs;
import it.unige.portcommand.behaviours.DebugWeatherOverrideBehaviour;
import it.unige.portcommand.behaviours.coordination.PeriodicWeatherBroadcastBehaviour;
import it.unige.portcommand.behaviours.coordination.WeatherEvolutionBehaviour;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;

/**
 * Weather service. Holds the current {@link WeatherSnapshot} (shared with its two
 * behaviours via an {@link AtomicReference}); evolves it on a seeded Markov chain
 * every 60 sim-minutes and broadcasts it every 15 sim-minutes (task-19 cadence fix —
 * see the two behaviours' javadocs). The master RNG, sim clock and shared bus are
 * injected via the {@code Object[]} args channel (args[1], args[2], args[3] — the bus
 * added by task 24 for the {@code WeatherChangeEvent}/{@code NotificationEvent} GUI
 * publishes).
 */
public final class WeatherAgent extends PortCommandAgent
        implements it.unige.portcommand.persistence.Mementoable {

    private final AtomicReference<WeatherSnapshot> current = new AtomicReference<>();
    private DebugWeatherOverrideBehaviour debugOverride;
    /** Held so a scripted weather change can force an immediate broadcast (task 26). */
    private PeriodicWeatherBroadcastBehaviour broadcast;

    @Override
    protected void registerServices() {
        registerDfService("weather", getLocalName());
    }

    @Override
    protected void onSetup() {
        WeatherInitArgs args = initArg(WeatherInitArgs.class);
        if (args == null) {
            throw new IllegalStateException(
                    "WeatherAgent " + getLocalName() + " requires WeatherInitArgs at args[0]");
        }
        RandomSource randomSource = argAt(1, RandomSource.class);
        SimClock simClock = argAt(2, SimClock.class);
        EventBus eventBus = argAt(3, EventBus.class);

        current.set(args.initial());
        Random weatherRng = randomSource.forStream("weather");
        TransitionMatrix matrix = args.matrix() != null ? args.matrix() : TransitionMatrix.defaults();
        List<WeatherInitArgs.ScriptedWeather> overrides =
                args.overrides() != null ? args.overrides() : List.of();

        addBehaviour(new WeatherEvolutionBehaviour(this, simClock, current, matrix, weatherRng, overrides));
        broadcast = new PeriodicWeatherBroadcastBehaviour(this, simClock, current, getServiceLocator(),
                eventBus);
        addBehaviour(broadcast);
        // Task 24: Debug-menu storm trigger (checkpoint demos) — uncounted parent-pkg behaviour.
        debugOverride = new DebugWeatherOverrideBehaviour(this, current, simClock, eventBus);
        addBehaviour(debugOverride);

        // Task 22 restore: a persisted current snapshot (appended by the restore-aware roster)
        // replaces the init-args starting weather — evolution continues from it with fresh
        // seeded draws (the Markov stream has no persistable cursor; dated note, planning/22).
        WeatherSnapshot restored = optionalArgOfType(WeatherSnapshot.class);
        if (restored != null) {
            current.set(restored);
            log.info("{}: restored weather {} kn / {} / {}", getLocalName(),
                    restored.wind(), restored.visibility(), restored.state());
        }
    }

    /**
     * Broadcasts the current weather immediately instead of waiting for the next 15-sim-minute
     * tick (task 26). Called by {@code WeatherChangeScriptedEvent} through a {@code
     * OneShotBehaviour} added to this agent, so the work runs on THIS agent's own thread — the
     * scripted event itself executes on the HarbourMaster's.
     *
     * <p>Without it a scripted storm's alert arrived up to 15 sim-minutes late, while a vessel's
     * channel-to-berth transit lasts 2, so the storm scenario's CANCEL cascade never fired in a
     * real playthrough. See {@link PeriodicWeatherBroadcastBehaviour#broadcastNow()}.
     */
    public void broadcastWeatherNow() {
        if (broadcast != null) {
            broadcast.broadcastNow();
        }
    }

    /** Unsubscribes the debug-override plan (task 22) — same rationale as the other takedowns. */
    @Override
    protected void onTakeDown() {
        if (debugOverride != null) {
            debugOverride.cancelSubscription();
        }
    }

    public it.unige.portcommand.persistence.dto.WeatherStateDTO snapshotDto() {
        return new it.unige.portcommand.persistence.dto.WeatherStateDTO(current.get());
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode snapshot() {
        return persistenceMapper().valueToTree(snapshotDto());
    }

    @Override
    public void restore(com.fasterxml.jackson.databind.JsonNode node) {
        current.set(persistenceMapper()
                .convertValue(node, it.unige.portcommand.persistence.dto.WeatherStateDTO.class).current());
    }

    private <T> T argAt(int index, Class<T> type) {
        Object[] a = getArguments();
        if (a == null || a.length <= index || a[index] == null) {
            throw new IllegalStateException("WeatherAgent " + getLocalName() + " requires "
                    + type.getSimpleName() + " at args[" + index + "]");
        }
        return type.cast(a[index]);
    }

    WeatherSnapshot current() {
        return current.get();
    }
}
