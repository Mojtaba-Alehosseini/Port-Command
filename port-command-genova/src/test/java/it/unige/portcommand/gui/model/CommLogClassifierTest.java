package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.RuleCatalogue;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.ontology.VesselSpec;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): how the comm log reads one message — weather or not, rule-citing or not, and
 * about which vessels. Every content string below is the shape its producer really writes
 * (compact JSON from {@code TerminalJson.write}); the producer is named beside each fixture.
 */
class CommLogClassifierTest {

    private static final String HM = "harbour_master";
    private static final String WEATHER = "weather_agent";
    private static final String CNP = "cnp-3f2b8c1e-0d4a-4a55-9c0e-7b1a2d3e4f50";

    /** The production predicate: the shipped catalogue, R1..R30. */
    private static final Predicate<String> KNOWN = RuleCatalogue.get()::contains;

    private static CommLogEvent message(String sender, List<String> receivers, int performative,
                                        String conversationId, String protocol, String content) {
        return new CommLogEvent(0L, sender, receivers, performative, "paraphrase", conversationId,
                MessageFactory.ONTOLOGY, protocol, content);
    }

    // ---- the six kinds of weather traffic ------------------------------------------------------

    /** PeriodicWeatherBroadcastBehaviour.broadcast — the routine check, ~96 a game day. */
    private static CommLogEvent periodic() {
        return message(WEATHER, List.of(HM), ACLMessage.INFORM, null, CommLogClassifier.WEATHER_ALERT_PROTOCOL,
                "{\"event\":\"weather_periodic\",\"wind_knots\":18,\"visibility\":\"good\",\"swell\":0.5,"
                        + "\"state\":\"sunny\",\"sim_time\":900000}");
    }

    /** PeriodicWeatherBroadcastBehaviour.alertHarbourMaster, worsening. */
    private static CommLogEvent thresholdAlert() {
        return message(WEATHER, List.of(HM), ACLMessage.INFORM, null, CommLogClassifier.WEATHER_ALERT_PROTOCOL,
                "{\"event\":\"weather_threshold\",\"reason\":\"threshold_crossed [R18]\",\"wind_knots\":36,"
                        + "\"visibility\":\"poor\",\"swell\":2.5,\"state\":\"stormy\"}");
    }

    /** PeriodicWeatherBroadcastBehaviour.alertHarbourMaster, clearing. */
    private static CommLogEvent clearAlert() {
        return message(WEATHER, List.of(HM), ACLMessage.INFORM, null, CommLogClassifier.WEATHER_ALERT_PROTOCOL,
                "{\"event\":\"weather_clear\",\"reason\":\"threshold_cleared [R18]\",\"wind_knots\":22,"
                        + "\"visibility\":\"good\",\"swell\":1.0,\"state\":\"cloudy\"}");
    }

    /** HandleWeatherAlertBehaviour.holdVessel / HandleTugDeclineBehaviour — HM tells the vessel. */
    private static CommLogEvent hold() {
        return message(HM, List.of("vessel_c501"), ACLMessage.INFORM, null,
                CommLogClassifier.VESSEL_WEATHER_PROTOCOL,
                "{\"event\":\"weather_hold\",\"wind_knots\":36.0,\"swell\":2.5,\"state\":\"stormy\"}");
    }

    /** HandleWeatherAlertBehaviour — the resume, with the emergency citation it can carry. */
    private static CommLogEvent resume() {
        return message(HM, List.of("vessel_c501"), ACLMessage.INFORM, null,
                CommLogClassifier.VESSEL_WEATHER_PROTOCOL,
                "{\"event\":\"weather_resume\",\"vessel_id\":\"C501\",\"wind_knots\":22.0,\"state\":\"cloudy\","
                        + "\"reason\":\"emergency_override [R29]\"}");
    }

    /** HandleWeatherAlertBehaviour.holdVessel — HM cancels the tug; no protocol on this one. */
    private static CommLogEvent tugCancel() {
        return message(HM, List.of("tug_1"), ACLMessage.CANCEL, null, null,
                "{\"reason\":\"weather_hold\",\"vessel_id\":\"C501\"}");
    }

    private static List<CommLogEvent> sixWeatherKinds() {
        return List.of(periodic(), thresholdAlert(), clearAlert(), hold(), resume(), tugCancel());
    }

    // ---- non-weather traffic -------------------------------------------------------------------

    /** InitiateCNPBehaviour — the call for tug bids names the vessel in content only. */
    private static CommLogEvent cfp() {
        return message(HM, List.of("tug_1", "tug_2", "tug_3", "tug_4"), ACLMessage.CFP, CNP, "fipa-contract-net",
                "{\"target_vessel_id\":\"C501\",\"target_vessel_position\":{\"x\":120.0,\"y\":40.0},"
                        + "\"reply_by\":30000}");
    }

    /** A tug's bid: cost, ETA, fuel, position — no vessel anywhere. */
    private static CommLogEvent tugBid() {
        return message("tug_1", List.of(HM), ACLMessage.PROPOSE, CNP, "fipa-contract-net",
                "{\"cost\":350.0,\"eta_minutes\":4.0,\"fuel_state\":0.9,\"position\":{\"x\":10.0,\"y\":5.0}}");
    }

    /** AutoFlowDispatcherBehaviour.grantContracted — the berth grant that names its rule. */
    private static CommLogEvent grant() {
        return new CommLogEvent(0L, HM, List.of("vessel_c501"), ACLMessage.ACCEPT_PROPOSAL,
                "Granted berth berth_1 (compatible [R7])", "berth-vessel_c501", MessageFactory.ONTOLOGY, null,
                "{\"berth_id\":\"berth_1\",\"price\":5200.0,\"hours\":8,\"reason\":\"compatible [R7]\"}");
    }

    // =========================================================================== weather

    @Test
    void allSixKindsOfWeatherTrafficAreWeather() {
        for (CommLogEvent event : sixWeatherKinds()) {
            assertTrue(CommLogClassifier.isWeather(event), () -> "must be weather: " + event.content());
            assertTrue(CommLogClassifier.classify(event, KNOWN).weather(),
                    () -> "classify() must agree: " + event.content());
        }
    }

    @Test
    void theRoutineCheckIsWeather() {
        assertTrue(CommLogClassifier.isWeather(periodic()));
    }

    @Test
    void theWorseningAlertIsWeather() {
        assertTrue(CommLogClassifier.isWeather(thresholdAlert()));
    }

    @Test
    void theClearingAlertIsWeather() {
        assertTrue(CommLogClassifier.isWeather(clearAlert()));
    }

    @Test
    void theHoldToAVesselIsWeatherThoughTheHarbourMasterSendsIt() {
        assertTrue(CommLogClassifier.isWeather(hold()));
    }

    @Test
    void theResumeToAVesselIsWeather() {
        assertTrue(CommLogClassifier.isWeather(resume()));
    }

    @Test
    void theTugCancelCausedByAHoldIsWeatherByItsReasonAlone() {
        // No weather sender, no protocol, no "event" key: only {"reason":"weather_hold"} says so.
        assertTrue(CommLogClassifier.isWeather(tugCancel()));
    }

    @Test
    void eachSignalIsEnoughOnItsOwn() {
        assertTrue(CommLogClassifier.isWeather(
                        new CommLogEvent(0L, WEATHER, List.of(HM), ACLMessage.INFORM, "p", null)),
                "the sender alone (a row with no message attached)");
        assertTrue(CommLogClassifier.isWeather(message(HM, List.of("tug_1"), ACLMessage.INFORM, null,
                CommLogClassifier.WEATHER_ALERT_PROTOCOL, null)), "the alert protocol alone");
        assertTrue(CommLogClassifier.isWeather(message(HM, List.of("vessel_c501"), ACLMessage.INFORM, null,
                CommLogClassifier.VESSEL_WEATHER_PROTOCOL, "not json")), "the vessel-weather protocol alone");
        assertTrue(CommLogClassifier.isWeather(message(HM, List.of("vessel_c501"), ACLMessage.INFORM, null, null,
                "{\"event\":\"weather_hold\"}")), "a weather_ content event alone");
        assertTrue(CommLogClassifier.isWeather(message(HM, List.of("tug_1"), ACLMessage.CANCEL, null, null,
                "{\"reason\":\"weather_hold\"}")), "the weather_hold reason alone");
    }

    @Test
    void trafficThatOnlyResemblesWeatherIsNotWeather() {
        // AwardContractBehaviour.holdVessel: a hold because no tug bid — nothing to do with weather.
        CommLogEvent tugHold = message(HM, List.of("vessel_c501"), ACLMessage.INFORM, null, null,
                "{\"event\":\"tug_hold\",\"reason\":\"no_bids\"}");
        // DispatchPlayerCommandBehaviour: the player withdrew from a negotiation.
        CommLogEvent playerWithdrew = message(HM, List.of("vessel_walkin_3"), ACLMessage.CANCEL, "nego-WALKIN-3",
                null, "{\"reason\":\"player_withdrew\"}");
        // The paraphrase is never read for this: a row with no message attached has no signal.
        CommLogEvent onlyTheTextSaysWeather = new CommLogEvent(0L, HM, List.of("tug_1"), ACLMessage.INFORM,
                "Notifying: weather hold (wind 36kn)", null);
        CommLogEvent almostTheReason = message(HM, List.of("tug_1"), ACLMessage.CANCEL, null, null,
                "{\"reason\":\"weather_hold_lifted\"}");
        CommLogEvent almostTheEvent = message(HM, List.of("tug_1"), ACLMessage.INFORM, null, null,
                "{\"event\":\"weatherproofing\"}");

        for (CommLogEvent event : List.of(tugHold, playerWithdrew, cfp(), tugBid(), grant(),
                onlyTheTextSaysWeather, almostTheReason, almostTheEvent)) {
            assertFalse(CommLogClassifier.isWeather(event), () -> "must NOT be weather: " + event);
            assertFalse(CommLogClassifier.classify(event, KNOWN).weather(), () -> "classify() must agree: " + event);
        }
    }

    @Test
    void onlyTheRoutineCheckIsPeriodic() {
        assertTrue(CommLogClassifier.isPeriodicWeather(periodic()));
        assertTrue(CommLogClassifier.classify(periodic(), KNOWN).periodicWeather());

        for (CommLogEvent event : List.of(thresholdAlert(), clearAlert(), hold(), resume(), tugCancel(), cfp())) {
            assertFalse(CommLogClassifier.isPeriodicWeather(event), () -> "must NOT be periodic: " + event.content());
            assertFalse(CommLogClassifier.classify(event, KNOWN).periodicWeather());
        }
        assertFalse(CommLogClassifier.isPeriodicWeather(
                        new CommLogEvent(0L, WEATHER, List.of(HM), ACLMessage.INFORM, "p", null)),
                "periodic is read from the content's event, not guessed from the sender");
    }

    @Test
    void theTwoProtocolLiteralsEqualTheBehaviourConstants() {
        // gui.model keeps its own copies so it does not import the behaviour packages; this is
        // what stops the copies drifting.
        assertEquals(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL, CommLogClassifier.WEATHER_ALERT_PROTOCOL);
        assertEquals(HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL, CommLogClassifier.VESSEL_WEATHER_PROTOCOL);
        assertEquals("port-command-weather-alert", CommLogClassifier.WEATHER_ALERT_PROTOCOL);
        assertEquals("port-command-vessel-weather", CommLogClassifier.VESSEL_WEATHER_PROTOCOL);
        assertEquals("weather_agent", CommLogClassifier.WEATHER_SENDER);
    }

    // =========================================================================== rules

    @Test
    void aRuleCitedInTheParaphraseOrInTheContentMakesARuleRow() {
        assertTrue(CommLogClassifier.classify(grant(), KNOWN).citesRule(), "paraphrase and content both cite R7");
        assertTrue(CommLogClassifier.classify(new CommLogEvent(0L, HM, List.of("vessel_c501"),
                        ACLMessage.ACCEPT_PROPOSAL, "Granted berth berth_1 (compatible [R7])", "berth-vessel_c501"),
                KNOWN).citesRule(), "the paraphrase alone — a row with no message attached");
        assertTrue(CommLogClassifier.classify(thresholdAlert(), KNOWN).citesRule(),
                "the content alone — the fixture's paraphrase cites nothing");
    }

    @Test
    void aRowThatCitesNothingKnownIsNotARuleRow() {
        assertFalse(CommLogClassifier.classify(periodic(), KNOWN).citesRule());
        assertFalse(CommLogClassifier.classify(cfp(), KNOWN).citesRule());
        assertFalse(CommLogClassifier.classify(message(HM, List.of("vessel_c501"), ACLMessage.INFORM, null, null,
                "{\"reason\":\"made_up [R31]\"}"), KNOWN).citesRule(), "there is no rule 31");
        assertFalse(CommLogClassifier.classify(grant(), id -> false).citesRule(),
                "the predicate decides what counts as a rule");
    }

    @Test
    void aWeatherAlertIsBothAWeatherRowAndARuleRow() {
        CommLogFacts facts = CommLogClassifier.classify(thresholdAlert(), KNOWN);

        assertTrue(facts.weather());
        assertTrue(facts.citesRule(), "it carries [R18]");
        assertFalse(facts.periodicWeather());
        assertEquals(Set.of(), facts.vesselKeys(), "a weather alert is about the whole port, not a vessel");
    }

    // =========================================================================== vessel attribution

    @Test
    void aVesselIsNamedByBeingTheSender() {
        CommLogEvent event = message("vessel_walkin_3", List.of(HM), ACLMessage.INFORM, null, null, null);

        assertEquals(Set.of("walkin_3"), CommLogClassifier.vesselsOf(event));
    }

    @Test
    void aVesselIsNamedByBeingAReceiver() {
        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(hold()), "the hold names its vessel only this way");
        assertEquals(Set.of("c501", "walkin_3"), CommLogClassifier.vesselsOf(
                message(HM, List.of("tug_1", "vessel_c501", "vessel_walkin_3"), ACLMessage.INFORM, null, null, null)));
    }

    @Test
    void aNegotiationConversationIdNamesItsVesselByRawId() {
        // Sender and receiver are deliberately not vessels: only the conversation id can say it.
        assertEquals(Set.of("walkin_3"), CommLogClassifier.vesselsOf(
                message(HM, List.of("assistant_agent"), ACLMessage.INFORM, "nego-WALKIN-3", null, null)));
    }

    @Test
    void aTerminalConversationIdNamesItsVessel() {
        // AutoFlowDispatcherBehaviour: the terminal's CONFIRM carries no vessel in content.
        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(
                message("terminal_general", List.of(HM), ACLMessage.CONFIRM, "term-C501",
                        "port-command-terminal-berth", "{\"berth_id\":\"berth_1\"}")));
    }

    @Test
    void aCustomsConversationIdNamesItsVessel() {
        assertEquals(Set.of("storm_w1"), CommLogClassifier.vesselsOf(
                message("customs_agent", List.of(HM), ACLMessage.CONFIRM, "cust-STORM-W1",
                        "port-command-customs-clearance", "{\"reason\":\"inspected [R22,R24]\"}")));
    }

    @Test
    void aBerthConversationIdNamesItsVesselByLocalName() {
        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(
                message(HM, List.of("assistant_agent"), ACLMessage.INFORM, "berth-vessel_c501", null, null)));
        assertEquals("c501", CommLogClassifier.vesselKeyOfConversationId("berth-vessel_c501"));
        assertEquals("c501", CommLogClassifier.vesselKeyOfConversationId("berth-C501"),
                "a raw id after berth- is tolerated");
    }

    @Test
    void conversationIdsThatNameNoVesselGiveNoKey() {
        assertNull(CommLogClassifier.vesselKeyOfConversationId(CNP), "a tug tender is a UUID");
        assertNull(CommLogClassifier.vesselKeyOfConversationId("conv-1"));
        assertNull(CommLogClassifier.vesselKeyOfConversationId(null));
        assertNull(CommLogClassifier.vesselKeyOfConversationId(""));
        assertNull(CommLogClassifier.vesselKeyOfConversationId("nego-"), "a scheme with nothing after it");
        assertNull(CommLogClassifier.vesselKeyOfConversationId("berth-vessel_"));
        assertNull(CommLogClassifier.vesselKeyOfConversationId("NEGO-WALKIN-3"), "the schemes are lower case");
        assertEquals("walkin_3", CommLogClassifier.vesselKeyOfConversationId("nego-WALKIN-3"));
        assertEquals("c501", CommLogClassifier.vesselKeyOfConversationId("term-C501"));
        assertEquals("c501", CommLogClassifier.vesselKeyOfConversationId("cust-C501"));
    }

    @Test
    void contentVesselIdNamesTheVessel() {
        // AwardContractBehaviour: the award ACCEPT to the winning tug.
        CommLogEvent award = message(HM, List.of("tug_1"), ACLMessage.ACCEPT_PROPOSAL, CNP, "fipa-contract-net",
                "{\"vessel_id\":\"C501\",\"berth_position\":{\"x\":200.0,\"y\":80.0}}");

        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(award));
        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(tugCancel()));
    }

    @Test
    void contentTargetVesselIdNamesTheVessel() {
        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(cfp()));
    }

    @Test
    void contentVesselNamesTheVessel() {
        // ProcessCargoHandlingBehaviour says "vessel", not "vessel_id".
        CommLogEvent handlingComplete = message("terminal_general", List.of(HM), ACLMessage.INFORM, null, null,
                "{\"notice\":\"handling_complete\",\"vessel\":\"C501\",\"berth\":\"berth_1\"}");

        assertEquals(Set.of("c501"), CommLogClassifier.vesselsOf(handlingComplete));
    }

    @Test
    void contentVesselSpecNamesTheVessel() {
        // OpeningProposalBehaviour nests the whole spec. Sender swapped for a non-vessel so the
        // nested id is the only thing that can name it.
        CommLogEvent openingOffer = message("assistant_agent", List.of(HM), ACLMessage.PROPOSE, "conv-1", null,
                "{\"intent\":\"opening_offer\",\"price\":1436.0,\"hours\":6,\"vessel_spec\":{\"vessel_id\":\"W040\","
                        + "\"vessel_type\":\"cargo_vessel\",\"draft\":9.0,\"length\":150.0,\"tonnage\":30000,"
                        + "\"cargo_class\":\"general_cargo\",\"eta_arrival_sim_millis\":0,"
                        + "\"vessel_name\":\"Santa Lucia\"}}");

        assertEquals(Set.of("w040"), CommLogClassifier.vesselsOf(openingOffer));
    }

    @Test
    void anUnknownOrBlankContentVesselNamesNobody() {
        // The terminal's defensive "unknown", and the tug decline's "" for a vessel it could not read.
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(message("terminal_general", List.of(HM), ACLMessage.INFORM,
                null, null, "{\"notice\":\"handling_complete\",\"vessel\":\"unknown\",\"berth\":\"berth_1\"}")));
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(message("tug_2", List.of(HM), ACLMessage.REFUSE, CNP,
                "port-command-post-award-decline",
                "{\"reason\":\"low_fuel\",\"vessel_id\":\"\",\"conversation_id\":\"" + CNP + "\"}")));
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(message(HM, List.of("tug_1"), ACLMessage.INFORM, null,
                null, "{\"vessel_id\":\"   \",\"target_vessel_id\":null}")));
    }

    @Test
    void rowsThatNameNoVesselHaveNoKeys() {
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(periodic()), "a weather alert has no vessel");
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(thresholdAlert()));
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(tugBid()), "a bid is tied to its vessel by the index");
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(
                message(HM, List.of("tug_3"), ACLMessage.REJECT_PROPOSAL, CNP, "fipa-contract-net", null)),
                "the loser's REJECT has no content at all");
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(
                message("unknown", List.of(HM), ACLMessage.INFORM, null, null, null)),
                "the publisher's placeholder for a sender it could not read");
    }

    @Test
    void everyPathContributesToOneUnion() {
        CommLogEvent event = message("vessel_a1", List.of(HM, "vessel_b2"), ACLMessage.INFORM, "nego-C3", null,
                "{\"vessel_id\":\"D4\",\"target_vessel_id\":\"E5\",\"vessel\":\"F6\","
                        + "\"vessel_spec\":{\"vessel_id\":\"G7\"}}");

        assertEquals(Set.of("a1", "b2", "c3", "d4", "e5", "f6", "g7"), CommLogClassifier.vesselsOf(event));
        assertEquals(Set.of("a1", "b2", "c3", "d4", "e5", "f6", "g7"),
                CommLogClassifier.classify(event, KNOWN).vesselKeys(), "classify() carries the same set");
    }

    @Test
    void aRowNamingTheSameVesselSeveralWaysHasOneKey() {
        // The walk-in's own opening offer: sender, conversation id and nested spec all say WALKIN-3.
        CommLogEvent event = message("vessel_walkin_3", List.of(HM), ACLMessage.PROPOSE, "nego-WALKIN-3", null,
                "{\"intent\":\"opening_offer\",\"price\":1436.0,\"hours\":6,"
                        + "\"vessel_spec\":{\"vessel_id\":\"WALKIN-3\"}}");

        assertEquals(Set.of("walkin_3"), CommLogClassifier.vesselsOf(event));
    }

    // =========================================================================== the key function

    @Test
    void theKeyIsExactlyWhatAgentRosterNamesTheVesselAgentAfter() {
        for (String id : List.of("C501", "WALKIN-3", "STORM-W1")) {
            VesselSpec spec = new VesselSpec(id, "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
            String localName = AgentRoster.vesselLocalName(spec);

            assertEquals(localName, "vessel_" + CommLogClassifier.keyOfVesselId(id),
                    () -> "the key of " + id + " must be the agent's local name minus its prefix");
            assertEquals(CommLogClassifier.keyOfVesselId(id), CommLogClassifier.keyOfLocalName(localName),
                    () -> "the raw id and the local name of " + id + " must meet at ONE key");
        }
    }

    @Test
    void theKeysOfTheThreeIdShapes() {
        assertEquals("c501", CommLogClassifier.keyOfVesselId("C501"));
        assertEquals("walkin_3", CommLogClassifier.keyOfVesselId("WALKIN-3"));
        assertEquals("storm_w1", CommLogClassifier.keyOfVesselId("STORM-W1"));
        assertEquals("a_b_c_d", CommLogClassifier.keyOfVesselId("A b.c/D"), "every other character becomes _");
        assertNull(CommLogClassifier.keyOfVesselId(null));
        assertNull(CommLogClassifier.keyOfVesselId(""));
        assertNull(CommLogClassifier.keyOfVesselId("   "), "a blank id names no vessel");
    }

    @Test
    void onlyAVesselAgentsLocalNameHasAKey() {
        assertEquals("c501", CommLogClassifier.keyOfLocalName("vessel_c501"));
        assertEquals("walkin_3", CommLogClassifier.keyOfLocalName("vessel_walkin_3"));
        for (String other : List.of("harbour_master", "weather_agent", "customs_agent", "assistant_agent",
                "terminal_container", "terminal_general", "tug_1", "unknown", "vessel_", "vessel", "")) {
            assertNull(CommLogClassifier.keyOfLocalName(other), () -> other + " is not a vessel agent");
        }
        assertNull(CommLogClassifier.keyOfLocalName(null));
    }

    @Test
    void walkin3AndWalkin30AreDifferentVessels() {
        String three = CommLogClassifier.keyOfVesselId("WALKIN-3");
        String thirty = CommLogClassifier.keyOfVesselId("WALKIN-30");
        assertNotEquals(three, thirty);

        Set<String> ofThirty = CommLogClassifier.vesselsOf(message("vessel_walkin_30", List.of(HM), ACLMessage.PROPOSE,
                "nego-WALKIN-30", null, "{\"vessel_spec\":{\"vessel_id\":\"WALKIN-30\"}}"));

        assertEquals(Set.of(thirty), ofThirty);
        assertFalse(ofThirty.contains(three), "membership, never a prefix test");
    }

    // =========================================================================== never throws

    @Test
    void contentThatCannotBeReadYieldsNoFactsAndNeverThrows() {
        List<String> unreadable = new ArrayList<>(List.of(
                "",
                "   ",
                "not json",
                "{",
                "{\"event\":",
                "{\"event\":\"weather_hold\"",
                "[1,2,3]",
                "\"just a string\"",
                "42",
                "null",
                "true",
                // Right keys, wrong shapes: an object, an array, a string where an object belongs, nulls.
                "{\"event\":{\"nested\":true},\"vessel_id\":[\"a\"],\"vessel_spec\":\"oops\",\"reason\":null,"
                        + "\"vessel\":{\"id\":\"x\"},\"target_vessel_id\":null}",
                "{\"vessel_spec\":null}",
                "{\"vessel_spec\":{\"vessel_id\":{\"deep\":1}}}",
                // Nested far past any real message.
                "[".repeat(2000)));
        unreadable.add(null); // List.of refuses null, and "no content at all" is the commonest case

        for (String content : unreadable) {
            // Nothing in the envelope names a vessel or says weather, so any fact would have to
            // come out of the content.
            CommLogEvent event = message(HM, List.of("tug_1"), ACLMessage.INFORM, "conv-1", null, content);
            Supplier<String> what = () -> "content: " + content;

            CommLogFacts facts = assertDoesNotThrow(() -> CommLogClassifier.classify(event, KNOWN), what);
            assertEquals(CommLogFacts.NONE, facts, what);
            assertEquals(Set.of(), CommLogClassifier.vesselsOf(event), what);
            assertFalse(CommLogClassifier.isWeather(event), what);
            assertFalse(CommLogClassifier.isPeriodicWeather(event), what);
        }
    }

    @Test
    void unreadableContentDoesNotEraseWhatTheEnvelopeSays() {
        CommLogFacts fromWeather = CommLogClassifier.classify(
                message(WEATHER, List.of(HM), ACLMessage.INFORM, null, null, "not json"), KNOWN);
        assertTrue(fromWeather.weather(), "the sender still says weather");

        CommLogFacts fromVessel = CommLogClassifier.classify(
                message("vessel_c501", List.of(HM), ACLMessage.INFORM, "berth-vessel_c501", null, "{"), KNOWN);
        assertEquals(Set.of("c501"), fromVessel.vesselKeys(), "the sender and the conversation still name it");
    }

    @Test
    void aNullEventHasNoFacts() {
        assertEquals(CommLogFacts.NONE, CommLogClassifier.classify(null, KNOWN));
        assertEquals(Set.of(), CommLogClassifier.vesselsOf(null));
        assertFalse(CommLogClassifier.isWeather(null));
        assertFalse(CommLogClassifier.isPeriodicWeather(null));
    }

    @Test
    void aMissingRulePredicateIsAWiringMistakeAndFailsLoudly() {
        assertThrows(NullPointerException.class, () -> CommLogClassifier.classify(grant(), null));
    }

    @Test
    void theFactsAreImmutable() {
        CommLogFacts facts = CommLogClassifier.classify(cfp(), KNOWN);

        assertThrows(UnsupportedOperationException.class, () -> facts.vesselKeys().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> CommLogClassifier.vesselsOf(cfp()).add("x"));
        assertEquals(Set.of(), new CommLogFacts(null, false, false, false).vesselKeys(), "null keys become none");
    }
}
