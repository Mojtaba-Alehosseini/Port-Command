package it.unige.portcommand.harbourmaster.cnp;

import it.unige.portcommand.harbourmaster.CnpCoordinator;
import it.unige.portcommand.harbourmaster.CnpRequest;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The zero-bid CNP retry ADR-08 deferred to task 15 (a negotiation-engine-adjacent internal, NOT
 * part of the task-05 51-behaviour agent-stub set per ADR-01/INVARIANTS.md — lives in this
 * package, distinct from the COUNTED {@code behaviours.cnp}, so {@code BehaviourCatalogueTest}
 * needs no change).
 *
 * <p>Bounded-retry cap enforcement (max 3 attempts) happens at the SCHEDULING site
 * ({@code AwardContractBehaviour.holdVessel}), not here — this class is deliberately just "wait,
 * then re-initiate," which keeps it trivial to unit-test in isolation. Re-entering via
 * {@link CnpCoordinator#initiate} re-runs the full DF-discover + CFP + collect + award cycle
 * (a fresh {@code cfpId}, a fresh bid window) — correct for the zero-bid case, since every tug
 * was a non-bidder last time, so "re-CFP everyone" and "re-CFP the non-bidders" are the same set.
 */
public final class CnpRetryBehaviour extends WakerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(CnpRetryBehaviour.class);

    /** Cap enforced by the caller before ever constructing this behaviour. */
    public static final int MAX_RETRIES = 3;

    private final CnpCoordinator coordinator;
    private final CnpRequest req;

    public CnpRetryBehaviour(Agent agent, CnpCoordinator coordinator, CnpRequest req, long delayWallMs) {
        super(agent, delayWallMs);
        this.coordinator = coordinator;
        this.req = req;
    }

    @Override
    protected void onWake() {
        log.info("CNP retry firing for {}", req.vesselId());
        coordinator.initiate(req);
    }
}
