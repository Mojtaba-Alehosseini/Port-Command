package it.unige.portcommand.gui.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PlayerCommandEvent}'s {@code content} defensive copy must preserve insertion order,
 * because {@code DispatchPlayerCommandBehaviour} serialises it straight into ACL wire content
 * ({@code proposeMessage}/{@code plainMessage} -> {@code TerminalJson.write}). {@code Map.copyOf}
 * would randomise the order per JVM launch (INVARIANTS.md "wire format" — the same rule that
 * governs {@code Frame}/{@code RasaParseResult}); this pins the {@link LinkedHashMap} behaviour so
 * a regression to {@code Map.copyOf} is caught.
 */
class PlayerCommandEventTest {

    @Test
    void contentIterationOrderFollowsInsertionOrderSoAclContentIsDeterministic() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("price", 2400L);
        ordered.put("berth_id", "berth_2");
        ordered.put("conversation_id", "nego-V1");

        PlayerCommandEvent event = new PlayerCommandEvent(PlayerCommandKind.PROPOSE, "V1", ordered);

        assertEquals(List.of("price", "berth_id", "conversation_id"),
                List.copyOf(event.content().keySet()),
                "content order must match insertion order, not a per-JVM hash order");
        assertEquals("{\"price\":2400,\"berth_id\":\"berth_2\",\"conversation_id\":\"nego-V1\"}",
                TerminalJson.write(event.content()), "ACL content must be byte-stable for a given command");
    }

    @Test
    void nullContentBecomesEmptyAndImmutable() {
        PlayerCommandEvent event = new PlayerCommandEvent(PlayerCommandKind.WITHDRAW, "V1", null);

        assertEquals(Map.of(), event.content());
        assertThrows(UnsupportedOperationException.class, () -> event.content().put("x", "y"));
    }

    @Test
    void contentIsDefensivelyCopied() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("price", 2000L);
        PlayerCommandEvent event = new PlayerCommandEvent(PlayerCommandKind.PROPOSE, "V1", source);

        source.put("berth_id", "berth_1"); // must not leak into the event

        assertEquals(Map.of("price", 2000L), event.content());
    }
}
