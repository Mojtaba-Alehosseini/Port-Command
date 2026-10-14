package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.nlp.DcgTokenizer;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.Frame;
import it.unige.portcommand.nlp.PrologDcgParser;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (REMAINING_AREAS_DECISIONS P7): every move Help lists is one the real grammar reads as that
 * move — each alternative of each row, with the placeholders filled in, in a tab where the player
 * has an offer standing and a second vessel is open. Same engine idiom as {@code InputExamplesDcgTest}.
 */
class HelpTextDcgTest {

    private static final String DIALOGUE = "nego-W001";
    private static final String OTHER_SHIP = "Genoa Star";

    private static PrologDcgParser parser;

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
        parser = new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer());
    }

    private static NegotiationOpenedEvent opening(String dialogueId, String vesselId, String type, double offer,
                                                  String name) {
        return new NegotiationOpenedEvent(dialogueId, new WalkInDialogueSnapshot(vesselId, type, 9.0, 150.0, 30000,
                "general_cargo", "berth_2", 6, offer, 0.0, 1, 0.0, false, name));
    }

    /** The tab the moves are typed in: their 1436 for 6h, the player's 1900 standing; and a tanker. */
    private static DialogueCtx context() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opening(DIALOGUE, "W001", "cargo_vessel", 1436.0, "Santa Lucia"));
        registry.onNegotiationOpened(opening("nego-W002", "W002", "tanker", 6000.0, OTHER_SHIP));
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6);
        return registry.buildCtx("W001");
    }

    private static String filledIn(String pattern) {
        return pattern.replace(HelpText.PRICE, "2400").replace(HelpText.HOURS, "8").replace(HelpText.NAME, OTHER_SHIP);
    }

    @Test
    void everyMoveHelpListsIsReadAsThatMove() {
        DialogueCtx ctx = context();
        List<String> checked = new ArrayList<>();
        for (HelpText.Section section : HelpText.sections()) {
            for (HelpText.Row row : section.rows()) {
                if (row.move() == null) {
                    continue;
                }
                for (String alternative : row.key().split(java.util.regex.Pattern.quote(HelpText.OR))) {
                    String typed = filledIn(alternative.trim());
                    Optional<Frame> frame = parser.parse(typed, ctx);
                    assertTrue(frame.isPresent(), "Help lists '" + typed + "', and the grammar does not read it");
                    assertEquals(row.move(), frame.get().move(), "'" + typed + "' is read as another move");
                    checked.add(typed);
                }
            }
        }
        assertTrue(checked.size() >= 20, "the moves were all checked: " + checked);
    }

    /** No literal price in what Help says to type (S15's rule for the input's examples). */
    @Test
    void helpNeverQuotesAPriceToType() {
        for (HelpText.Section section : HelpText.sections()) {
            if (!section.typed()) {
                continue;
            }
            for (HelpText.Row row : section.rows()) {
                assertFalse(row.key().matches(".*\\b\\d{3,}\\b.*") && !row.key().startsWith("200 more"),
                        "a price copied from Help could close a deal under the ship's own offer: " + row.key());
            }
        }
    }

    /** The placeholders are filled in by the test only: what Help shows says what to put there. */
    @Test
    void thePatternsUseThePlaceholders() {
        String all = HelpText.sections().stream().flatMap(s -> s.rows().stream()).map(HelpText.Row::key)
                .reduce("", String::concat);
        assertTrue(all.contains(HelpText.PRICE) && all.contains(HelpText.HOURS) && all.contains(HelpText.NAME));
    }
}
