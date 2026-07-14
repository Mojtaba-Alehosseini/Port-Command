package it.unige.portcommand.bootstrap;

/**
 * Immutable boot configuration for {@link JadeBootstrap}.
 *
 * @param mainPort              JADE Main Container port (default 1099; overridable via {@code -Djade.main.port})
 * @param enableRMA            launch the JADE RMA debug GUI (default false; {@code -Djade.gui=true})
 * @param simClockMode         descriptive label for the clock pacing mode
 * @param realSecondsPerGameDay real seconds mapped to one simulated 24&nbsp;h (default 300)
 */
public record BootstrapConfig(int mainPort, boolean enableRMA, String simClockMode, long realSecondsPerGameDay) {

    public static final int DEFAULT_PORT = 1099;
    public static final long DEFAULT_REAL_SECONDS_PER_GAME_DAY = 300L;
    public static final String DEFAULT_SIMCLOCK_MODE = "realtime";

    public BootstrapConfig {
        if (mainPort < 1 || mainPort > 65_535) {
            throw new IllegalArgumentException("mainPort out of range 1..65535: " + mainPort);
        }
        if (realSecondsPerGameDay <= 0) {
            throw new IllegalArgumentException("realSecondsPerGameDay must be > 0: " + realSecondsPerGameDay);
        }
    }

    public static BootstrapConfig defaults() {
        return new BootstrapConfig(DEFAULT_PORT, false, DEFAULT_SIMCLOCK_MODE, DEFAULT_REAL_SECONDS_PER_GAME_DAY);
    }

    /** Reads {@code jade.main.port}, {@code jade.gui}, {@code simclock.real.seconds.per.day}, else defaults. */
    public static BootstrapConfig fromSystemProperties() {
        int port = Integer.getInteger("jade.main.port", DEFAULT_PORT);
        boolean rma = Boolean.getBoolean("jade.gui");
        long rate = Long.getLong("simclock.real.seconds.per.day", DEFAULT_REAL_SECONDS_PER_GAME_DAY);
        return new BootstrapConfig(port, rma, DEFAULT_SIMCLOCK_MODE, rate);
    }
}
