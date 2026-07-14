% PLUnit suite for rules_compatibility.pl (R1–R8). Owned by task 04.
% Driver consults port_ontology.pl + rules_compatibility.pl + this file, then run_tests(compatibility).
:- use_module(library(plunit)).

:- begin_tests(compatibility).

% Fixtures (default/3 + instance_of/2 are dynamic, declared in port_ontology.pl).
% Assert into user: (where the rules live and where JPL asserts in production).
mk_tanker(V) :-
    assertz(user:default(V, has_draft, 14.0)),
    assertz(user:default(V, has_length, 140.0)),
    assertz(user:instance_of(V, tanker)).
rm_vessel(V) :-
    retractall(user:default(V, _, _)),
    retractall(user:instance_of(V, _)).

% RULE R1
test(r1_loa_fit, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    loa_fit(berth_1, tv).

% RULE R2
test(r2_draft_fit_ok, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    draft_fit(berth_1, tv).
test(r2_draft_fit_too_deep, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv)), fail]) :-
    draft_fit(berth_4, tv).               % draft 14 > berth_4 max 9

% RULE R3
test(r3_beam_fit, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    beam_fit(berth_1, tv).                % tanker beam 20 <= berth_1 max 50

% RULE R4
test(r4_fender_fit, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    fender_fit(berth_1, tv).             % tanker needs heavy, berth_1 heavy
test(r4_fender_too_light, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv)), fail]) :-
    fender_fit(berth_4, tv).             % berth_4 light < tanker heavy

% RULE R5
test(r5_cargo_handling_fit, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    cargo_handling_fit(berth_1, tv).     % deep_water provides liquid+hazmat handling
test(r5_cargo_handling_miss, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv)), fail]) :-
    cargo_handling_fit(berth_4, tv).     % ferry_berth lacks liquid_handling

% RULE R6
test(r6_berth_provides) :-
    berth_provides(berth_2, container_crane).

% RULE R7
test(r7_compatible_true, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    compatible(berth_1, tv).
test(r7_compatible_false_on_draft, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv)), fail]) :-
    compatible(berth_4, tv).

% RULE R8
test(r8_find_compatible_berths, [setup(mk_tanker(tv)), cleanup(rm_vessel(tv))]) :-
    find_compatible_berths(tv, Berths),
    member(berth_1, Berths),
    \+ member(berth_4, Berths).

:- end_tests(compatibility).
