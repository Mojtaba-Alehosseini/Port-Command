package it.unige.portcommand.gui.events;

import java.util.Map;
import java.util.Objects;

import it.unige.portcommand.util.Event;

/**
 * A chat-originated (or Assistant-autopilot-originated — planning/10 §10.7) player command,
 * consumed by HarbourMasterAgent (task 11) and translated into a real ACL message to the target
 * vessel. Shape per planning/17 §"Files to create". See {@link HintButtonEvent}'s ownership note.
 *
 * @param kind           the command kind
 * @param targetVesselId the vessel this command targets
 * @param content        command-specific payload (e.g. {@code {"price": 2400}} for a PROPOSE)
 */
public record PlayerCommandEvent(PlayerCommandKind kind, String targetVesselId, Map<String, Object> content)
        implements Event {

    public PlayerCommandEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetVesselId, "targetVesselId");
        content = content == null ? Map.of() : Map.copyOf(content);
    }

    /** Identical to the internal {@code PlayerCommand.kind} (task 11) this event is mapped to. */
    public enum PlayerCommandKind {
        PROPOSE, ACCEPT, REJECT, ASK, WITHDRAW
    }
}
