package it.unige.portcommand.negotiation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionTest {

    @Test
    void factoriesSetTypeAndFields() {
        Decision accept = Decision.accept("ok");
        assertEquals(Decision.Type.ACCEPT, accept.type());
        assertEquals("ok", accept.reason());

        Decision counter = Decision.counter(5200.0, "haggle");
        assertEquals(Decision.Type.COUNTER, counter.type());
        assertEquals(5200.0, counter.newCounter());

        Decision withdraw = Decision.withdraw("player_refused");
        assertEquals(Decision.Type.WITHDRAW, withdraw.type());
        assertEquals("player_refused", withdraw.reason());
    }
}
