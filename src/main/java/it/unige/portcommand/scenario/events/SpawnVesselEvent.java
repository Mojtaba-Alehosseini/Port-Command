package it.unige.portcommand.scenario.events;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.RealNegotiationEngine;
import it.unige.portcommand.negotiation.WalkInBeliefOverrides;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.scenario.GameContext;
import it.unige.portcommand.scenario.ScenarioVessels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted vessel spawn — the scenario engine's (and the daily contracted stream's)
 * entry into the SAME vessel-creation path the Poisson spawner uses
 * ({@code AgentRoster.spawnContractedVessel}/{@code spawnWalkIn}; planning/23 §23.4).
 *
 * <p><b>Contracted</b> ({@code channel="contracted"}): {@code contractRef} resolves in
 * the HarbourMaster's live contract map; the vessel id comes from the contract, dims
 * from {@link ScenarioVessels}' compat-pinned table. {@code etaSimMillis} is set only
 * by the programmatic daily stream ({@code ContractSchedule}) — scenario JSON omits it
 * and the vessel arrives at the event's own scripted time.
 *
 * <p><b>Walk-in</b> ({@code channel="walk_in"}): dims sampled from
 * {@code vessel_templates.json}; {@code personality}/{@code minPrice}/{@code targetPrice}
 * pin the corresponding hidden beliefs (P-04 — still hidden: they ride a
 * {@link WalkInBeliefOverrides} constructor arg, never an ACL message or bus event),
 * falling back to template draws when null (planning/23 "Notes": honor the overrides).
 *
 * <p>Idempotency: {@code ScriptedEventBehaviour} fires each event at most once; as
 * defence in depth this spawn also skips (WARN) if an agent with the derived local
 * name already lives in the directory.
 */
public record SpawnVesselEvent(
        long simTimeSeconds,
        String vesselType,
        String channel,
        String vesselId,        // walk-in only (contracted takes the contract's vessel id)
        String contractRef,     // contracted only
        String personality,     // walk-in belief pin (nullable)
        Double minPrice,        // walk-in belief pin (nullable)
        Double targetPrice,     // walk-in belief pin (nullable)
        Long etaSimMillis)      // daily-stream only: absolute arrival instant
        implements ScriptedEvent {

    public static final String CHANNEL_CONTRACTED = "contracted";
    public static final String CHANNEL_WALK_IN = "walk_in";

    private static final Logger log = LoggerFactory.getLogger(SpawnVesselEvent.class);

    @Override
    public void apply(GameContext ctx) {
        if (CHANNEL_CONTRACTED.equals(channel)) {
            applyContracted(ctx);
        } else {
            applyWalkIn(ctx);
        }
    }

    private void applyContracted(GameContext ctx) {
        ServiceContract contract = ctx.contracts().get(contractRef);
        if (contract == null) {
            log.error("scripted spawn: contract {} not in the HarbourMaster map — skipping", contractRef);
            return;
        }
        long eta = etaSimMillis != null ? etaSimMillis : ctx.simClock().nowSimMillis();
        VesselSpec spec = ScenarioVessels.contractedSpec(contract, eta);
        if (alreadySpawned(ctx, spec)) {
            return;
        }
        AgentRoster.spawnContractedVessel(ctx.spawner(), spec, contract.contractId(),
                ctx.simClock(), ctx.marketHistory(), ctx.directory());
        log.info("scripted spawn: contracted {} ({}, contract {}, eta sim {})",
                spec.vesselId(), vesselType, contract.contractId(), eta);
    }

    private void applyWalkIn(GameContext ctx) {
        VesselSpec spec = ScenarioVessels.sampledWalkInSpec(vesselId, vesselType,
                ctx.randomSource(), ctx.simClock().nowSimMillis());
        if (alreadySpawned(ctx, spec)) {
            return;
        }
        RealNegotiationEngine engine = new RealNegotiationEngine(Settings.load().roundLimit(),
                ctx.randomSource().forStream("nego-" + vesselId));
        WalkInBeliefOverrides overrides = personality == null && minPrice == null && targetPrice == null
                ? null
                : new WalkInBeliefOverrides(
                        personality == null ? null : Personality.valueOf(personality),
                        minPrice, targetPrice);
        AgentRoster.spawnWalkIn(ctx.spawner(), spec, ctx.simClock(), ctx.marketHistory(),
                ctx.randomSource(), engine, ctx.directory(), overrides);
        log.info("scripted spawn: walk-in {} ({}{})", vesselId, vesselType,
                overrides == null ? "" : ", beliefs pinned");
    }

    private boolean alreadySpawned(GameContext ctx, VesselSpec spec) {
        boolean exists = ctx.directory() != null
                && ctx.directory().byName(AgentRoster.vesselLocalName(spec)).isPresent();
        if (exists) {
            log.warn("scripted spawn: {} already alive — skipping duplicate", spec.vesselId());
        }
        return exists;
    }
}
