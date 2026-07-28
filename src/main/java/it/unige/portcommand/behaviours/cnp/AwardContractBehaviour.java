package it.unige.portcommand.behaviours.cnp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.harbourmaster.cnp.CnpRetryBehaviour;
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
 * PROPOSE malformed) -> INFORM the vessel to hold, then schedule task 15's
 * {@link CnpRetryBehaviour} (bounded at {@link CnpRetryBehaviour#MAX_RETRIES}). One or more
 * usable bids -> {@link PrologQueries#selectBestBids} (RULE R13) picks the winner(s) — the
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
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
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
            holdVessel(hm, "no_bids");
            // Terminal path (the retry, if scheduled, re-enters through the queue) — release
            // the serialized-CNP queue (B2).
            hm.cnpCoordinator().completed();
            return;
        }

        Map<String, Double> costByTugId = new LinkedHashMap<>();
        bids.forEach(b -> costByTugId.put(b.tugId(), b.cost()));

        List<String> winners = PrologQueries.selectBestBids(bids, req.tugsRequired());
        for (Map.Entry<String, ACLMessage> entry : bidsByTugId.entrySet()) {
            ACLMessage bid = entry.getValue();
            AID tugAid = bid.getSender();
            if (winners.contains(entry.getKey())) {
                ACLMessage accept = MessageFactory.reply(bid, ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(TerminalJson.write(
                        Map.of("vessel_id", req.vesselId(), "berth_position", req.berthPosition())));
                hm.sendLogged(accept);
                log.info("CNP for {}: ACCEPT -> {}", req.vesselId(), tugAid.getLocalName());
                // Task 20: the winning bid's cost dies here otherwise — the ACCEPT above carries
                // only vessel_id + berth_position, and the later escort_complete INFORM carries no
                // cost either. Publishing it is what lets the wallet pay the price Prolog actually
                // chose, so a cheaper bid really does save the port money. Charge-point is AWARD
                // (Moji, 2026-07-17); the ledger dedupes on (conversationId, tugId) because two
                // co-winning tugs share this one cfpId. See TugJobAwardedEvent's javadoc.
                hm.eventBus().publish(new TugJobAwardedEvent(
                        req.vesselId(), entry.getKey(), costByTugId.get(entry.getKey()),
                        bid.getConversationId(), hm.simClock().nowSimMillis()));
            } else {
                hm.sendLogged(MessageFactory.reply(bid, ACLMessage.REJECT_PROPOSAL));
                log.info("CNP for {}: REJECT -> {}", req.vesselId(), tugAid.getLocalName());
            }
        }

        hm.activeVessels().computeIfPresent(req.vesselId(),
                (id, tracking) -> tracking.withTugs(winners).withStage(Stage.IN_TRANSIT));
        hm.cnpRetryAttempts().remove(req.vesselId());
        // Awards are on the wire (before any queued CFP, per-pair FIFO) — the winning tugs will
        // set currentJob before they see the next session's CFP, which is what makes their
        // busy-REFUSE guard effective. Release the serialized-CNP queue (B2).
        hm.cnpCoordinator().completed();
    }

    private void holdVessel(HarbourMasterAgent hm, String reason) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(req.vesselAid());
        inform.setContent(TerminalJson.write(Map.of("event", "tug_hold", "reason", reason)));
        hm.sendLogged(inform);
        log.info("CNP for {} held: {}", req.vesselId(), reason);
        if ("no_bids".equals(reason)) {
            scheduleRetryIfWithinCap(hm);
        }
    }

    /** Schedules {@link CnpRetryBehaviour}, bounded at {@link CnpRetryBehaviour#MAX_RETRIES}. */
    private void scheduleRetryIfWithinCap(HarbourMasterAgent hm) {
        int attempt = hm.cnpRetryAttempts()
                .computeIfAbsent(req.vesselId(), id -> new AtomicInteger(0))
                .incrementAndGet();
        if (attempt > CnpRetryBehaviour.MAX_RETRIES) {
            log.warn("CNP for {}: giving up after {} retries", req.vesselId(), CnpRetryBehaviour.MAX_RETRIES);
            hm.cnpRetryAttempts().remove(req.vesselId());
            return;
        }
        // Floored (audit A-03): unfloored this was 1 ms under --day-length 5, so all three retries
        // collected zero bids as fast as the scheduler could run them and the cap was spent in
        // milliseconds. See InitiateCNPBehaviour.MIN_CNP_WINDOW_WALL_MS.
        long delayWallMs = InitiateCNPBehaviour.cnpWindowWallMs(
                hm.simClock(), Settings.load().cnpRetryAfterSimSeconds());
        myAgent.addBehaviour(new CnpRetryBehaviour(myAgent, hm.cnpCoordinator(), req, delayWallMs));
        log.info("CNP for {}: scheduling retry {}/{} in {} sim-s",
                req.vesselId(), attempt, CnpRetryBehaviour.MAX_RETRIES, Settings.load().cnpRetryAfterSimSeconds());
    }
}
