% rules_weather.pl — weather operating limits (R15–R20).
% Canonical per-vessel-type wind limits 30/35/40/45 kn (proposal); never 50. Sources: references.md.

% --- Data: per-vessel-type wind limit (knots) ---
wind_limit(tanker, 30).
wind_limit(ferry, 35).
wind_limit(cruise_ship, 35).
wind_limit(container_vessel, 40).
wind_limit(cargo_vessel, 45).

% --- Data: operating swell limit (metres) and adequate-visibility classes ---
max_swell(4.0).
visibility_adequate(good).
visibility_adequate(fair).

% RULE R15: wind within the vessel type's limit.
wind_within_limit(VType, Wind) :-
    wind_limit(VType, Limit),
    Wind < Limit,
    !.

% RULE R16: visibility adequate for operations (good/fair; poor/fog are not).
visibility_ok(Vis) :-
    visibility_adequate(Vis),
    !.

% RULE R17: swell within the operating limit.
swell_within_limit(Swell) :-
    max_swell(Max),
    Swell =< Max,
    !.

% RULE R18: which canonical threshold (30/35/40/45) a wind has crossed.
%   Intentionally nondeterministic — enumerates every crossed threshold.
threshold_crossed(Wind, Threshold) :-
    member(Threshold, [30, 35, 40, 45]),
    Wind >= Threshold.

% RULE R19: a stormy weather state is unsafe regardless of the numeric reading.
weather_state_unsafe(stormy).

% RULE R20: operation_safe/3 — composite: wind under the type limit AND visibility adequate.
operation_safe(Vessel, Wind, Vis) :-
    instance_of(Vessel, VType),
    vessel_type(VType),
    wind_within_limit(VType, Wind),
    visibility_ok(Vis),
    !.
