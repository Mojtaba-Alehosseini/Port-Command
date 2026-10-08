package it.unige.portcommand.gui.events;

import java.util.Objects;

import it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind;
import it.unige.portcommand.util.Event;

/**
 * The HarbourMaster's Prolog gate refused a binding player or autopilot command, so nothing went
 * on the wire (S13, live-playtest F-7). Published by {@code DispatchPlayerCommandBehaviour} next to
 * the player-visible refusal text ({@link AssistantChatEvent} + {@link NotificationEvent}), so the
 * chat tab can take back what it showed before the verdict: the offer strip records a PROPOSE's
 * terms at send time, and a refused one must not stay there as if the vessel had heard it.
 *
 * @param dialogueId the negotiation the command targeted ({@code "nego-" + vesselId})
 * @param kind       the refused command's kind
 * @param commandId  the refused command's {@link PlayerCommandEvent#commandId()};
 *                   {@link PlayerCommandEvent#NO_COMMAND_ID} when its producer assigned none
 *                   (the autopilot, a button), which nothing on the GUI side has to undo
 * @param standingPlayerPrice the price the vessel last heard from the player side ({@code 0.0} =
 *                   none yet): the HarbourMaster's own record, read on its thread at the refusal,
 *                   after every earlier dispatch — the autopilot's included, which the chat tab
 *                   never sees. The tab restores THIS price rather than its own memory of the strip
 *                   (review finding, S13: an autopilot counter landing between a chat send and its
 *                   refusal made the tab's memory stale)
 * @param reason     the gate's verdict, failing rule id included
 */
public record PlayerCommandRefusedEvent(String dialogueId, PlayerCommandKind kind, long commandId,
                                        double standingPlayerPrice, String reason)
        implements Event {

    public PlayerCommandRefusedEvent {
        Objects.requireNonNull(dialogueId, "dialogueId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
    }
}
