% PLUnit suite for rules_escort.pl (R9–R14). Owned by task 04.
:- use_module(library(plunit)).

:- begin_tests(escort).

mk_vessel(V, Type, Tonnage) :-
    assertz(user:instance_of(V, Type)),
    assertz(user:default(V, has_tonnage, Tonnage)).
rm_vessel(V) :-
    retractall(user:instance_of(V, _)),
    retractall(user:default(V, _, _)).

% RULE R9
test(r9_base_tugs_small) :- base_tugs(15000, 1).
test(r9_base_tugs_mid)   :- base_tugs(30000, 2).
test(r9_base_tugs_large) :- base_tugs(60000, 3).

% RULE R10
test(r10_tanker_minimum) :- type_tug_minimum(tanker, 2).
test(r10_other_minimum)  :- type_tug_minimum(ferry, 0).

% RULE R11 — canonical tug outcomes per PROJECT_DEFINITION S5.2 (updated by task 07b,
% v1.1 2026-07-04: ferry exempt = own propulsion; every other type capped at 2 because
% the fleet is 4 tugs — the pre-07b kernel gave big tankers/cargo 3. Doc-truth, not a
% test weakening; see MASTER_PLAN S8 items 20-21).
test(r11_ferry_zero_own_propulsion, [setup(mk_vessel(ev, ferry, 12000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 0.       % ferry exempt regardless of tonnage
test(r11_tanker_small_still_two, [setup(mk_vessel(ev, tanker, 15000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 2.       % base 1 lifted to tanker minimum 2
test(r11_tanker_large_capped_two, [setup(mk_vessel(ev, tanker, 120000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 2.       % base 3 capped at 2 (fleet cap)
test(r11_container_large_capped_two, [setup(mk_vessel(ev, container_vessel, 90000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 2.       % base 3 capped at 2
test(r11_cargo_small_one, [setup(mk_vessel(ev, cargo_vessel, 15000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 1.       % <20k -> base 1, no type minimum
test(r11_cargo_mid_two, [setup(mk_vessel(ev, cargo_vessel, 40000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 2.       % 20-50k -> base 2
test(r11_cruise_large_capped_two, [setup(mk_vessel(ev, cruise_ship, 160000)), cleanup(rm_vessel(ev))]) :-
    tugs_required(ev, N), N =:= 2.       % base 3 capped at 2

% RULE R12
test(r12_score_bid) :-
    score_bid(bid(tug_a, 500, 6.0, 0.8), Score-tug_a),
    Expected is 0.8 * 100 - 500 * 0.1 - 6.0,
    Score =:= Expected.

% RULE R13
test(r13_best_one) :-
    best_n_bids([bid(tug_a, 500, 6.0, 0.8), bid(tug_b, 400, 5.0, 0.9)], 1, W),
    W == [tug_b].                        % tug_b scores higher
test(r13_best_two_sorted_desc) :-
    best_n_bids([bid(tug_a, 500, 6.0, 0.8), bid(tug_b, 400, 5.0, 0.9), bid(tug_c, 900, 12.0, 0.2)], 2, W),
    W == [tug_b, tug_a].
test(r13_best_empty) :-
    best_n_bids([], 2, W), W == [].

% RULE R14
test(r14_take_first_n)  :- take_first_n([5-a, 3-b, 1-c], 2, [5-a, 3-b]).
test(r14_extract_tugs)  :- extract_tugs([5-a, 3-b], [a, b]).

:- end_tests(escort).
