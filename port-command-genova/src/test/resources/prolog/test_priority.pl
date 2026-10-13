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
% S16 (ADR-20, decision C4): a walk-in arrival ranks BELOW a scheduled arrival - a fact under
% R25, not a new rule. It shares rank 5 with departure, so neither outranks the other.
test(r25_rank_walk_in)     :- priority_rank(walk_in_arrival, 5).
test(r27_scheduled_outranks_walk_in) :- higher_priority(scheduled_arrival, walk_in_arrival).
test(r27_ferry_outranks_walk_in)     :- higher_priority(scheduled_ferry, walk_in_arrival).
test(r27_walk_in_not_above_scheduled, [fail]) :- higher_priority(walk_in_arrival, scheduled_arrival).
test(r27_walk_in_ties_departure, [fail])      :- higher_priority(walk_in_arrival, departure).
test(r27_walk_in_above_maintenance)  :- higher_priority(walk_in_arrival, maintenance).
% R26 clause 1 carries it: the HarbourMaster hands walk_in_arrival in as the vessel's class
% override, and the override wins over the type - a walk-in FERRY is a walk-in arrival.
test(r26_walk_in_class_wins_over_ferry,
     [setup((mk_vessel(pv, ferry), assertz(user:vessel_priority_override(pv, walk_in_arrival)))),
      cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, walk_in_arrival),
    effective_priority(pv, 5).
% R29 still holds for it: an emergency goes ahead of a walk-in arrival too.
test(r29_emergency_overrides_walk_in) :- emergency_overrides_all(walk_in_arrival).

% RULE R26
test(r26_ferry_class, [setup(mk_vessel(pv, ferry)), cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, scheduled_ferry).
test(r26_default_class, [setup(mk_vessel(pv, tanker)), cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, scheduled_arrival).
test(r26_override_wins, [setup((mk_vessel(pv, tanker), assertz(user:vessel_priority_override(pv, emergency)))),
                         cleanup(rm_vessel(pv))]) :-
    priority_class_of(pv, emergency).
% S11 steadfastness pins: R26 must be a FUNCTION of the vessel — a checking call with the
% wrong class must fail. Before the fix the red cuts let a ferry pass as scheduled_arrival
% (clause 3) and an overridden vessel still answer its un-overridden class.
test(r26_ferry_is_not_default_checkmode, [setup(mk_vessel(pv, ferry)), cleanup(rm_vessel(pv)), fail]) :-
    priority_class_of(pv, scheduled_arrival).
test(r26_override_shadows_default_checkmode,
     [setup((mk_vessel(pv, tanker), assertz(user:vessel_priority_override(pv, emergency)))),
      cleanup(rm_vessel(pv)), fail]) :-
    priority_class_of(pv, scheduled_arrival).

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
