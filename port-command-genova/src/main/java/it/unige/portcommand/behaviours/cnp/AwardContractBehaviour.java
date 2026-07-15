package it.unige.portcommand.behaviours.cnp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.prolog.TugBid;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores the collected bids and closes out the Contract Net. Zero bids (or every
 * PROPOSE malformed) -> INFORM the vessel to hold; the retry seam is task 15's
 * {@code CnpRetryBehaviour}, not fired here. One or more usable bids ->
 * {@link PrologQueries#selectBestBids} (RULE R13) picks the winner(s) — the
 * Prolog-gated decision behind every tug ACCEPT_PROPOSAL this agent sends.
 */
public final class AwardContractBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AwardContractBehaviour.class);

    private final CnpRequest req;
    private final List<ACLMessage> proposals;

    public AwardContractBehaviour(Agent agent, CnpRequest req, List<ACLMessage> proposals) {
        super(agent);
        this.req = req;
        this.proposals = proposals;
    }

    @Override
    public void action() {
        Map<String, ACLMessage> bidsByTugId = new LinkedHashMap<>();
        List<TugBid> bids = new ArrayList<>(proposals.size());
        for (ACLMessage p : proposals) {
            JsonNode content = TerminalJson.readTreeOrNull(p.getContent());
            if (content == null || !content.hasNonNull("cost") || !content.hasNonNull("eta_minutes")
                    || !content.hasNonNull("fuel_state")) {
                continue; // malformed bid, silently excluded from scoring
            }
            String tugId = p.getSender().getLocalName();
            bids.add(new TugBid(tugId, content.get("cost").asDouble(),
                    content.get("eta_minutes").asDouble(), content.get("fuel_state").asDouble()));
            bidsByTugId.put(tugId, p);
        }
        if (bids.isEmpty()) {
            holdVessel("no_bids");
            return;
        }

        List<String> winners = PrologQueries.selectBestBids(bids, req.tugsRequired());
        for (Map.Entry<String, ACLMessage> entry : bidsByTugId.entrySet()) {
            ACLMessage bid = entry.getValue();
            AID tugAid = bid.getSender();
            if (winners.contains(entry.getKey())) {
                ACLMessage accept = MessageFactory.reply(bid, ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(TerminalJson.write(
                        Map.of("vessel_id", req.vesselId(), "berth_position", req.berthPosition())));
                myAgent.send(accept);
                log.info("CNP for {}: ACCEPT -> {}", req.vesselId(), tugAid.getLocalName());
            } else {
                myAgent.send(MessageFactory.reply(bid, ACLMessage.REJECT_PROPOSAL));
                log.info("CNP for {}: REJECT -> {}", req.vesselId(), tugAid.getLocalName());
            }
        }

        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        hm.activeVessels().computeIfPresent(req.vesselId(),
                (id, tracking) -> tracking.withTugs(winners).withStage(Stage.IN_TRANSIT));
    }

    private void holdVessel(String reason) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(req.vesselAid());
        inform.setContent(TerminalJson.write(Map.of("event", "tug_hold", "reason", reason)));
        myAgent.send(inform);
        log.info("CNP for {} held: {}", req.vesselId(), reason);
    }
}
