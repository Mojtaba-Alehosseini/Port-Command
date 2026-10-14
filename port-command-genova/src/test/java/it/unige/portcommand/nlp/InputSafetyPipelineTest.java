package it.unige.portcommand.nlp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.prolog.PrologEngine;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Input safety through the REAL tokeniser, the REAL grammar and the production validator
 * (S18a, ADR-23) — what a player's own keystrokes become. {@code NLPPipelineTest} proves each
 * check against a hand-built frame; this proves that the frames the grammar actually builds reach
 * those checks in the shape they expect (a money amount the check can read, a duration, the
 * reference offer the grammar completed from). Rasa is DOWN for the whole class — a closed port —
 * so everything asserted here is decided without it.
 */
class InputSafetyPipelineTest {

    private static ExecutorService executor;
    private static NLPPipeline pipeline;

    @BeforeAll
    static void setUp() throws IOException {
        PrologEngine.getInstance().init();
        executor = Executors.newFixedThreadPool(2);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        RasaBridge rasa = new RasaBridge(URI.create("http://localhost:" + closedPort + "/model/parse"),
                HttpClient.newHttpClient(), new ObjectMapper());
        pipeline = new NLPPipeline(new PreprocessRegex(), rasa, new ConfidenceGate(),
                new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer()), new OntologyValidator(),
                executor);
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Santa Lucia has hailed: she offers EUR 1436 for 6 h at berth 3. {@code playerOffer} is the
     * player's own last demand, or {@code null} on a tab where they have not answered yet. */
    private static DialogueCtx table(Double playerOffer) {
        return new DialogueCtx("V1",
                new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(1436.0, 6, "berth_3"),
                        playerOffer == null ? DialogueCtx.OfferView.NONE
                                : new DialogueCtx.OfferView(playerOffer, 6, "berth_3")),
                ROSTER, "berth_3");
    }

    private static final List<DialogueCtx.RosterEntry> ROSTER = List.of(
            new DialogueCtx.RosterEntry("V1", "cargo_vessel", "Santa Lucia", "berth_3", 6),
            new DialogueCtx.RosterEntry("T1", "tanker", "Carthago", "berth_2", 8));

    /** The other open tab: the tanker Carthago offers EUR 4500 for 8 h at berth 2. */
    private static final DialogueCtx CARTHAGO = new DialogueCtx("T1",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(4500.0, 8, "berth_2"),
                    DialogueCtx.OfferView.NONE),
            ROSTER, "berth_2");

    private static DialogueCtx tabOf(String vesselId) {
        return "T1".equals(vesselId) ? CARTHAGO : null;
    }

    private static final DialogueCtx FRESH = table(null);
    private static final DialogueCtx AFTER_A_BID = table(1900.0);

    private static PipelineResult typed(String text, DialogueCtx ctx) throws Exception {
        return pipeline.processChatInput(text, ctx).get(15, TimeUnit.SECONDS);
    }

    private static String asked(String text, DialogueCtx ctx) throws Exception {
        return assertInstanceOf(PipelineResult.NeedsClarification.class, typed(text, ctx),
                () -> "expected a question, not a route, for: " + text).message();
    }

    private static JsonNode sent(String text, DialogueCtx ctx) throws Exception {
        ACLMessage msg = assertInstanceOf(PipelineResult.Routed.class, typed(text, ctx),
                () -> "expected a route for: " + text).msg();
        assertEquals(ACLMessage.PROPOSE, msg.getPerformative(), text);
        return new ObjectMapper().readTree(msg.getContent());
    }

    // --- N1 -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] is never sent as a price")
    @ValueSource(strings = {"8", "make it 8", "how about 8", "2", "I want 12", "8 euros", "24"})
    void aSmallNumberIsNeverSentAsAPrice(String text) throws Exception {
        assertTrue(asked(text, FRESH).startsWith("Did you mean €"), text);
        assertTrue(asked(text, AFTER_A_BID).startsWith("Did you mean €"), text);
    }

    @ParameterizedTest(name = "[{0}] is far below her offer")
    @ValueSource(strings = {"700 for 6h", "make it 400", "190 for 6 hours", "how about 500", "1436 less"})
    void aPriceBelowHalfOfHerOfferIsAskedAbout(String text) throws Exception {
        String message = asked(text, AFTER_A_BID);
        assertTrue(message.contains("less than half of their offer (€1436)"), text + " -> " + message);
    }

    // --- N2, and the signed forms of N1 -------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] is a demand of 1900")
    @ValueSource(strings = {"€1.900", "1.900 for 6h", "make it 1.900.", "I'll give you 1.900.",
            "how about 1.900 euros", "1900"})
    void europeanThousandsReachTheWireAsWholeNumbers(String text) throws Exception {
        assertEquals(1900.0, sent(text, FRESH).path("money").path("amount").asDouble(), 1e-9, text);
    }

    @ParameterizedTest(name = "[{0}] -> {1} on a fresh tab, {2} after a bid of 1900")
    @CsvSource({
            "+200,          1636, 2100",   // a change to the reference offer: hers, then the player's own
            "-200,          1236, 1700",
            "+10%,          1580, 2090",
            "+200 euros,    1636, 2100",
            "200 higher,    1636, 2100",
            "10 percent lower, 1292, 1710",
    })
    void aSignedNumberIsAChangeToTheOfferOnTheTable(String text, double fresh, double afterABid) throws Exception {
        assertEquals(fresh, sent(text, FRESH).path("money").path("amount").asDouble(), 1e-9, text);
        assertEquals(afterABid, sent(text, AFTER_A_BID).path("money").path("amount").asDouble(), 1e-9, text);
    }

    // --- the demo's own lines, and the new phrasings, still go out -------------------------------------

    @ParameterizedTest(name = "[{0}] still routes")
    @ValueSource(strings = {"1900 for 6 hours at berth 3", "2400 for 6 hours at berth 3", "1900 for 6 hours",
            "how about 1900 for 6 hours at berth 3", "can you do 1800", "I want 1900 and 8 hours",
            "1900 + 6 hours"})
    void theDemoLinesAndTheNewPhrasingsStillRoute(String text) throws Exception {
        assertTrue(sent(text, FRESH).path("money").path("amount").asDouble() >= 1800, text);
    }

    // --- hours without a price (review finding 2) --------------------------------------------------------

    /**
     * Born red. With only HER offer on the table the grammar completes "8h" with her own price -
     * a demand of exactly what she offers, which she accepts on the spot: a question about hours
     * closed the deal at the opening price. The hours-only forms are older than S18a ("make it 6
     * hours"); S18a added the cue forms and a hint that pointed a player at "8h", so it is closed
     * here for all of them.
     */
    @ParameterizedTest(name = "[{0}] on a fresh tab does not close at her price")
    @ValueSource(strings = {"8h", "what about 8 hours", "make it 8 hours", "same but 8 hours", "can you do 8 hours?",
            "I want 8 hours"})
    void hoursWithoutAPriceDoNotCloseAtHerPrice(String text) throws Exception {
        assertEquals("That would close at their own price (€1436) for 8h — you gave hours but no "
                + "price. Write both, like '{example}', or press Accept to take their offer as it stands.",
                asked(text, FRESH), text);
    }

    /** Once the player has named a price, hours alone change the hours of THAT demand - the
     * ordinary ellipsis, and nothing to ask about. */
    @Test
    void hoursAloneAfterABidChangeTheHoursOfThatBid() throws Exception {
        JsonNode content = sent("what about 8 hours", AFTER_A_BID);

        assertEquals(1900.0, content.path("money").path("amount").asDouble(), 1e-9);
        assertEquals(8, content.path("duration").asInt());
    }

    /** A player who TYPES her price means it: that is an acceptance with other hours, in words. */
    @Test
    void herOwnPriceTypedOutIsSent() throws Exception {
        JsonNode content = sent("1436 for 8h", FRESH);

        assertEquals(1436.0, content.path("money").path("amount").asDouble(), 1e-9);
        assertEquals(8, content.path("duration").asInt());
    }

    // --- a move for another tab's vessel (review finding 5) -------------------------------------------------

    /**
     * "Carthago: 1000", typed in Santa Lucia's tab. The line parses in her tab (the inner "1000"
     * completes from HER offer) and is then re-parsed under Carthago's own - so the price is held
     * against Carthago's EUR 4500, and the refusal says so and says whom it is about. EUR 1000 is
     * above half of Santa Lucia's EUR 1436: judged against the tab on screen it would have gone
     * out, and Carthago would have taken it.
     */
    @Test
    void aMoveForAnotherTabsVesselIsHeldAgainstThatVesselsOffer() throws Exception {
        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                pipeline.processChatInput("Carthago: 1000", FRESH, InputSafetyPipelineTest::tabOf)
                        .get(15, TimeUnit.SECONDS));

        assertEquals("Did you mean €1000? That is less than half of Carthago's offer (€4500), so nothing "
                + "was sent — a price looks like '{example}'.", asked.message());
        assertEquals("T1", asked.aboutVesselId(), "the chat takes the example from Carthago's tab");
    }

    /** The other route to the same place: with nothing on the table in the tab it was typed in,
     * the line misses there and parses only under its addressee (the "Name:" prefix re-parse). */
    @Test
    void theSameHoldsWhenTheLineOnlyParsesUnderItsAddressee() throws Exception {
        DialogueCtx nothingHere = new DialogueCtx("V1", DialogueCtx.StandingOffer.NONE, ROSTER, null);

        PipelineResult.NeedsClarification asked = assertInstanceOf(PipelineResult.NeedsClarification.class,
                pipeline.processChatInput("Carthago: 1000", nothingHere, InputSafetyPipelineTest::tabOf)
                        .get(15, TimeUnit.SECONDS));

        assertTrue(asked.message().contains("less than half of Carthago's offer (€4500)"), asked.message());
        assertEquals("T1", asked.aboutVesselId());

        PipelineResult.NeedsClarification hours = assertInstanceOf(PipelineResult.NeedsClarification.class,
                pipeline.processChatInput("Carthago: 10 hours", nothingHere, InputSafetyPipelineTest::tabOf)
                        .get(15, TimeUnit.SECONDS));
        assertTrue(hours.message().startsWith("That would close at Carthago's own price (€4500) for 10h"),
                hours.message());
        assertEquals("T1", hours.aboutVesselId());
    }

    /** A plausible price for Carthago goes to Carthago, from either tab state. */
    @Test
    void aPlausibleMoveForAnotherTabsVesselIsSentToIt() throws Exception {
        PipelineResult.Routed routed = assertInstanceOf(PipelineResult.Routed.class,
                pipeline.processChatInput("Carthago: 5000 for 8 hours", FRESH, InputSafetyPipelineTest::tabOf)
                        .get(15, TimeUnit.SECONDS));

        assertEquals("T1", routed.addressee());
        assertEquals(5000.0, new ObjectMapper().readTree(routed.msg().getContent())
                .path("money").path("amount").asDouble(), 1e-9);
    }

    // --- answered before Rasa (which is down) -------------------------------------------------------------

    @Test
    void aBerthThatDoesNotExistIsAnsweredWithRasaDown() throws Exception {
        assertEquals("There is no berth 5 — berths are 1 to 4 (ferries fit only berth 4).",
                asked("berth 5", FRESH));
        assertEquals("There is no berth 9 — berths are 1 to 4 (ferries fit only berth 4).",
                asked("2000 for 6h at berth-9", FRESH));
    }

    @Test
    void aPolicyIsAnsweredWithRasaDown() throws Exception {
        assertEquals("Policies are set in Settings → Autopilot policy — nothing was sent to the vessel.",
                asked("auto accept if price > 6000 for tankers", FRESH));
    }
}
