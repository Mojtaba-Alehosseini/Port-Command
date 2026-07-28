package it.unige.portcommand.behaviours.negotiation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.WalkInState;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walk-in opening move: PROPOSE an opening price (a personality-sampled lowball
 * fraction of {@code targetPrice} — task 19 STEP 1; see {@link Personality}'s
 * javadoc) and the desired duration to the HarbourMaster, and stamp the negotiation
 * start time. Only price / hours / vessel_spec go on the wire — the hidden beliefs
 * stay in {@link WalkInState}.
 */
public final class OpeningProposalBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(OpeningProposalBehaviour.class);

    private final AtomicReference<WalkInState> stateRef;
    private final String conversationId;
    private final AtomicBoolean concluded;
    private final double openingRoll;

    /**
     * @param openingRoll a pre-drawn {@code [0,1)} value (from the caller's own seeded
     *                    sub-stream) picking where in the personality's opening sub-band
     *                    this vessel's offer lands — kept out of this class so
     *                    {@link #computeOpening} stays a pure, directly unit-testable
     *                    function with no {@code java.util.Random} dependency here.
     */
    public OpeningProposalBehaviour(Agent agent, AtomicReference<WalkInState> stateRef,
                                    String conversationId, AtomicBoolean concluded, double openingRoll) {
        super(agent);
        this.stateRef = stateRef;
        this.conversationId = conversationId;
        this.concluded = concluded;
        this.openingRoll = openingRoll;
    }

    @Override
    public void action() {
        if (concluded.get()) {
            return;
        }
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        WalkInState state = stateRef.get();
        double opening = computeOpening(state.targetPrice(), state.personality(), openingRoll);

        Optional<AID> harbourMaster = vessel.serviceLocator().findUnique("harbour-master");
        if (harbourMaster.isEmpty()) {
            log.warn("{}: no harbour-master in DF; cannot open negotiation", myAgent.getLocalName());
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("intent", "opening_offer");
        content.put("price", opening);
        content.put("hours", state.dealHours());
        content.put("vessel_spec", vessel.spec());

        ACLMessage propose = MessageFactory.create(ACLMessage.PROPOSE);
        propose.addReceiver(harbourMaster.get());
        propose.setConversationId(conversationId);
        propose.setContent(TerminalJson.write(content));
        myAgent.send(propose);

        stateRef.set(state.markStarted(vessel.simClock().nowSimMillis())
                .recordOwnOffer(opening, state.dealHours()));
        // DEBUG since task 26: the PROPOSE is already in the comm log, and the hub logs the
        // forwarded offer. Three classes were reporting one event.
        log.debug("{} opened at {} ({}h) -> harbour-master", myAgent.getLocalName(), opening, state.dealHours());
    }

    /**
     * The opening price: a personality-sampled lowball fraction of {@code targetPrice},
     * rounded to whole euros at the source (task 19 STEP 1 — the player must never see
     * sub-euro cents). Public and static (not package-private) so the
     * {@code [0.70*minTarget, 0.85*maxTarget]} bound is unit-testable with no live JADE
     * agent at all, from {@code OpeningProposalBehaviourTest} in the parent {@code
     * behaviours} test package — a test in this sub-package would shadow
     * {@code BehaviourCatalogueTest}'s scan.
     */
    public static double computeOpening(double targetPrice, Personality personality, double roll) {
        return Math.round(targetPrice * personality.sampleOpeningFraction(roll));
    }
}
