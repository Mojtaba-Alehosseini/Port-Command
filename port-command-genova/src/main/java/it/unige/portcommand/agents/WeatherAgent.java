package it.unige.portcommand.agents;

import it.unige.portcommand.behaviours.coordination.PeriodicWeatherBroadcastBehaviour;

/**
 * Weather service (one instance). Stub: registers DF {@code weather} and attaches
 * the periodic broadcast (a plain 1 s Ticker stub; task 09 swaps it to a
 * SimTicker on the real 5-sim-minute cadence).
 */
public final class WeatherAgent extends PortCommandAgent {

    @Override
    protected void registerServices() {
        registerDfService("weather", getLocalName());
    }

    @Override
    protected void onSetup() {
        addBehaviour(new PeriodicWeatherBroadcastBehaviour(this));
    }
}
