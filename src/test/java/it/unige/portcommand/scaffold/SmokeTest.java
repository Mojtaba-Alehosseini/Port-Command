package it.unige.portcommand.scaffold;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke test: proves JADE 4.6 + SWI-Prolog 10 / JPL 7 + Java 21 cooperate.
 *
 * <p>Runs the JPL native-binding probe first (in isolation), then boots a JADE
 * main container, spawns {@link SmokeAgent}, and shuts down cleanly.
 *
 * <p>This method never calls {@code System.exit} — that belongs to
 * {@link it.unige.portcommand.Main} so the JUnit runner survives. The container
 * lifecycle is wrapped in try/finally so the container is always killed.
 */
public final class SmokeTest {

    private static final Logger log = LoggerFactory.getLogger(SmokeTest.class);

    private SmokeTest() {
    }

    public static void run() throws Exception {
        log.info("=== Smoke test start ===");
        try {
            // 1. Prove the JPL native bind in isolation (fail fast, before JADE).
            JplProbe.check();

            // 2. Boot a JADE main container and a trivial DF agent.
            Runtime rt = Runtime.instance();
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, "localhost");
            profile.setParameter(Profile.MAIN_PORT, "1099");
            profile.setParameter(Profile.GUI, "false");
            AgentContainer container = rt.createMainContainer(profile);
            try {
                AgentController smoke =
                        container.createNewAgent("smoke", SmokeAgent.class.getName(), null);
                smoke.start();
                // Allow setup() (log + DF register) and the self-delete to run.
                Thread.sleep(2000);
            } finally {
                try {
                    container.kill();
                } catch (Exception killEx) {
                    log.warn("Container shutdown raised an exception", killEx);
                }
            }

            log.info("=== Smoke test pass ===");
        } catch (Exception e) {
            log.error("=== Smoke test FAILED ===", e);
            throw e;
        }
    }
}
