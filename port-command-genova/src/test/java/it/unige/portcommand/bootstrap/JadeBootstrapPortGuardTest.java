package it.unige.portcommand.bootstrap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the fail-fast behaviour: when the JADE main port is already held by a
 * stale listener, {@link JadeBootstrap#start} must throw a clear error promptly —
 * well under the 5&nbsp;s readiness timeout — instead of blocking forever inside
 * {@code createMainContainer()} (the cause of the task-05 ~1h25m build hang).
 *
 * <p>Pure-JVM (no real container boots): {@code start()} throws before reaching
 * JADE. The probe socket disables {@code SO_REUSEADDR} so the held port is
 * detected on every platform (on Windows, REUSEADDR would let the bind succeed).
 */
class JadeBootstrapPortGuardTest {

    @Test
    void startFailsFastWhenMainPortIsAlreadyHeld() throws Exception {
        // Ensure the cold-boot probe runs regardless of JVM state (the probe is
        // skipped once a Main Container has booted in this JVM).
        JadeBootstrap.resetBootLatchForTest();
        try (ServerSocket holder = new ServerSocket()) {
            holder.setReuseAddress(false);
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)); // OS-assigned free port
            int heldPort = holder.getLocalPort();

            JadeBootstrap boot = new JadeBootstrap();
            BootstrapConfig cfg = new BootstrapConfig(heldPort, false, "realtime", 300);

            IllegalStateException ex = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(IllegalStateException.class, () -> boot.start(cfg)),
                    "start() must fail fast on a held port, not block in createMainContainer()");

            assertTrue(ex.getMessage().contains("already in use"),
                    "message should name the port conflict, got: " + ex.getMessage());
            assertFalse(boot.isStarted(), "bootstrap must not be marked started after a failed boot");
        }
    }
}
