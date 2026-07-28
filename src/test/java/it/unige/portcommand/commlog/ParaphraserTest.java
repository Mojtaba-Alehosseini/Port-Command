package it.unige.portcommand.commlog;

import java.util.Map;
import java.util.stream.Stream;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven: every case is a REAL content shape a {@code MessageFactory}
 * call site in this codebase actually sends (catalogued file:line in each
 * case's name — see {@code Paraphraser}'s own per-helper javadoc for the
 * source), not an invented one.
 */
class ParaphraserTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("realContentShapes")
    void paraphrasesRealContentShapes(String caseName, ACLMessage message, String expected) {
        assertEquals(expected, Paraphraser.paraphrase(message));
    }

    static Stream<Arguments> realContentShapes() {
        return Stream.of(
                // REQUEST
                Arguments.of("REQUEST vessel->HM request_berth (AnnounceArrivalBehaviour)",
                        request(Map.of("intent", "request_berth", "contract", "C1",
                                "vessel_spec", Map.of("vessel_id", "C001"))),
                        "Requesting action for C001"),
                Arguments.of("REQUEST HM->terminal 9-field BerthRequest (AutoFlowDispatcherBehaviour:171)",
                        request(Map.of("vessel_id", "C001", "vessel_type", "cargo_vessel", "draft", 9.0,
                                "length", 150.0, "tonnage", 30000, "berth_id", "berth_1", "eta_sim", 0L,
                                "duration_hours", 8, "cargo_class", "general_cargo")),
                        "Requesting berth berth_1 for C001"),
                Arguments.of("REQUEST HM->customs pre-clearance (AutoFlowDispatcherBehaviour:213)",
                        request(Map.of("vessel_id", "CU1", "vessel_type", "cargo_vessel",
                                "cargo_class", "hazmat_class_1")),
                        "Requesting action for CU1"),

                // PROPOSE
                Arguments.of("PROPOSE walk-in opening offer (OpeningProposalBehaviour:62)",
                        propose(Map.of("intent", "opening_offer", "price", 4800.0, "hours", 6,
                                "vessel_spec", Map.of("vessel_id", "W001"))),
                        "Offering €4800 to W001"),
                Arguments.of("PROPOSE tug CNP bid (BidInCNPBehaviour:80)",
                        propose(Map.of("cost", 100.0, "eta_minutes", 5.0, "fuel_state", 0.9)),
                        "Offering escort bid: €100 (ETA 5m)"),

                // ACCEPT_PROPOSAL
                Arguments.of("ACCEPT_PROPOSAL HM grants berth (AutoFlowDispatcherBehaviour:114)",
                        accept(Map.of("berth_id", "berth_1", "price", 5200.0, "hours", 8)),
                        "Granted berth berth_1"),
                Arguments.of("ACCEPT_PROPOSAL CNP award to winning tug (AwardContractBehaviour:71)",
                        accept(Map.of("vessel_id", "T001",
                                "berth_position", Map.of("x", 100.0, "y", 200.0, "heading_deg", 90.0))),
                        "Granted the escort contract for T001"),

                // REJECT_PROPOSAL
                Arguments.of("REJECT_PROPOSAL CNP loser, no content at all (AwardContractBehaviour:77)",
                        rejectNoContent(),
                        "Declined the offer"),

                // CFP
                Arguments.of("CFP tug escort (InitiateCNPBehaviour:69)",
                        cfp(Map.of("target_vessel_id", "C001",
                                "target_vessel_position", Map.of("x", 700.0, "y", 200.0, "heading_deg", 180.0),
                                "reply_by", 123456L)),
                        "Escort needed for C001"),

                // CONFIRM
                Arguments.of("CONFIRM vessel deal_confirmed (DealClosedBehaviour:46)",
                        confirm(Map.of("intent", "deal_confirmed", "price", 4900.0, "hours", 6)),
                        "Cleared the deal at €4900"),
                Arguments.of("CONFIRM terminal berth assignment (HandleBerthRequestBehaviour:90)",
                        confirm(Map.of("berth_id", "berth_1", "crane_assigned", 2)),
                        "Cleared berth berth_1 assignment"),
                Arguments.of("CONFIRM customs clearance ref (HandleClearanceRequestBehaviour:75)",
                        confirm(Map.of("ref", "CL-2026-1")),
                        "Cleared clearance CL-2026-1"),

                // INFORM
                Arguments.of("INFORM vessel withdraw (WithdrawalBehaviour:49)",
                        inform(Map.of("intent", "withdraw", "reason", "over_priced"), false),
                        "Notifying: withdrawal (over priced)"),
                Arguments.of("INFORM vessel departed (DepartBehaviour:45)",
                        inform(Map.of("intent", "departed", "vessel_id", "V1"), false),
                        "Notifying: V1 has departed"),
                Arguments.of("INFORM tug_hold no bids (AwardContractBehaviour:89)",
                        inform(Map.of("event", "tug_hold", "reason", "no_bids"), false),
                        "Notifying: tug hold (no bids)"),
                Arguments.of("INFORM weather_threshold + priority=high envelope param (PeriodicWeatherBroadcastBehaviour:82)",
                        inform(Map.of("event", "weather_threshold", "wind_knots", 40, "visibility", "poor",
                                "swell", 3.0, "state", "stormy"), true),
                        "Notifying: URGENT weather alert (wind 40kn, stormy)"),
                Arguments.of("INFORM cargo handling complete (ProcessCargoHandlingBehaviour:71)",
                        inform(Map.of("notice", "handling_complete", "vessel", "V1", "berth", "berth_1"), false),
                        "Notifying: cargo handling complete at berth berth_1"),
                Arguments.of("INFORM routine weather broadcast, no intent/event/notice key (PeriodicWeatherBroadcastBehaviour:64)",
                        inform(Map.of("wind_knots", 10, "visibility", "good", "swell", 1.0,
                                "state", "sunny", "sim_time", 1000L), false),
                        "Notifying: conditions: wind 10kn, sunny"),

                // REFUSE
                Arguments.of("REFUSE incompatible (ForwardWalkInToPlayerBehaviour:132)",
                        refuse(Map.of("reason", "incompatible")),
                        "Refusing (incompatible)"),
                Arguments.of("REFUSE no_compatible_berth + intent (ForwardWalkInToPlayerBehaviour:87)",
                        refuse(Map.of("intent", "refuse", "reason", "no_compatible_berth")),
                        "Refusing (no compatible berth)"),

                // CANCEL
                Arguments.of("CANCEL weather hold (HandleWeatherAlertBehaviour:90)",
                        cancel(Map.of("reason", "weather_hold")),
                        "Cancelling (weather hold)"),
                Arguments.of("CANCEL player withdrew (DispatchPlayerCommandBehaviour:92)",
                        cancel(Map.of("reason", "player_withdrew")),
                        "Cancelling (player withdrew)"),

                // DISCONFIRM
                Arguments.of("DISCONFIRM terminal retracts after flood (RetractIfFloodBehaviour:73)",
                        disconfirm(Map.of("notice", "retracted", "berth_id", "berth_2")),
                        "Retracting berth berth_2 confirmation")
        );
    }

    @Test
    void nullMessageNeverThrows() {
        assertEquals("(no message)", Paraphraser.paraphrase(null));
    }

    @Test
    void unknownPerformativeFallsBackSafelyInsteadOfThrowing() {
        // QUERY_REF really is sent (player ASK command, NLP query_status/ask) but is not one
        // of the 10 templated performatives — must degrade gracefully, never throw.
        ACLMessage queryRef = MessageFactory.create(ACLMessage.QUERY_REF);
        queryRef.setContent(TerminalJson.write(Map.of("price", 100.0)));
        String result = Paraphraser.paraphrase(queryRef);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void malformedJsonContentNeverThrows() {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.setContent("not valid json {{{");
        assertEquals("Notifying: an update", Paraphraser.paraphrase(inform));
    }

    @Test
    void missingContentEntirelyNeverThrows() {
        ACLMessage refuse = MessageFactory.create(ACLMessage.REFUSE);
        // content left unset (null) entirely
        assertTrue(Paraphraser.paraphrase(refuse).startsWith("Refusing"));
    }

    /**
     * #6 (checkpoint #5–#7): a PROPOSE FROM the HarbourMaster is a price DEMAND, everyone else's is
     * an OFFER. Sender-aware verb only — same content, same performative, different reading.
     */
    @Test
    void harbourMasterProposeReadsAsDemandWhileAVesselOfferStaysOffering() {
        ACLMessage propose = propose(Map.of("intent", "counter_offer", "price", 5000.0, "hours", 8,
                "vessel_spec", Map.of("vessel_id", "WALKIN-S1")));

        assertTrue(Paraphraser.paraphrase(propose, "WALKIN-S1").startsWith("Offering "),
                "a vessel's own PROPOSE is an offer");
        assertTrue(Paraphraser.paraphrase(propose).startsWith("Offering "),
                "no attached sender (null AID) defaults to Offering");
        assertTrue(Paraphraser.paraphrase(propose, "harbour_master").startsWith("Demanding "),
                "the HarbourMaster's PROPOSE is a demand");
    }

    private static ACLMessage request(Map<String, Object> content) {
        return withContent(ACLMessage.REQUEST, content);
    }

    private static ACLMessage propose(Map<String, Object> content) {
        return withContent(ACLMessage.PROPOSE, content);
    }

    private static ACLMessage accept(Map<String, Object> content) {
        return withContent(ACLMessage.ACCEPT_PROPOSAL, content);
    }

    private static ACLMessage rejectNoContent() {
        return MessageFactory.create(ACLMessage.REJECT_PROPOSAL);
    }

    private static ACLMessage cfp(Map<String, Object> content) {
        return withContent(ACLMessage.CFP, content);
    }

    private static ACLMessage confirm(Map<String, Object> content) {
        return withContent(ACLMessage.CONFIRM, content);
    }

    private static ACLMessage inform(Map<String, Object> content, boolean urgent) {
        ACLMessage m = withContent(ACLMessage.INFORM, content);
        if (urgent) {
            m.addUserDefinedParameter("priority", "high");
        }
        return m;
    }

    private static ACLMessage refuse(Map<String, Object> content) {
        return withContent(ACLMessage.REFUSE, content);
    }

    private static ACLMessage cancel(Map<String, Object> content) {
        return withContent(ACLMessage.CANCEL, content);
    }

    private static ACLMessage disconfirm(Map<String, Object> content) {
        return withContent(ACLMessage.DISCONFIRM, content);
    }

    private static ACLMessage withContent(int performative, Map<String, Object> content) {
        ACLMessage m = MessageFactory.create(performative);
        m.setContent(TerminalJson.write(content));
        return m;
    }
}
