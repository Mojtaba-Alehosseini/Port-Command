% PLUnit suite for rules_weather.pl (R15–R20). Owned by task 04.
:- use_module(library(plunit)).

:- begin_tests(weather).

mk_vessel(V, Type) :- assertz(user:instance_of(V, Type)).
rm_vessel(V) :- retractall(user:instance_of(V, _)).

% RULE R15
test(r15_tanker_wind_ok)        :- wind_within_limit(tanker, 28).
test(r15_tanker_wind_over, [fail]) :- wind_within_limit(tanker, 32).
test(r15_container_higher_limit) :- wind_within_limit(container_vessel, 38).

% RULE R16
test(r16_visibility_good)       :- visibility_ok(good).
test(r16_visibility_poor, [fail]) :- visibility_ok(poor).

% RULE R17
test(r17_swell_ok)              :- swell_within_limit(3.0).
test(r17_swell_over, [fail])    :- swell_within_limit(5.0).

% RULE R18 (threshold_crossed is an enumerator — nondet by design)
test(r18_threshold_crossed_35, [nondet]) :- threshold_crossed(36, 35).
test(r18_threshold_not_crossed, [fail]) :- threshold_crossed(28, 30).

% RULE R19
test(r19_stormy_unsafe)         :- weather_state_unsafe(stormy).

% RULE R20
test(r20_operation_safe, [setup(mk_vessel(wv, tanker)), cleanup(rm_vessel(wv))]) :-
    operation_safe(wv, 28, good).
test(r20_unsafe_wind, [setup(mk_vessel(wv, tanker)), cleanup(rm_vessel(wv)), fail]) :-
    operation_safe(wv, 32, good).        % storm 32 kn trips the tanker limit
test(r20_unsafe_visibility, [setup(mk_vessel(wv, tanker)), cleanup(rm_vessel(wv)), fail]) :-
    operation_safe(wv, 28, poor).

:- end_tests(weather).
