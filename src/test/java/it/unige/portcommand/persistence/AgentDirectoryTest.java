package it.unige.portcommand.persistence;

import java.util.List;

import jade.core.Agent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 22: the live-agent registry the snapshot walks. Unit-level via the explicit-name
 * register/unregister forms — {@code Agent.getLocalName()} is final (javap-verified) and
 * container-bound, so unbound test agents carry their names through the string variant.
 */
class AgentDirectoryTest {

    private static class PlainAgent extends Agent {
    }

    private static final class SpecialAgent extends PlainAgent {
    }

    @Test
    void registerLookupUnregisterRoundTrip() {
        AgentDirectory directory = new AgentDirectory();
        PlainAgent tugOne = new PlainAgent();
        directory.register("tug_2", new PlainAgent());
        directory.register("tug_1", tugOne);
        directory.register("harbour_master", new SpecialAgent());

        assertEquals(3, directory.size());
        assertSame(tugOne, directory.byName("tug_1").orElseThrow());
        assertEquals(3, directory.byType(PlainAgent.class).size(), "byType matches subclasses");
        assertEquals(1, directory.byType(SpecialAgent.class).size());

        directory.unregister("tug_1", tugOne);
        assertEquals(2, directory.size());
        assertTrue(directory.byName("tug_1").isEmpty());
    }

    @Test
    void byTypeIsSortedByLocalName_theDeterminismRule() {
        AgentDirectory directory = new AgentDirectory();
        PlainAgent three = new PlainAgent();
        PlainAgent one = new PlainAgent();
        PlainAgent two = new PlainAgent();
        directory.register("tug_3", three);
        directory.register("tug_1", one);
        directory.register("tug_2", two);
        assertEquals(List.of(one, two, three), directory.byType(PlainAgent.class));
    }

    @Test
    void unregisterRemovesOnlyTheExactInstance_respawnRaceSafety() {
        AgentDirectory directory = new AgentDirectory();
        PlainAgent oldWorld = new PlainAgent();
        PlainAgent newWorld = new PlainAgent();
        directory.register("harbour_master", oldWorld);
        directory.register("harbour_master", newWorld); // respawn re-registered first
        directory.unregister("harbour_master", oldWorld); // late takedown must NOT evict it
        assertSame(newWorld, directory.byName("harbour_master").orElseThrow());
    }
}
