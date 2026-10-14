package it.unige.portcommand.gui.model;

import java.util.List;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.RuleCatalogue;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.model.RuleRefs.Ref;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the comm log's two texts for one message. The plain line was a private,
 * untested method of the panel until now — its exact shape is pinned here because the panel, the
 * demo script and the player's eye all depend on it — and the Raw block is new.
 */
class CommLogLineFormatTest {

    /** 08:14:03 on the sim clock. */
    private static final long T_08_14_03 = 8 * 3_600_000L + 14 * 60_000L + 3_000L;

    private static final String GRANT_CONTENT =
            "{\"berth_id\":\"berth_1\",\"price\":5200.0,\"hours\":8,\"reason\":\"compatible [R7]\"}";

    /** The berth grant of the demo's first beat, as the HarbourMaster publishes it. */
    private static CommLogEvent grant() {
        return new CommLogEvent(T_08_14_03, "harbour_master", List.of("vessel_c501"), ACLMessage.ACCEPT_PROPOSAL,
                "Granted berth berth_1 (compatible [R7])", "berth-vessel_c501",
                MessageFactory.ONTOLOGY, null, GRANT_CONTENT);
    }

    private static CommLogEvent withContent(String content) {
        return new CommLogEvent(0L, "harbour_master", List.of("tug_1"), ACLMessage.INFORM, "p", "c1",
                MessageFactory.ONTOLOGY, null, content);
    }

    private static String lastLine(String block) {
        return block.substring(block.lastIndexOf('\n') + 1);
    }

    // ------------------------------------------------------------------ plain line + timestamp

    @Test
    void thePlainLineKeepsItsExactShape() {
        CommLogEvent cfp = new CommLogEvent(3_723_000L, "harbour_master", List.of("tug_1", "tug_2"),
                ACLMessage.CFP, "Escort needed for C501", "cnp-1");

        assertEquals("[D1 01:02:03] harbour_master -> tug_1,tug_2: CFP \"Escort needed for C501\"",
                CommLogLineFormat.plain(cfp));
    }

    @Test
    void thePlainLineDoesNotChangeWhenTheRawFieldsAreAttached() {
        assertEquals("[D1 08:14:03] harbour_master -> vessel_c501: ACCEPT-PROPOSAL "
                        + "\"Granted berth berth_1 (compatible [R7])\"",
                CommLogLineFormat.plain(grant()));
    }

    @Test
    void theTimestampIsZeroPaddedAndNamesTheDay() {
        assertEquals("D1 00:00:00", CommLogLineFormat.timestamp(0L));
        assertEquals("D1 00:00:59", CommLogLineFormat.timestamp(59_999L), "sub-second millis are dropped");
        assertEquals("D1 23:59:59", CommLogLineFormat.timestamp(86_399_000L));
        // S18b (UI tree U16): "timestamps have no day" — day 2 used to read like day 1.
        assertEquals("D2 00:00:01", CommLogLineFormat.timestamp(86_401_000L), "day 2 says so");
        assertEquals("D1 08:14:03", CommLogLineFormat.timestamp(T_08_14_03));
    }

    // ------------------------------------------------------------------ the Raw block

    @Test
    void theRawBlockIsTheFourLabelledLines() {
        String expected = String.join("\n",
                "[D1 08:14:03] ACCEPT-PROPOSAL",
                "  sender: harbour_master  receiver: vessel_c501",
                "  ontology: port_command_v1  protocol: —  conversation-id: berth-vessel_c501",
                "  content: {\"berth_id\": \"berth_1\", \"price\": 5200.0, \"hours\": 8, "
                        + "\"reason\": \"compatible [R7]\"}");

        assertEquals(expected, CommLogLineFormat.raw(grant()));
    }

    @Test
    void theRawBlockNamesEverySlotAndLeavesTheParaphraseOut() {
        String raw = CommLogLineFormat.raw(new CommLogEvent(T_08_14_03, "harbour_master",
                List.of("tug_1", "tug_2"), ACLMessage.CFP, "Escort needed for C501", "cnp-42",
                MessageFactory.ONTOLOGY, "fipa-contract-net", "{\"target_vessel_id\":\"C501\"}"));

        for (String label : List.of("sender: ", "receiver: ", "ontology: ", "protocol: ", "conversation-id: ",
                "content: ")) {
            assertTrue(raw.contains("  " + label), () -> "missing label " + label + " in:\n" + raw);
        }
        assertTrue(raw.startsWith("[D1 08:14:03] CFP\n"), "the performative heads the block");
        assertTrue(raw.contains("  protocol: fipa-contract-net  conversation-id: cnp-42"));
        assertFalse(raw.contains("Escort needed"), "the paraphrase is not a field of the message");
    }

    @Test
    void severalReceiversAreJoinedWithACommaAndASpace() {
        String raw = CommLogLineFormat.raw(new CommLogEvent(0L, "harbour_master",
                List.of("tug_1", "tug_2", "tug_3"), ACLMessage.CFP, "p", "cnp-1"));

        assertTrue(raw.contains("\n  sender: harbour_master  receiver: tug_1, tug_2, tug_3\n"), raw);
    }

    @Test
    void aDashStandsForEveryFieldTheMessageDidNotCarry() {
        // The pre-S15 shape with nothing attached: no receivers, no conversation, no raw fields.
        String raw = CommLogLineFormat.raw(
                new CommLogEvent(0L, "harbour_master", List.of(), ACLMessage.INFORM, "p", null));

        assertEquals(String.join("\n",
                "[D1 00:00:00] INFORM",
                "  sender: harbour_master  receiver: —",
                "  ontology: —  protocol: —  conversation-id: —",
                "  content: —"), raw);
    }

    @Test
    void theDashIsAnEmDash() {
        assertEquals("\u2014", CommLogLineFormat.ABSENT, "U+2014, whatever encoding this file was saved in");
    }

    @Test
    void aBlankFieldReadsAsAbsentToo() {
        String raw = CommLogLineFormat.raw(new CommLogEvent(0L, "harbour_master", List.of("tug_1"),
                ACLMessage.INFORM, "p", " ", "", "  ", ""));

        assertTrue(raw.contains("  ontology: —  protocol: —  conversation-id: —\n"), raw);
        assertEquals("  content: —", lastLine(raw));
    }

    @Test
    void theRawBlockIsFourLinesWithNoTrailingNewline() {
        String raw = CommLogLineFormat.raw(grant());

        assertEquals(4, raw.split("\n", -1).length);
        assertFalse(raw.endsWith("\n"), "the panel adds the row separator, not the format");
        assertFalse(raw.contains("\r"));
    }

    // ------------------------------------------------------------------ content spacing

    @Test
    void contentGetsASpaceAfterEachSeparatorOutsideStringsOnly() {
        assertEquals("{\"reason\": \"a,b: [R7,R8]\", \"n\": 1}",
                CommLogLineFormat.spacedContent("{\"reason\":\"a,b: [R7,R8]\",\"n\":1}"),
                "the comma and colon INSIDE the string — and its rule citation — are untouched");
    }

    @Test
    void escapedQuotesDoNotEndAString() {
        // Wire text: {"a":"he said \"x,y:z\" ok","b":2}
        assertEquals("{\"a\": \"he said \\\"x,y:z\\\" ok\", \"b\": 2}",
                CommLogLineFormat.spacedContent("{\"a\":\"he said \\\"x,y:z\\\" ok\",\"b\":2}"));
    }

    @Test
    void anEscapedBackslashBeforeTheClosingQuoteStillEndsTheString() {
        // Wire text: {"a":"c:\\","b":1} — the string's value ends in ONE backslash.
        assertEquals("{\"a\": \"c:\\\\\", \"b\": 1}",
                CommLogLineFormat.spacedContent("{\"a\":\"c:\\\\\",\"b\":1}"));
    }

    @Test
    void nestedObjectsAndArraysAreSpacedAllTheWayDown() {
        assertEquals("{\"vessel_spec\": {\"vessel_id\": \"W040\", \"length\": 180.0}, \"tags\": [1, 2]}",
                CommLogLineFormat.spacedContent("{\"vessel_spec\":{\"vessel_id\":\"W040\",\"length\":180.0},"
                        + "\"tags\":[1,2]}"));
        assertEquals("[1, 2, 3]", CommLogLineFormat.spacedContent("[1,2,3]"), "an array is JSON too");
    }

    @Test
    void numbersAndKeyOrderAreExactlyWhatWasSent() {
        // Not a re-serialisation: 5200.0 stays 5200.0 (not 5200), 1e3 stays 1e3, order is the wire's.
        assertEquals("{\"z\": 5200.0, \"a\": 1e3, \"m\": -0.50}",
                CommLogLineFormat.spacedContent("{\"z\":5200.0,\"a\":1e3,\"m\":-0.50}"));
    }

    @Test
    void contentThatIsAlreadySpacedIsNotSpacedTwice() {
        String spaced = "{\"a\": 1, \"b\": [2, 3]}";

        assertEquals(spaced, CommLogLineFormat.spacedContent(spaced));
        assertEquals(spaced, CommLogLineFormat.spacedContent(CommLogLineFormat.spacedContent("{\"a\":1,\"b\":[2,3]}")),
                "spacing twice changes nothing");
    }

    @Test
    void contentThatIsNotJsonIsShownVerbatim() {
        assertEquals("hold:wind,swell", CommLogLineFormat.spacedContent("hold:wind,swell"));
        assertEquals("  content: hold:wind,swell", lastLine(CommLogLineFormat.raw(withContent("hold:wind,swell"))));
        assertNull(CommLogLineFormat.spacedContent(null));
        assertEquals("", CommLogLineFormat.spacedContent(""));
    }

    @Test
    void malformedJsonIsStillPrintedNeverThrown() {
        // An unterminated string and a dangling separator: the scanner just runs to the end.
        assertEquals("{\"a\": \"never closed, : still inside",
                CommLogLineFormat.spacedContent("{\"a\":\"never closed, : still inside"));
        assertEquals("{\"a\": 1,", CommLogLineFormat.spacedContent("{\"a\":1,"));
        assertEquals("  content: {\"a\": 1,", lastLine(CommLogLineFormat.raw(withContent("{\"a\":1,"))));
    }

    // ------------------------------------------------------------------ defensive rules

    @Test
    void lineBreaksInTheContentAreFlattenedToOneSpace() {
        String raw = CommLogLineFormat.raw(withContent("line one\r\nline two\nline three\rline four"));

        assertEquals("  content: line one line two line three line four", lastLine(raw));
        assertEquals(4, raw.split("\n", -1).length, "still exactly four lines");
        assertFalse(raw.contains("\r"), "a carriage return never reaches the document");
    }

    @Test
    void prettyPrintedJsonIsFlattenedWithoutDoubleSpacing() {
        String raw = CommLogLineFormat.raw(withContent("{\"a\":1,\n\"b\":2}"));

        assertEquals("  content: {\"a\": 1, \"b\": 2}", lastLine(raw));
    }

    @Test
    void aLineBreakInAnyOtherFieldCannotBreakTheBlockEither() {
        String raw = CommLogLineFormat.raw(new CommLogEvent(0L, "harbour\nmaster", List.of("tug\r\n1"),
                ACLMessage.INFORM, "p", "conv\n1", "onto\nlogy", "proto\rcol", "{}"));

        assertEquals(String.join("\n",
                "[D1 00:00:00] INFORM",
                "  sender: harbour master  receiver: tug 1",
                "  ontology: onto logy  protocol: proto col  conversation-id: conv 1",
                "  content: {}"), raw);
    }

    @Test
    void contentBeyondTheLimitIsCutAndSaysHowMuchIsMissing() {
        int limit = CommLogLineFormat.RAW_CONTENT_MAX_CHARS;
        assertEquals(2000, limit);

        String atLimit = lastLine(CommLogLineFormat.raw(withContent("x".repeat(limit))));
        assertEquals("  content: " + "x".repeat(limit), atLimit, "exactly at the limit is shown whole");

        String over = lastLine(CommLogLineFormat.raw(withContent("x".repeat(limit + 500))));
        assertEquals("  content: " + "x".repeat(limit) + " … (+500 more chars)", over);
    }

    @Test
    void theLimitCountsTheTextAsShownThatIsAfterSpacing() {
        // 1,001 array elements, compact: 2,003 chars on the wire. Spaced, each of the 1,000 commas
        // gains a space. What is cut — and counted — is the text the player would have seen.
        String wire = "[" + "1,".repeat(1000) + "1]";
        String spaced = CommLogLineFormat.spacedContent(wire);
        assertEquals(wire.length() + 1000, spaced.length());

        String shown = lastLine(CommLogLineFormat.raw(withContent(wire)));

        assertEquals("  content: " + spaced.substring(0, 2000) + " … (+" + (spaced.length() - 2000) + " more chars)",
                shown);
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        // U+1F6A2 (a ship) is two chars; place it across the 2000-char boundary.
        String ship = new String(Character.toChars(0x1F6A2));
        String content = "x".repeat(1999) + ship + "tail";

        String shown = lastLine(CommLogLineFormat.raw(withContent(content)));

        assertEquals("  content: " + "x".repeat(1999) + " … (+6 more chars)", shown,
                "the cut backs off one char instead of leaving a lone high surrogate");
    }

    // ------------------------------------------------------------------ with RuleRefs

    @Test
    void ruleIdsInARawBlockAreFoundAndTheTimestampBracketIsNot() {
        String raw = CommLogLineFormat.raw(grant());

        List<Ref> refs = RuleRefs.find(raw, RuleCatalogue.get()::contains);

        assertEquals(1, refs.size(), "only the citation inside the content; [08:14:03] is not a rule");
        assertEquals("R7", raw.substring(refs.get(0).start(), refs.get(0).end()));
    }

    @Test
    void aMultiIdCitationSurvivesSpacingUntouched() {
        String raw = CommLogLineFormat.raw(withContent(
                "{\"berth_id\":\"berth_2\",\"reason\":\"after_queue scheduled_ferry rank 3 [R25,R26,R27,R30]\"}"));

        assertTrue(raw.contains("[R25,R26,R27,R30]"), "no space was added inside the string");
        assertEquals(4, RuleRefs.find(raw, RuleCatalogue.get()::contains).size());
    }
}
