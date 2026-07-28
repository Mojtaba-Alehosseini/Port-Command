package it.unige.portcommand.behaviours;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleEmergencyBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.CnpRequest;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot post-load dispatcher on the rebuilt HarbourMaster (task 22; UNCOUNTED parent
 * {@code behaviours} package — catalogue stays 51). Re-drives exactly what the save
 * interrupted, after waiting for the respawned fleet to become DF-visible (the
 * {@code sendTerminalRequest} retry pattern — a fixed number of re-wakes spaced past the
 * ServiceLocator's 1&nbsp;s DF-cache TTL):
 *
 * <ul>
 *   <li><b>Tug-less AWAITING_TUG vessels → a FRESH Contract Net.</b> No award existed at
 *       the save (an award flips the stage to IN_TRANSIT and assigns tugs), so no charge
 *       existed either — the fresh CNP's award will charge exactly once. Weather-held
 *       vessels are skipped: the weather-clear sweep owns their re-dispatch (task 24).
 *       Each CFP decision re-queries Prolog {@code tugsRequired} (CLAUDE.md rule 4).</li>
 *   <li><b>Pending customs REQUESTs re-sent.</b> The old reply died with the old
 *       container; processing the reply is the charge point, so re-asking charges exactly
 *       once. The conversation ids are the persisted ones, keeping
 *       {@code pendingCustomsChecks} correlation intact.</li>
 * </ul>
 *
 * Captures the agent in a final field (the task-19 lesson): the re-wake chain runs after
 * this OneShot's {@code action()} returned and {@code myAgent} may already be nulled.
 */
public final class RestoreDispatchBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RestoreDispatchBehaviour.class);
    private static final int DF_RETRY_ATTEMPTS = 5;
    private static final long DF_RETRY_WALL_MS = 1200L;

    private final HarbourMasterAgent hm;

    public RestoreDispatchBehaviour(HarbourMasterAgent hm) {
        super(hm);
        this.hm = hm;
    }

    @Override
    public void action() {
        dispatchWhenFleetVisible(DF_RETRY_ATTEMPTS);
    }

    private void dispatchWhenFleetVisible(int attemptsLeft) {
        boolean tugsUp = !hm.serviceLocator().findAll("tug-escort").isEmpty();
        boolean customsUp = hm.resolveCustomsAid() != null;
        if ((!tugsUp || !customsUp) && attemptsLeft > 0) {
            hm.addBehaviour(new WakerBehaviour(hm, DF_RETRY_WALL_MS) {
                @Override
                protected void onWake() {
                    dispatchWhenFleetVisible(attemptsLeft - 1);
                }
            });
            return;
        }
        if (!tugsUp) {
            log.warn("restore dispatch: no tug visible in DF after retries — "
                    + "AWAITING_TUG vessels will rely on the CNP retry path");
        }
        redispatchTugless();
        resendPendingCustoms();
    }

    private void redispatchTugless() {
        List<VesselTracking> tugless = hm.activeVessels().values().stream()
                .filter(v -> v.stage() == Stage.AWAITING_TUG)
                .filter(v -> v.assignedTugs().isEmpty())
                .filter(v -> !v.weatherHeld())
                .sorted(java.util.Comparator.comparing(VesselTracking::vesselId))
                .toList();
        for (VesselTracking tracking : tugless) {
            int tugsRequired = PrologQueries.tugsRequired(tracking.spec().vesselType(),
                    tracking.spec().tonnage(), tracking.spec().length());
            if (tugsRequired <= 0 || tracking.assignedBerth() == null) {
                continue;
            }
            hm.cnpCoordinator().initiate(new CnpRequest(tracking.vesselId(), tracking.aid(),
                    AutoFlowDispatcherBehaviour.CHANNEL_ENTRY,
                    BerthPositions.position(tracking.assignedBerth()), tugsRequired));
            log.info("restore dispatch: fresh CNP for {} ({} tug(s))", tracking.vesselId(), tugsRequired);
        }
    }

    private void resendPendingCustoms() {
        for (Map.Entry<String, String> pending : hm.pendingCustomsChecks().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            VesselTracking tracking = hm.activeVessels().get(pending.getValue());
            if (tracking == null) {
                hm.pendingCustomsChecks().remove(pending.getKey());
                continue; // the vessel did not survive the save — nothing to clear
            }
            jade.core.AID customs = hm.resolveCustomsAid();
            if (customs == null) {
                log.warn("restore dispatch: customs not DF-visible — {} stays pending", pending.getValue());
                continue;
            }
            ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
            request.addReceiver(customs);
            request.setConversationId(pending.getKey());
            request.setProtocol(HandleEmergencyBehaviour.CUSTOMS_CLEARANCE_PROTOCOL);
            request.setContent(TerminalJson.write(Map.of(
                    "vessel_id", tracking.vesselId(),
                    "vessel_type", tracking.spec().vesselType(),
                    "cargo_class", tracking.spec().cargoClass())));
            hm.sendLogged(request);
            log.info("restore dispatch: customs pre-clearance re-sent for {}", tracking.vesselId());
        }
    }
}
