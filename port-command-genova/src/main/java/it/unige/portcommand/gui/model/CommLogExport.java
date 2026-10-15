package it.unige.portcommand.gui.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S19 (playtest 5, PT29 / PT35 / PT43): the comm log as a text file - the log's Export… button and
 * Game ▸ Export comm log…. Playtest 5: the log was hard to select and copy, and Game ▸ Open comm-log
 * file showed the raw JSON archive. The export is the log as it is shown (plain or Raw, the
 * current view), then the rules this game cited - the counts Help ▸ Rules (30) shows. The JSON
 * archive stays on disk under {@code logs/}, for analysis. Pure: no Swing.
 */
public final class CommLogExport {

    /** What the save dialog proposes. */
    public static final String DEFAULT_FILE_NAME = "port-command-comm-log.txt";

    private CommLogExport() {
    }

    /** The export's text: {@code shownLog}, then the rules cited, by number ({@code \n} line breaks). */
    public static String text(String shownLog, Map<String, Integer> cited) {
        StringBuilder out = new StringBuilder(shownLog == null ? "" : shownLog);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append('\n');
        if (cited == null || cited.isEmpty()) {
            return out.append("--- Rules cited this game: none ---\n").toString();
        }
        out.append("--- Rules cited this game (Help ▸ Rules lists all 30) ---\n");
        List<String> ids = new ArrayList<>(cited.keySet());
        ids.sort(RuleCitations.BY_NUMBER);
        for (String id : ids) {
            out.append(id).append(": ").append(cited.get(id)).append('\n');
        }
        return out.toString();
    }

    /** Writes {@code text} to {@code target} as UTF-8, its line breaks the system's own. */
    public static void write(Path target, String text) throws IOException {
        Files.writeString(target, text.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }
}
