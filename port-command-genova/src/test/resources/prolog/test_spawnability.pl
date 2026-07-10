% PLUnit suite: spawnability gate (owned by task 07b, v1.1 2026-07-04).
% Locks the invariant that EVERY vessel type, across the full min/mid/max span of
% its vessel_templates.json draft+length ranges, has >=1 compatible berth. Before
% 07b, ferry AND cruise had none, and containers >250 m had none — this suite goes
% RED on that pre-07b data, so it is a genuine gate, not a smoke test.
% Driver consults port_ontology.pl + rules_compatibility.pl + this file, then
% run_tests(spawnability). Dims below are the {min, mid, max} of each template range;
% tonnage is irrelevant to compatibility (it drives tug counts, not berths).
:- use_module(library(plunit)).

:- begin_tests(spawnability).

% Transient fixtures asserted user:-qualified (where the rules resolve them, as in
% production and the other suites). Compatibility needs only type + draft + length;
% beam/fender/cargo-need all derive from the vessel TYPE in the loaded ontology.
mk(V, Type, Draft, Length) :-
    assertz(user:instance_of(V, Type)),
    assertz(user:default(V, has_draft, Draft)),
    assertz(user:default(V, has_length, Length)).
rm(V) :-
    retractall(user:instance_of(V, _)),
    retractall(user:default(V, _, _)).

nonempty(V) :- find_compatible_berths(V, Bs), Bs \= [].
fits(V, Berth) :- find_compatible_berths(V, Bs), member(Berth, Bs).

% --- tanker: draft [12.0, 15.5], length [180, 240] -> deep-water berth_1 ---------
test(tanker_min, [setup(mk(v, tanker, 12.0, 180.0)),  cleanup(rm(v))]) :- nonempty(v).
test(tanker_mid, [setup(mk(v, tanker, 13.75, 210.0)), cleanup(rm(v))]) :- nonempty(v).
test(tanker_max, [setup(mk(v, tanker, 15.5, 240.0)),  cleanup(rm(v))]) :- nonempty(v).

% --- container_vessel: draft [10.0, 14.0], length [200, 300] -> berth_2 only -----
% (needs container_crane; berth_2's length 340 is what lets the 300 m max fit — the fix)
test(container_min, [setup(mk(v, container_vessel, 10.0, 200.0)), cleanup(rm(v))]) :- nonempty(v).
test(container_mid, [setup(mk(v, container_vessel, 12.0, 250.0)), cleanup(rm(v))]) :- nonempty(v).
test(container_max, [setup(mk(v, container_vessel, 14.0, 300.0)), cleanup(rm(v))]) :-
    fits(v, berth_2).                 % 300 m container fits berth_2 (acceptance criterion)

% --- cargo_vessel: draft [8.0, 12.0], length [120, 180] -> berth_1/2/3 -----------
test(cargo_min, [setup(mk(v, cargo_vessel, 8.0, 120.0)),  cleanup(rm(v))]) :- nonempty(v).
test(cargo_mid, [setup(mk(v, cargo_vessel, 10.0, 150.0)), cleanup(rm(v))]) :- nonempty(v).
test(cargo_max, [setup(mk(v, cargo_vessel, 12.0, 180.0)), cleanup(rm(v))]) :- nonempty(v).

% --- ferry: draft [4.0, 6.5], length [80, 140] -> ferry_berth_4 only -------------
% (needs ro_ro_ramp; berth_4's beam 26 is what lets ferry beam 25 fit — the fix)
test(ferry_min, [setup(mk(v, ferry, 4.0, 80.0)),   cleanup(rm(v))]) :- fits(v, berth_4).
test(ferry_mid, [setup(mk(v, ferry, 5.25, 110.0)), cleanup(rm(v))]) :- fits(v, berth_4).
test(ferry_max, [setup(mk(v, ferry, 6.5, 140.0)),  cleanup(rm(v))]) :- fits(v, berth_4).

% --- cruise_ship: draft [7.5, 9.5], length [250, 330] -> berth_2 (scenic quay) ---
% (needs passenger_terminal; berth_2 now provides it AND is long enough at 340 — the fix)
test(cruise_min, [setup(mk(v, cruise_ship, 7.5, 250.0)), cleanup(rm(v))]) :- fits(v, berth_2).
test(cruise_mid, [setup(mk(v, cruise_ship, 8.5, 290.0)), cleanup(rm(v))]) :- fits(v, berth_2).
test(cruise_max, [setup(mk(v, cruise_ship, 9.5, 330.0)), cleanup(rm(v))]) :- fits(v, berth_2).

% --- boundary: draft == berth max_draft must pass (R2 uses =<). Pins the specific
%     berth whose ceiling equals the draft, so a future '<' regression is caught. ---
test(boundary_container_draft_14_eq_berth2, [setup(mk(v, container_vessel, 14.0, 250.0)), cleanup(rm(v))]) :-
    compatible(berth_2, v).           % draft 14.0 == berth_2 has_max_draft 14.0
test(boundary_cargo_draft_12_eq_berth3, [setup(mk(v, cargo_vessel, 12.0, 150.0)), cleanup(rm(v))]) :-
    compatible(berth_3, v).           % draft 12.0 == berth_3 has_max_draft 12.0

% --- negative control: a 350 m cruise is over berth_2's 340 (its only capable big
%     berth); berth_1 is long enough but provides no passenger_terminal; 3/4 too small. ---
test(neg_control_350m_cruise_finds_nothing, [setup(mk(v, cruise_ship, 9.5, 350.0)), cleanup(rm(v))]) :-
    find_compatible_berths(v, Bs), Bs == [].

:- end_tests(spawnability).
