package it.unige.portcommand.harbourmaster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PlayerCommand}'s {@code content} defensive copy must preserve insertion order for the
 * exact same reason as {@link PlayerCommandEvent}'s (which this is built {@link
 * PlayerCommand#from(PlayerCommandEvent) from}): {@code DispatchPlayerCommandBehaviour} serialises
 * it straight into ACL wire content via {@code TerminalJson.write}. Fixing only {@code
 * PlayerCommandEvent} would be undone here — {@code Map.copyOf} on an already-ordered (but not
 * JDK-immutable) map still re-randomises it — so both records carry the identical fix
 * (INVARIANTS.md "wire format", the {@code Frame}/{@code RasaParseResult} precedent).
 */
class PlayerCommandTest {

    @Test
    void contentIterationOrderFollowsInsertionOrderSoAclContentIsDeterministic() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("price", 2400L);
        ordered.put("berth_id", "berth_2");
        ordered.put("conversation_id", "nego-V1");

        PlayerCommand cmd = new PlayerCommand(PlayerCommandKind.PROPOSE, "V1", ordered);

        assertEquals(List.of("price", "berth_id", "conversation_id"),
                List.copyOf(cmd.content().keySet()),
                "content order must match insertion order, not a per-JVM hash order");
        assertEquals("{\"price\":2400,\"berth_id\":\"berth_2\",\"conversation_id\":\"nego-V1\"}",
                TerminalJson.write(cmd.content()), "ACL content must be byte-stable for a given command");
    }

    /** Proves the fix survives the actual conversion hop, not just each record in isolation. */
    @Test
    void fromPreservesTheEventsContentOrderEndToEnd() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("price", 2400L);
        ordered.put("berth_id", "berth_2");
        PlayerCommandEvent event = new PlayerCommandEvent(PlayerCommandKind.PROPOSE, "V1", ordered);

        PlayerCommand cmd = PlayerCommand.from(event);

        assertEquals(List.of("price", "berth_id"), List.copyOf(cmd.content().keySet()),
                "PlayerCommand.from must not re-randomise the order PlayerCommandEvent already fixed");
    }

    @Test
    void nullContentBecomesEmptyAndImmutable() {
        PlayerCommand cmd = new PlayerCommand(PlayerCommandKind.WITHDRAW, "V1", null);

        assertEquals(Map.of(), cmd.content());
        assertThrows(UnsupportedOperationException.class, () -> cmd.content().put("x", "y"));
    }

    @Test
    void contentIsDefensivelyCopied() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("price", 2000L);
        PlayerCommand cmd = new PlayerCommand(PlayerCommandKind.PROPOSE, "V1", source);

        source.put("berth_id", "berth_1"); // must not leak into the command

        assertEquals(Map.of("price", 2000L), cmd.content());
    }
}
