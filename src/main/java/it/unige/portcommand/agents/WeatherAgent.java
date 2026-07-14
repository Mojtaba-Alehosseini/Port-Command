package it.unige.portcommand.agents;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.InitArgs.WeatherInitArgs;
import it.unige.portcommand.behaviours.coordination.PeriodicWeatherBroadcastBehaviour;
import it.unige.portcommand.behaviours.coordination.WeatherEvolutionBehaviour;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;

/**
 * Weather service. Holds the current {@link WeatherSnapshot} (shared with its two
 * behaviours via an {@link AtomicReference}); evolves it on a seeded Markov chain
 * every 5 sim-minutes and broadcasts it every 60 sim-seconds. The master RNG and
 * sim clock are injected via the {@code Object[]} args channel (args[1], args[2]).
 */
public final class WeatherAgent extends PortCommandAgent {

    private final AtomicReference<WeatherSnapshot> current = new AtomicReference<>();

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

        current.set(args.initial());
        Random weatherRng = randomSource.forStream("weather");
        TransitionMatrix matrix = args.matrix() != null ? args.matrix() : TransitionMatrix.defaults();
        List<WeatherInitArgs.ScriptedWeather> overrides =
                args.overrides() != null ? args.overrides() : List.of();

        addBehaviour(new WeatherEvolutionBehaviour(this, simClock, current, matrix, weatherRng, overrides));
        addBehaviour(new PeriodicWeatherBroadcastBehaviour(this, simClock, current, getServiceLocator()));
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
