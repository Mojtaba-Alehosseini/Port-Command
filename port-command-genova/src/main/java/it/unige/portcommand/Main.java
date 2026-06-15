package it.unige.portcommand;

import it.unige.portcommand.scaffold.SmokeTest;

/**
 * Application entry point.
 *
 * <p>For task 01 this only runs the smoke test; later tasks rewrite it to boot
 * the game. It calls {@code System.exit(0)} after the smoke test because JADE
 * leaves non-daemon threads alive that would otherwise keep the JVM running.
 * The exit lives here and never inside {@link SmokeTest#run()} so the JUnit
 * runner is not terminated by the integration test.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        SmokeTest.run();
        System.exit(0);
    }
}
