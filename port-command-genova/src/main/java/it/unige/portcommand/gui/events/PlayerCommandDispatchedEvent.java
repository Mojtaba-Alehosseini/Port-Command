package it.unige.portcommand.gui.events;

import java.util.Objects;

import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.util.Event;

/**
 * A player or autopilot command went on the wire: the counterpart of
 * {@link PlayerCommandRefusedEvent} (S17a, ADR-21, decision A9). Published by
 * {@code DispatchPlayerCommandBehaviour} right after the send, for a command that carried a
 * {@link PlayerCommandEvent#commandId()} - so whoever sent it can tell "it was said" from "the
 * Prolog gate refused it".
 *
 * <p>The autopilot is the reader: it used to write "Assistant (autopilot): counter at ..." into
 * the chat the moment it published its command, before the gate had judged it - a refused move
 * read exactly like a made one. It now writes that line when this event names its command.
 *
 * @param dialogueId the negotiation the command targeted ({@code "nego-" + vesselId})
 * @param kind       the command's kind
 * @param commandId  the dispatched command's id; never {@link PlayerCommandEvent#NO_COMMAND_ID}
 */
public record PlayerCommandDispatchedEvent(String dialogueId, PlayerCommandKind kind, long commandId)
        implements Event {

    public PlayerCommandDispatchedEvent {
        Objects.requireNonNull(dialogueId, "dialogueId");
        Objects.requireNonNull(kind, "kind");
    }
}
