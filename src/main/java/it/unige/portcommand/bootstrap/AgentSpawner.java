package it.unige.portcommand.bootstrap;

import jade.core.Agent;
import jade.wrapper.AgentController;

/**
 * Single place that turns a class + args into a running JADE agent. An interface
 * so tests can substitute a mock spawner instead of booting a container.
 */
public interface AgentSpawner {

    /**
     * Spawns and starts an agent.
     *
     * @param localName  agent local name; must match {@code ^[a-z][a-z0-9_]*$}
     * @param agentClass the {@link Agent} subclass to instantiate
     * @param args       constructor-style args delivered to the agent's {@code getArguments()} (may be null)
     * @return the controller for the started agent
     * @throws AgentSpawnException if JADE fails to create/start the agent
     */
    AgentController spawn(String localName, Class<? extends Agent> agentClass, Object[] args);
}
