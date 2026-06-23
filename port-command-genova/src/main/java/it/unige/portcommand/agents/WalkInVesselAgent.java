package it.unige.portcommand.agents;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.behaviours.negotiation.EvaluateCounterOfferBehaviour;
import it.unige.portcommand.behaviours.negotiation.OpeningProposalBehaviour;
import it.unige.portcommand.behaviours.negotiation.TimeoutWithdrawalBehaviour;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import it.unige.portcommand.negotiation.WalkInState;
import it.unige.portcommand.util.RandomSource;

/**
 * Vessel arriving without a contract (Channel B); negotiates price/duration with the
 * player (relayed by the HarbourMaster). On arrival it samples its hidden beliefs from
 * {@code vessel_templates.json} via a per-vessel {@code RandomSource} sub-stream and
 * fires the opening proposal + counter-offer evaluation + timeout behaviours.
 *
 * <p>The hidden beliefs live ONLY in the {@link WalkInState} held by {@code stateRef},
 * which is handed to the negotiation behaviours by constructor — they are never on this
 * agent's public surface and never serialised into an ACL message (task-07 §29). The
 * {@code engine} (task 15's real binding; a Mockito mock in tests) is injected at args[3],
 * the {@code RandomSource} at args[4].
 */
public final class WalkInVesselAgent extends BaseVesselAgent {

    private static final int MAX_ROUNDS = 4;

    private NegotiationEngine engine;
    private RandomSource randomSource;
    private final AtomicReference<WalkInState> stateRef = new AtomicReference<>();
    private final AtomicBoolean concluded = new AtomicBoolean(false);
    private String conversationId;

    @Override
    protected void onSetup() {
        this.engine = argAt(3, NegotiationEngine.class);
        this.randomSource = argAt(4, RandomSource.class);
        super.onSetup();
    }

    @Override
    protected void onArrival() {
        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = randomSource.forStream("vessel-" + spec.vesselId());
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);
        int wait = template.sampleMaxWaitMinutes(r);
        int hours = 6 + r.nextInt(13); // 6..18 sim-hours of desired service
        stateRef.set(WalkInState.initial(spec.vesselType(), min, target, personality, wait, hours));
        this.conversationId = "nego-" + spec.vesselId();
        log.info("{} arrived (walk-in, {}) -> opening proposal", getLocalName(), personality);

        addBehaviour(new OpeningProposalBehaviour(this, stateRef, conversationId, concluded));
        addBehaviour(new EvaluateCounterOfferBehaviour(this, stateRef, engine, conversationId, concluded));
        addBehaviour(new TimeoutWithdrawalBehaviour(this, simClock(), stateRef, conversationId, concluded));
    }

    // --- hidden-belief accessors: package-private only; never public, never in ACL content ---

    double minAcceptablePrice() {
        WalkInState s = stateRef.get();
        return s == null ? 0.0 : s.minAcceptablePrice();
    }

    double targetPrice() {
        WalkInState s = stateRef.get();
        return s == null ? 0.0 : s.targetPrice();
    }

    int maxWaitMinutes() {
        WalkInState s = stateRef.get();
        return s == null ? 0 : s.maxWaitMinutes();
    }

    int roundsRemaining() {
        WalkInState s = stateRef.get();
        return s == null ? MAX_ROUNDS : Math.max(0, MAX_ROUNDS + 1 - s.round());
    }

    String personality() {
        WalkInState s = stateRef.get();
        return s == null ? null : s.personality().name();
    }
}
