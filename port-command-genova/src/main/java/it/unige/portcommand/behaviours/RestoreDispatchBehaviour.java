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
 *       Each CFP decision re-queries Prolog {@code tugsRequired} (design rule 4).</li>
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
        // S16 (found by the gate): the restored SHIPS too. The loader spawns them after the
        // fixed roster and each registers in the directory as the last step of its own setup,
        // so on a fast boot the tugs and customs can be visible before a ship is. The sweep
        // below then found "no restored agent" for a queued ship and dropped its tracking - a
        // ship with a paid deal vanished from the loaded game. A ship that never registers is
        // still dropped, once the retries are spent.
        boolean vesselsUp = hm.directory() == null || restoredVesselsRegistered(
                hm.activeVessels().values(), name -> hm.directory().byName(name).isPresent());
        if ((!tugsUp || !customsUp || !vesselsUp) && attemptsLeft > 0) {
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
        resendPendingCustoms();
        requeueAwaitingBerth();
        // S16 (ADR-20): one sweep over the restored world with the reading the save carried
        // (the HarbourMaster is seeded with it at boot - decision B7). It holds every waiting or
        // moving ship the gate refuses, and for the rest tenders for the tugs they still lack -
        // through the gate, where the pre-S16 redispatchTugless tendered without looking and
        // treated a world loaded in mid-storm as calm for its first fifteen game minutes.
        MovementControl.onWeather(hm);
    }

    /**
     * Whether every tracked vessel's agent has registered under its local name. The restore
     * sweep looks queued ships up in the directory and sends orders to the rest, so it must not
     * run while one of them is still booting.
     */
    static boolean restoredVesselsRegistered(java.util.Collection<VesselTracking> tracked,
                                             java.util.function.Predicate<String> registered) {
        return tracked.stream().allMatch(vessel -> registered.test(
                it.unige.portcommand.bootstrap.AgentRoster.vesselLocalName(vessel.spec())));
    }

    /**
     * S5 (ADR-16): restored {@code AWAITING_BERTH} vessels re-enter the wait queue — the queue
     * itself is never serialized (membership is derivable from the stage), so a load rebuilds it
     * here and immediately attempts a release: if the blocking vessel was dropped by the same
     * save, the berth is already free and the grant lands at once. Deal terms come from the
     * restored tracking's DTO fields ({@code dealPrice}/{@code dealHours} persisted vessel-side,
     * mirrored into the HM snapshot by {@code GameStateBuilder}); contracted entries re-derive
     * their pin from {@code contractRef}.
     */
    private void requeueAwaitingBerth() {
        List<VesselTracking> queued = hm.activeVessels().values().stream()
                .filter(v -> v.stage() == Stage.AWAITING_BERTH)
                .sorted(java.util.Comparator.comparing(VesselTracking::vesselId))
                .toList();
        for (VesselTracking tracking : queued) {
            var vessel = hm.directory() == null ? null
                    : hm.directory().byName(
                                    it.unige.portcommand.bootstrap.AgentRoster.vesselLocalName(tracking.spec()))
                            .filter(it.unige.portcommand.agents.BaseVesselAgent.class::isInstance)
                            .map(it.unige.portcommand.agents.BaseVesselAgent.class::cast)
                            .orElse(null);
            if (vessel == null) {
                // S5 adversarial finding 11: also drop the TRACKING, not just the queue entry —
                // an agent-less AWAITING_BERTH entry would otherwise never be queued, never be
                // granted, and be re-saved as-is by every later snapshot (a ghost that survives
                // every reload). Removing it matches the departed-vessel cleanup shape.
                log.warn("restore dispatch: AWAITING_BERTH {} has no restored agent — dropping "
                        + "its tracking and queue entry", tracking.vesselId());
                hm.activeVessels().remove(tracking.vesselId());
                MovementControl.forget(hm, tracking.vesselId()); // every side table, one list
                continue;
            }
            hm.berthWaitQueue().enqueue(tracking.vesselId(), vessel.dealPrice(), vessel.dealHours(),
                    hm.simClock().nowSimMillis());
            String contractRef = vessel.snapshotDto().contractRef();
            if (contractRef != null && !contractRef.isEmpty()) {
                hm.pendingContractGrants().put(tracking.vesselId(), contractRef);
            }
            log.info("restore dispatch: {} re-queued for a berth", tracking.vesselId());
        }
        if (!queued.isEmpty()) {
            it.unige.portcommand.behaviours.coordination.ForwardWalkInToPlayerBehaviour
                    .releaseQueuedGrants(hm);
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
            // S16 (adversarial review): through the live path's sender, which retries while the
            // customs agent is still registering and - when it never does - clears the ship
            // instead of leaving it "pending" for good. This loop used to give up after its own
            // DF lookup with a log line; since S16 a pending check stops the ship, so a customs
            // agent that failed to boot would have kept every such ship at the channel entry.
            it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour.sendCustomsRequest(
                    hm, tracking.spec(),
                    it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour.DF_RETRY_ATTEMPTS);
            log.info("restore dispatch: customs pre-clearance re-sent for {}", tracking.vesselId());
        }
    }
}
