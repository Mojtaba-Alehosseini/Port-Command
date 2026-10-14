package it.unige.portcommand.gui.model;

import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;

/**
 * The two texts the comm log can show for one {@link CommLogEvent} (S15, 2026-10-12): the plain
 * line it always had, and the Raw block that prints the message's real FIPA fields.
 *
 * <p>The plain line used to be a private method of {@code CommLogPanel} that no test could reach;
 * it lives here so its exact shape is pinned ({@code CommLogLineFormatTest}) and so the Raw block
 * shares the timestamp arithmetic. Pure strings, no Swing/AWT: the panel only chooses which of
 * the two to insert and in which colour.
 */
public final class CommLogLineFormat {

    /** Printed in the Raw block for a field the message did not carry (an em dash, U+2014). */
    public static final String ABSENT = "—";

    /**
     * Longest content the Raw block prints before cutting. The largest real content is an opening
     * offer with its vessel spec, about 260 characters; this is a guard against a runaway
     * payload freezing the text pane, not a limit anything in the game reaches. The archive on
     * disk always keeps the full text.
     */
    static final int RAW_CONTENT_MAX_CHARS = 2000;

    private CommLogLineFormat() {
    }

    /** Formatted from the event's own {@code simTimeMillis} — no {@code SimClock} reference
     * needed, using the same hour/minute/second modular arithmetic {@code SimClock} itself uses.
     * S18b (UI tree U16): with the game day in front — {@code D2 14:20:05}; a free-play log runs
     * over many days, and two rows a day apart used to read alike. */
    public static String timestamp(long simTimeMillis) {
        long hour = (simTimeMillis / 3_600_000L) % 24;
        long minute = (simTimeMillis / 60_000L) % 60;
        long second = (simTimeMillis / 1_000L) % 60;
        return String.format(java.util.Locale.ROOT, "D%d %02d:%02d:%02d",
                it.unige.portcommand.util.SimClock.gameDayOf(simTimeMillis), hour, minute, second);
    }

    /**
     * The plain-English line: {@code [HH:MM:SS] sender -> receivers: PERFORMATIVE "paraphrase"}.
     * Moved verbatim from {@code CommLogPanel.formatLine} — byte-identical, receivers still
     * joined by a bare comma.
     */
    public static String plain(CommLogEvent event) {
        return String.format("[%s] %s -> %s: %s \"%s\"",
                timestamp(event.simTimeMillis()), event.sender(), String.join(",", event.receivers()),
                ACLMessage.getPerformative(event.performative()), event.paraphrase());
    }

    /**
     * The Raw block: four lines joined by {@code '\n'}, no trailing newline, never a {@code '\r'}.
     * The labels are the FIPA parameter names; the paraphrase is deliberately absent because it
     * is not a field of the message.
     *
     * <pre>
     * [08:14:03] ACCEPT-PROPOSAL
     *   sender: harbour_master  receiver: vessel_c501
     *   ontology: port_command_v1  protocol: —  conversation-id: berth-vessel_c501
     *   content: {"berth_id": "berth_1", "price": 5200.0, "hours": 8, "reason": "compatible [R7]"}
     * </pre>
     *
     * A field the message did not carry prints {@link #ABSENT}; several receivers are joined by
     * a comma and a space. The content is the wire text with spaces added for wrapping (see
     * {@link #spacedContent}), flattened to one line and cut at {@link #RAW_CONTENT_MAX_CHARS}.
     */
    public static String raw(CommLogEvent event) {
        StringBuilder block = new StringBuilder(256);
        block.append('[').append(timestamp(event.simTimeMillis())).append("] ")
                .append(ACLMessage.getPerformative(event.performative())).append('\n');
        block.append("  sender: ").append(field(event.sender()))
                .append("  receiver: ").append(receivers(event)).append('\n');
        block.append("  ontology: ").append(field(event.ontology()))
                .append("  protocol: ").append(field(event.protocol()))
                .append("  conversation-id: ").append(field(event.conversationId())).append('\n');
        block.append("  content: ").append(content(event.content()));
        return block.toString();
    }

    /**
     * The wire content with ONE space added after each {@code ,} and {@code :} that sits outside
     * a JSON string — nothing else changes. It is not a re-serialisation: numbers, key order and
     * every character inside a string (a {@code [R7,R8]} citation, an escaped quote) are exactly
     * what was sent.
     *
     * <p>Why spaces at all: a text pane cannot wrap a token that has no whitespace, and compact
     * JSON has none, so an unspaced content line would run off the edge of a 380 px column.
     *
     * <p>Only content that starts like JSON (an opening brace or square bracket) is touched;
     * anything else — and {@code null} — is returned as it came. A separator already followed by
     * whitespace gets no second space.
     */
    static String spacedContent(String content) {
        if (content == null || !startsLikeJson(content)) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length() + content.length() / 4);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            out.append(c);
            if (inString) {
                if (escaped) {
                    escaped = false; // the character after a backslash never ends the string
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if ((c == ',' || c == ':') && i + 1 < content.length()
                    && !Character.isWhitespace(content.charAt(i + 1))) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    private static boolean startsLikeJson(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == '[';
            }
        }
        return false;
    }

    private static String receivers(CommLogEvent event) {
        if (event.receivers().isEmpty()) {
            return ABSENT;
        }
        return oneLine(String.join(", ", event.receivers()));
    }

    private static String field(String value) {
        return value == null || value.isBlank() ? ABSENT : oneLine(value);
    }

    private static String content(String content) {
        if (content == null || content.isBlank()) {
            return ABSENT;
        }
        String shown = oneLine(spacedContent(content));
        if (shown.length() <= RAW_CONTENT_MAX_CHARS) {
            return shown;
        }
        int cut = RAW_CONTENT_MAX_CHARS;
        if (Character.isHighSurrogate(shown.charAt(cut - 1))) {
            cut--; // never leave half of a surrogate pair at the cut
        }
        return shown.substring(0, cut) + " … (+" + (shown.length() - cut) + " more chars)";
    }

    /**
     * Collapses each run of CR/LF to one space. The block's four-line shape is what the panel's
     * row offsets and the reader's eye both rely on, so no field may bring a line break of its
     * own — and a {@code '\r'} in a Swing document is never wanted.
     */
    private static String oneLine(String text) {
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean inBreak = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (!inBreak) {
                    out.append(' ');
                    inBreak = true;
                }
            } else {
                out.append(c);
                inBreak = false;
            }
        }
        return out.toString();
    }
}
