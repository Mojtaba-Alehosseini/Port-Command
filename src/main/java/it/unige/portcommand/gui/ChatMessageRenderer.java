package it.unige.portcommand.gui;

import java.util.List;

import it.unige.portcommand.gui.model.DialogueTabModel.ChatEntry;

/**
 * Pure HTML-snippet rendering for one dialogue tab's transcript — no Swing import,
 * so its output is directly assertable in a headless test. {@code DialogueTabView}
 * feeds the result straight into a {@code JTextPane} via {@code setContentType(
 * "text/html")} + {@code setText(...)}. Player messages right-aligned (light blue),
 * vessel messages left-aligned (light grey), Assistant messages full-width and
 * visually distinct (yellow) — the "🤖 Assistant" / "🤖 Assistant (autopilot)" prefix
 * is already baked into the text by the producing behaviour, this class never adds
 * its own. System notices (deal closed, withdrawal, unsupported command, parse
 * error) share one centered, muted style — they're all "the system talking", not a
 * fourth conversational party.
 */
public final class ChatMessageRenderer {

    private ChatMessageRenderer() {
    }

    public static String renderTranscript(List<ChatEntry> entries) {
        StringBuilder html = new StringBuilder("<html><body style=\"font-family:sans-serif;font-size:11px\">");
        for (ChatEntry entry : entries) {
            html.append(renderEntry(entry));
        }
        html.append("</body></html>");
        return html.toString();
    }

    static String renderEntry(ChatEntry entry) {
        String text = escape(entry.text());
        return switch (entry.speaker()) {
            case PLAYER -> bubble("right", "#cce5ff", text);
            case VESSEL -> bubble("left", "#e0e0e0", text);
            case ASSISTANT -> "<div style=\"margin:4px\"><span style=\"background:#fff3b0;padding:4px 8px;"
                    + "border-radius:8px;display:block\">" + text + "</span></div>";
            case SYSTEM -> "<div style=\"text-align:center;margin:4px;color:#883333;font-style:italic\">"
                    + text + "</div>";
        };
    }

    private static String bubble(String align, String background, String text) {
        return "<div style=\"text-align:" + align + ";margin:4px\"><span style=\"background:" + background
                + ";padding:4px 8px;border-radius:8px\">" + text + "</span></div>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
