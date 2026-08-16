package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The HarbourMaster's berth wait queue (S5, ADR-16, the real audit-P1-12 fix): vessels whose deal
 * is closed (or whose contracted arrival is announced) but whose every compatible berth is
 * occupied. They wait here — in FIFO arrival order — until a berth frees, instead of being
 * refused (S3 proved refuse-on-held deletes scripted walk-ins: four of the five vessel types have
 * exactly ONE compatible berth, so "all held" is the common case, and post-deal waits are free —
 * negotiation patience stops at the deal and {@code AwaitBerthBehaviour} has no deadline).
 *
 * <p><b>Threading: HarbourMaster-thread confined.</b> Every mutator runs from an HM behaviour
 * ({@code handleDealConfirmed}, {@code handleDeparted}, the periodic weather sweep, AutoFlow,
 * RestoreDispatch), all on the agent's single thread. Not thread-safe by design — a plain
 * {@code LinkedHashMap} keeps arrival order for the FIFO tiebreak. The save path never reads this
 * object: queue membership is derivable from {@code Stage.AWAITING_BERTH}, so nothing new is
 * serialized and a load rebuilds the queue from restored tracking. Stated cost of that choice
 * (ADR-16 D3, adversarial finding 10): ARRIVAL ORDER does not survive a save/load — the rebuild
 * enqueues in vessel-id order with fresh timestamps, so a same-class tie that would have gone to
 * the earlier arrival goes to the alphabetically-first vessel after a reload. Priority ordering
 * (the thing the release sweep actually decides on) is unaffected.
 *
 * <p><b>Selection is not FIFO</b> — it is the R25-R30 priority module's answer (rank via
 * {@code effective_priority}, pairwise order via {@code higher_priority}), with FIFO breaking
 * ties. That decision lives in {@code ForwardWalkInToPlayerBehaviour}'s release sweep, next to
 * the Prolog calls; this class only remembers who waits and what terms they closed at.
 *
 * <p>Uncounted package on purpose: {@code harbourmaster/} is not scanned by
 * {@code BehaviourCatalogueTest} — this is coordinator state, not a behaviour.
 */
public final class BerthWaitQueue {

    /** One parked grant: the deal terms the eventual ACCEPT must carry. */
    public record Waiting(String vesselId, double price, int hours, long queuedAtSimMillis) {
        public Waiting {
            Objects.requireNonNull(vesselId, "vesselId");
        }
    }

    private final Map<String, Waiting> waiting = new LinkedHashMap<>();

    /** Parks a vessel; re-parking the same vessel keeps its ORIGINAL position (FIFO fairness). */
    public void enqueue(String vesselId, double price, int hours, long nowSimMillis) {
        waiting.putIfAbsent(vesselId, new Waiting(vesselId, price, hours, nowSimMillis));
    }

    /** The parked entry, or null. */
    public Waiting get(String vesselId) {
        return waiting.get(vesselId);
    }

    /** Removes a vessel (granted, withdrawn, or departed-while-queued cleanup). */
    public Waiting remove(String vesselId) {
        return waiting.remove(vesselId);
    }

    /** Snapshot of everyone waiting, in arrival order — the release sweep's candidate list. */
    public List<Waiting> inArrivalOrder() {
        return new ArrayList<>(waiting.values());
    }

    public boolean isEmpty() {
        return waiting.isEmpty();
    }

    public int size() {
        return waiting.size();
    }
}
