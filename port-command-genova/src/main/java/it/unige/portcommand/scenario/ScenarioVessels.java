package it.unige.portcommand.scenario;

import java.util.Map;
import java.util.Random;

import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.RandomSource;

/**
 * Vessel-spec construction for scripted spawns (task 23).
 *
 * <p><b>Contracted spawns use pinned per-type dimensions</b>, not template draws: a
 * contracted vessel's berth is fixed by its contract, so its dims must be guaranteed
 * compatible (RULES R1–R8) with that berth — a template roll could randomly exceed a
 * berth limit and turn a scripted arrival into a REFUSE. The pinned values are the
 * IT-proven combinations ({@code HarbourMasterAgentIT}: cargo 9.0/150/30k at berth_1,
 * ferry 5.0/110/9k at berth_4, tanker at berth_1) extended per the task-04 berth table
 * (berth_1 deep-water 22.0/350/50, berth_2 container 14.0/340/40); a unit test asserts
 * every shipped (contract berth × pinned dims) pair against the live Prolog gate.
 *
 * <p><b>Walk-in spawns sample from {@code vessel_templates.json}</b> via the same
 * seeded per-vessel streams the Poisson spawner uses — their berth is negotiated at
 * grant time against whatever dims they rolled, so sampling is safe there.
 *
 * <p>Cargo classes are the canonical {@code cargo_class/1} atoms.
 *
 * <p><b>Correction 2026-07-27 (audit D-04): no shipped vessel is hazmat, and the customs leg is
 * therefore cold in every scenario.</b> This javadoc used to claim "the tanker's {@code liquid_bulk}
 * is deliberately hazmat so scripted tankers exercise the customs pre-clearance leg (the storm
 * scenario's cascade)". That is false against the shipped ontology. {@code port_ontology.pl:80}
 * generates {@code is_hazmat(C) :- subclass_of(C, hazmat_cargo).} — a ONE-HOP test over the
 * converter's direct edges — and the only direct children of {@code hazmat_cargo} are
 * {@code hazmat_class_1} and {@code hazmat_class_3}. {@code liquid_bulk}'s edge is
 * {@code subclass_of(liquid_bulk, cargo)}, not {@code hazmat_cargo}. The complete set of cargo
 * classes any vessel ever receives is {@code liquid_bulk} / {@code containerized_cargo} /
 * {@code general_cargo} here plus a hard-coded {@code general_cargo} for every Poisson walk-in —
 * none of them hazmat — so {@code AutoFlowDispatcherBehaviour}'s {@code isHazmat} gate never opens,
 * {@code sendCustomsRequest} is never called, and the CustomsAgent registers on the DF and receives
 * nothing all game. R22–R24 and {@code CustomsClearedEvent} are exercised only by tests, which use
 * {@code hazmat_class_1}/{@code hazmat_class_3} — values the simulation never produces.
 *
 * <p><b>TAKEN 2026-07-28 (Moji's decision on the D-04 escalation): the tanker is now
 * {@code hazmat_class_3}, and the customs leg is live.</b> What that actually turns on, measured
 * rather than assumed:
 * <ul>
 *   <li><b>Exactly one vessel sends a customs REQUEST — but more than one carries the new cargo
 *       class.</b> Stated precisely, because the first draft of this note got it wrong (adversarial
 *       review, 2026-07-28): {@link #sampledWalkInSpec} and {@code GameStateBuilder.dockedVessel}
 *       call {@link #cargoClassFor} too — only the DIMENSIONS come from
 *       {@code vessel_templates.json} — so {@code busy_day}'s scripted WALK-IN tanker, and any
 *       pre-docked or restored tanker, now carry {@code hazmat_class_3} in their
 *       {@code VesselSpec} as well. What none of them do is trigger customs: {@code sendCustomsRequest}
 *       has exactly one call site, inside the CONTRACTED grant (ADR-09 — a walk-in sends the
 *       terminal and customs nothing). {@code contracts.json}'s two daily contracts are a
 *       cargo_vessel and a ferry, and only {@code storm.json}'s ST-901 is a contracted tanker.
 *       The cargo class is otherwise inert: berth compatibility keys on vessel TYPE (R5 via
 *       {@code vessel_class_needs/2}), {@code ExpenseRules.marginalCost} takes a cargo class and
 *       never reads it, and {@code IncomeRules.hazmatSurcharge} has no production call site. So the
 *       observable change beyond storm is the literal string in a {@code BerthRequest}, a
 *       {@code WalkInDialogueSnapshot} and the save DTO.</li>
 *   <li><b>A deterministic CONFIRM, not a dice roll.</b> {@code HandleClearanceRequestBehaviour}
 *       sends hazmat down {@code clearance_ok/2} (R21), which succeeds unless the
 *       (type, cargo) pair is in the dynamic {@code blacklisted_combo/2} — and nothing asserts
 *       into it. The RNG branch is the NON-hazmat one. So the storm's customs leg is reproducible.</li>
 *   <li><b>€100 once</b>, guarded twice over: {@code HandleEmergencyBehaviour} consumes the
 *       conversation from {@code pendingCustomsChecks} BEFORE publishing {@code CustomsClearedEvent},
 *       and {@code LedgerCoordinator.chargedClearances} dedupes on vesselId.</li>
 *   <li><b>The §7.5 calibration is untouched.</b> {@code DefaultDayCalibrationTest} models a default
 *       day off {@code contracts.json}, which has no tanker and therefore no customs line.</li>
 * </ul>
 */
public final class ScenarioVessels {

    private record Dims(double draft, double length, int tonnage) {
    }

    private static final Map<String, Dims> CONTRACTED_DIMS = Map.of(
            "cargo_vessel", new Dims(9.0, 150.0, 30_000),
            "ferry", new Dims(5.0, 110.0, 9_000),
            "tanker", new Dims(13.0, 210.0, 80_000),
            "container_vessel", new Dims(12.0, 250.0, 60_000),
            "cruise_ship", new Dims(8.5, 280.0, 100_000));

    private static final Map<String, String> CARGO_CLASS = Map.of(
            // hazmat_class_3 (flammable liquids) — the ONE entry that makes the customs leg live.
            // See the class javadoc: liquid_bulk is NOT hazmat under the shipped ontology, so with
            // it here `is_hazmat/1` was false for every cargo class the simulation produces and the
            // CustomsAgent received nothing all game. Class 3 rather than class 1 (explosives)
            // because it is what a tanker actually carries.
            "tanker", "hazmat_class_3",
            "container_vessel", "containerized_cargo",
            "cargo_vessel", "general_cargo",
            "ferry", "general_cargo",
            "cruise_ship", "general_cargo");

    private ScenarioVessels() {
    }

    /** The pinned, compat-guaranteed spec for a contracted spawn. */
    public static VesselSpec contractedSpec(ServiceContract contract, long etaSimMillis) {
        Dims d = CONTRACTED_DIMS.get(contract.vesselType());
        return new VesselSpec(contract.vesselId(), contract.vesselType(), d.draft(), d.length(),
                d.tonnage(), cargoClassFor(contract.vesselType()), etaSimMillis);
    }

    /** A template-sampled walk-in spec, drawn from the vessel's own seeded sub-stream. */
    public static VesselSpec sampledWalkInSpec(String vesselId, String vesselType,
                                               RandomSource randomSource, long etaSimMillis) {
        VesselTemplate template = VesselTemplates.forType(vesselType);
        Random r = randomSource.forStream("spawn-dims-" + vesselId);
        return new VesselSpec(vesselId, vesselType, template.sampleDraft(r), template.sampleLength(r),
                template.sampleTonnage(r), cargoClassFor(vesselType), etaSimMillis);
    }

    /** The canonical cargo-class atom a scripted spawn of {@code vesselType} carries. */
    public static String cargoClassFor(String vesselType) {
        return CARGO_CLASS.get(vesselType);
    }

    /** The pinned draft/length/tonnage for compat tests. */
    public static double[] pinnedDims(String vesselType) {
        Dims d = CONTRACTED_DIMS.get(vesselType);
        return new double[] {d.draft(), d.length(), d.tonnage()};
    }
}
