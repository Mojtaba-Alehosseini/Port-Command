package it.unige.portcommand.bootstrap;

import it.unige.portcommand.assistant.AutopilotPacing;

/**
 * Immutable boot configuration for {@link JadeBootstrap}.
 *
 * @param mainPort              JADE Main Container port (default 1099; overridable via {@code -Djade.main.port})
 * @param enableRMA            launch the JADE RMA debug GUI (default false; {@code -Djade.gui=true})
 * @param simClockMode         descriptive label for the clock pacing mode
 * @param realSecondsPerGameDay real seconds mapped to one simulated 24&nbsp;h at ×1 speed (default 3600
 *                              since S14 — a 60-minute sandbox day; scenarios pin their own)
 * @param autopilotMoveDelayMillis S17a (ADR-21, PT12): real milliseconds the autopilot waits between
 *                              deciding a move and sending it, so a negotiation can be watched and
 *                              taken over. A LAUNCHED game gets about two seconds
 *                              ({@link #defaults()}, {@link #fromSystemProperties()}); the four-argument
 *                              constructor — every test harness and the smoke run — gets none
 */
public record BootstrapConfig(int mainPort, boolean enableRMA, String simClockMode, long realSecondsPerGameDay,
                              long autopilotMoveDelayMillis) {

    public static final int DEFAULT_PORT = 1099;
    public static final long DEFAULT_REAL_SECONDS_PER_GAME_DAY = 3_600L;
    public static final String DEFAULT_SIMCLOCK_MODE = "realtime";

    public BootstrapConfig {
        if (mainPort < 1 || mainPort > 65_535) {
            throw new IllegalArgumentException("mainPort out of range 1..65535: " + mainPort);
        }
        if (realSecondsPerGameDay <= 0) {
            throw new IllegalArgumentException("realSecondsPerGameDay must be > 0: " + realSecondsPerGameDay);
        }
        if (autopilotMoveDelayMillis < 0) {
            throw new IllegalArgumentException("autopilotMoveDelayMillis must be >= 0: " + autopilotMoveDelayMillis);
        }
    }

    /**
     * The pre-S17a form, and the one every test and the smoke run builds: NO autopilot pacing, so
     * an autopilot move is sent in the step that decided it and nothing waits on a timer.
     */
    public BootstrapConfig(int mainPort, boolean enableRMA, String simClockMode, long realSecondsPerGameDay) {
        this(mainPort, enableRMA, simClockMode, realSecondsPerGameDay, 0L);
    }

    public static BootstrapConfig defaults() {
        return new BootstrapConfig(DEFAULT_PORT, false, DEFAULT_SIMCLOCK_MODE, DEFAULT_REAL_SECONDS_PER_GAME_DAY,
                AutopilotPacing.GAME_MOVE_DELAY_MILLIS);
    }

    /**
     * Reads {@code jade.main.port}, {@code jade.gui}, {@code simclock.real.seconds.per.day} and
     * {@code autopilot.move.delay.ms}, else defaults.
     */
    public static BootstrapConfig fromSystemProperties() {
        int port = Integer.getInteger("jade.main.port", DEFAULT_PORT);
        boolean rma = Boolean.getBoolean("jade.gui");
        long rate = Long.getLong("simclock.real.seconds.per.day", DEFAULT_REAL_SECONDS_PER_GAME_DAY);
        long autopilotDelay = Long.getLong("autopilot.move.delay.ms", AutopilotPacing.GAME_MOVE_DELAY_MILLIS);
        return new BootstrapConfig(port, rma, DEFAULT_SIMCLOCK_MODE, rate, autopilotDelay);
    }

    /** The autopilot pacing this boot was asked for, in the form the Assistant takes it. */
    public AutopilotPacing autopilotPacing() {
        return new AutopilotPacing(autopilotMoveDelayMillis);
    }
}
