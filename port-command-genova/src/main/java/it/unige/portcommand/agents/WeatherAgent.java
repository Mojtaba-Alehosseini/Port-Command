package it.unige.portcommand.agents;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.InitArgs.WeatherInitArgs;
import it.unige.portcommand.behaviours.DebugWeatherOverrideBehaviour;
import it.unige.portcommand.behaviours.coordination.PeriodicWeatherBroadcastBehaviour;
import it.unige.portcommand.behaviours.coordination.WeatherEvolutionBehaviour;
import it.unige.portcommand.scenario.ScriptedWeatherWindow;
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
 *
 * <p><b>S16 (2026-10-13, ADR-20).</b> The chain's steps are owned by a {@link WeatherStepper}
 * built here: one step on every full game hour of the sim clock (B6), suspended inside the
 * scenario's {@link ScriptedWeatherWindow} when the roster passes one (B5, found by type among
 * the args), with swell and visibility drawn from a second random stream,
 * {@code "weather-detail"} (B8). A step that takes effect is broadcast at once instead of
 * waiting for the next 15-sim-minute tick.
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
        // Task 22 restore: a persisted current snapshot (appended by the restore-aware roster)
        // replaces the init-args starting weather — evolution continues from it with fresh
        // seeded draws (the Markov stream has no persistable cursor; dated note, planning/22).
        // S16: moved up here, ahead of the behaviours. The cell must hold the reading this
        // world BOOTS with before the broadcast behaviour is built, because that behaviour
        // takes it as its alert baseline (and decides from it whether a clearing is still
        // owed). Do not build the broadcast above this block.
        WeatherSnapshot restored = optionalArgOfType(WeatherSnapshot.class);
        if (restored != null) {
            current.set(restored);
            log.info("{}: restored weather {} kn / {} / {}", getLocalName(),
                    restored.wind(), restored.visibility(), restored.state());
        }

        Random weatherRng = randomSource.forStream("weather");
        // S16 (B8): swell and visibility come from their OWN stream, so the state and wind
        // sequence of every seed stays the one the "weather" stream has always produced.
        Random detailRng = randomSource.forStream("weather-detail");
        TransitionMatrix matrix = args.matrix() != null ? args.matrix() : TransitionMatrix.defaults();
        List<WeatherInitArgs.ScriptedWeather> overrides =
                args.overrides() != null ? args.overrides() : List.of();
        // S16 (B5): present only when the scenario scripts weather (AgentRoster.weatherArgs);
        // null in free play, in the tutorial and in busy_day.
        ScriptedWeatherWindow scriptedWindow = optionalArgOfType(ScriptedWeatherWindow.class);
        if (scriptedWindow != null) {
            log.info("{}: random weather steps suspended from sim-ms {} to {} (scripted weather)",
                    getLocalName(), scriptedWindow.fromSimMillis(), scriptedWindow.untilSimMillis());
        }
        // S16 (B6): the first step is the first full game hour strictly after THIS instant. On a
        // load the clock has already been restored (and is paused) when agents are spawned, so
        // "this instant" is the save's own time and the next step is its next full hour.
        WeatherStepper stepper = new WeatherStepper(matrix, weatherRng, detailRng, scriptedWindow,
                overrides, simClock.nowSimMillis());

        addBehaviour(new WeatherEvolutionBehaviour(this, simClock, current, stepper,
                this::broadcastWeatherNow));
        broadcast = new PeriodicWeatherBroadcastBehaviour(this, simClock, current, getServiceLocator(),
                eventBus);
        addBehaviour(broadcast);
        // Task 24: Debug-menu storm trigger (checkpoint demos) — uncounted parent-pkg behaviour.
        debugOverride = new DebugWeatherOverrideBehaviour(this, current, simClock, eventBus);
        addBehaviour(debugOverride);
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
     *
     * <p>S16: also what {@code WeatherEvolutionBehaviour} runs after every weather step that
     * took effect (already on this agent's thread), for the same reason — the HarbourMaster must
     * not go on granting movements under a reading that is up to fifteen game minutes old.
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
