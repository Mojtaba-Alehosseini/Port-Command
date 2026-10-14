package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import it.unige.portcommand.commlog.PerformativeColours;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.RuleCatalogue;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.model.CommLogLineFormat;
import it.unige.portcommand.gui.model.CommLogModel;
import it.unige.portcommand.gui.model.CommLogPreset;
import it.unige.portcommand.gui.model.RuleRefs;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.EventBus;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless behaviour tests for {@link CommLogPanel}. Follows the established GUI-test idiom (see
 * {@code GuiPanelsSubscriptionLifecycleTest}): real {@link EventBus}, construct the plain
 * {@code JPanel} (never shown — tests run under {@code -Djava.awt.headless=true}), publish
 * {@link CommLogEvent}s, flush the EDT with {@code invokeAndWait(() -> { })}, and assert on the
 * panel's model / test-seams rather than on painted Swing state.
 *
 * <p>A never-laid-out scrollpane (no window) reports meaningless value/extent/maximum, so most
 * cases here do not scroll at all: {@code setFollowingTailForTest(boolean)} puts the panel in the
 * state the user's own scrolling would ("scrolled up" / "back at the tail"). The two cases under
 * "the scrollbar for real" do lay the panel out ({@link HeadlessSwing#layOut}) and move the
 * actual scrollbar, because what they pin is exactly the scrollbar arithmetic: a layout change
 * must not end the following, and a scrolled-up view must stay on its row.
 *
 * <p>S15 (2026-10-12): the first two cases are the scroll-follow affordance as it was. The rest
 * pin the reworked column — the four view toggles and Raw in place of the five filter widgets,
 * the underlined rule ids and the rule line they fill, the "This vessel" view following the chat
 * tab, and the rule that nothing here can take the keyboard focus. The rows are 9-argument events
 * shaped like the ones {@code HarbourMasterAgent.publishCommLog} really logs, because the views
 * are decided from the message's own content. A toggle is clicked with {@code doClick}; a rule id
 * is "clicked" through {@code clickRuleForTest} — a real mouse press on a text pane cannot be
 * dispatched headless (its caret reaches for the system selection). Which rows a view selects is
 * {@code CommLogModelPresetTest}'s; here it is what reaches the screen.
 */
class CommLogPanelTest {

    private static final String HM = "harbour_master";
    private static final String WEATHER_AGENT = "weather_agent";
    private static final String WEATHER_ALERT_PROTOCOL = "port-command-weather-alert";
    private static final String RULE_LINE_PROMPT = "Click a rule id such as [R7] in the log to read the rule.";

    private static CommLogEvent event(long millis, String paraphrase) {
        return new CommLogEvent(millis, "hm", List.of("v1"), ACLMessage.INFORM, paraphrase, "conv-1");
    }

    // ---- rows, as the HarbourMaster logs them ---------------------------------------------------

    /** The weather agent's routine check: weather, but it cites no rule. */
    private static CommLogEvent routineWeatherCheck(long millis) {
        return new CommLogEvent(millis, WEATHER_AGENT, List.of(HM), ACLMessage.INFORM,
                "Notifying: weather check (18 kn, sunny)", null, MessageFactory.ONTOLOGY, WEATHER_ALERT_PROTOCOL,
                "{\"event\":\"weather_periodic\",\"wind_knots\":18,\"visibility\":\"good\",\"swell\":0.5,"
                        + "\"state\":\"sunny\"}");
    }

    /** A worsening-weather alert: weather AND a rule row. */
    private static CommLogEvent weatherAlert(long millis) {
        return new CommLogEvent(millis, WEATHER_AGENT, List.of(HM), ACLMessage.INFORM,
                "Notifying: URGENT weather alert (threshold crossed [R18])", null, MessageFactory.ONTOLOGY,
                WEATHER_ALERT_PROTOCOL,
                "{\"event\":\"weather_threshold\",\"reason\":\"threshold_crossed [R18]\",\"wind_knots\":36,"
                        + "\"visibility\":\"poor\",\"swell\":2.5,\"state\":\"stormy\"}");
    }

    /** The contracted vessel C501 is granted its berth: a rule row about C501, not weather. */
    private static CommLogEvent berthGrant(long millis) {
        return new CommLogEvent(millis, HM, List.of("vessel_c501"), ACLMessage.ACCEPT_PROPOSAL,
                "Granted berth berth_1 (compatible [R7])", "berth-vessel_c501", MessageFactory.ONTOLOGY, null,
                "{\"berth_id\":\"berth_1\",\"price\":5200.0,\"hours\":8,\"reason\":\"compatible [R7]\"}");
    }

    /** The walk-in WALKIN-3 leaves the berth queue: four rule ids in ONE bracket group. */
    private static CommLogEvent queueGrant(long millis) {
        return new CommLogEvent(millis, HM, List.of("vessel_walkin_3"), ACLMessage.ACCEPT_PROPOSAL,
                "Granted berth berth_2 (after queue scheduled ferry rank 3 [R25,R26,R27,R30])",
                "berth-vessel_walkin_3", MessageFactory.ONTOLOGY, null,
                "{\"berth_id\":\"berth_2\",\"price\":1900.0,\"hours\":6,"
                        + "\"reason\":\"after_queue scheduled_ferry rank 3 [R25,R26,R27,R30]\"}");
    }

    /** The walk-in WALKIN-3 opens its negotiation: about WALKIN-3, no rule, not weather. */
    private static CommLogEvent walkInOffer(long millis) {
        return new CommLogEvent(millis, "vessel_walkin_3", List.of(HM), ACLMessage.PROPOSE,
                "Offering €1436 for 6h from Santa Lucia (WALKIN-3)", "nego-WALKIN-3", MessageFactory.ONTOLOGY, null,
                "{\"price\":1436.0,\"hours\":6,\"vessel_spec\":{\"vessel_id\":\"WALKIN-3\","
                        + "\"vessel_name\":\"Santa Lucia\"}}");
    }

    /** A call for tug bids at 08:14:03: the one kind of row here with EVERY FIPA field set. */
    private static CommLogEvent tugCallForBids() {
        return new CommLogEvent(29_643_000L, HM, List.of("tug_1", "tug_2"), ACLMessage.CFP,
                "Escort needed for C501", "cnp-1234", MessageFactory.ONTOLOGY, "fipa-contract-net",
                "{\"target_vessel_id\":\"C501\",\"reply_by\":30000}");
    }

    /** A row about nothing in particular — no vessel, no weather, no rule (the 6-argument shape). */
    private static CommLogEvent dayNotice(long millis) {
        return new CommLogEvent(millis, HM, List.of("assistant_agent"), ACLMessage.INFORM,
                "Notifying: day started", null);
    }

    // ---- driving the panel ----------------------------------------------------------------------

    private static <T> T onEdt(Supplier<T> read) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(read.get()));
        return result.get();
    }

    private static void runOnEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static CommLogPanel newPanel(EventBus bus) throws Exception {
        return onEdt(() -> new CommLogPanel(bus));
    }

    /** Publish, then flush the EDT: every row is in the model and rendered when this returns. */
    private static void publish(EventBus bus, CommLogEvent... rows) throws Exception {
        for (CommLogEvent row : rows) {
            bus.publish(row);
        }
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void click(AbstractButton button) throws Exception {
        SwingUtilities.invokeAndWait(() -> button.doClick(0));
    }

    private static boolean clickRule(CommLogPanel panel, String ruleId) throws Exception {
        return onEdt(() -> panel.clickRuleForTest(ruleId));
    }

    // ---- reading the panel ----------------------------------------------------------------------

    private static String text(CommLogPanel panel) throws Exception {
        return onEdt(panel::visibleTextForTest);
    }

    private static List<RuleRefs.Ref> spans(CommLogPanel panel) throws Exception {
        return onEdt(panel::ruleSpansForTest);
    }

    private static String ruleLine(CommLogPanel panel) throws Exception {
        return onEdt(panel::ruleLineTextForTest);
    }

    private static String tooltipOf(JComponent component) throws Exception {
        return onEdt(() -> component.getToolTipText());
    }

    /** What the pane shows for these rows in the plain view: one line each, in this order. */
    private static String plainRows(CommLogEvent... rows) {
        StringBuilder text = new StringBuilder();
        for (CommLogEvent row : rows) {
            text.append(CommLogLineFormat.plain(row)).append('\n');
        }
        return text.toString();
    }

    private static void collect(Container container, List<Component> into) {
        for (Component child : container.getComponents()) {
            into.add(child);
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }

    /** The panel and every component under it, the scroll pane's own children included. */
    private static List<Component> everythingIn(CommLogPanel panel) {
        List<Component> all = new ArrayList<>();
        all.add(panel);
        collect(panel, all);
        return all;
    }

    /** The ONE component under the panel with that name, which must be of that kind. */
    private static <T extends JComponent> T named(CommLogPanel panel, String name, Class<T> type) {
        List<Component> found = new ArrayList<>();
        for (Component component : everythingIn(panel)) {
            if (name.equals(component.getName())) {
                found.add(component);
            }
        }
        assertEquals(1, found.size(), "exactly one component is named " + name);
        return assertInstanceOf(type, found.get(0), name);
    }

    private static String describe(Component component) {
        String name = component.getName();
        return component.getClass().getName() + (name == null ? "" : " '" + name + "'");
    }

    /** How one character of the pane is drawn. */
    private record Glyph(boolean underlined, Color colour) {
    }

    /** One {@link Glyph} per character of the pane, in document order. */
    private static List<Glyph> glyphs(CommLogPanel panel) throws Exception {
        JTextPane pane = named(panel, "commlog-text", JTextPane.class);
        return onEdt(() -> {
            StyledDocument doc = pane.getStyledDocument();
            List<Glyph> glyphs = new ArrayList<>();
            for (int offset = 0; offset < doc.getLength(); offset++) {
                AttributeSet attributes = doc.getCharacterElement(offset).getAttributes();
                glyphs.add(new Glyph(StyleConstants.isUnderline(attributes), StyleConstants.getForeground(attributes)));
            }
            return glyphs;
        });
    }

    private static long underlinedCount(List<Glyph> glyphs) {
        return glyphs.stream().filter(Glyph::underlined).count();
    }

    private static boolean isGrey(Color colour) {
        return colour.getRed() == colour.getGreen() && colour.getGreen() == colour.getBlue()
                && colour.getRed() > 0 && colour.getRed() < 255;
    }

    private static Rectangle2D drawnAt(JTextPane pane, int offset) {
        try {
            return pane.modelToView2D(offset);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    // =================================================================== scroll-follow (pre-S15)

    @Test
    @Timeout(5)
    void scrolledUpSuppressesAutoScrollAndShowsResumeButtonWhileEntriesArrive() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);

        // Baseline "at bottom": a new entry auto-scrolls to the tail and the resume button is hidden.
        SwingUtilities.invokeAndWait(() -> panel.setFollowingTailForTest(true));
        bus.publish(event(0L, "first"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().size(), "positive control: constructor subscription is live");
        assertTrue(panel.lastRenderAutoScrolledForTest(), "at bottom: render pins the view to the tail");
        assertFalse(panel.resumeButtonForTest().isVisible(), "at bottom: resume button hidden");

        // The user scrolls up: the resume button appears immediately.
        SwingUtilities.invokeAndWait(() -> panel.setFollowingTailForTest(false));
        assertTrue(panel.resumeButtonForTest().isVisible(), "scrolled up: resume button visible");

        // New entries keep arriving; the view must NOT be yanked back down.
        bus.publish(event(1000L, "second"));
        bus.publish(event(2000L, "third"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(3, panel.model().size());
        assertFalse(panel.lastRenderAutoScrolledForTest(),
                "scrolled up: auto-scroll to bottom is suppressed while entries arrive");
        assertTrue(panel.resumeButtonForTest().isVisible(),
                "scrolled up: resume button stays visible as entries arrive");
    }

    @Test
    @Timeout(5)
    void clickingResumeReturnsToBottomAndHidesTheButton() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);

        // Enter the scrolled-up state with some content in the log.
        SwingUtilities.invokeAndWait(() -> panel.setFollowingTailForTest(false));
        bus.publish(event(0L, "first"));
        bus.publish(event(1000L, "second"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.resumeButtonForTest().isVisible(), "precondition: scrolled up, button visible");

        // Click resume: it jumps to the tail, hides itself, and the log follows the tail again.
        SwingUtilities.invokeAndWait(() -> panel.resumeButtonForTest().doClick(0));
        assertFalse(panel.resumeButtonForTest().isVisible(), "after resume: button hidden");
        assertTrue(panel.followingTailForTest(), "after resume: new rows are followed again");
        bus.publish(event(2000L, "third"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.lastRenderAutoScrolledForTest(), "and the next arrival pins to the tail");
    }

    // ============================================================================ S15: the views

    @Test
    @Timeout(5)
    void anEmptyLogSaysSoInOneGreyLineInsteadOfShowingABlankPane() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);

        assertEquals("No messages yet.", text(panel), "a new panel already says why it is empty");
        assertTrue(spans(panel).isEmpty(), "a hint has nothing to click");
        for (Glyph glyph : glyphs(panel)) {
            assertTrue(isGrey(glyph.colour()), "the hint is grey, not a performative colour: " + glyph.colour());
            assertFalse(glyph.underlined(), "the hint is never underlined");
        }
        assertEquals(RULE_LINE_PROMPT, ruleLine(panel), "the rule line starts by saying what to click");
        assertFalse(panel.lastRenderAutoScrolledForTest(), "building the panel makes no scroll decision");
        assertFalse(panel.resumeButtonForTest().isVisible(), "and offers nothing to resume");

        CommLogEvent notice = dayNotice(0L);
        publish(bus, notice);
        assertEquals(plainRows(notice), text(panel), "the first row replaces the hint");
    }

    @Test
    @Timeout(5)
    void aPresetClickRerendersAndTheRoutineWeatherCheckShowsUnderAllAndWeatherButNotRules() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        CommLogEvent check = routineWeatherCheck(0L);
        CommLogEvent alert = weatherAlert(1_000L);
        CommLogEvent grant = berthGrant(2_000L);
        CommLogEvent notice = dayNotice(3_000L);
        publish(bus, check, alert, grant, notice);

        assertTrue(panel.presetToggleForTest(CommLogPreset.ALL).isSelected(), "a new panel shows All");
        assertEquals(plainRows(check, alert, grant, notice), text(panel),
                "All is literally every row — the weather agent's routine check included");

        click(panel.presetToggleForTest(CommLogPreset.WEATHER));
        assertEquals(CommLogPreset.WEATHER, panel.model().preset(), "the click reached the model");
        assertEquals(plainRows(check, alert), text(panel), "Weather: the routine check and the alert, re-rendered");
        assertTrue(panel.presetToggleForTest(CommLogPreset.WEATHER).isSelected());
        assertFalse(panel.presetToggleForTest(CommLogPreset.ALL).isSelected(), "one view at a time");

        click(panel.presetToggleForTest(CommLogPreset.RULES));
        assertEquals(plainRows(alert, grant), text(panel),
                "Rules: the rows that cite one — the routine check cites none and is gone");

        click(panel.presetToggleForTest(CommLogPreset.ALL));
        assertEquals(plainRows(check, alert, grant, notice), text(panel), "back to All: nothing was lost");
    }

    @Test
    @Timeout(5)
    void rawShowsTheLabelledFipaFieldsOfTheMessageInsteadOfTheParaphrase() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        CommLogEvent cfp = tugCallForBids();
        publish(bus, cfp);
        assertEquals("[D1 08:14:03] harbour_master -> tug_1,tug_2: CFP \"Escort needed for C501\"\n", text(panel),
                "precondition: the plain line");

        click(panel.rawToggleForTest());
        String raw = text(panel);
        assertEquals(CommLogLineFormat.raw(cfp) + "\n", raw, "Raw shows the model's Raw block for the row");
        assertTrue(raw.startsWith("[D1 08:14:03] CFP\n"), "time and performative head the block: " + raw);
        for (String field : List.of(
                "  sender: harbour_master",
                "  receiver: tug_1, tug_2",
                "  ontology: port_command_v1",
                "  protocol: fipa-contract-net",
                "  conversation-id: cnp-1234",
                "  content: {\"target_vessel_id\": \"C501\", \"reply_by\": 30000}")) {
            assertTrue(raw.contains(field), "Raw shows <" + field + "> in: " + raw);
        }
        assertFalse(raw.contains("Escort needed"), "the paraphrase is not a field of the message");

        click(panel.rawToggleForTest());
        assertEquals(plainRows(cfp), text(panel), "Raw off: the plain line again");
    }

    // ========================================================================= S15: rule ids

    @Test
    @Timeout(5)
    void ruleIdsAreUnderlinedAtTheirOwnOffsetsAndKeepTheRowColour() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        CommLogEvent grant = berthGrant(0L);
        CommLogEvent queued = queueGrant(1_000L);
        CommLogEvent notice = dayNotice(2_000L);
        publish(bus, grant, queued, notice);

        String text = text(panel);
        List<RuleRefs.Ref> spans = spans(panel);
        assertEquals(List.of("R7", "R25", "R26", "R27", "R30"), spans.stream().map(RuleRefs.Ref::ruleId).toList(),
                "one span per cited id, in reading order");
        for (RuleRefs.Ref span : spans) {
            assertEquals(span.ruleId(), text.substring(span.start(), span.end()),
                    "a span covers exactly its id in the document");
        }
        assertEquals(text.indexOf("[R7]") + 1, spans.get(0).start(), "the first row's id");
        assertEquals(text.indexOf("[R25,") + 1, spans.get(1).start(),
                "a later row's offsets count every row above it");
        assertEquals(text.indexOf("R30]"), spans.get(4).start(), "the last id of the group");

        List<Glyph> glyphs = glyphs(panel);
        assertEquals(text.length(), glyphs.size());
        Color grantColour = PerformativeColours.colourFor(ACLMessage.ACCEPT_PROPOSAL);
        for (RuleRefs.Ref span : spans) {
            for (int offset = span.start(); offset < span.end(); offset++) {
                assertTrue(glyphs.get(offset).underlined(), span.ruleId() + " is underlined at " + offset);
                assertEquals(grantColour, glyphs.get(offset).colour(),
                        "the underline is added to the row colour, it does not replace it");
            }
            assertFalse(glyphs.get(span.start() - 1).underlined(), "the bracket or comma before an id is plain");
            assertFalse(glyphs.get(span.end()).underlined(), "the comma or bracket after an id is plain");
        }
        assertEquals(2 + 3 + 3 + 3 + 3, underlinedCount(glyphs),
                "nothing else is underlined — not the [00:00:00] time stamp, not a row without a rule");

        assertEquals(grantColour, glyphs.get(0).colour(), "a row is drawn in its performative's colour, as before");
        int noticeStart = text.indexOf(CommLogLineFormat.plain(notice));
        assertEquals(PerformativeColours.colourFor(ACLMessage.INFORM), glyphs.get(noticeStart).colour());
    }

    @Test
    @Timeout(5)
    void aRuleClickFillsTheRuleLineAndItStaysUntilTheNextClick() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        publish(bus, berthGrant(0L), queueGrant(1_000L));
        JTextArea line = named(panel, "commlog-rule-line", JTextArea.class);
        assertEquals(RULE_LINE_PROMPT, ruleLine(panel), "precondition: nothing clicked yet");

        assertTrue(clickRule(panel, "R7"), "R7 is rendered and clickable");
        String r7 = "R7 — A vessel fits a berth only if length, draft, beam, fenders and cargo handling all fit.";
        assertEquals(r7, ruleLine(panel), "the id, a dash, and the catalogue's one-line text");
        assertEquals("compatible/2 · rules_compatibility.pl", tooltipOf(line),
                "the tooltip names the predicate and its kernel file");

        // It is not a tooltip: more traffic, another view and Raw all leave it standing.
        publish(bus, dayNotice(2_000L));
        click(panel.presetToggleForTest(CommLogPreset.RULES));
        click(panel.rawToggleForTest());
        assertEquals(r7, ruleLine(panel), "the rule stays until another id is clicked");

        RuleCatalogue.Rule r25 = RuleCatalogue.get().find("R25").orElseThrow();
        assertTrue(clickRule(panel, "R25"), "R25 is rendered (inside the Raw content) and clickable");
        assertEquals("R25 — " + r25.text(), ruleLine(panel), "the next click replaces it");
        assertEquals(r25.predicate() + " · " + r25.module() + ".pl", tooltipOf(line));
    }

    @Test
    @Timeout(5)
    void anIdTheCatalogueDoesNotKnowIsNotClickable() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        CommLogEvent mixed = new CommLogEvent(0L, HM, List.of("vessel_c501"), ACLMessage.REFUSE,
                "Refusing (no berth fits [R7,R31])", "berth-vessel_c501");
        CommLogEvent unknownOnly = new CommLogEvent(1_000L, HM, List.of("vessel_c501"), ACLMessage.REFUSE,
                "Refusing (made up [R31])", "berth-vessel_c501");
        CommLogEvent lookalike = new CommLogEvent(2_000L, "vessel_walkin_r7", List.of(HM), ACLMessage.INFORM,
                "Notifying: WALKIN-R7 [WALKIN-R7] departed", "nego-WALKIN-R7");
        publish(bus, mixed, unknownOnly, lookalike);

        String text = text(panel);
        List<RuleRefs.Ref> spans = spans(panel);
        assertEquals(1, spans.size(), "only the R7 of [R7,R31] is a link: there is no rule 31, "
                + "and a vessel id that looks like a rule id is not a citation — " + spans);
        assertEquals("R7", spans.get(0).ruleId());
        assertEquals(text.indexOf("[R7,R31]") + 1, spans.get(0).start());

        List<Glyph> glyphs = glyphs(panel);
        assertEquals(2, underlinedCount(glyphs), "R7's two characters and nothing else");
        assertFalse(glyphs.get(text.indexOf("R31")).underlined(), "R31 next to a real id stays plain");
        assertFalse(glyphs.get(text.lastIndexOf("R31")).underlined(), "R31 on its own stays plain");

        assertFalse(clickRule(panel, "R31"), "there is nothing to click for R31");
        assertEquals(RULE_LINE_PROMPT, ruleLine(panel), "and the rule line is left alone");
        assertTrue(clickRule(panel, "R7"), "positive control: the real id beside it is clickable");
        assertTrue(ruleLine(panel).startsWith("R7 — "), ruleLine(panel));

        click(panel.presetToggleForTest(CommLogPreset.RULES));
        assertEquals(plainRows(mixed), text(panel), "a row citing only an unknown id is not a rule row either");
    }

    @Test
    @Timeout(5)
    void ruleIdsInsideRawContentAreLinksToo() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        publish(bus, berthGrant(0L));

        click(panel.rawToggleForTest());
        String raw = text(panel);
        List<RuleRefs.Ref> spans = spans(panel);
        assertEquals(1, spans.size(),
                "Raw drops the paraphrase, so the id is found once — inside the content: " + spans);
        RuleRefs.Ref span = spans.get(0);
        assertEquals("R7", raw.substring(span.start(), span.end()), "the offsets are Raw-block offsets");
        assertEquals(raw.indexOf("\"reason\": \"compatible [R7]\"") + "\"reason\": \"compatible [".length(),
                span.start(), "it is the id inside the content's reason");

        List<Glyph> glyphs = glyphs(panel);
        assertTrue(glyphs.get(span.start()).underlined() && glyphs.get(span.end() - 1).underlined(),
                "underlined in Raw as in the plain line");
        assertEquals(2, underlinedCount(glyphs), "the [00:00:00] time stamp of the block is not a citation");

        assertTrue(clickRule(panel, "R7"), "and it is clickable");
        assertTrue(ruleLine(panel).startsWith("R7 — "), ruleLine(panel));
    }

    /**
     * The hit test behind a mouse press and the hand cursor. A text pane answers "which position
     * is NEAREST this point", so without the confirmation against where the id is painted, a press
     * on the bracket beside an id would count as a press on the id. The pane is given a size (wide
     * enough that the row does not wrap) so that it has geometry; no window is involved.
     */
    @Test
    @Timeout(5)
    void aPressCountsOnlyWhereTheRuleIdIsDrawn() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        publish(bus, berthGrant(0L));
        JTextPane pane = named(panel, "commlog-text", JTextPane.class);
        RuleRefs.Ref span = spans(panel).get(0);

        Optional<RuleRefs.Ref> beforeLayout = onEdt(() -> panel.ruleSpanAtForTest(new Point2D.Double(5, 5)));
        assertTrue(beforeLayout.isEmpty(), "a pane with no size has nothing under any point");

        List<Optional<RuleRefs.Ref>> hits = onEdt(() -> {
            pane.setSize(2000, 400);
            Rectangle2D first = drawnAt(pane, span.start());
            Rectangle2D last = drawnAt(pane, span.end());
            double middle = (first.getMinX() + last.getMinX()) / 2;
            double row = first.getCenterY();
            return List.of(
                    panel.ruleSpanAtForTest(new Point2D.Double(middle, row)),
                    panel.ruleSpanAtForTest(new Point2D.Double(first.getMinX() - 1, row)),
                    panel.ruleSpanAtForTest(new Point2D.Double(last.getMinX() + 1, row)),
                    panel.ruleSpanAtForTest(new Point2D.Double(middle, first.getMaxY() + 200)),
                    panel.ruleSpanAtForTest(new Point2D.Double(1900, row)));
        });
        assertEquals(Optional.of(span), hits.get(0), "on the id: a hit");
        assertTrue(hits.get(1).isEmpty(), "one pixel left of the id is the opening bracket, not the id");
        assertTrue(hits.get(2).isEmpty(), "one pixel right of the id is the closing bracket, not the id");
        assertTrue(hits.get(3).isEmpty(), "below the last row is not the id");
        assertTrue(hits.get(4).isEmpty(), "the blank area after the end of the line is not the id");
    }

    // ===================================================================== S15: "This vessel"

    @Test
    @Timeout(5)
    void aFocusChangeRerendersOnlyUnderThisVessel() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        CommLogEvent grant = berthGrant(0L);
        CommLogEvent offer = walkInOffer(1_000L);
        CommLogEvent notice = dayNotice(2_000L);
        JToggleButton thisVessel = panel.presetToggleForTest(CommLogPreset.THIS_VESSEL);
        // "Scrolled up" before every arrival: an arriving row then renders WITHOUT pinning, so a
        // render caused by a focus change (a view change, which always pins) stands out in the
        // flag. Set again after each view change — that takes the view back to the tail, and the
        // log follows it from there.
        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, grant, offer, notice);
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: arrivals do not pin while scrolled up");

        // Under All the vessel is remembered and named in the tooltip — and nothing is re-rendered.
        runOnEdt(() -> panel.setFocusVessel("WALKIN-3"));
        assertEquals("WALKIN-3", panel.model().focusVesselId(), "the model knows the vessel under any view");
        assertEquals("Messages about WALKIN-3 — the selected chat tab", tooltipOf(thisVessel));
        assertEquals(plainRows(grant, offer, notice), text(panel), "All still shows every row");
        assertFalse(panel.lastRenderAutoScrolledForTest(), "no render ran: All does not depend on the vessel");

        click(thisVessel);
        assertEquals(plainRows(offer), text(panel), "This vessel: WALKIN-3's rows only");

        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, dayNotice(3_000L));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: the flag is down again after an arrival");
        assertEquals(plainRows(offer), text(panel), "a row about no vessel does not show under This vessel");

        runOnEdt(() -> panel.setFocusVessel("C501"));
        assertEquals(plainRows(grant), text(panel), "the other tab's vessel: the log follows it");
        assertTrue(panel.lastRenderAutoScrolledForTest(), "under This vessel a focus change re-renders");

        // The chat reports its selection on every tab event; an unchanged vessel must cost nothing.
        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, dayNotice(4_000L));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: the flag is down again");
        runOnEdt(() -> panel.setFocusVessel("C501"));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "the same vessel again: no render");
        assertEquals(plainRows(grant), text(panel));
    }

    @Test
    @Timeout(5)
    void thisVesselWithNoChatTabSelectedSaysSoInsteadOfShowingNothing() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        CommLogEvent grant = berthGrant(0L);
        publish(bus, grant);

        click(panel.presetToggleForTest(CommLogPreset.THIS_VESSEL));
        String noTab = text(panel);
        assertTrue(noTab.startsWith("No chat tab is selected"), "the no-tab hint is shown: " + noTab);
        assertEquals(panel.model().emptyStateHint(), noTab, "it is the model's hint, as one line");
        assertFalse(noTab.contains("\n"), "one line");
        assertTrue(spans(panel).isEmpty(), "a hint has nothing to click");
        for (Glyph glyph : glyphs(panel)) {
            assertTrue(isGrey(glyph.colour()), "the hint is grey: " + glyph.colour());
        }

        runOnEdt(() -> panel.setFocusVessel("WALKIN-3"));
        assertEquals("No messages about WALKIN-3 in the log yet.", text(panel),
                "a selected tab with no traffic yet names the vessel");

        runOnEdt(() -> panel.setFocusVessel("C501"));
        assertEquals(plainRows(grant), text(panel), "a vessel with traffic shows its rows, not a hint");

        runOnEdt(() -> panel.setFocusVessel(null));
        assertEquals(noTab, text(panel), "the tab closed: back to the no-tab hint");
    }

    /**
     * Run on an EMPTY log on purpose: {@code clear()} then archives nothing, so the fast lane does
     * not write to the real {@code logs/} folder ({@code CommLogModelPresetTest} owns
     * clear-with-rows, with a temp-dir archiver).
     */
    @Test
    @Timeout(5)
    void aLoadedGameDropsTheFocusVessel() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        JToggleButton thisVessel = panel.presetToggleForTest(CommLogPreset.THIS_VESSEL);
        runOnEdt(() -> panel.setFocusVessel("WALKIN-3"));
        click(thisVessel);
        assertEquals("No messages about WALKIN-3 in the log yet.", text(panel), "precondition: following WALKIN-3");
        assertEquals("Messages about WALKIN-3 — the selected chat tab", tooltipOf(thisVessel));

        bus.publish(new GameLoadedEvent(1, 44_800.0, 62));
        SwingUtilities.invokeAndWait(() -> { });

        assertNull(panel.model().focusVesselId(), "the vessel belonged to the world that was replaced");
        assertTrue(text(panel).startsWith("No chat tab is selected"), "so the log says no tab is selected");
        assertEquals("Messages about the vessel whose chat tab is selected", tooltipOf(thisVessel),
                "and the toggle no longer names a vessel");
        assertEquals(CommLogPreset.THIS_VESSEL, panel.model().preset(), "the chosen view itself survives a load");
        assertTrue(thisVessel.isSelected());
        assertEquals(0, panel.model().size());
    }

    // ================================================================ S15: scrolling and focus

    @Test
    @Timeout(5)
    void aViewChangePinsToTheTailEvenWhenScrolledUp() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        publish(bus, berthGrant(0L), routineWeatherCheck(1_000L));

        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, dayNotice(2_000L));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: scrolled up, an arriving row does not pin");
        assertTrue(panel.resumeButtonForTest().isVisible(), "precondition: scrolled up, resume is offered");

        click(panel.presetToggleForTest(CommLogPreset.RULES));
        assertTrue(panel.lastRenderAutoScrolledForTest(), "another preset goes to the tail");
        assertFalse(panel.resumeButtonForTest().isVisible(), "at the tail there is nothing to resume");

        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, weatherAlert(3_000L));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: scrolled up again");
        assertTrue(panel.resumeButtonForTest().isVisible());
        click(panel.rawToggleForTest());
        assertTrue(panel.lastRenderAutoScrolledForTest(), "Raw goes to the tail: a row is four lines now");
        assertFalse(panel.resumeButtonForTest().isVisible());

        click(panel.presetToggleForTest(CommLogPreset.THIS_VESSEL));
        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, dayNotice(4_000L));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: scrolled up again");
        runOnEdt(() -> panel.setFocusVessel("C501"));
        assertTrue(panel.lastRenderAutoScrolledForTest(), "another vessel under This vessel goes to the tail");
        assertFalse(panel.resumeButtonForTest().isVisible());

        // A second click on the view that is already showing is not a view change.
        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, dayNotice(5_000L));
        click(panel.presetToggleForTest(CommLogPreset.THIS_VESSEL));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "re-clicking the current view does not move the reader");
        assertTrue(panel.presetToggleForTest(CommLogPreset.THIS_VESSEL).isSelected(), "and it stays selected");
    }

    // ------------------------------------------------------------------ the scrollbar for real

    /**
     * Lays the panel out at this size, as a window of that size would, and lets it react: a
     * layout moves the scrollbar, and what the panel does about that (a jump to the tail, a
     * button shown or hidden) runs one EDT turn later — and may change the layout once more.
     */
    private static void settle(CommLogPanel panel, int width, int height) throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            runOnEdt(() -> HeadlessSwing.layOut(panel, width, height));
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static JScrollBar barOf(CommLogPanel panel) throws Exception {
        return onEdt(() -> panel.scrollPaneForTest().getVerticalScrollBar());
    }

    private static void assertAtTail(CommLogPanel panel, String when) throws Exception {
        JScrollBar bar = barOf(panel);
        runOnEdt(() -> {
            assertTrue(bar.getMaximum() > bar.getVisibleAmount(), when + ": precondition, more rows than fit");
            assertEquals(bar.getMaximum(), bar.getValue() + bar.getVisibleAmount(),
                    when + ": the last row is in view");
            assertTrue(panel.followingTailForTest(), when + ": still following the tail");
            assertFalse(panel.resumeButtonForTest().isVisible(), when + ": nothing to resume");
        });
    }

    /**
     * Review finding (S15): "at the tail" used to be read off the scrollbar every time, so a
     * narrower column (the text re-wraps and the document grows) or a shorter one left the bar
     * short of its maximum and the log silently stopped following — nobody had scrolled.
     */
    @Test
    @Timeout(20)
    void aLayoutChangeNeverStopsTheLogFollowingTheTail() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        settle(panel, 380, 300);
        for (int i = 0; i < 60; i++) {
            publish(bus, queueGrant(i * 1_000L)); // a long row: it wraps, so its height depends on the width
        }
        settle(panel, 380, 300);
        assertAtTail(panel, "after 60 rows");
        int maximumAt380 = onEdt(() -> maximumOf(panel));

        settle(panel, 300, 300);
        assertTrue(onEdt(() -> maximumOf(panel)) > maximumAt380,
                "precondition: the narrower column re-wrapped the text into a longer document");
        assertAtTail(panel, "after the column got narrower");

        settle(panel, 300, 180);
        assertAtTail(panel, "after the column got shorter");

        publish(bus, queueGrant(99_000L));
        assertTrue(panel.lastRenderAutoScrolledForTest(), "the next row is followed");
        settle(panel, 300, 180);
        assertAtTail(panel, "after one more row");

        // Only the user's own scrolling ends it — and brings up the way back.
        JScrollBar bar = barOf(panel);
        runOnEdt(() -> bar.setValue(bar.getValue() / 2));
        assertFalse(panel.followingTailForTest(), "the user scrolled up: no longer following");
        assertTrue(panel.resumeButtonForTest().isVisible(), "and resume is offered");
        settle(panel, 300, 180);
        assertFalse(panel.followingTailForTest(), "a layout pass does not take the reader back down");

        click(panel.resumeButtonForTest());
        settle(panel, 300, 180);
        assertAtTail(panel, "after resume");
    }

    /**
     * Fix-check finding (S15): a wheel notch that is already in the event queue when a message
     * is rendered is dispatched BEFORE that render's jump to the tail. The listener used to
     * ignore everything while a jump was pending, and the jump then undid the scroll. The user's
     * own scrolling wins, also there.
     */
    @Test
    @Timeout(20)
    void aWheelNotchLandingBetweenAMessageAndItsJumpIsNotThrownAway() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        settle(panel, 380, 300);
        for (int i = 0; i < 60; i++) {
            publish(bus, queueGrant(i * 1_000L));
        }
        settle(panel, 380, 300);
        assertAtTail(panel, "after 60 rows");
        JScrollPane scroll = onEdt(panel::scrollPaneForTest);
        JScrollBar bar = barOf(panel);
        int tailValue = onEdt(bar::getValue);

        // One EDT turn queues the message's delivery and, right behind it, a wheel notch up: the
        // render runs, queues its jump, and the notch is dispatched before that jump.
        runOnEdt(() -> {
            bus.publish(queueGrant(99_000L));
            SwingUtilities.invokeLater(() -> scroll.dispatchEvent(new MouseWheelEvent(scroll,
                    MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 50, 50, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1)));
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(onEdt(bar::getValue) < tailValue, "precondition: the wheel moved the view up");
        assertFalse(panel.followingTailForTest(), "the user scrolled up: the pending jump is called off");
        assertTrue(panel.resumeButtonForTest().isVisible(), "and resume is offered");
        settle(panel, 380, 300);
        assertFalse(panel.followingTailForTest(), "and the layout catching up does not take the reader back down");
        JScrollBar after = barOf(panel);
        assertTrue(onEdt(() -> after.getValue() + after.getVisibleAmount() < after.getMaximum()),
                "the view is still above the tail");
    }

    private static int maximumOf(CommLogPanel panel) {
        return panel.scrollPaneForTest().getVerticalScrollBar().getMaximum();
    }

    /**
     * Review finding (S15): once the log holds its 500 rows every arriving message drops the
     * oldest one. A scrolled-up view kept its pixel offset, so the rows being read slid up by one
     * row per message. It now stays on the ROW. (The model is given a temp-dir archiver: dropped
     * rows are archived, and the production one writes under {@code logs/}.)
     */
    @Test
    @Timeout(30)
    void aScrolledUpViewStaysOnItsRowWhileTheFullLogDropsItsOldestRows(@TempDir Path archiveDir) throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = onEdt(() -> new CommLogPanel(bus, new CommLogModel(new LogArchiver(archiveDir))));
        settle(panel, 380, 300);
        // Up to one short of the cap straight into the model (no render per row), then one
        // published row that renders them all.
        runOnEdt(() -> {
            for (int i = 0; i < CommLogModel.MAX_ENTRIES - 1; i++) {
                panel.model().add(event(i * 1_000L, "row " + i));
            }
        });
        publish(bus, event(499_000L, "row 499"));
        settle(panel, 380, 300);
        assertEquals(CommLogModel.MAX_ENTRIES, panel.model().size(), "precondition: the log is full");
        assertAtTail(panel, "a full log");

        // The user scrolls up to somewhere in the middle, a few pixels into a row.
        JScrollBar bar = barOf(panel);
        runOnEdt(() -> bar.setValue(bar.getMaximum() / 2 + 5));
        settle(panel, 380, 300);
        assertFalse(panel.followingTailForTest(), "precondition: scrolled up");
        CommLogEvent reading = onEdt(panel::topRowForTest);
        int valueBefore = onEdt(bar::getValue);
        assertTrue(reading != null && reading.paraphrase().startsWith("row "), "precondition: a row is at the top");

        // Five more messages: each drops the oldest row, so everything above the reader gets
        // shorter by one row five times.
        for (int i = 0; i < 5; i++) {
            publish(bus, event(500_000L + i * 1_000L, "late " + i));
            settle(panel, 380, 300);
        }
        assertEquals(CommLogModel.MAX_ENTRIES, panel.model().size(), "still full: five rows were dropped");
        assertFalse(panel.lastRenderAutoScrolledForTest(), "arrivals did not pin: the user is scrolled up");
        assertSame(reading, onEdt(panel::topRowForTest), "the row being read is still the one at the top");
        assertTrue(onEdt(bar::getValue) < valueBefore,
                "and to keep it there the view moved up with it (five rows above it are gone)");
        assertFalse(panel.followingTailForTest(), "still scrolled up");
        assertTrue(panel.resumeButtonForTest().isVisible(), "resume still offered");
    }

    @Test
    @Timeout(5)
    void nothingInTheColumnCanTakeTheKeyboardFocusAndTheOldFilterWidgetsAreGone() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        // Rendered rows, Raw and a visible resume button: the state must not matter.
        runOnEdt(() -> panel.setFollowingTailForTest(false));
        publish(bus, berthGrant(0L), dayNotice(1_000L));
        click(panel.rawToggleForTest());

        List<Component> all = everythingIn(panel);
        for (Component component : all) {
            assertFalse(component.isFocusable(),
                    describe(component) + " must not be focusable — a click here must leave the typing in the chat");
            assertFalse(component instanceof JComboBox<?>, describe(component) + ": the filter combos are gone");
            assertFalse(component instanceof JTextField, describe(component) + ": the From/To fields are gone");
        }
        // Positive control: the walk did reach the controls, the text and the scroll pane's parts.
        for (CommLogPreset preset : CommLogPreset.values()) {
            assertTrue(all.contains(panel.presetToggleForTest(preset)), preset + " toggle is in the panel");
        }
        assertTrue(all.contains(panel.rawToggleForTest()));
        assertTrue(all.contains(panel.resumeButtonForTest()));
        JTextPane pane = named(panel, "commlog-text", JTextPane.class);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, pane);
        assertTrue(all.contains(scroll.getVerticalScrollBar()), "the scrollbar was checked too");
    }

    @Test
    @Timeout(5)
    void theControlsCarryTheAgreedNamesLabelsAndTooltips() throws Exception {
        CommLogPanel panel = newPanel(new EventBus());

        JToggleButton all = named(panel, "commlog-preset-all", JToggleButton.class);
        JToggleButton thisVessel = named(panel, "commlog-preset-this-vessel", JToggleButton.class);
        JToggleButton rules = named(panel, "commlog-preset-rules", JToggleButton.class);
        JToggleButton weather = named(panel, "commlog-preset-weather", JToggleButton.class);
        JCheckBox raw = named(panel, "commlog-raw", JCheckBox.class);
        JTextPane pane = named(panel, "commlog-text", JTextPane.class);
        JTextArea line = named(panel, "commlog-rule-line", JTextArea.class);
        JButton resume = named(panel, "commlog-resume", JButton.class);

        assertSame(panel.presetToggleForTest(CommLogPreset.ALL), all);
        assertSame(panel.presetToggleForTest(CommLogPreset.THIS_VESSEL), thisVessel);
        assertSame(panel.presetToggleForTest(CommLogPreset.RULES), rules);
        assertSame(panel.presetToggleForTest(CommLogPreset.WEATHER), weather);
        assertSame(panel.rawToggleForTest(), raw);
        assertSame(panel.resumeButtonForTest(), resume);

        assertEquals("All", all.getText());
        assertEquals("This vessel", thisVessel.getText());
        assertEquals("Rules", rules.getText());
        assertEquals("Weather", weather.getText());
        assertEquals("Raw", raw.getText());
        assertFalse(raw.isSelected(), "the plain-English line is the default");

        assertEquals(CommLogPanel.TIP_ALL, all.getToolTipText());
        assertEquals("Messages about the vessel whose chat tab is selected", thisVessel.getToolTipText());
        assertEquals("Messages that cite a Prolog rule, e.g. [R7]", rules.getToolTipText());
        assertEquals("Weather alerts, routine checks, and the holds, resumes and tug cancels they cause",
                weather.getToolTipText());
        assertEquals(CommLogPanel.TIP_RAW, raw.getToolTipText());

        assertFalse(pane.isEditable(), "the log is read-only");
        assertFalse(line.isEditable(), "so is the rule line");
        assertEquals(2, line.getRows(), "the rule line is two rows, whatever it says");
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, pane);
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy(),
                "the text wraps to the column; there is no horizontal scrollbar");
    }

    // =============== S18b: All leaves the repeats out (PT9), and the log can be copied (PT11)

    /** Playtest 2, PT9: "Comm log flooded by routine weather check INFORMs (every 15 game min)." */
    @Test
    @Timeout(5)
    void allSaysHowManyUnchangedWeatherChecksItLeavesOut() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        publish(bus, routineWeatherCheck(0L), routineWeatherCheck(900_000L), routineWeatherCheck(1_800_000L));

        assertEquals("2 weather checks with no change are not shown — Weather has them.",
                onEdt(panel::hiddenNoteForTest));
        assertEquals(1, text(panel).lines().count(), "one row: the first check");

        click(named(panel, CommLogPanel.NAME_PRESET_WEATHER, javax.swing.JToggleButton.class));
        assertEquals("", onEdt(panel::hiddenNoteForTest), "Weather shows every check: nothing to say");
        assertEquals("1 weather check with no change is not shown — Weather has it.",
                CommLogPanel.hiddenChecksText(1));
    }

    /** UI tree U16: "'All' tooltip says 'Every message' but only HarbourMaster traffic is logged";
     * "Raw view promises 'real FIPA fields' but lacks priority / reply-with / in-reply-to". */
    @Test
    void theTooltipsSayWhatIsReallyThere() {
        assertTrue(CommLogPanel.TIP_ALL.startsWith("All the HarbourMaster's messages"), CommLogPanel.TIP_ALL);
        assertTrue(CommLogPanel.TIP_ALL.contains("under Weather"), "and where the hidden checks are");
        assertTrue(CommLogPanel.TIP_RAW.contains("main FIPA fields"), CommLogPanel.TIP_RAW);
        assertFalse(CommLogPanel.TIP_RAW.contains("real FIPA fields"));
    }

    /** Playtest 2, PT11: "The comm log cannot be copied." Copy all copies the log as it is shown. */
    @Test
    @Timeout(5)
    void copyAllIsTheLogAsItIsShown() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = newPanel(bus);
        publish(bus, event(0L, "first"), event(1_000L, "second"));

        assertEquals(text(panel), onEdt(panel::allText));
        assertTrue(onEdt(panel::allText).contains("\"second\""), onEdt(panel::allText));
        assertTrue(onEdt(() -> named(panel, CommLogPanel.NAME_TEXT, javax.swing.JTextPane.class)
                .getCaret().isSelectionVisible()), "a selection shows although the log never takes the focus");
    }
}
