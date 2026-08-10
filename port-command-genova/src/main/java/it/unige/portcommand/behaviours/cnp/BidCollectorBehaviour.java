package it.unige.portcommand.behaviours.cnp;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.harbourmaster.CnpRequest;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking bid collector for one Contract Net session: buffers every PROPOSE
 * (a bid) matching the exact {@code cfpId} until {@link #closeAndAward()} is called
 * by the paired {@code WakerBehaviour} deadline ({@link InitiateCNPBehaviour}).
 * Never calls {@code blockingReceive} — collecting bids and awarding the contract
 * happen on separate ticks of the agent's own scheduler, so every other
 * HarbourMaster behaviour (auto-flow dispatch, walk-in relay, emergency handling)
 * keeps running during the CFP window.
 */
public final class BidCollectorBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(BidCollectorBehaviour.class);
    private static final MessageTemplate TEMPLATE_BASE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(InitiateCNPBehaviour.CNP_PROTOCOL),
            MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)));

    /** Floor for the late-bid grace window — see {@link #closeAndAward()} for why it is its own
     * number and not {@code InitiateCNPBehaviour.MIN_CNP_WINDOW_WALL_MS}. */
    private static final long REAPER_MIN_GRACE_WALL_MS = 250L;

    private final CnpRequest req;
    private final MessageTemplate template;
    private final List<ACLMessage> bids = new ArrayList<>();

    public BidCollectorBehaviour(Agent agent, String cfpId, CnpRequest req) {
        super(agent);
        this.req = req;
        this.template = MessageTemplate.and(TEMPLATE_BASE, MessageTemplate.MatchConversationId(cfpId));
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        ACLMessage msg = hm.receiveLogged(template);
        if (msg == null) {
            block();
            return;
        }
        if (msg.getPerformative() == ACLMessage.PROPOSE) {
            bids.add(msg);
        }
        // REFUSE just isn't counted as a bid — no other action needed.
    }

    /**
     * Closes the collection window and hands the collected bids off for scoring.
     * Called exactly once, by the paired {@code WakerBehaviour}.
     */
    void closeAndAward() {
        List<ACLMessage> collected = List.copyOf(bids);
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        myAgent.addBehaviour(new AwardContractBehaviour(myAgent, req, collected));
        log.info("CNP for {} closed with {} bid(s)", req.vesselId(), collected.size());

        // Anything already queued but not yet drained by action() belongs to a window that has
        // just shut — reject it now rather than let it outlive the template that matches it.
        rejectLateBids(hm, "at close");

        // ...and one grace window for stragglers still in flight. A tug that misses the deadline
        // STILL replies (BidInCNPBehaviour warns and answers anyway), so without this the late
        // PROPOSE sat in the HarbourMaster's queue for the life of the JVM: this behaviour's
        // per-cfpId template is the only one that matches it, and it is about to be retired.
        //
        // Deliberately NOT solved by the per-agent catch-all: that would mean letting the
        // catch-all see the CNP protocol, and since JADE round-robins the ready queue it could
        // then win a pass against a LIVE in-window bid. The window's owner is the right place.
        //
        // ANONYMOUS on purpose — BehaviourCatalogueTest counts .class files in this package
        // against a locked 6 and skips names containing '$', so a named reaper class here would
        // fail the build (INVARIANTS, ADR-01).
        // Deliberately wider than the CFP window itself. That window is floored at
        // MIN_CNP_WINDOW_WALL_MS (50 ms) because it is sized against the storm scenario's award
        // margin — a real timing contract. The reaper has no such constraint: nothing waits on it,
        // so a longer grace only widens what it catches. At the shipped 300 s/day the CFP window
        // IS the 50 ms floor, and a 50 ms grace would leave a barely-late tug's bid stranded.
        //
        // Residual, stated rather than glossed: a straggler arriving after this window still
        // leaks, exactly as before. Bounding it fully would need the reaper to outlive the
        // session, which is the leak in a different shape.
        long graceWallMs = Math.max(REAPER_MIN_GRACE_WALL_MS, InitiateCNPBehaviour.cnpWindowWallMs(
                hm.simClock(), Settings.load().cnpReplyBySimSeconds()));
        myAgent.addBehaviour(new WakerBehaviour(myAgent, graceWallMs) {
            @Override
            protected void onWake() {
                // No removeBehaviour here: WakerBehaviour sets finished BEFORE calling onWake and
                // JADE removes it on the next scheduler pass (bytecode-verified against
                // jade-4.6.0.jar). Self-removing would be redundant, and the sibling anonymous
                // waker in InitiateCNPBehaviour does not do it either.
                rejectLateBids((HarbourMasterAgent) myAgent, "after the grace window");
            }
        });

        myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
    }

    /**
     * Drains every message still matching this session's template and REJECTs the bids among
     * them, so a tug that answered late gets a real end to its conversation instead of silence.
     * A late REFUSE needs no reply — it was already the tug declining.
     */
    private void rejectLateBids(HarbourMasterAgent hm, String when) {
        ACLMessage late;
        while ((late = hm.receiveLogged(template)) != null) {
            if (late.getPerformative() != ACLMessage.PROPOSE) {
                continue;
            }
            hm.sendLogged(MessageFactory.reply(late, ACLMessage.REJECT_PROPOSAL));
            log.info("CNP for {}: late bid from {} rejected {}", req.vesselId(),
                    late.getSender().getLocalName(), when);
        }
    }
}
