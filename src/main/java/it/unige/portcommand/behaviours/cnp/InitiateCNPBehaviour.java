package it.unige.portcommand.behaviours.cnp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a tug Contract Net: DF-discovers every {@code tug-escort}, sends a CFP with
 * a sim-time {@code reply_by} deadline, then attaches the non-blocking
 * {@link BidCollectorBehaviour} paired with a {@link WakerBehaviour} that closes the
 * window. Never blocks the agent thread. Zero tugs registered -> INFORM the vessel
 * to hold; nothing to collect.
 */
public final class InitiateCNPBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(InitiateCNPBehaviour.class);

    /**
     * FIPA's standard Contract-Net protocol tag — discriminates CNP traffic from
     * every other conversation on the HarbourMaster's shared inbound queue
     * ({@code ForwardWalkInToPlayerBehaviour} explicitly excludes it).
     */
    public static final String CNP_PROTOCOL = "fipa-contract-net";

    /**
     * Wall-clock floor for the CFP bid window and the CNP retry delay (audit A-03, 2026-07-27).
     *
     * <p>These two were the only sim→wall conversions in the codebase without one —
     * {@code SimTickerBehaviour.wallPeriodMs}, {@code RefuelIfLowBehaviour} and
     * {@code PoissonSpawnBehaviour.nextGapWallMs} all clamp. At the sandbox default (300
     * real-seconds per game day) {@code simSecondsToWallMs(5)} is <b>17 ms</b>: every tug had to
     * receive the CFP, score a bid and reply, and the hub's collector had to run, inside 17 ms —
     * and on the first CNP of a process, with Jackson still class-loading its serializers, that is
     * a coin flip. Under {@code --day-length 17} or below the integer division floors to a literal
     * <b>0</b>, so the waker fired on the next scheduler pass and {@code closeAndAward()} ran with
     * zero bids every single time. The retry delay collapsed with it — 30 sim-seconds is 5 ms at
     * {@code --day-length 17} and 1 ms at the {@code --day-length 5} that {@code Main}'s own javadoc
     * names for integration runs — so all three retries were spent as fast as the scheduler could
     * run them. The visible outcome was an empty Contract Net: a held vessel, no
     * CANCEL/ACCEPT/REJECT traffic at all.
     *
     * <p><b>Why 50 and not more.</b> The floor must stay BELOW the smallest window any shipped
     * scenario already uses — 52 ms at 900 s/day — so that no scenario timing moves. That matters
     * concretely: in {@code storm.json} the tanker SPAWNS at t=960 s and the wind crosses 30 kn at
     * t=990 s, which is 312 ms of wall clock at 900 s/day, and the demo's CANCEL beat only exists if
     * the tugs are awarded inside that gap. Note 312 ms is an UPPER BOUND on the real margin, not
     * the margin itself: the grant follows the spawn through the arrival REQUEST, a DF resolve and
     * the Prolog gate, so the window the award actually has is strictly shorter. A larger floor
     * would eat the margin task 26 retimed (BUG-02). Pinned by {@code CnpWindowFloorTest}.
     */
    public static final long MIN_CNP_WINDOW_WALL_MS = 50L;

    /** {@link SimClock#simSecondsToWallMs} with {@link #MIN_CNP_WINDOW_WALL_MS} applied. */
    public static long cnpWindowWallMs(SimClock simClock, long simSeconds) {
        return Math.max(MIN_CNP_WINDOW_WALL_MS, simClock.simSecondsToWallMs(simSeconds));
    }

    private final CnpRequest req;
    private final ServiceLocator serviceLocator;
    private final SimClock simClock;

    public InitiateCNPBehaviour(Agent agent, CnpRequest req, ServiceLocator serviceLocator, SimClock simClock) {
        super(agent);
        this.req = req;
        this.serviceLocator = serviceLocator;
        this.simClock = simClock;
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        List<AID> tugs = serviceLocator.findAll("tug-escort");
        if (tugs.isEmpty()) {
            log.warn("no tug-escort registered in DF; holding vessel {}", req.vesselId());
            holdVessel(hm, "no_tugs_registered");
            // Terminal path with no collector spawned — release the serialized-CNP queue (B2).
            hm.cnpCoordinator().completed();
            return;
        }

        int replyBySimSeconds = Settings.load().cnpReplyBySimSeconds();
        String cfpId = "cnp-" + UUID.randomUUID();
        long replyBySimMillis = simClock.nowSimMillis() + replyBySimSeconds * 1000L;

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("target_vessel_id", req.vesselId());
        content.put("target_vessel_position", req.vesselPosition());
        content.put("reply_by", replyBySimMillis);

        ACLMessage cfp = MessageFactory.create(ACLMessage.CFP);
        tugs.forEach(cfp::addReceiver);
        cfp.setProtocol(CNP_PROTOCOL);
        cfp.setConversationId(cfpId);
        cfp.setContent(TerminalJson.write(content));
        hm.sendLogged(cfp);
        log.info("CFP {} ({} tug(s) required) -> {} tug(s) on CNP {}",
                req.vesselId(), req.tugsRequired(), tugs.size(), cfpId);

        BidCollectorBehaviour collector = new BidCollectorBehaviour(myAgent, cfpId, req);
        myAgent.addBehaviour(collector);
        myAgent.addBehaviour(new WakerBehaviour(myAgent, cnpWindowWallMs(simClock, replyBySimSeconds)) {
            @Override
            protected void onWake() {
                collector.closeAndAward();
            }
        });
    }

    private void holdVessel(HarbourMasterAgent hm, String reason) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(req.vesselAid());
        inform.setContent(TerminalJson.write(Map.of("event", "tug_hold", "reason", reason)));
        hm.sendLogged(inform);
    }
}
