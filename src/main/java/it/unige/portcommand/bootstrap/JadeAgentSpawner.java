package it.unige.portcommand.bootstrap;

import java.util.Objects;
import java.util.regex.Pattern;

import jade.core.Agent;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class that calls {@code container.createNewAgent(...)} — every other
 * package spawns through the {@link AgentSpawner} interface (SOLID: single
 * responsibility, testable seam).
 */
public final class JadeAgentSpawner implements AgentSpawner {

    private static final Logger log = LoggerFactory.getLogger(JadeAgentSpawner.class);
    private static final Pattern LOCAL_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final AgentContainer container;

    public JadeAgentSpawner(AgentContainer container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    @Override
    public AgentController spawn(String localName, Class<? extends Agent> agentClass, Object[] args) {
        Objects.requireNonNull(agentClass, "agentClass");
        if (localName == null || !LOCAL_NAME.matcher(localName).matches()) {
            throw new IllegalArgumentException("localName must match ^[a-z][a-z0-9_]*$, got " + localName);
        }
        try {
            AgentController controller = container.createNewAgent(localName, agentClass.getName(), args);
            controller.start();
            log.info("Agent {} spawned (class={})", localName, agentClass.getSimpleName());
            return controller;
        } catch (StaleProxyException e) {
            throw new AgentSpawnException("failed to spawn agent " + localName, e);
        }
    }
}
