package it.unige.portcommand.harbourmaster;

import java.util.ArrayDeque;
import java.util.Deque;

import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real (not hardcoded) tug Contract Net: DF-discovers tugs and runs a non-blocking
 * CFP/collect/award cycle scored by Prolog {@code best_n_bids}. Renamed from
 * {@code StubCnpCoordinator} (task 15) — this class was already the real cycle as of task 11/
 * ADR-08; only the name was stale. Zero-bid retry is now bounded via task 15's
 * {@code CnpRetryBehaviour}, scheduled from {@code AwardContractBehaviour.holdVessel}; the CFP
 * reply-by window is Settings-driven ({@code InitiateCNPBehaviour}). Post-ACCEPT-failure handling
 * (a winning tug REFUSEs) is not implemented — the documented, accepted exit ramp.
 *
 * <h2>One Contract Net at a time (task 24, visual-checkpoint-#5 B2)</h2>
 * Concurrent CNPs used to race: several same-instant {@code initiate()} calls (the weather-clear
 * re-dispatch fires one per held vessel) put all CFPs on the wire before ANY award, every tug bid
 * on every session (the busy-REFUSE guard only checks an already-SET {@code currentJob}), and the
 * same best-scoring tugs won every contract simultaneously — observed triple-booking of tug_3 and
 * tug_4. {@code initiate()} therefore now QUEUES: one CNP is in flight at a time, and the next
 * CFP goes out only after the previous session reaches a terminal state ({@link #completed()} —
 * awards sent, zero-bid hold, or no tugs in DF). JADE's per-sender/receiver FIFO delivery then
 * guarantees a tug processes its ACCEPT (setting {@code currentJob}) before it ever sees the next
 * CFP, which is what makes the tug-side busy-REFUSE guard actually effective. The ledger's
 * exactly-once keys ({@code (conversationId, tugId)}) are untouched — serialization changes
 * WHEN a CFP goes out, never how its money is counted.
 *
 * <p>Methods are {@code synchronized}: production calls both from the HarbourMaster scheduler
 * thread, but integration tests drive {@code initiate()} from the test thread (the documented
 * self-ref seam), so thread confinement is not assumed. A CNP whose award behaviour dies before
 * calling {@link #completed()} would stall the queue — acceptable: every terminal path in
 * {@code InitiateCNPBehaviour}/{@code AwardContractBehaviour} calls it, and an award path dying
 * mid-action is already a stuck vessel today.
 */
public final class RealCnpCoordinator implements CnpCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RealCnpCoordinator.class);

    private final Agent agent;
    private final ServiceLocator serviceLocator;
    private final SimClock simClock;
    /** S5: vesselId -> priority-class override (emergency/medical), or null. Supplied by the
     * HarbourMaster from its escalation map so the dequeue never reaches into agent internals. */
    private final java.util.function.Function<String, String> overrides;
    private final Deque<CnpRequest> pending = new ArrayDeque<>();
    private boolean inFlight;

    public RealCnpCoordinator(Agent agent, ServiceLocator serviceLocator, SimClock simClock) {
        this(agent, serviceLocator, simClock, id -> null);
    }

    public RealCnpCoordinator(Agent agent, ServiceLocator serviceLocator, SimClock simClock,
                              java.util.function.Function<String, String> overrides) {
        this.agent = agent;
        this.serviceLocator = serviceLocator;
        this.simClock = simClock;
        this.overrides = overrides;
    }

    @Override
    public synchronized void initiate(CnpRequest req) {
        if (inFlight) {
            pending.add(req);
            log.info("CNP for {} queued behind the in-flight session ({} waiting)",
                    req.vesselId(), pending.size());
            return;
        }
        inFlight = true;
        // Must run on the agent thread (task-15's own documented common mistake) —
        // addBehaviour is safe to call from any thread and wakes the scheduler.
        agent.addBehaviour(new InitiateCNPBehaviour(agent, req, serviceLocator, simClock));
    }

    @Override
    public synchronized void completed() {
        CnpRequest next = dequeueHighestPriority();
        if (next == null) {
            inFlight = false;
            return;
        }
        log.info("CNP session closed -> launching queued CNP for {} ({} still waiting)",
                next.vesselId(), pending.size());
        agent.addBehaviour(new InitiateCNPBehaviour(agent, next, serviceLocator, simClock));
    }

    /**
     * S5 (audit P2-4, R25-R30): the backlog is dequeued by PRIORITY, not FIFO — the storm
     * re-dispatch of an emergency-escalated vessel outranks an ordinary queued session. R28's
     * {@code preempts/2} is the comparison, genuinely CALLED per candidate pair over each
     * request's R30 {@code effective_priority} class (overrides read from the HarbourMaster's
     * escalation map); encounter order — FIFO — breaks ties, so with no emergency in the backlog
     * this is byte-for-byte the old behaviour. The head-of-line bypass in {@code initiate} is
     * untouched: priority applies to the backlog only.
     *
     * <p>Lock-ordering note (design review finding 20): this holds the coordinator monitor while
     * taking the Prolog lock. No cycle exists — nothing takes them in the other order — but a
     * future {@code inLock(...)} that calls back into this coordinator would deadlock; keep
     * Prolog calls OUT of any code the engine lock wraps. Cost, stated (adversarial finding 15):
     * one uncached assert→query→retract engine round-trip per queued candidate per completion —
     * fine because the backlog is shallow (weather re-dispatches plus the odd concurrent grant;
     * single digits), not because the call is free.
     */
    private CnpRequest dequeueHighestPriority() {
        if (pending.isEmpty()) {
            return null;
        }
        CnpRequest best = null;
        String bestClass = null;
        for (CnpRequest candidate : pending) {
            String candidateClass = PrologQueries.effectivePriority(candidate.vesselType(),
                    overrides.apply(candidate.vesselId())).priorityClass();
            if (best == null || PrologQueries.preempts(candidateClass, bestClass)) {
                best = candidate;
                bestClass = candidateClass;
            }
        }
        pending.remove(best);
        if (!bestClass.equals("scheduled_arrival")) {
            log.info("CNP backlog dequeued by priority: {} ({}) preempts {} waiting [R28,R30]",
                    best.vesselId(), bestClass, pending.size());
        }
        return best;
    }
}
