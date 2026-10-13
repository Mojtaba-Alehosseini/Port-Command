package it.unige.portcommand.behaviours.cnp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.MovementControl;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.MovementGate;
import it.unige.portcommand.harbourmaster.VesselTracking;
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
    /** S16: bids came in, but too few for the whole escort while another ship holds a partial one. */
    static final String NOT_ENOUGH_TUGS = "not_enough_tugs";

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
        // S11 (logic hunt L3 + fix review): completed() lives in finally — EVERY exit from the
        // award step, an unexpected exception included, must release the serialized-CNP queue,
        // or each later Contract Net queues forever behind a session that never closed. The
        // catch keeps the HarbourMaster alive: JADE's Agent.run treats an uncaught action()
        // exception as agent death (the B11 dead-hub lesson), which is strictly worse than one
        // unawarded session — the vessel's own timeout/withdraw machinery still runs.
        try {
            awardOrReject(hm);
        } catch (RuntimeException e) {
            log.error("CNP for {}: award step failed — session closed with no award; no retry "
                    + "was scheduled", req.vesselId(), e);
            // S16: the ship's tender is over as far as the notebook goes, or it could never be
            // tendered again (MovementControl opens one request per ship at a time).
            MovementControl.tenderClosed(hm, req.vesselId());
        } finally {
            hm.cnpCoordinator().completed();
        }
    }

    private void awardOrReject(HarbourMasterAgent hm) {
        Map<String, ACLMessage> bidsByTugId = new LinkedHashMap<>();
        List<TugBid> bids = new ArrayList<>(proposals.size());
        for (ACLMessage p : proposals) {
            JsonNode content = TerminalJson.readTreeOrNull(p.getContent());
            // S11 (logic hunt, L3): non-finite is malformed. JSON "1e999" parses to Infinity,
            // PrologTerms would render it as the bare token `Infinity` — a VARIABLE in Prolog
            // — and R12's is/2 then RAISES instantiation_error rather than failing. Filter it
            // here at the seam, where a bad bid costs one bidder and not the session.
            if (content == null || !content.hasNonNull("cost") || !content.hasNonNull("eta_minutes")
                    || !content.hasNonNull("fuel_state")
                    || !Double.isFinite(content.get("cost").asDouble())
                    || !Double.isFinite(content.get("eta_minutes").asDouble())
                    || !Double.isFinite(content.get("fuel_state").asDouble())) {
                continue; // malformed bid, silently excluded from scoring
            }
            String tugId = p.getSender().getLocalName();
            bids.add(new TugBid(tugId, content.get("cost").asDouble(),
                    content.get("eta_minutes").asDouble(), content.get("fuel_state").asDouble()));
            bidsByTugId.put(tugId, p);
        }
        // S16 (ADR-20): the movement gate, asked AT THE AWARD. A Contract Net is asked for when
        // the ship is granted and may be awarded much later (a queued session, a retry), so what
        // was true when it was asked for is checked again here, in this order:
        //   - the ship is still granted and waiting (not gone, not already under way, and not
        //     HELD: both hold producers write held entries at AWAITING_TUG and the release sweep
        //     tenders afresh on clear - S11, fix review R2);
        //   - the weather still allows the movement (before S16 nothing asked: tugs were hired
        //     into a storm, cancelled without refund and hired again);
        //   - it still lacks tugs (a tender that was queued behind another one for the same ship
        //     must not over-hire).
        VesselTracking current = hm.activeVessels().get(req.vesselId());
        if (current == null || current.stage() != Stage.AWAITING_TUG || current.weatherHeld()) {
            log.info("CNP for {}: award overtaken ({}) — {} bid(s) rejected", req.vesselId(),
                    current == null ? "vessel gone" : current.weatherHeld() ? "weather hold"
                            : "stage " + current.stage(), bidsByTugId.size());
            rejectAll(hm, bidsByTugId);
            MovementControl.tenderClosed(hm, req.vesselId());
            return;
        }
        MovementGate.Verdict verdict = MovementGate.check(current.vesselType(), MovementControl.reading(hm));
        if (!verdict.safe()) {
            log.info("CNP for {}: weather turned unsafe before the award ({}) — {} bid(s) rejected, "
                    + "the vessel is held", req.vesselId(), verdict.reason(), bidsByTugId.size());
            rejectAll(hm, bidsByTugId);
            MovementControl.tenderClosed(hm, req.vesselId());
            MovementControl.hold(hm, current, verdict);
            return;
        }
        int shortfall = MovementControl.escortShortfall(current);
        if (shortfall <= 0) {
            log.info("CNP for {}: escort already complete — {} bid(s) rejected", req.vesselId(),
                    bidsByTugId.size());
            rejectAll(hm, bidsByTugId);
            MovementControl.tenderClosed(hm, req.vesselId());
            return;
        }

        if (bids.isEmpty()) {
            holdVessel(hm, NO_BIDS, shortfall);
            // Terminal path (the retry, if scheduled, re-enters through the queue); the
            // serialized-CNP queue is released by action()'s finally (B2).
            return;
        }

        Map<String, Double> costByTugId = new LinkedHashMap<>();
        bids.forEach(b -> costByTugId.put(b.tugId(), b.cost()));
        List<String> winners = PrologQueries.selectBestBids(bids, Math.min(req.tugsRequired(), shortfall));
        if (winners.isEmpty()) {
            // Audit P1-5: a DEFENSIVE guard, not a live bug. CnpRequest validates tugsRequired >= 1
            // and R13 (best_n_bids) returns at least one winner for any non-empty well-formed bid
            // list, so reaching here needs the Prolog goal to FAIL outright (a non-finite cost is
            // filtered at the parse above; a raise lands in action()'s catch). Every bidder is
            // answered before leaving, so no tug waits on a Contract Net that never concluded.
            log.warn("CNP for {}: {} bid(s) collected but Prolog selected no winner — holding "
                    + "rather than sending the vessel out unescorted", req.vesselId(), bids.size());
            rejectAll(hm, bidsByTugId);
            holdVessel(hm, NO_WINNING_BID, shortfall);
            return;
        }

        if (winners.size() < shortfall && current.assignedTugs().isEmpty()
                && MovementControl.anotherShipHoldsAPartialEscort(hm, req.vesselId())) {
            // S16 (adversarial review): at most ONE ship at a time holds an under-strength
            // escort. Another ship is already waiting with part of its escort on station; a
            // second partial award here would park one more tug beside it, and with a fleet of
            // four that ends with four ships holding one tug each and none able to move. This
            // ship waits for a tender that can give it its whole escort.
            log.info("CNP for {}: only {} of {} tug(s) on offer while another ship already holds a "
                    + "partial escort — {} bid(s) rejected, the vessel waits", req.vesselId(),
                    winners.size(), shortfall, bidsByTugId.size());
            rejectAll(hm, bidsByTugId);
            holdVessel(hm, NOT_ENOUGH_TUGS, shortfall);
            return;
        }

        // S17b (E11): the surcharge is billed on the ship's escort as it stands, so each award says
        // how big the escort is with this tug in it - the tugs that are already waiting count.
        java.util.Set<String> escortSoFar = new java.util.LinkedHashSet<>(current.assignedTugs());
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
                escortSoFar.add(entry.getKey());
                hm.eventBus().publish(new TugJobAwardedEvent(
                        req.vesselId(), entry.getKey(), costByTugId.get(entry.getKey()),
                        bid.getConversationId(), hm.simClock().nowSimMillis(), escortSoFar.size()));
            } else {
                hm.sendLogged(MessageFactory.reply(bid, ACLMessage.REJECT_PROPOSAL));
                log.info("CNP for {}: REJECT -> {}", req.vesselId(), tugAid.getLocalName());
            }
        }

        // S9 (P1-8): MERGE with any surviving escort rather than replace.
        // S16 (ADR-20): the award no longer writes IN_TRANSIT. The tugs have been HIRED, not
        // delivered: the ship stays AWAITING_TUG - at the channel entry - until every one of them
        // reports on station and MovementControl.tryProceed sends ship and escort off together.
        // Before S16 the tracking read "in transit" from this line on, while the tugs were some
        // three game hours from the ship and the ship itself had already docked.
        hm.activeVessels().computeIfPresent(req.vesselId(),
                (id, tracking) -> {
                    java.util.LinkedHashSet<String> escort =
                            new java.util.LinkedHashSet<>(tracking.assignedTugs());
                    escort.addAll(winners);
                    return tracking.withTugs(java.util.List.copyOf(escort));
                });
        hm.cnpRetryAttempts().remove(req.vesselId());
        // A ship released from a weather hold hears weather_resume now that its escort is hired.
        MovementControl.onAwarded(hm, req.vesselId());

        // S16 (C2, backlog A-08): an under-strength escort is not an escort. R13 returns as many
        // winners as there were bidders, so a two-tug ship with one free tug used to sail with
        // one. It now keeps waiting, the tug it has waits with it, and the missing tugs are
        // tendered for again - through the same bounded retry a no-bid session uses.
        VesselTracking after = hm.activeVessels().get(req.vesselId());
        int stillMissing = after == null ? 0 : MovementControl.escortShortfall(after);
        if (stillMissing > 0) {
            log.info("CNP for {}: {} tug(s) awarded, {} still missing — the vessel waits and the "
                    + "rest is tendered again", req.vesselId(), winners.size(), stillMissing);
            scheduleRetryIfWithinCap(hm, stillMissing);
        } else {
            MovementControl.tenderClosed(hm, req.vesselId());
        }
        // Awards are on the wire (before any queued CFP, per-pair FIFO) — the winning tugs will
        // set currentJob before they see the next session's CFP, which is what makes their
        // busy-REFUSE guard effective. The serialized-CNP queue is released by action()'s
        // finally (B2).
    }

    /** Contentless REJECT to every bidder — the shape the ordinary loser path sends. */
    private void rejectAll(HarbourMasterAgent hm, Map<String, ACLMessage> bidsByTugId) {
        for (Map.Entry<String, ACLMessage> entry : bidsByTugId.entrySet()) {
            hm.sendLogged(MessageFactory.reply(entry.getValue(), ACLMessage.REJECT_PROPOSAL));
            log.info("CNP for {}: REJECT -> {} (no winner selected)", req.vesselId(), entry.getKey());
        }
    }

    private void holdVessel(HarbourMasterAgent hm, String reason, int shortfall) {
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(req.vesselAid());
        inform.setContent(TerminalJson.write(Map.of("event", "tug_hold", "reason", reason)));
        hm.sendLogged(inform);
        log.info("CNP for {} held: {}", req.vesselId(), reason);
        // Both no-escort-available outcomes retry: no tug answered, or tugs answered but none was
        // selectable. The reasons stay DISTINCT on the wire (audit P1-5) — saying "no_bids" when
        // bids were collected would misreport the situation in the vessel's INFORM and the comm
        // log — but they warrant the same bounded recovery.
        scheduleRetryIfWithinCap(hm, shortfall);
    }

    /**
     * Schedules {@link CnpRetryBehaviour} for the {@code shortfall} tugs still missing, bounded at
     * {@link CnpRetryBehaviour#MAX_RETRIES} quick retries.
     *
     * <p>S16 (C1, ADR-20): when the quick retries are spent the HarbourMaster no longer "gives
     * up". It used to — a log line, nothing else — and the ship, which had left for its berth at
     * the grant, docked unescorted and paid for no tug. The ship now stays where it is: its
     * tender is closed in the notebook, the player is told once, and the next tug to report
     * itself available (or the periodic sweep) tenders again with a fresh set of retries.
     */
    private void scheduleRetryIfWithinCap(HarbourMasterAgent hm, int shortfall) {
        int attempt = hm.cnpRetryAttempts()
                .computeIfAbsent(req.vesselId(), id -> new AtomicInteger(0))
                .incrementAndGet();
        if (attempt > CnpRetryBehaviour.MAX_RETRIES) {
            log.info("CNP for {}: {} quick retries spent — the vessel keeps waiting for a free tug",
                    req.vesselId(), CnpRetryBehaviour.MAX_RETRIES);
            hm.cnpRetryAttempts().remove(req.vesselId());
            MovementControl.tenderClosed(hm, req.vesselId());
            MovementControl.onTenderExhausted(hm, req.vesselId());
            return;
        }
        // Floored (audit A-03): unfloored this was 1 ms under --day-length 5, so all three retries
        // collected zero bids as fast as the scheduler could run them and the cap was spent in
        // milliseconds. See InitiateCNPBehaviour.MIN_CNP_WINDOW_WALL_MS.
        long delayWallMs = InitiateCNPBehaviour.cnpWindowWallMs(
                hm.simClock(), Settings.load().cnpRetryAfterSimSeconds());
        CnpRequest retry = shortfall == req.tugsRequired() ? req
                : new CnpRequest(req.vesselId(), req.vesselAid(), req.vesselPosition(),
                        req.berthPosition(), Math.max(1, shortfall), req.vesselType());
        myAgent.addBehaviour(new CnpRetryBehaviour(myAgent, hm.cnpCoordinator(), retry, delayWallMs));
        log.info("CNP for {}: scheduling retry {}/{} in {} sim-s ({} tug(s) wanted)",
                req.vesselId(), attempt, CnpRetryBehaviour.MAX_RETRIES,
                Settings.load().cnpRetryAfterSimSeconds(), retry.tugsRequired());
    }
}
