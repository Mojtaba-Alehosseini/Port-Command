% PLUnit suite for rules_priority.pl (R25–R30). Owned by task 04.
:- use_module(library(plunit)).

:- begin_tests(priority).

mk_vessel(V, Type) :- assertz(user:instance_of(V, Type)).
rm_vessel(V) :-
    retractall(user:instance_of(V, _)),
    retractall(user:vessel_priority_override(V, _)).

% RULE R25
test(r25_rank_emergency)   :- priority_rank(emergency, 1).
test(r25_rank_maintenance) :- priority_rank(maintenance, 6).

% RULE R26
test(r26_ferry_class, [setup(mk_vessel(pv, ferry)), cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, scheduled_ferry).
test(r26_default_class, [setup(mk_vessel(pv, tanker)), cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, scheduled_arrival).
test(r26_override_wins, [setup((mk_vessel(pv, tanker), assertz(user:vessel_priority_override(pv, emergency)))),
                         cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, emergency).

% RULE R27
test(r27_higher_priority)        :- higher_priority(emergency, maintenance).
test(r27_not_higher, [fail])     :- higher_priority(maintenance, emergency).

% RULE R28
test(r28_preempts)               :- preempts(medical, departure).

% RULE R29
test(r29_emergency_overrides)    :- emergency_overrides_all(scheduled_ferry).

% RULE R30
test(r30_effective_priority, [setup(mk_vessel(pv, ferry)), cleanup(rm_vessel(pv))]) :-
    effective_priority(pv, 3).

:- end_tests(priority).
