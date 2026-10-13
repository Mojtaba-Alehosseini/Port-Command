package it.unige.portcommand.gui.events;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.core.MessageFactory;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S15 (2026-10-12): {@link CommLogEvent} grew three components — ontology, protocol, content —
 * for the comm log's Raw view and its presets. The six-argument constructor stayed so that every
 * existing producer of a synthetic row (tests, mostly) keeps compiling; this pins what each shape
 * carries, and that the receiver list is still defensively copied in both.
 */
class CommLogEventTest {

    private static final String CONTENT =
            "{\"berth_id\":\"berth_1\",\"price\":5200.0,\"hours\":8,\"reason\":\"compatible [R7]\"}";

    @Test
    void theSixArgumentConstructorLeavesTheRawFieldsNull() {
        CommLogEvent event = new CommLogEvent(1_000L, "harbour_master", List.of("tug_1", "tug_2"),
                ACLMessage.CFP, "Escort needed for C501", "cnp-1");

        assertEquals(1_000L, event.simTimeMillis());
        assertEquals("harbour_master", event.sender());
        assertEquals(List.of("tug_1", "tug_2"), event.receivers());
        assertEquals(ACLMessage.CFP, event.performative());
        assertEquals("Escort needed for C501", event.paraphrase());
        assertEquals("cnp-1", event.conversationId());
        assertNull(event.ontology(), "no message attached: nothing to show for the raw fields");
        assertNull(event.protocol());
        assertNull(event.content());
    }

    @Test
    void theNineArgumentConstructorCarriesTheRawFields() {
        CommLogEvent event = new CommLogEvent(29_643_000L, "harbour_master", List.of("vessel_c501"),
                ACLMessage.ACCEPT_PROPOSAL, "Granted berth berth_1 (compatible [R7])", "berth-vessel_c501",
                MessageFactory.ONTOLOGY, "fipa-contract-net", CONTENT);

        assertEquals("port_command_v1", event.ontology());
        assertEquals("fipa-contract-net", event.protocol());
        assertEquals(CONTENT, event.content(), "the wire text, character for character");
        // The six older components are where they always were.
        assertEquals(29_643_000L, event.simTimeMillis());
        assertEquals("harbour_master", event.sender());
        assertEquals(List.of("vessel_c501"), event.receivers());
        assertEquals(ACLMessage.ACCEPT_PROPOSAL, event.performative());
        assertEquals("Granted berth berth_1 (compatible [R7])", event.paraphrase());
        assertEquals("berth-vessel_c501", event.conversationId());
    }

    @Test
    void theTwoShapesAreTheSameEventWhenTheRawFieldsAreAbsent() {
        CommLogEvent six = new CommLogEvent(5L, "hm", List.of("tug_1"), ACLMessage.INFORM, "p", "c1");
        CommLogEvent nine = new CommLogEvent(5L, "hm", List.of("tug_1"), ACLMessage.INFORM, "p", "c1",
                null, null, null);

        assertEquals(nine, six);
        assertEquals(nine.hashCode(), six.hashCode());
    }

    @Test
    void nullReceiversBecomeAnEmptyListInBothShapes() {
        CommLogEvent six = new CommLogEvent(0L, "hm", null, ACLMessage.INFORM, "p", null);
        CommLogEvent nine = new CommLogEvent(0L, "hm", null, ACLMessage.INFORM, "p", null,
                MessageFactory.ONTOLOGY, null, "{}");

        assertEquals(List.of(), six.receivers());
        assertEquals(List.of(), nine.receivers());
    }

    @Test
    void theReceiverListIsCopiedAndCannotBeModifiedInEitherShape() {
        List<String> source = new ArrayList<>(List.of("tug_1"));
        CommLogEvent six = new CommLogEvent(0L, "hm", source, ACLMessage.INFORM, "p", null);
        CommLogEvent nine = new CommLogEvent(0L, "hm", source, ACLMessage.INFORM, "p", null,
                MessageFactory.ONTOLOGY, null, null);

        source.add("tug_2"); // the publisher reuses nothing, but a caller's list must not leak in

        assertEquals(List.of("tug_1"), six.receivers());
        assertEquals(List.of("tug_1"), nine.receivers());
        assertThrows(UnsupportedOperationException.class, () -> six.receivers().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> nine.receivers().add("x"));
    }
}
