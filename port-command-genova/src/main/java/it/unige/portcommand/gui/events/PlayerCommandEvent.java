package it.unige.portcommand.gui.events;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * @param commandId      S13 (live-playtest F-7): a correlation id, or {@link #NO_COMMAND_ID}. The
 *                       chat tab assigns one to every PROPOSE whose terms it shows on the offer
 *                       strip before the HarbourMaster's Prolog gate has judged it; a gate refusal
 *                       echoes it in {@link PlayerCommandRefusedEvent} so the tab can take exactly
 *                       that offer back. S17a (ADR-21): the autopilot gives every move of its own
 *                       an id too, from the same {@link #nextCommandId()} source, and hears back
 *                       either that refusal or {@link PlayerCommandDispatchedEvent}. Bus-only: it
 *                       is a record field, never a {@code content} key, so it never reaches the
 *                       wire.
 */
public record PlayerCommandEvent(PlayerCommandKind kind, String targetVesselId, Map<String, Object> content,
                                 long commandId)
        implements Event {

    /** The {@code commandId} of a command no one needs to correlate (buttons, tests). */
    public static final long NO_COMMAND_ID = 0L;

    /** JVM-wide on purpose, like the Assistant hint ids: a verdict still in flight across a load
     * must not match the next world, and since S17a two producers draw from it - the chat tab
     * (S13) and the autopilot, which now waits for the verdict on each of its moves. One source,
     * so an autopilot id can never be mistaken for a tab id and roll an offer strip back. */
    private static final java.util.concurrent.atomic.AtomicLong COMMAND_IDS =
            new java.util.concurrent.atomic.AtomicLong();

    /** A fresh correlation id; never {@link #NO_COMMAND_ID}. */
    public static long nextCommandId() {
        return COMMAND_IDS.incrementAndGet();
    }

    /**
     * Defensive copy that KEEPS insertion order — deliberately not {@code Map.copyOf}, which
     * randomises iteration order per JVM launch. {@code content} flows straight into ACL wire
     * content via {@code DispatchPlayerCommandBehaviour}'s {@code proposeMessage}/{@code
     * plainMessage} -> {@code TerminalJson.write} (through {@code PlayerCommand}, which shares
     * this exact fix), so a random order here means the same player command serialises to
     * different bytes between runs. Same established anti-pattern as {@code Frame} and {@code
     * RasaParseResult} (INVARIANTS.md).
     */
    public PlayerCommandEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetVesselId, "targetVesselId");
        content = content == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(content));
    }

    /** A command with no correlation id — every producer except the chat tab's PROPOSE send and,
     * since S17a, the autopilot's moves. */
    public PlayerCommandEvent(PlayerCommandKind kind, String targetVesselId, Map<String, Object> content) {
        this(kind, targetVesselId, content, NO_COMMAND_ID);
    }

    /** Identical to the internal {@code PlayerCommand.kind} (task 11) this event is mapped to. */
    public enum PlayerCommandKind {
        PROPOSE, ACCEPT, REJECT, ASK, WITHDRAW
    }
}
