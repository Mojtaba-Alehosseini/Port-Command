% PLUnit suite for rules_weather.pl (R15–R20). Owned by task 04.
:- use_module(library(plunit)).

:- begin_tests(weather).

mk_vessel(V, Type) :- assertz(user:instance_of(V, Type)).
rm_vessel(V) :- retractall(user:instance_of(V, _)).

% RULE R15
test(r15_tanker_wind_ok)        :- wind_within_limit(tanker, 28).
test(r15_tanker_wind_over, [fail]) :- wind_within_limit(tanker, 32).
test(r15_container_higher_limit) :- wind_within_limit(container_vessel, 38).
% S7 (audit P3-6) — THE BOUNDARY, pinned as current behaviour: the comparison is a STRICT
% `Wind < Limit`, so a tanker at exactly its 30 kn limit is UNSAFE. references.md marks the
% 30/35/40/45 figures VERIFY ("a proposal", task-04 D4) — there is no confirmed external
% citation to contradict; the <-vs-=< choice is the project's own, and this case is what
% keeps it from drifting silently. swipl-verified 2026-08-16.
test(r15_tanker_at_limit_unsafe, [fail]) :- wind_within_limit(tanker, 30).

% RULE R16
test(r16_visibility_good)       :- visibility_ok(good).
test(r16_visibility_poor, [fail]) :- visibility_ok(poor).
% S7: `fair` is in the data (visibility_adequate/1) and was never exercised — deleting the
% fact used to keep this suite green. COLREGs-adjacent source is VERIFY-tagged; pinned as-is.
test(r16_visibility_fair)       :- visibility_ok(fair).

% RULE R17
test(r17_swell_ok)              :- swell_within_limit(3.0).
test(r17_swell_over, [fail])    :- swell_within_limit(5.0).
% S7 — THE BOUNDARY: swell is INCLUSIVE (`Swell =< Max`), so exactly 4.0 m IS within limit —
% deliberately the opposite polarity of R15's strict wind bound. HarbourMasterAgentIT pins
% the same fact from the Java side; this is the Prolog suite's own copy.
test(r17_swell_at_limit)        :- swell_within_limit(4.0).

% RULE R18 (threshold_crossed is an enumerator — nondet by design)
test(r18_threshold_crossed_35, [nondet]) :- threshold_crossed(36, 35).
test(r18_threshold_not_crossed, [fail]) :- threshold_crossed(28, 30).
% S7: the >= boundary — a wind of exactly 30 HAS crossed the 30 kn threshold — and the full
% enumeration, which is what the Java-side WeatherAlertPolicy.windBand now counts (S5 wired
% R18 as the band source; the count IS the contract).
test(r18_at_exact_threshold, [nondet]) :- threshold_crossed(30, 30).
test(r18_enumerates_all_crossed) :-
    findall(T, threshold_crossed(46, T), Ts),
    Ts == [30, 35, 40, 45].

% RULE R19
test(r19_stormy_unsafe)         :- weather_state_unsafe(stormy).
% S7: the NEGATIVE half — weather_state_unsafe/1 is a single fact, and without these a stray
% catch-all clause (weather_state_unsafe(_)) would make every state unsafe while the suite
% stayed green.
test(r19_sunny_not_unsafe, [fail])  :- weather_state_unsafe(sunny).
test(r19_cloudy_not_unsafe, [fail]) :- weather_state_unsafe(cloudy).

% RULE R20
test(r20_operation_safe, [setup(mk_vessel(wv, tanker)), cleanup(rm_vessel(wv))]) :-
    operation_safe(wv, 28, good).
test(r20_unsafe_wind, [setup(mk_vessel(wv, tanker)), cleanup(rm_vessel(wv)), fail]) :-
    operation_safe(wv, 32, good).        % storm 32 kn trips the tanker limit
test(r20_unsafe_visibility, [setup(mk_vessel(wv, tanker)), cleanup(rm_vessel(wv)), fail]) :-
    operation_safe(wv, 28, poor).

:- end_tests(weather).
