package it.unige.portcommand.harbourmaster;

import java.util.Map;
import java.util.Objects;

import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;

/**
 * HarbourMaster-internal representation of a player action, mapped 1:1 from the
 * GUI-facing {@link PlayerCommandEvent} by {@code DispatchPlayerCommandBehaviour} —
 * the only conversion point ({@code kind}'s values are identical in both types).
 */
public record PlayerCommand(PlayerCommandKind kind, String targetVesselId, Map<String, Object> content) {

    public PlayerCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetVesselId, "targetVesselId");
        content = content == null ? Map.of() : Map.copyOf(content);
    }

    public static PlayerCommand from(PlayerCommandEvent event) {
        return new PlayerCommand(event.kind(), event.targetVesselId(), event.content());
    }
}
