package it.unige.portcommand.harbourmaster.financial;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.util.EventBus;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformativeCounterTest {

    private EventBus bus;
    private PerformativeCounter counter;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        counter = new PerformativeCounter(bus);
    }

    private static CommLogEvent msg(int performative) {
        return new CommLogEvent(0L, "hm", List.of("v1"), performative, "paraphrase", "conv-1");
    }

    /**
     * The display names are JADE's own {@code ACLMessage.getPerformative(int)} output — the real
     * FIPA spelling, which HYPHENATES ({@code ACCEPT-PROPOSAL}). planning/20 and the demo
     * transcript both write {@code ACCEPT_PROPOSAL}; that is the Java constant's identifier, not
     * the performative's name. Pinned against the jar's actual behaviour, not the doc's spelling.
     */
    @Test
    void reportsAllTenCanonicalPerformativesInCanonicalOrder() {
        assertEquals(
                List.of("REQUEST", "INFORM", "PROPOSE", "ACCEPT-PROPOSAL", "REJECT-PROPOSAL",
                        "REFUSE", "CFP", "CANCEL", "CONFIRM", "DISCONFIRM"),
                List.copyOf(counter.snapshot().keySet()));
        assertEquals(PerformativeCounter.canonicalNames(), List.copyOf(counter.snapshot().keySet()),
                "canonicalNames() is the single source consumers must read");
    }

    /**
     * The counter's canonical set must be exactly {@code commlog.PerformativeColours}' — the two
     * lists are duplicated on purpose (that class imports {@code java.awt.Color} and nothing under
     * {@code harbourmaster/} may), so nothing but this test stops them drifting apart.
     */
    @Test
    void canonicalSetAgreesWithPerformativeColours() {
        List<Integer> coloured = List.of(ACLMessage.REQUEST, ACLMessage.INFORM, ACLMessage.PROPOSE,
                ACLMessage.ACCEPT_PROPOSAL, ACLMessage.REJECT_PROPOSAL, ACLMessage.REFUSE,
                ACLMessage.CFP, ACLMessage.CANCEL, ACLMessage.CONFIRM, ACLMessage.DISCONFIRM);

        for (int performative : coloured) {
            assertTrue(counter.snapshot().containsKey(ACLMessage.getPerformative(performative)),
                    "PerformativeColours colours " + ACLMessage.getPerformative(performative)
                            + " but the EOD report does not count it");
        }
        assertEquals(coloured.size(), counter.snapshot().size());
    }

    /**
     * A calm day exercises 8 of 10; the zeroes are the point. {@code CANCEL: 0} says "this scenario
     * did not cancel", not "we forgot to count CANCEL" — the demo transcript footnote's whole
     * argument.
     */
    @Test
    void aQuietDayStillReportsEveryPerformativeAsZero() {
        Map<String, Integer> snapshot = counter.snapshot();

        assertEquals(10, snapshot.size());
        assertTrue(snapshot.values().stream().allMatch(c -> c == 0));
    }

    @Test
    void countsEachPerformativeIndependently() {
        bus.publish(msg(ACLMessage.REQUEST));
        bus.publish(msg(ACLMessage.PROPOSE));
        bus.publish(msg(ACLMessage.PROPOSE));
        bus.publish(msg(ACLMessage.CFP));

        Map<String, Integer> snapshot = counter.snapshot();
        assertEquals(1, snapshot.get("REQUEST"));
        assertEquals(2, snapshot.get("PROPOSE"));
        assertEquals(1, snapshot.get("CFP"));
        assertEquals(0, snapshot.get("INFORM"));
        assertEquals(4, snapshot.values().stream().mapToInt(Integer::intValue).sum());
    }

    /**
     * {@code QUERY_REF} really is sent (the player's ASK command). It must not throw, must not
     * appear in the FIPA-10 report, and must not inflate the total.
     */
    @Test
    void aNonCanonicalPerformativeIsNotReported() {
        bus.publish(msg(ACLMessage.QUERY_REF));

        Map<String, Integer> snapshot = counter.snapshot();
        assertEquals(10, snapshot.size());
        assertEquals(0, snapshot.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void resetForNewDayReturnsTheDayAndStartsTheNextFromZero() {
        bus.publish(msg(ACLMessage.REQUEST));
        bus.publish(msg(ACLMessage.INFORM));

        Map<String, Integer> ended = counter.resetForNewDay();

        assertEquals(1, ended.get("REQUEST"));
        assertEquals(1, ended.get("INFORM"));
        assertEquals(0, counter.snapshot().values().stream().mapToInt(Integer::intValue).sum(),
                "the new day starts empty");

        bus.publish(msg(ACLMessage.CFP));
        assertEquals(1, counter.snapshot().get("CFP"));
        assertEquals(1, ended.get("REQUEST"), "the returned day is a snapshot, not a live view");
    }

    /**
     * planning/20's flagged risk: "Performative counter reset. Must happen atomically at midnight;
     * verify with concurrent CommLogEvent publishing." The reset is a swap, so every message is
     * counted in exactly one day — none lost at the boundary, none double-counted.
     */
    @Test
    void noMessageIsLostOrDoubleCountedAcrossAConcurrentMidnightReset() throws Exception {
        int publishers = 8;
        int perPublisher = 500;
        ExecutorService pool = Executors.newFixedThreadPool(publishers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rolledUp = new AtomicInteger();

        for (int i = 0; i < publishers; i++) {
            pool.submit(() -> {
                start.await();
                for (int j = 0; j < perPublisher; j++) {
                    bus.publish(msg(ACLMessage.PROPOSE));
                }
                return null;
            });
        }
        start.countDown();
        // Roll the day over repeatedly while the publishers are mid-flight.
        for (int i = 0; i < 50; i++) {
            rolledUp.addAndGet(counter.resetForNewDay().get("PROPOSE"));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "publishers finished");
        rolledUp.addAndGet(counter.resetForNewDay().get("PROPOSE"));

        assertEquals(publishers * perPublisher, rolledUp.get(),
                "every message landed in exactly one day");
    }

    @Test
    void snapshotIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> counter.snapshot().put("REQUEST", 99));
    }

    @Test
    void closeStopsCounting() {
        counter.close();
        bus.publish(msg(ACLMessage.REQUEST));

        assertEquals(0, counter.snapshot().get("REQUEST"));
    }
}
