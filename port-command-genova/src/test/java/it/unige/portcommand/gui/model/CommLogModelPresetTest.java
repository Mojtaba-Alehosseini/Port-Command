package it.unige.portcommand.gui.model;

import java.nio.file.Path;
import java.util.List;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.gui.LogArchiver;
import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the comm log's four presets — All, This vessel, Rules, Weather — and the
 * conversation index that lets "This vessel" follow a tug tender.
 *
 * <p>{@code CommLogModelTest} keeps covering the model's older behaviour unchanged; this class
 * only adds. Each row's paraphrase doubles as its label, so an assertion reads as the list of
 * rows the player would see. Cases that overflow the 500-row window or call {@code clear()} use
 * a temp-dir archiver, so the fast lane never writes to the real {@code logs/} folder.
 */
class CommLogModelPresetTest {

    private static final String HM = "harbour_master";
    private static final String WEATHER = "weather_agent";
    private static final String CNP_PROTOCOL = "fipa-contract-net";
    private static final String WEATHER_ALERT = "port-command-weather-alert";
    private static final String VESSEL_WEATHER = "port-command-vessel-weather";

    private static CommLogEvent row(String label, String sender, List<String> receivers, int performative,
                                    String conversationId, String protocol, String content) {
        return new CommLogEvent(0L, sender, receivers, performative, label, conversationId,
                MessageFactory.ONTOLOGY, protocol, content);
    }

    private static List<String> labels(CommLogModel model) {
        return model.filteredEntries().stream().map(CommLogEvent::paraphrase).toList();
    }

    // ---- weather: the six kinds ----------------------------------------------------------------

    private static CommLogEvent periodic() {
        return row("routine check", WEATHER, List.of(HM), ACLMessage.INFORM, null, WEATHER_ALERT,
                "{\"event\":\"weather_periodic\",\"wind_knots\":18,\"visibility\":\"good\",\"swell\":0.5,"
                        + "\"state\":\"sunny\",\"sim_time\":900000}");
    }

    private static CommLogEvent thresholdAlert() {
        return row("alert", WEATHER, List.of(HM), ACLMessage.INFORM, null, WEATHER_ALERT,
                "{\"event\":\"weather_threshold\",\"reason\":\"threshold_crossed [R18]\",\"wind_knots\":36,"
                        + "\"visibility\":\"poor\",\"swell\":2.5,\"state\":\"stormy\"}");
    }

    private static CommLogEvent clearAlert() {
        return row("clearing", WEATHER, List.of(HM), ACLMessage.INFORM, null, WEATHER_ALERT,
                "{\"event\":\"weather_clear\",\"reason\":\"threshold_cleared [R18]\",\"wind_knots\":22,"
                        + "\"visibility\":\"good\",\"swell\":1.0,\"state\":\"cloudy\"}");
    }

    private static CommLogEvent hold(String vesselLocalName) {
        return row("hold", HM, List.of(vesselLocalName), ACLMessage.INFORM, null, VESSEL_WEATHER,
                "{\"event\":\"weather_hold\",\"wind_knots\":36.0,\"swell\":2.5,\"state\":\"stormy\"}");
    }

    private static CommLogEvent resume(String vesselLocalName, String vesselId) {
        return row("resume", HM, List.of(vesselLocalName), ACLMessage.INFORM, null, VESSEL_WEATHER,
                "{\"event\":\"weather_resume\",\"vessel_id\":\"" + vesselId + "\",\"wind_knots\":22.0,"
                        + "\"state\":\"cloudy\"}");
    }

    /** No protocol and a tug as receiver: only the content says both "weather" and which vessel. */
    private static CommLogEvent tugCancel(String vesselId) {
        return row("tug cancel", HM, List.of("tug_1"), ACLMessage.CANCEL, null, null,
                "{\"reason\":\"weather_hold\",\"vessel_id\":\"" + vesselId + "\"}");
    }

    // ---- a tug tender: one cnp-<UUID> conversation ---------------------------------------------

    private static CommLogEvent cfp(String label, String conversationId, String vesselId) {
        return row(label, HM, List.of("tug_1", "tug_2", "tug_3", "tug_4"), ACLMessage.CFP, conversationId,
                CNP_PROTOCOL, "{\"target_vessel_id\":\"" + vesselId + "\","
                        + "\"target_vessel_position\":{\"x\":120.0,\"y\":40.0},\"reply_by\":30000}");
    }

    /** A bid names no vessel anywhere: cost, ETA, fuel, position. */
    private static CommLogEvent bid(String label, String tug, String conversationId) {
        return row(label, tug, List.of(HM), ACLMessage.PROPOSE, conversationId, CNP_PROTOCOL,
                "{\"cost\":350.0,\"eta_minutes\":4.0,\"fuel_state\":0.9,\"position\":{\"x\":10.0,\"y\":5.0}}");
    }

    /** A tug declining the call for bids: a reason, no vessel. */
    private static CommLogEvent cfpRefusal(String label, String tug, String conversationId) {
        return row(label, tug, List.of(HM), ACLMessage.REFUSE, conversationId, CNP_PROTOCOL,
                "{\"reason\":\"busy\"}");
    }

    private static CommLogEvent award(String label, String tug, String conversationId, String vesselId) {
        return row(label, HM, List.of(tug), ACLMessage.ACCEPT_PROPOSAL, conversationId, CNP_PROTOCOL,
                "{\"vessel_id\":\"" + vesselId + "\",\"berth_position\":{\"x\":200.0,\"y\":80.0}}");
    }

    /** The loser's REJECT has no content at all. */
    private static CommLogEvent reject(String label, String tug, String conversationId) {
        return row(label, HM, List.of(tug), ACLMessage.REJECT_PROPOSAL, conversationId, CNP_PROTOCOL, null);
    }

    // ---- other traffic -------------------------------------------------------------------------

    private static CommLogEvent grant(String label, String vesselLocalName, String reason) {
        return row(label, HM, List.of(vesselLocalName), ACLMessage.ACCEPT_PROPOSAL, "berth-" + vesselLocalName, null,
                "{\"berth_id\":\"berth_1\",\"price\":5200.0,\"hours\":8,\"reason\":\"" + reason + "\"}");
    }

    /** A row about nothing in particular: no vessel, no weather, no rule, no conversation. */
    private static CommLogEvent filler(String label) {
        return row(label, HM, List.of("assistant_agent"), ACLMessage.INFORM, null, null, "{\"notice\":\"tick\"}");
    }

    // =========================================================================== ALL

    @Test
    void thePresetIsAllUntilSomeoneChoosesAnother() {
        assertEquals(CommLogPreset.ALL, new CommLogModel().preset());
        assertNull(new CommLogModel().focusVesselId());
    }

    @Test
    void allShowsEveryRowTheRoutineWeatherCheckIncluded() {
        CommLogModel model = new CommLogModel();
        model.add(periodic());
        model.add(thresholdAlert());
        model.add(clearAlert());
        model.add(hold("vessel_c501"));
        model.add(resume("vessel_c501", "C501"));
        model.add(tugCancel("C501"));
        model.add(grant("grant", "vessel_c501", "compatible [R7]"));
        model.add(cfp("cfp", "cnp-a", "C501"));
        model.add(bid("bid", "tug_1", "cnp-a"));
        model.add(filler("filler"));

        // DECISIONS 7: "All" is literally all rows — the log has to match the end-of-day tally,
        // which counts the routine checks too.
        assertEquals(List.of("routine check", "alert", "clearing", "hold", "resume", "tug cancel", "grant",
                "cfp", "bid", "filler"), labels(model));
        assertEquals(model.size(), model.filteredEntries().size());
    }

    // =========================================================================== WEATHER

    @Test
    void weatherShowsAllSixKindsAndNothingElse() {
        CommLogModel model = new CommLogModel();
        model.add(grant("grant", "vessel_c501", "compatible [R7]"));
        model.add(periodic());
        model.add(thresholdAlert());
        model.add(cfp("cfp", "cnp-a", "C501"));
        model.add(hold("vessel_c501"));
        model.add(tugCancel("C501"));
        model.add(bid("bid", "tug_1", "cnp-a"));
        model.add(clearAlert());
        model.add(resume("vessel_c501", "C501"));
        // Looks like weather, is not: a hold because no tug bid.
        model.add(row("tug hold", HM, List.of("vessel_c501"), ACLMessage.INFORM, null, null,
                "{\"event\":\"tug_hold\",\"reason\":\"no_bids\"}"));

        model.setPreset(CommLogPreset.WEATHER);

        assertEquals(List.of("routine check", "alert", "hold", "tug cancel", "clearing", "resume"), labels(model),
                "the six kinds, in arrival order — the HarbourMaster's holds and the tug cancel included");
    }

    // =========================================================================== RULES

    @Test
    void rulesShowsTheRowsThatCiteAKnownRule() {
        CommLogModel model = new CommLogModel();
        model.add(grant("grant R7", "vessel_c501", "compatible [R7]"));
        model.add(periodic());
        model.add(thresholdAlert()); // [R18] in its content, nothing in its paraphrase
        model.add(grant("grant R31", "vessel_c502", "made_up [R31]")); // there is no rule 31
        model.add(new CommLogEvent(0L, HM, List.of("vessel_w040"), ACLMessage.ACCEPT_PROPOSAL,
                "Granted berth berth_2 (after queue scheduled ferry rank 3 [R25,R26,R27,R30])", "berth-vessel_w040"));
        model.add(filler("filler"));

        model.setPreset(CommLogPreset.RULES);

        assertEquals(List.of("grant R7", "alert",
                        "Granted berth berth_2 (after queue scheduled ferry rank 3 [R25,R26,R27,R30])"),
                labels(model), "content or paraphrase — either citation makes a rule row");
    }

    // =========================================================================== THIS_VESSEL

    @Test
    void thisVesselShowsTheRowsThatNameTheVesselThemselves() {
        CommLogModel model = new CommLogModel();
        // WALKIN-3's own chain: every way a row can name its vessel without the index.
        model.add(row("offer", "vessel_walkin_3", List.of(HM), ACLMessage.PROPOSE, "nego-WALKIN-3", null,
                "{\"intent\":\"opening_offer\",\"price\":1436.0,\"hours\":6,"
                        + "\"vessel_spec\":{\"vessel_id\":\"WALKIN-3\"}}"));
        model.add(row("counter", HM, List.of("vessel_walkin_3"), ACLMessage.PROPOSE, "nego-WALKIN-3", null,
                "{\"price\":1900.0,\"hours\":6}"));
        model.add(row("terminal request", HM, List.of("terminal_general"), ACLMessage.REQUEST, "term-WALKIN-3",
                "port-command-terminal-berth", "{\"vessel_id\":\"WALKIN-3\",\"vessel_type\":\"cargo_vessel\"}"));
        model.add(row("terminal confirm", "terminal_general", List.of(HM), ACLMessage.CONFIRM, "term-WALKIN-3",
                "port-command-terminal-berth", "{\"berth_id\":\"berth_3\"}"));
        model.add(row("customs confirm", "customs_agent", List.of(HM), ACLMessage.CONFIRM, "cust-WALKIN-3",
                "port-command-customs-clearance", "{\"reason\":\"no_inspection_required [R22,R23]\"}"));
        model.add(grant("grant", "vessel_walkin_3", "compatible [R8]"));
        model.add(tugCancel("WALKIN-3"));
        model.add(row("handling complete", "terminal_general", List.of(HM), ACLMessage.INFORM, null, null,
                "{\"notice\":\"handling_complete\",\"vessel\":\"WALKIN-3\",\"berth\":\"berth_3\"}"));
        // Somebody else's rows, and the port's.
        model.add(grant("other grant", "vessel_c501", "compatible [R7]"));
        model.add(row("other customs", "customs_agent", List.of(HM), ACLMessage.CONFIRM, "cust-C501",
                "port-command-customs-clearance", "{\"reason\":\"inspected [R23]\"}"));
        model.add(periodic());
        model.add(filler("filler"));

        model.setPreset(CommLogPreset.THIS_VESSEL);
        model.setFocusVessel("WALKIN-3");

        assertEquals(List.of("offer", "counter", "terminal request", "terminal confirm", "customs confirm", "grant",
                "tug cancel", "handling complete"), labels(model));
    }

    @Test
    void thisVesselFollowsATugTenderThroughTheConversationIndex() {
        CommLogModel model = new CommLogModel();
        model.add(cfp("cfp", "cnp-a", "WALKIN-3"));
        model.add(bid("bid", "tug_1", "cnp-a"));
        model.add(cfpRefusal("refusal", "tug_2", "cnp-a"));
        model.add(bid("losing bid", "tug_3", "cnp-a"));
        model.add(award("award", "tug_1", "cnp-a", "WALKIN-3"));
        model.add(reject("reject", "tug_3", "cnp-a"));
        // A second tender, for another vessel, interleaved in time.
        model.add(cfp("other cfp", "cnp-b", "C501"));
        model.add(bid("other bid", "tug_4", "cnp-b"));
        model.add(reject("other reject", "tug_4", "cnp-b"));

        model.setPreset(CommLogPreset.THIS_VESSEL);
        model.setFocusVessel("WALKIN-3");

        assertEquals(List.of("cfp", "bid", "refusal", "losing bid", "award", "reject"), labels(model),
                "the bid, the refusal and the contentless REJECT name no vessel; the conversation does");

        model.setFocusVessel("C501");

        assertEquals(List.of("other cfp", "other bid", "other reject"), labels(model));
    }

    @Test
    void aTenderRowThatArrivedBeforeItsConversationWasKnownIsAttributedOnceItIs() {
        // The index is consulted at render time, not frozen into the row when it is added.
        CommLogModel model = new CommLogModel();
        model.setPreset(CommLogPreset.THIS_VESSEL);
        model.setFocusVessel("C501");
        model.add(bid("early bid", "tug_1", "cnp-a"));
        assertEquals(List.of(), labels(model), "nothing says yet which vessel cnp-a is for");

        model.add(award("award", "tug_1", "cnp-a", "C501"));

        assertEquals(List.of("early bid", "award"), labels(model));
    }

    @Test
    void theIndexSurvivesEvictionOfTheCfpRow(@TempDir Path tempDir) {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        model.add(cfp("cfp", "cnp-a", "WALKIN-3"));
        for (int i = 0; i < CommLogModel.MAX_ENTRIES; i++) {
            model.add(filler("filler-" + i));
        }
        assertEquals(CommLogModel.MAX_ENTRIES, model.size());
        assertEquals("filler-0", labels(model).get(0), "the CFP has left the 500-row window");

        model.add(bid("late bid", "tug_2", "cnp-a"));
        model.setPreset(CommLogPreset.THIS_VESSEL);
        model.setFocusVessel("WALKIN-3");

        assertEquals(List.of("late bid"), labels(model),
                "the row that taught the index is gone; what it taught is not");
    }

    @Test
    void clearDropsTheIndexAndKeepsThePresetAndTheFocus(@TempDir Path tempDir) {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        model.setPreset(CommLogPreset.THIS_VESSEL);
        model.setFocusVessel("WALKIN-3");
        model.add(cfp("cfp", "cnp-a", "WALKIN-3"));
        assertEquals(1, model.conversationIndexSizeForTest());

        model.clear();

        assertEquals(0, model.conversationIndexSizeForTest(), "a tender of the replaced timeline is history");
        assertEquals(CommLogPreset.THIS_VESSEL, model.preset(), "the chosen view is a preference, not history");
        assertEquals("WALKIN-3", model.focusVesselId());

        model.add(bid("bid after load", "tug_1", "cnp-a"));

        assertEquals(List.of(), labels(model), "the old tender must not attribute a row of the new timeline");
    }

    @Test
    void conversationsThatNameTheirVesselThemselvesAreNotIndexed() {
        CommLogModel model = new CommLogModel();
        model.add(row("offer", "vessel_walkin_3", List.of(HM), ACLMessage.PROPOSE, "nego-WALKIN-3", null, null));
        model.add(grant("grant", "vessel_walkin_3", "compatible [R8]")); // berth-vessel_walkin_3
        model.add(row("terminal request", HM, List.of("terminal_general"), ACLMessage.REQUEST, "term-WALKIN-3",
                null, "{\"vessel_id\":\"WALKIN-3\"}"));
        model.add(row("customs request", HM, List.of("customs_agent"), ACLMessage.REQUEST, "cust-WALKIN-3",
                null, "{\"vessel_id\":\"WALKIN-3\"}"));
        model.add(resume("vessel_walkin_3", "WALKIN-3")); // names a vessel, but has no conversation id
        model.add(bid("orphan bid", "tug_1", "cnp-z")); // a conversation, but no vessel to learn

        assertEquals(0, model.conversationIndexSizeForTest());

        model.add(cfp("cfp", "cnp-a", "WALKIN-3"));

        assertEquals(1, model.conversationIndexSizeForTest(), "only the tender needs the index");
    }

    @Test
    void withNoFocusVesselThisVesselShowsNothingAndSaysWhy() {
        CommLogModel model = new CommLogModel();
        model.add(grant("grant", "vessel_walkin_3", "compatible [R8]"));
        model.add(cfp("cfp", "cnp-a", "WALKIN-3"));

        model.setPreset(CommLogPreset.THIS_VESSEL);

        assertEquals(List.of(), labels(model));
        assertEquals("No chat tab is selected, so there is no \"this vessel\" yet. "
                + "Open a walk-in's chat tab to follow its messages here.", model.emptyStateHint());

        model.setFocusVessel("WALKIN-3");
        assertEquals(List.of("grant", "cfp"), labels(model));

        model.setFocusVessel(null);
        assertEquals(List.of(), labels(model), "the tab closed: back to nothing");
        assertNull(model.focusVesselId());
    }

    @Test
    void walkin3DoesNotMatchWalkin30() {
        CommLogModel model = new CommLogModel();
        model.add(row("offer 30", "vessel_walkin_30", List.of(HM), ACLMessage.PROPOSE, "nego-WALKIN-30", null,
                "{\"vessel_spec\":{\"vessel_id\":\"WALKIN-30\"}}"));
        model.add(grant("grant 30", "vessel_walkin_30", "compatible [R8]"));
        model.add(cfp("cfp 30", "cnp-thirty", "WALKIN-30"));
        model.add(bid("bid 30", "tug_1", "cnp-thirty"));
        model.setPreset(CommLogPreset.THIS_VESSEL);

        model.setFocusVessel("WALKIN-3");
        assertEquals(List.of(), labels(model), "set membership on keys, never a prefix test");
        assertEquals("No messages about WALKIN-3 in the log yet.", model.emptyStateHint());

        model.setFocusVessel("WALKIN-30");
        assertEquals(List.of("offer 30", "grant 30", "cfp 30", "bid 30"), labels(model));
    }

    @Test
    void theFocusIsKeptAsGivenAndMatchedThroughItsKey() {
        CommLogModel model = new CommLogModel();
        model.add(grant("grant", "vessel_storm_w1", "compatible [R8]"));
        model.setPreset(CommLogPreset.THIS_VESSEL);

        model.setFocusVessel("STORM-W1");

        assertEquals("STORM-W1", model.focusVesselId(), "the raw id, for texts the player reads");
        assertEquals(List.of("grant"), labels(model), "matched against vessel_storm_w1 through the key");
    }

    // =========================================================================== empty-state hints

    @Test
    void everyPresetHasItsOwnEmptyStateLine() {
        CommLogModel model = new CommLogModel();

        assertEquals("No messages yet.", model.emptyStateHint());

        model.setPreset(CommLogPreset.RULES);
        assertEquals("No message has cited a rule yet.", model.emptyStateHint());

        model.setPreset(CommLogPreset.WEATHER);
        assertEquals("No weather messages yet.", model.emptyStateHint());

        model.setPreset(CommLogPreset.THIS_VESSEL);
        assertEquals("No chat tab is selected, so there is no \"this vessel\" yet. "
                + "Open a walk-in's chat tab to follow its messages here.", model.emptyStateHint());

        model.setFocusVessel("WALKIN-3");
        assertEquals("No messages about WALKIN-3 in the log yet.", model.emptyStateHint());

        model.setFocusVessel("   ");
        assertNull(model.focusVesselId(), "a blank id is no vessel");
        assertEquals("No chat tab is selected, so there is no \"this vessel\" yet. "
                + "Open a walk-in's chat tab to follow its messages here.", model.emptyStateHint());
    }

    // =========================================================================== with the older filters

    @Test
    void thePresetAndCombinesWithTheOlderFilters() {
        CommLogModel model = new CommLogModel();
        model.add(new CommLogEvent(1_000L, WEATHER, List.of(HM), ACLMessage.INFORM, "alert", null,
                MessageFactory.ONTOLOGY, WEATHER_ALERT,
                "{\"event\":\"weather_threshold\",\"reason\":\"threshold_crossed [R18]\"}"));
        model.add(new CommLogEvent(2_000L, HM, List.of("vessel_c501"), ACLMessage.INFORM, "hold", null,
                MessageFactory.ONTOLOGY, VESSEL_WEATHER, "{\"event\":\"weather_hold\"}"));
        model.add(new CommLogEvent(3_000L, HM, List.of("tug_1"), ACLMessage.CANCEL, "tug cancel", null,
                MessageFactory.ONTOLOGY, null, "{\"reason\":\"weather_hold\",\"vessel_id\":\"C501\"}"));
        model.add(new CommLogEvent(4_000L, HM, List.of("vessel_c501"), ACLMessage.ACCEPT_PROPOSAL, "grant",
                "berth-vessel_c501", MessageFactory.ONTOLOGY, null, "{\"reason\":\"compatible [R7]\"}"));

        model.setPreset(CommLogPreset.WEATHER);
        assertEquals(List.of("alert", "hold", "tug cancel"), labels(model));

        model.setSenderMuted(WEATHER, true); // the old "Hide weather": mutes the weather AGENT only
        assertEquals(List.of("hold", "tug cancel"), labels(model), "preset AND not-muted");
        model.setSenderMuted(WEATHER, false);

        model.setPerformativeFilter(ACLMessage.CANCEL);
        assertEquals(List.of("tug cancel"), labels(model), "preset AND performative");
        model.setPerformativeFilter(null);

        model.setReceiverFilter("vessel_c501");
        assertEquals(List.of("hold"), labels(model), "preset AND receiver");
        model.setReceiverFilter(null);

        model.setTimeRange(1_500L, 2_500L);
        assertEquals(List.of("hold"), labels(model), "preset AND time range");
        model.setTimeRange(null, null);

        model.setPreset(CommLogPreset.RULES);
        model.setSenderFilter(HM);
        assertEquals(List.of("grant"), labels(model), "the alert cites [R18] too, but is not from the HarbourMaster");
        model.setSenderFilter(null);

        model.setPreset(CommLogPreset.THIS_VESSEL);
        model.setFocusVessel("C501");
        model.setTimeRange(null, 3_000L);
        assertEquals(List.of("hold", "tug cancel"), labels(model), "the grant is about C501 but after the range");
    }

    @Test
    void aPresetChosenBeforeTheRowsArriveAppliesToThemToo() {
        CommLogModel model = new CommLogModel();
        model.setPreset(CommLogPreset.WEATHER);

        model.add(filler("filler"));
        model.add(periodic());

        assertEquals(List.of("routine check"), labels(model));
        assertEquals(2, model.size(), "the preset filters the VIEW; every row is still stored");
    }

    // =========================================================================== onChange

    @Test
    void onChangeFiresOnSetPresetAndOnlyOnARealChangeOfFocus() {
        CommLogModel model = new CommLogModel();
        int[] changes = {0};
        model.setOnChange(() -> changes[0]++);

        model.setPreset(CommLogPreset.RULES);
        assertEquals(1, changes[0], "choosing a preset notifies");

        model.setFocusVessel("WALKIN-3");
        assertEquals(2, changes[0], "a new focus vessel notifies");

        model.setFocusVessel("WALKIN-3");
        assertEquals(2, changes[0], "the same vessel again must NOT — the chat reports on every tab event");

        model.setFocusVessel("WALKIN-4");
        assertEquals(3, changes[0]);

        model.setFocusVessel(null);
        assertEquals(4, changes[0], "losing the tab is a change");

        model.setFocusVessel(null);
        model.setFocusVessel("");
        assertEquals(4, changes[0], "no tab, still no tab");
    }

    @Test
    void aNullPresetIsRefused() {
        CommLogModel model = new CommLogModel();

        assertThrows(NullPointerException.class, () -> model.setPreset(null));
        assertEquals(CommLogPreset.ALL, model.preset(), "and nothing changed");
    }

    @Test
    void aNullEventIsRefusedBeforeAnythingIsStored() {
        CommLogModel model = new CommLogModel();

        assertThrows(NullPointerException.class, () -> model.add(null));
        assertEquals(0, model.size());
        assertTrue(model.filteredEntries().isEmpty());
    }

    // =========================================================================== the index is bounded

    @Test
    void theConversationIndexIsBounded(@TempDir Path tempDir) {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));

        for (int i = 0; i < CommLogModel.MAX_CONVERSATIONS + 10; i++) {
            model.add(cfp("cfp-" + i, "cnp-" + i, "V" + i));
        }

        assertEquals(CommLogModel.MAX_CONVERSATIONS, model.conversationIndexSizeForTest());
        assertEquals(1024, CommLogModel.MAX_CONVERSATIONS);
    }

    @Test
    void theConversationSilentLongestIsTheOneForgotten(@TempDir Path tempDir) {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        // Fill the index exactly: cnp-0 (vessel V0) is the oldest, cnp-1 (V1) the next.
        for (int i = 0; i < CommLogModel.MAX_CONVERSATIONS; i++) {
            model.add(cfp("cfp-" + i, "cnp-" + i, "V" + i));
        }
        // A bid arrives on the OLDEST tender: it is in use again.
        model.add(bid("bid on 0", "tug_1", "cnp-0"));
        // One more tender overflows the index by one.
        model.add(cfp("cfp-new", "cnp-new", "VNEW"));
        assertEquals(CommLogModel.MAX_CONVERSATIONS, model.conversationIndexSizeForTest());

        model.add(bid("later bid on 0", "tug_2", "cnp-0"));
        model.add(bid("later bid on 1", "tug_2", "cnp-1"));
        model.setPreset(CommLogPreset.THIS_VESSEL);

        model.setFocusVessel("V0");
        assertEquals(List.of("bid on 0", "later bid on 0"), labels(model),
                "cnp-0 was used again, so it was kept (its CFP row itself left the 500-row window long ago)");

        model.setFocusVessel("V1");
        assertEquals(List.of(), labels(model), "cnp-1 had been silent longest and was the one dropped");
    }
}
