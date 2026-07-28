package it.unige.portcommand.harbourmaster;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * Defensive copy that KEEPS insertion order — deliberately not {@code Map.copyOf}. {@code
     * content} is serialised straight to ACL wire content by {@code DispatchPlayerCommandBehaviour}
     * ({@code proposeMessage}/{@code plainMessage} -> {@code TerminalJson.write}); {@code
     * Map.copyOf} randomises iteration order per JVM launch, which would silently re-randomise the
     * order even if {@link PlayerCommandEvent#content()} (the map this is built {@link #from}) was
     * already fixed to preserve it — the two records share this exact fix for that reason. Same
     * established anti-pattern as {@code Frame} and {@code RasaParseResult} (INVARIANTS.md).
     */
    public PlayerCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetVesselId, "targetVesselId");
        content = content == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(content));
    }

    public static PlayerCommand from(PlayerCommandEvent event) {
        return new PlayerCommand(event.kind(), event.targetVesselId(), event.content());
    }
}
