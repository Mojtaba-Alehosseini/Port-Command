% rules_compatibility.pl — berth/vessel compatibility kernel (R1–R8).
% Consumes ontology facts (default/3, instance_of/2, vessel_type/1, subclass_of/2).
% No assertz/retract here — the Java PrologQueries facade asserts transient
% per-vessel facts (default/3, instance_of/2) under the engine lock around a query.
% Rule sources are catalogued in references.md.

% --- Data: per-vessel-type cargo-handling needs (the six rows deferred from task 02) ---
vessel_class_needs(tanker, liquid_handling).
vessel_class_needs(tanker, hazmat_handling).
vessel_class_needs(container_vessel, container_crane).
vessel_class_needs(cargo_vessel, general_crane).
vessel_class_needs(ferry, ro_ro_ramp).
vessel_class_needs(cruise_ship, passenger_terminal).

% --- Data: capabilities each berth subclass provides ---
berth_class_provides(deep_water_berth, liquid_handling).
berth_class_provides(deep_water_berth, hazmat_handling).
berth_class_provides(deep_water_berth, general_crane).
berth_class_provides(container_berth, container_crane).
berth_class_provides(container_berth, general_crane).
% berth_2 (container_berth) doubles as the scenic cruise quay (job2 S2.3 "Cruise
% berth = B2"; task 07b, v1.1 2026-07-04) — gives cruise_ship a compatible berth
% (its only other passenger_terminal provider, ferry_berth/berth_4, is far too small).
berth_class_provides(container_berth, passenger_terminal).
berth_class_provides(general_cargo_berth, general_crane).
berth_class_provides(ferry_berth, ro_ro_ramp).
berth_class_provides(ferry_berth, passenger_terminal).

% --- Data: fender ratings (rank: light<medium<heavy) ---
fender_rank(light, 1).
fender_rank(medium, 2).
fender_rank(heavy, 3).
berth_fender(berth_1, heavy).
berth_fender(berth_2, heavy).
berth_fender(berth_3, medium).
berth_fender(berth_4, light).
vessel_fender_need(tanker, heavy).
vessel_fender_need(container_vessel, heavy).
vessel_fender_need(cargo_vessel, medium).
vessel_fender_need(ferry, light).
vessel_fender_need(cruise_ship, heavy).

% Trailing green cuts: each fit is a ground, single-answer boolean check, so we
% commit to the first solution (the temp vessel's dynamic default/3 facts would
% otherwise leave a spurious choicepoint).

% RULE R1: LOA fit — the vessel's length must not exceed the berth's max length.
loa_fit(Berth, Vessel) :-
    default(Vessel, has_length, L),
    default(Berth, has_max_length, MaxL),
    L =< MaxL,
    !.

% RULE R2: Draft fit — the vessel's draft must not exceed the berth's max draft.
draft_fit(Berth, Vessel) :-
    default(Vessel, has_draft, D),
    default(Berth, has_max_draft, MaxD),
    D =< MaxD,
    !.

% RULE R3: Beam fit — the vessel type's beam must not exceed the berth's max beam.
beam_fit(Berth, Vessel) :-
    instance_of(Vessel, VType),
    vessel_type(VType),
    default(VType, has_beam, B),
    default(Berth, has_max_beam, MaxB),
    B =< MaxB,
    !.

% RULE R4: Fender compatibility — the berth's fender rating must meet the vessel type's need.
fender_fit(Berth, Vessel) :-
    instance_of(Vessel, VType),
    vessel_fender_need(VType, Need),
    berth_fender(Berth, Has),
    fender_rank(Need, NeedRank),
    fender_rank(Has, HasRank),
    NeedRank =< HasRank,
    !.

% RULE R5: Cargo-handling capability — the berth must provide every capability the vessel type needs.
cargo_handling_fit(Berth, Vessel) :-
    instance_of(Vessel, VType),
    vessel_type(VType),
    forall(vessel_class_needs(VType, Cap), berth_provides(Berth, Cap)),
    !.

% RULE R6: berth_provides/2 — a berth provides a capability iff its berth class provides it.
berth_provides(Berth, Capability) :-
    instance_of(Berth, BerthClass),
    berth_class_provides(BerthClass, Capability).

% RULE R7: compatible/2 — composite: all five fit checks pass.
compatible(Berth, Vessel) :-
    loa_fit(Berth, Vessel),
    draft_fit(Berth, Vessel),
    beam_fit(Berth, Vessel),
    fender_fit(Berth, Vessel),
    cargo_handling_fit(Berth, Vessel).

% RULE R8: find_compatible_berths/2 — every berth compatible with the vessel.
find_compatible_berths(Vessel, Berths) :-
    findall(Berth,
            ( instance_of(Berth, BerthClass),
              subclass_of(BerthClass, berth),
              compatible(Berth, Vessel) ),
            Berths).
