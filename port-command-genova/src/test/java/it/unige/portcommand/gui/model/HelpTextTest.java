package it.unige.portcommand.gui.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19 (playtest 5): what Help says about the comm log - how to read it (PT34), where its export
 * is (PT29 / PT35) - and about the one pause control (PT24).
 */
class HelpTextTest {

    private static HelpText.Section section(String title) {
        return HelpText.sections().stream().filter(s -> s.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("no Help section '" + title + "'"));
    }

    private static List<HelpText.Row> allRows() {
        return HelpText.sections().stream().flatMap(s -> s.rows().stream()).toList();
    }

    /** PT34: "the comm log is hard to read (berth/tug part)" - the life of one ship, in six steps. */
    @Test
    void theHelpSaysHowToReadTheCommLogAsTheLifeOfOneShip() {
        HelpText.Section reading = section("How to read the comm log");

        assertEquals(List.of("1  Deal", "2  Berth", "3  Customs", "4  Tug auction", "5  Escort and dock", "6  Departure"),
                reading.rows().subList(0, 6).stream().map(HelpText.Row::key).toList());
        HelpText.Row tip = reading.rows().get(reading.rows().size() - 1);
        assertEquals("Tip", tip.key());
        assertTrue(tip.meaning().contains("This vessel"), tip.meaning());
    }

    /** PT35: Game ▸ Open comm-log file showed the raw JSON archive; the menu has the readable export. */
    @Test
    void theHelpNamesTheExportNotTheArchiveFile() {
        assertTrue(allRows().stream().noneMatch(r -> r.key().contains("Open comm-log file")
                || r.meaning().contains("Open comm-log file")), "the old menu item is gone");
        assertTrue(allRows().stream().anyMatch(r -> r.key().equals("Game ▸ Export comm log…")));
        HelpText.Row log = section("The window").rows().stream().filter(r -> r.key().equals("Comm log"))
                .findFirst().orElseThrow();
        assertTrue(log.meaning().contains("Export…"), log.meaning());
        assertTrue(allRows().stream().anyMatch(r -> r.key().startsWith("Help ▸ Rules")), "PT43's window is named");
    }

    /** PT24: one pause control, "▶ Resume" while paused. */
    @Test
    void theWindowSectionNamesTheResumeButton() {
        assertTrue(section("The window").rows().stream().anyMatch(r -> r.key().contains("▶ Resume")));
    }
}
