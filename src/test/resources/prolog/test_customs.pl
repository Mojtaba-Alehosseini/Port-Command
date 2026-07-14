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

:- end_tests(customs).
