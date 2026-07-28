package it.unige.portcommand.harbourmaster.financial;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 22: {@link PerformativeCounter#restore} seeds today's tally by canonical name. */
class PerformativeCounterRestoreTest {

    @Test
    void restoreSeedsCanonicalCountsAndIgnoresUnknownNames() {
        PerformativeCounter counter = new PerformativeCounter(new EventBus());
        Map<String, Integer> persisted = new LinkedHashMap<>();
        persisted.put("REQUEST", 5);
        persisted.put("ACCEPT-PROPOSAL", 2); // the real FIPA spelling, hyphenated
        persisted.put("NOT-A-PERFORMATIVE", 99);
        counter.restore(persisted);

        Map<String, Integer> snapshot = counter.snapshot();
        assertEquals(5, snapshot.get("REQUEST"));
        assertEquals(2, snapshot.get("ACCEPT-PROPOSAL"));
        assertEquals(0, snapshot.get("INFORM"), "unmentioned canonicals render as zero");
        assertEquals(10, snapshot.size(), "always exactly the 10 canonical rows");
        counter.close();
    }

    @Test
    void countingContinuesOnTopOfTheRestoredTally() {
        EventBus bus = new EventBus();
        PerformativeCounter counter = new PerformativeCounter(bus);
        counter.restore(Map.of("INFORM", 7));
        counter.onCommLog(new it.unige.portcommand.gui.events.CommLogEvent(
                0L, "harbour_master", java.util.List.of("tug_1"),
                jade.lang.acl.ACLMessage.INFORM, "x", "c1"));
        assertEquals(8, counter.snapshot().get("INFORM"));
        counter.close();
    }
}
