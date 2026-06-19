% rules_customs.pl — customs pre-clearance (R21–R24). Sources: references.md.
% is_hazmat/1 comes from port_ontology.pl (single owner). No permitted_for/2 — removed.

:- dynamic blacklisted_combo/2.

% RULE R21: clearance_ok/2 — both atoms valid and the (vessel_type, cargo_class) pair
%   is not blacklisted. Clearance succeeds unless blacklisted (flag/manifest checks are
%   folded into blacklisted_combo/2 for v1).
clearance_ok(VesselType, CargoClass) :-
    vessel_type(VesselType),
    cargo_class(CargoClass),
    \+ blacklisted_combo(VesselType, CargoClass),
    !.

% RULE R22: hazmat cargo needs an inspection.
needs_inspection(CargoClass) :-
    cargo_class(CargoClass),
    is_hazmat(CargoClass),
    !.

% RULE R23: inspection_probability/3 — hazmat 0.8, otherwise 0.2.
inspection_probability(VesselType, CargoClass, Prob) :-
    vessel_type(VesselType),
    cargo_class(CargoClass),
    ( is_hazmat(CargoClass) -> Prob = 0.8 ; Prob = 0.2 ).

% RULE R24: high-risk combination — clears but with an above-even inspection probability.
high_risk(VesselType, CargoClass) :-
    inspection_probability(VesselType, CargoClass, Prob),
    Prob >= 0.5.
