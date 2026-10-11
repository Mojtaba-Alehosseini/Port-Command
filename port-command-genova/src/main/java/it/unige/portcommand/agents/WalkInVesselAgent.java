package it.unige.portcommand.agents;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.behaviours.negotiation.EvaluateCounterOfferBehaviour;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.behaviours.negotiation.OpeningProposalBehaviour;
import it.unige.portcommand.behaviours.negotiation.TimeoutWithdrawalBehaviour;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import it.unige.portcommand.negotiation.WalkInState;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.util.RandomSource;
import jade.lang.acl.MessageTemplate;
import it.unige.portcommand.ontology.VesselSpec;

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

    private NegotiationEngine engine;
    private RandomSource randomSource;
    private final AtomicReference<WalkInState> stateRef = new AtomicReference<>();
    private final AtomicBoolean concluded = new AtomicBoolean(false);
    /** S14 (ADR-18): this dialogue's patience on the conversation clock — set by the opening
     * proposal, extended by the counter-eval, watched by the timeout. Null until the opening. */
    private final AtomicReference<it.unige.portcommand.util.ConversationClock.Conversation> conversationRef =
            new AtomicReference<>();
    private String conversationId;

    @Override
    protected void onSetup() {
        this.engine = argAt(3, NegotiationEngine.class);
        this.randomSource = argAt(4, RandomSource.class);
        super.onSetup();
    }

    /**
     * Task 22 restore: hidden beliefs are adopted VERBATIM from the save — never re-rolled
     * from {@code vessel_templates.json} (planning/22 "Notes": re-rolling would defeat the
     * seed). Only post-deal walk-ins are ever persisted (open negotiations are dropped at
     * save), so the dialogue is marked concluded and NO negotiation behaviour re-attaches:
     * the beliefs are carried for determinism and the privacy round-trip, not for play.
     */
    @Override
    protected void onRestore(VesselStateDTO dto) {
        this.conversationId = "nego-" + spec.vesselId();
        this.concluded.set(true);
        stateRef.set(new WalkInState(
                spec.vesselType(),
                dto.minAcceptablePrice() == null ? 0.0 : dto.minAcceptablePrice(),
                dto.targetPrice() == null ? 0.0 : dto.targetPrice(),
                dto.personality() == null ? Personality.NEUTRAL : Personality.valueOf(dto.personality()),
                dto.maxWaitMinutes() == null ? 0 : dto.maxWaitMinutes(),
                dto.minDurationHours() == null ? 1 : dto.minDurationHours(),
                dto.negotiationRound() == null ? 1 : dto.negotiationRound(),
                dto.negotiationStartedAtSimMillis() == null ? 0L : dto.negotiationStartedAtSimMillis(),
                0.0, 0.0, 0, dto.dealHours(), dto.dealHours()));
    }

    /**
     * The flat persisted record, walk-in side (task 22): flow position + deal + position +
     * the hidden beliefs, verbatim. HM-side tracking fields (stage, tugs, holds) are filled
     * by {@code GameStateBuilder} from {@code VesselTracking} — this agent does not know them.
     */
    @Override
    public VesselStateDTO snapshotDto() {
        WalkInState s = stateRef.get();
        return new VesselStateDTO(
                spec.vesselId(), spec.vesselType(), VesselStateDTO.CHANNEL_WALK_IN,
                spec.draft(), spec.length(), spec.tonnage(), spec.cargoClass(),
                spec.etaArrivalSimMillis(),
                null, flowPhase() == null ? null : flowPhase().name(), phaseDeadlineSimMillis(),
                assignedBerth(), dealPrice(), dealHours(),
                null, false, false,
                position().x(), position().y(), position().headingDeg(),
                null,
                s == null ? null : s.personality().name(),
                s == null ? null : s.minAcceptablePrice(),
                s == null ? null : s.targetPrice(),
                s == null ? null : s.maxWaitMinutes(),
                s == null ? null : s.minDurationHours(),
                s == null ? null : s.round(),
                s == null ? null : s.negotiationStartedAtSimMillis(),
                spec.vesselName());
    }

    @Override
    protected void onArrival() {
        VesselTemplate template = VesselTemplates.forType(spec.vesselType());
        Random r = randomSource.forStream("vessel-" + spec.vesselId());
        Personality personality = template.samplePersonality(r);
        double min = template.sampleMinAcceptablePrice(r);
        double target = template.sampleTargetPrice(r);
        // S14 (ADR-18): this draw no longer decides anything — patience is conversation time
        // (PatiencePolicy), not these sim-minutes. It is still TAKEN, and still carried in
        // WalkInState and the save, because the "vessel-"+id stream is replay-pinned: skipping
        // the draw would shift the hours draw below and change every seeded walk-in's stay.
        int wait = template.sampleMaxWaitMinutes(r);
        int hours = 6 + r.nextInt(13); // 6..18 sim-hours of desired service
        // Task 23: scenario-pinned beliefs (optional args entry, found by type) REPLACE the
        // sampled values after every draw has been taken, so the "vessel-"+id stream's
        // sequence is identical with or without overrides (replay/pinned-seed discipline).
        // Still hidden beliefs — the override rides the in-JVM args channel only (P-04).
        it.unige.portcommand.negotiation.WalkInBeliefOverrides overrides =
                optionalArgOfType(it.unige.portcommand.negotiation.WalkInBeliefOverrides.class);
        if (overrides != null) {
            personality = overrides.personality() != null ? overrides.personality() : personality;
            min = overrides.minAcceptablePrice() != null ? overrides.minAcceptablePrice() : min;
            target = overrides.targetPrice() != null ? overrides.targetPrice() : target;
        }
        // Task 19b: the hidden cargo floor, from its own ISOLATED substream (same rationale as
        // the opening roll below — the "vessel-"+id sequence is replay-pinned), clamped to the
        // announced preferred stay so a vessel never opens below its own floor.
        int floorDraw = template.sampleMinDurationHours(randomSource.forStream("duration-" + spec.vesselId()));
        int minDurationHours = Math.min(floorDraw, hours);
        stateRef.set(WalkInState.initial(spec.vesselType(), min, target, personality, wait, hours,
                minDurationHours));
        this.conversationId = "nego-" + spec.vesselId();
        log.info("{} arrived (walk-in, {}) -> opening proposal", getLocalName(), personality);

        // Isolated substream (task 19 STEP 1): a fresh draw here must never shift the
        // "vessel-"+id stream's own sequence, which other tests/replication already depend on.
        double openingRoll = randomSource.forStream("opening-" + spec.vesselId()).nextDouble();
        addBehaviour(new OpeningProposalBehaviour(this, stateRef, conversationRef, conversationId, concluded,
                openingRoll));
        addBehaviour(new EvaluateCounterOfferBehaviour(this, stateRef, conversationRef, engine, conversationId,
                concluded));
        addBehaviour(new TimeoutWithdrawalBehaviour(this, conversationRef, stateRef, conversationId, concluded));
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

    /**
     * Rounds this vessel has left, on the SAME formula {@link
     * it.unige.portcommand.negotiation.RealNegotiationEngine} uses ({@code roundLimit + 1 - round},
     * because {@code WalkInState.recordPlayerCounter} owns the increment and runs before the engine
     * is consulted — ADR-09 decision 1; do not "simplify" the offset away).
     *
     * <p>Sourced from {@code Settings.roundLimit()} since 2026-08-10 (audit P1-10, closing the
     * 2026-07-17 TODO): it was hardcoded 4 while the engine took the limit from Settings at both
     * spawn sites, so the two disagreed for any configured limit other than 4. Read statically per
     * the house pattern — {@code Settings} is a cached classpath record, never threaded through the
     * constructor chain (INVARIANTS "Negotiation engine").
     *
     * <p>Honest scope note: this accessor currently has no callers, and the offer strip's
     * "Round n of m" reads {@code Settings.roundLimit()} directly, so the fix removes a latent
     * disagreement rather than a visible one.
     */
    int roundsRemaining() {
        return roundsRemaining(Settings.load().roundLimit(), stateRef.get());
    }

    /** The pure arithmetic, so it is testable across the configured 2..8 range without reaching
     * into {@code Settings}' JVM-lifetime cache. */
    static int roundsRemaining(int roundLimit, WalkInState state) {
        return state == null ? roundLimit : Math.max(0, roundLimit + 1 - state.round());
    }

    String personality() {
        WalkInState s = stateRef.get();
        return s == null ? null : s.personality().name();
    }

    /** Adds the negotiation conversation to {@link BaseVesselAgent}'s berth-grant one. */
    @Override
    protected MessageTemplate handledTemplate() {
        return MessageTemplate.or(super.handledTemplate(),
                MessageTemplate.MatchConversationId("nego-" + initArg(VesselSpec.class).vesselId()));
    }

}
