% rules_priority.pl — operation priority (R25–R30). Sources: references.md.
% Order (highest first): emergency > medical > scheduled_ferry > scheduled_arrival > departure > maintenance.

:- dynamic vessel_priority_override/2.

% RULE R25: priority_rank/2 — 1 (highest) … 6 (lowest).
priority_rank(emergency, 1).
priority_rank(medical, 2).
priority_rank(scheduled_ferry, 3).
priority_rank(scheduled_arrival, 4).
priority_rank(departure, 5).
priority_rank(maintenance, 6).

% RULE R26: priority_class_of/2 — bridge a vessel to its priority class. A runtime
%   override (emergency/medical) wins; ferries are scheduled_ferry; default scheduled_arrival.
priority_class_of(Vessel, Class) :-
    vessel_priority_override(Vessel, Class),
    !.
priority_class_of(Vessel, scheduled_ferry) :-
    instance_of(Vessel, ferry),
    !.
priority_class_of(Vessel, scheduled_arrival) :-
    instance_of(Vessel, VType),
    vessel_type(VType),
    !.

% RULE R27: higher_priority/2 — A outranks B (lower rank number).
higher_priority(ClassA, ClassB) :-
    priority_rank(ClassA, RA),
    priority_rank(ClassB, RB),
    RA < RB.

% RULE R28: preempts/2 — a higher-priority class preempts a lower one.
preempts(ClassA, ClassB) :-
    higher_priority(ClassA, ClassB).

% RULE R29: emergency overrides everything else.
emergency_overrides_all(Class) :-
    priority_rank(Class, _),
    Class \= emergency,
    preempts(emergency, Class).

% RULE R30: effective_priority/2 — the rank of a vessel's (possibly overridden) class.
effective_priority(Vessel, Rank) :-
    priority_class_of(Vessel, Class),
    priority_rank(Class, Rank).
