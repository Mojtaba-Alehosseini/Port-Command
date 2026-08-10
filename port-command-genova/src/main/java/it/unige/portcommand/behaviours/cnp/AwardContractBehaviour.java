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

    /** No tug answered the CFP at all. */
    static final String NO_BIDS = "no_bids";
    /** Tugs answered, but Prolog's R13 selected none of them (audit P1-5). */
    static final String NO_WINNING_BID = "no_winning_bid";

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
            holdVessel(hm, NO_BIDS);
            // Terminal path (the retry, if scheduled, re-enters through the queue) — release
            // the serialized-CNP queue (B2).
            hm.cnpCoordinator().completed();
            return;
        }

        Map<String, Double> costByTugId = new LinkedHashMap<>();
        bids.forEach(b -> costByTugId.put(b.tugId(), b.cost()));

        List<String> winners = PrologQueries.selectBestBids(bids, req.tugsRequired());
        if (winners.isEmpty()) {
            // Audit P1-5. Without this the loop below REJECTs every bidder and the vessel is
            // still set withTugs([]).withStage(IN_TRANSIT) — under way with no escort at all, no
            // hold, no retry, and the retry counter cleared. Route it to the same hold+retry the
            // zero-bid branch above uses instead.
            //
            // Honest reachability note: this is a DEFENSIVE guard, not a live bug. CnpRequest
            // validates tugsRequired >= 1, and R13 (best_n_bids) returns at least one winner for
            // any non-empty, well-formed bid list, so reaching here needs the Prolog goal itself
            // to fail — e.g. a non-finite cost rendering as an unbound variable in the term.
            // PrologQueries.selectBestBids collapses exactly that failure to an empty list with no
            // log line, which is why it deserves a guard rather than a comment. The genuinely
            // reachable sibling — winners.size() < tugsRequired, an under-strength escort — is
            // backlog A-08 and is deliberately NOT changed here.
            log.warn("CNP for {}: {} bid(s) collected but Prolog selected no winner — holding "
                    + "rather than sending the vessel out unescorted", req.vesselId(), bids.size());
            // Answer every bidder before leaving (2026-08-10). Holding the vessel without
            // replying left each tug that bid waiting on a Contract Net that never concluded —
            // its own CFP conversation simply went quiet. Same contentless REJECT the ordinary
            // loser path below sends, so the tugs see a normal end to the session.
            rejectAll(hm, bidsByTugId);
            holdVessel(hm, NO_WINNING_BID);
            hm.cnpCoordinator().completed();
            return;
        }
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

    /** Contentless REJECT to every bidder — the shape the ordinary loser path sends. */
    private void rejectAll(HarbourMasterAgent hm, Map<String, ACLMessage> bidsByTugId) {
        for (Map.Entry<String, ACLMessage> entry : bidsByTugId.entrySet()) {
            hm.sendLogged(MessageFactory.reply(entry.getValue(), ACLMessage.REJECT_PROPOSAL));
            log.info("CNP for {}: REJECT -> {} (no winner selected)", req.vesselId(), entry.getKey());
        }
    }

    private void holdVessel(HarbourMasterAgent hm, String reason) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(req.vesselAid());
        inform.setContent(TerminalJson.write(Map.of("event", "tug_hold", "reason", reason)));
        hm.sendLogged(inform);
        log.info("CNP for {} held: {}", req.vesselId(), reason);
        // Both no-escort-available outcomes retry: no tug answered, or tugs answered but none was
        // selectable. The reasons stay DISTINCT on the wire (audit P1-5) — saying "no_bids" when
        // bids were collected would misreport the situation in the vessel's INFORM and the comm
        // log — but they warrant the same bounded recovery.
        if (NO_BIDS.equals(reason) || NO_WINNING_BID.equals(reason)) {
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
