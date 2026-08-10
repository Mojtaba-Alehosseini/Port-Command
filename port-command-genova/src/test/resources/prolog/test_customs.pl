% PLUnit suite for rules_customs.pl (R21–R24). Owned by task 04.
% Includes the assert/retract of the dynamic blacklisted_combo/2.
:- use_module(library(plunit)).

:- begin_tests(customs).

% RULE R21
test(r21_clearance_ok) :-
    clearance_ok(tanker, hazmat_class_3).
test(r21_clearance_blacklisted,
        [ setup(assertz(user:blacklisted_combo(tanker, hazmat_class_3))),
          cleanup(retract(user:blacklisted_combo(tanker, hazmat_class_3))),
          fail ]) :-
    clearance_ok(tanker, hazmat_class_3).
test(r21_clearance_unknown_type, [fail]) :-
    clearance_ok(submarine, general_cargo).

% RULE R22
test(r22_needs_inspection_hazmat) :-
    needs_inspection(hazmat_class_3).
test(r22_no_inspection_general, [fail]) :-
    needs_inspection(general_cargo).

% RULE R23
test(r23_inspection_prob_hazmat) :-
    inspection_probability(tanker, hazmat_class_3, P), P =:= 0.8.
test(r23_inspection_prob_general) :-
    inspection_probability(cargo_vessel, general_cargo, P), P =:= 0.2.

% RULE R24
test(r24_high_risk_hazmat) :-
    high_risk(tanker, hazmat_class_3).
test(r24_low_risk_general, [fail]) :-
    high_risk(cargo_vessel, general_cargo).

% --- is_hazmat/1 reflexive-transitive (audit P1-7, 2026-08-10) ---------------
% is_hazmat lives in port_ontology.pl, not in the rule kernel, but every one of its
% consumers is here (R22/R23/R24), so its cases belong with them.
%
% It used to be the single one-hop clause `is_hazmat(C) :- subclass_of(C, hazmat_cargo).`,
% under which the PARENT class failed its own predicate. The first three tests below are
% the regression: all three fail on that clause.

% [nondet] on the two positive is_hazmat probes: the recursive clause legitimately leaves a
% choicepoint (it retries subclass_of/2 on backtracking). The Java facade asks only for a
% first solution, so this is expected rather than a defect worth cutting away.
test(p1_7_hazmat_parent_is_itself_hazmat, [nondet]) :-
    is_hazmat(hazmat_cargo).
test(p1_7_hazmat_parent_needs_inspection) :-
    needs_inspection(hazmat_cargo).
test(p1_7_hazmat_parent_is_rated_high_risk) :-
    inspection_probability(tanker, hazmat_cargo, P), P =:= 0.8,
    high_risk(tanker, hazmat_cargo).

% One hop — worked before, must keep working.
test(p1_7_one_hop_child_still_hazmat, [nondet]) :-
    is_hazmat(hazmat_class_1),
    is_hazmat(hazmat_class_3).

% NO two-hop case here, deliberately: the shipped ontology is exactly two levels deep
% (hazmat_class_1|_3 -> hazmat_cargo -> cargo), so there is no descendant to test with, and
% subclass_of/2 is STATIC — asserting a synthetic edge raises "No permission to modify static
% procedure", and making it dynamic would forfeit the purity that lets PrologQueries.isHazmat
% cache its result (INVARIANTS: never cache a goal that reads a dynamic predicate). The
% transitive leg is therefore future-proofing with no live effect; it was verified
% out-of-band in swipl against a synthetic 2-hop edge when the clause was written.
% The exhaustive set below is what pins that no OTHER class became hazmat.
test(p1_7_hazmat_set_is_exactly_the_three_hazmat_classes) :-
    findall(C, is_hazmat(C), Cs), sort(Cs, Sorted),
    Sorted == [hazmat_cargo, hazmat_class_1, hazmat_class_3].

% Controls: non-hazmat siblings, and the GRANDPARENT must not become hazmat by recursion.
test(p1_7_non_hazmat_control, [fail]) :-
    is_hazmat(general_cargo).
test(p1_7_liquid_bulk_control, [fail]) :-
    is_hazmat(liquid_bulk).
test(p1_7_cargo_root_is_not_hazmat, [fail]) :-
    is_hazmat(cargo).

:- end_tests(customs).
