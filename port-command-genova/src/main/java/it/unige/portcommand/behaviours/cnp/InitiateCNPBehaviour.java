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
        myAgent.addBehaviour(new WakerBehaviour(myAgent, simClock.simSecondsToWallMs(replyBySimSeconds)) {
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
