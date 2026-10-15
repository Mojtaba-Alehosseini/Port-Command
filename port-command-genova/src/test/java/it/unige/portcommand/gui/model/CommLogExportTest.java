package it.unige.portcommand.gui.model;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S19 (playtest 5, PT29 / PT35 / PT43): the comm log as a text file. */
class CommLogExportTest {

    private static final String LOG = "[D1 09:03:00] vessel_c501 -> harbour_master: REQUEST \"C501 requests a berth\"\n"
            + "[D1 09:03:04] harbour_master -> vessel_c501: ACCEPT-PROPOSAL \"berth_1 granted (compatible [R7])\"\n";

    @Test
    void theExportIsTheLogAsShownThenTheRulesCitedThisGame() {
        Map<String, Integer> cited = new LinkedHashMap<>();
        cited.put("R7", 12);
        cited.put("R15", 3);

        assertEquals(LOG + "\n--- Rules cited this game (Help ▸ Rules lists all 30) ---\nR7: 12\nR15: 3\n",
                CommLogExport.text(LOG, cited));
    }

    @Test
    void aGameThatCitedNoRuleSaysSo() {
        assertEquals(LOG + "\n--- Rules cited this game: none ---\n", CommLogExport.text(LOG, Map.of()));
        assertEquals("No messages yet.\n\n--- Rules cited this game: none ---\n",
                CommLogExport.text("No messages yet.", Map.of()), "a log without a final line break gets one");
    }

    @Test
    void theFileIsUtf8WithTheSystemsLineBreaks(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("log.txt");

        CommLogExport.write(target, "a ▸ b\nc\n");

        String sep = System.lineSeparator();
        assertEquals("a ▸ b" + sep + "c" + sep, Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(CommLogExport.DEFAULT_FILE_NAME.endsWith(".txt"));
    }
}
