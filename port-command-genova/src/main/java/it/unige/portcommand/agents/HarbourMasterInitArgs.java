package it.unige.portcommand.agents;

/**
 * HarbourMaster startup config: opening wallet balance and reputation, and (S16) the weather the
 * world opens on and whether it has a customs office. Read at {@code args[0]} like the other
 * agents' init-args records, but deliberately NOT part of the sealed {@link InitArgs} hierarchy
 * — it is always constructed directly in Java (by {@code AgentRoster} or a test), never
 * (de)serialised from scenario JSON.
 *
 * <p><b>S16 (2026-10-13, ADR-20).</b> {@code openingWeather} seeds the movement gate's reading at
 * boot, so the gate is never open merely because no weather message has arrived yet (decision
 * B7): {@code AgentRoster} passes the default weather on a fresh game and the SAVED snapshot on
 * every load and scenario start. {@code customsOffice} says whether a customs agent exists to
 * clear arriving ships; when it does, no ship proceeds to its berth before customs has answered
 * (decision C5).
 *
 * @param openingWeather the reading the world starts under; {@code null} leaves the gate closed
 *                       until the first weather message
 * @param customsOffice  whether arriving ships must be cleared by a customs agent
 */
public record HarbourMasterInitArgs(double startingWallet, double startingReputation,
                                    WeatherSnapshot openingWeather, boolean customsOffice) {

    /**
     * The HarbourMaster-only TEST HARNESS form: calm opening weather and no customs office — a
     * hub booted with probes instead of a fleet has neither a weather agent to send it a first
     * reading nor a customs agent to answer a clearance request. Production boots never use
     * this; they state both ({@code AgentRoster}).
     */
    public HarbourMasterInitArgs(double startingWallet, double startingReputation) {
        this(startingWallet, startingReputation, new WeatherSnapshot(12, "good", 0.5, "sunny", 0L), false);
    }
}
