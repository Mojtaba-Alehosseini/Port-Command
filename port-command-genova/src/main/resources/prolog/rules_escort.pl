% rules_escort.pl — tug escort requirements + bid selection (R9–R14).
% Consumes ontology facts (instance_of/2, vessel_type/1, default/3). Rule sources: references.md.
% No assertz/retract here — transient vessel facts are asserted by the Java facade under the lock.

% RULE R9: base tug count by gross tonnage bracket. Green cuts: brackets are disjoint.
base_tugs(Tonnage, 1) :- Tonnage < 20000, !.
base_tugs(Tonnage, 2) :- Tonnage >= 20000, Tonnage < 50000, !.
base_tugs(_, 3).

% RULE R10: per-vessel-type minimum (tankers need pilot + 2 tugs in the approach lane).
type_tug_minimum(tanker, 2) :- !.
type_tug_minimum(VType, 0) :- vessel_type(VType).

% RULE R11: tugs_required/2 — ferry is exempt (own propulsion -> 0 tugs; task 07b,
%   v1.1 2026-07-04); every other type takes max(tonnage bracket, type minimum)
%   capped at 2, because the fleet is only 4 tugs (the pre-v1.1 kernel gave big
%   tankers 3, which would starve the CNP and break the P&L's ~1-2-tugs/vessel
%   assumption). Canonical outcomes: PROJECT_DEFINITION S5.2 — tanker/container/
%   cruise 2, cargo 1-2, ferry 0. Rule count stays 30 (body change, not a new rule).
tugs_required(Vessel, 0) :-
    instance_of(Vessel, ferry),
    !.
tugs_required(Vessel, N) :-
    instance_of(Vessel, VType),
    vessel_type(VType),
    default(Vessel, has_tonnage, T),
    base_tugs(T, Base),
    type_tug_minimum(VType, Min),
    N is min(max(Base, Min), 2),
    !.

% RULE R12: score_bid — higher is better; favours fuel, penalises cost and ETA.
%   Score = FuelState*100 - Cost*0.1 - EtaMinutes.
score_bid(bid(Tug, Cost, Eta, Fuel), Score-Tug) :-
    Score is Fuel * 100 - Cost * 0.1 - Eta.

% RULE R13: best_n_bids/3 — top-N tug ids by score (descending). Tiebreaker on equal
%   scores: standard order of the Score-Tug pair, i.e. tug id descending.
best_n_bids([], _, []) :- !.
best_n_bids(Bids, N, Winners) :-
    Bids \= [],
    maplist(score_bid, Bids, Scored),
    sort(0, @>=, Scored, Sorted),
    take_first_n(Sorted, N, Top),
    extract_tugs(Top, Winners).

% RULE R14: list helpers for best_n_bids.
take_first_n(_, 0, []) :- !.
take_first_n([H|T], N, [H|Rest]) :- N > 0, N1 is N - 1, take_first_n(T, N1, Rest).
take_first_n([], _, []).

extract_tugs([], []).
extract_tugs([_-Tug|T], [Tug|Rest]) :- extract_tugs(T, Rest).
