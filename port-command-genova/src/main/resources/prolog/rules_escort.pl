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

% RULE R11: tugs_required/2 — the greater of the tonnage bracket and the type minimum.
tugs_required(Vessel, N) :-
    instance_of(Vessel, VType),
    vessel_type(VType),
    default(Vessel, has_tonnage, T),
    base_tugs(T, Base),
    type_tug_minimum(VType, Min),
    N is max(Base, Min),
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
