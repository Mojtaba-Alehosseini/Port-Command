% PLUnit suite for dcg_negotiation.pl — the 16-M1 negotiation-move corpus.
% Owned by task 16 (task 04 owns the five rules_*.pl suites; task 25 owns the goldens).
% Driver consults dcg_negotiation.pl + this file, then run_tests(dcg).
%
% Suite name is `dcg` (not `dcg_negotiation`) so it drops straight into the established
% test_<suite>.pl / run_tests(<suite>) convention PrologRulesIT drives by @ValueSource.
%
% ASSERTION STYLE: the full element list is matched EXACTLY (==), not by member/2. Element
% order is a published contract (see dcg_negotiation.pl's header), and exact matching is
% what catches a SILENTLY DROPPED slot — the precise bug class the mention-guards exist to
% prevent. A member/2 corpus would have passed happily while "at berth 9" vanished.
:- use_module(library(plunit)).

:- begin_tests(dcg).

% =====================================================================
% propose — the opening offer
% =====================================================================

propose_case([i,will,give,you,2000,for,5,hours,at,berth,3],
             [move=propose, money=price(2000,eur), duration=5, berth=berth_3]).
propose_case([i,will,give,you,2000,for,5,hours],
             [move=propose, money=price(2000,eur), duration=5]).
propose_case([i,will,give,you,2000],
             [move=propose, money=price(2000,eur)]).
propose_case(['i\'ll',pay,1800,euros,for,berth,3],
             [move=propose, money=price(1800,eur), berth=berth_3]).
propose_case(['i\'ll',give,you,2200,for,6,hours,at,berth,2],
             [move=propose, money=price(2200,eur), duration=6, berth=berth_2]).
propose_case([we,will,offer,3000,for,8,hours,at,berth,1],
             [move=propose, money=price(3000,eur), duration=8, berth=berth_1]).
propose_case([we,offer,2200,for,6,hours,by,'19:30'],
             [move=propose, money=price(2200,eur), duration=6, deadline='19:30']).
propose_case([give,you,2000,usd,for,5,h],
             [move=propose, money=price(2000,usd), duration=5]).
propose_case([i,will,pay,1500,dollars,for,4,hrs,at,berth,4],
             [move=propose, money=price(1500,usd), duration=4, berth=berth_4]).
propose_case([let,me,offer,1900,for,3,hours],
             [move=propose, money=price(1900,eur), duration=3]).
propose_case([i,will,give,you,2000,please],
             [move=propose, money=price(2000,eur)]).
propose_case([offer,2500,eur,for,12,hours,at,berth,2,thanks],
             [move=propose, money=price(2500,eur), duration=12, berth=berth_2]).
propose_case([i,will,give,them,1700,for,2,hours,in,berth,3],
             [move=propose, money=price(1700,eur), duration=2, berth=berth_3]).

test('propose parses to the expected frame', [forall(propose_case(Tokens, Expected))]) :-
    parse_move(Tokens, frame(Name, Elements)),
    assertion(Name == commerce_sell),
    assertion(Elements == Expected).

% =====================================================================
% counter — a re-proposal (FIPA: a counter-offer IS a fresh proposal)
% =====================================================================

counter_case(['i\'ll',only,give,you,1500],
             [move=counter, money=price(1500,eur)]).
counter_case([i,can,only,do,1500],
             [move=counter, money=price(1500,eur)]).
counter_case([how,about,1800],
             [move=counter, money=price(1800,eur)]).
counter_case([how,about,1800,for,5,hours],
             [move=counter, money=price(1800,eur), duration=5]).
counter_case([what,about,1750,at,berth,3],
             [move=counter, money=price(1750,eur), berth=berth_3]).
counter_case([1900,instead],
             [move=counter, money=price(1900,eur)]).
counter_case([only,1600,for,4,hours,at,berth,2],
             [move=counter, money=price(1600,eur), duration=4, berth=berth_2]).
counter_case([i,can,only,do,2000,euros,for,6,hours],
             [move=counter, money=price(2000,eur), duration=6]).
counter_case([how,about,2100,usd],
             [move=counter, money=price(2100,usd)]).
counter_case(['i\'ll',only,pay,1400,for,3,hours,at,berth,4],
             [move=counter, money=price(1400,eur), duration=3, berth=berth_4]).

test('counter parses to the expected frame', [forall(counter_case(Tokens, Expected))]) :-
    parse_move(Tokens, frame(Name, Elements)),
    assertion(Name == commerce_sell),
    assertion(Elements == Expected).

% =====================================================================
% accept — elements are EXACTLY [move=accept] (an acceptance adds no terms)
% =====================================================================

accept_case([deal]).
accept_case([yes]).
accept_case([agreed]).
accept_case([agree]).
accept_case([accept]).
accept_case([accepted]).
accept_case([ok]).
accept_case([okay]).
accept_case([fine]).
accept_case([done]).
accept_case([sounds,good]).
accept_case([you,got,it]).
accept_case([that,works]).
accept_case([yes,please]).
accept_case([deal,thanks]).
accept_case([i,agree]).
accept_case([we,accept]).
accept_case(['i\'ll',accept]).

test('accept parses to exactly [move=accept]', [forall(accept_case(Tokens))]) :-
    parse_move(Tokens, Frame),
    assertion(Frame == frame(commerce_sell, [move=accept])).

% The task file pins this one literally (planning/16 Step 16.2).
test('accept_simple pinned by the task file') :-
    parse_move([deal], frame(commerce_sell, [move=accept])).

% =====================================================================
% reject — reason inferred from the cue; bare refusals omit the slot
% =====================================================================

reject_case([too,low],        [move=reject, reason=price_too_low]).
reject_case([too,cheap],      [move=reject, reason=price_too_low]).
reject_case([too,high],       [move=reject, reason=price_too_high]).
reject_case([too,expensive],  [move=reject, reason=price_too_high]).
reject_case([too,short],      [move=reject, reason=duration_too_short]).
reject_case([too,long],       [move=reject, reason=duration_too_long]).
reject_case([no,deal],        [move=reject]).
reject_case([no],             [move=reject]).
reject_case([not,interested], [move=reject]).
reject_case([forget,it],      [move=reject]).
reject_case([pass],           [move=reject]).
reject_case([reject],         [move=reject]).
reject_case([rejected],       [move=reject]).
reject_case([refuse],         [move=reject]).
reject_case([no,deal,mate],   [move=reject]).
reject_case([too,low,please], [move=reject, reason=price_too_low]).

test('reject parses to the expected frame', [forall(reject_case(Tokens, Expected))]) :-
    parse_move(Tokens, frame(Name, Elements)),
    assertion(Name == commerce_sell),
    assertion(Elements == Expected).

% The task file pins this one literally (planning/16 Step 16.2).
test('reject_with_reason pinned by the task file') :-
    parse_move([too, low], frame(commerce_sell, [move=reject, reason=price_too_low])).

% "no deal" must NOT parse as a bare [no] leaving "deal" in Rest — the longest-cue-first
% ordering in reject_phrase//1 is what guarantees the FIRST solution is the greedy one,
% and parse_move/2's cut is what commits to it.
% [nondet] is correct and deliberate: this probes bare phrase/3 (below parse_move/2's cut),
% and phrase/3 with an unbound Rest is nondet by nature — enumerating gives exactly
%   Rest=[]     -> frame(commerce_sell,[move=reject])   (from reject_phrase --> [no,deal])
%   Rest=[deal] -> frame(commerce_sell,[move=reject])   (from reject_phrase --> [no])
% Both yield the SAME frame, so the residual ambiguity is harmless semantically; the
% ordering still matters for Rest hygiene, which is what this test pins.
test('no deal consumes both tokens, not just [no]', [nondet]) :-
    phrase(negotiation_move(_), [no, deal], Rest),
    assertion(Rest == []).

% =====================================================================
% ask — question word (+ optional copula) + a closed topic set
% =====================================================================

ask_case([what,berths,are,free],           free_berths).
ask_case([which,berths,are,free],          free_berths).
ask_case([what,free,berths,do,you,have],   free_berths).
ask_case([are,berths,available],           free_berths).
ask_case([how,many,tugs,do,you,have],      tug_availability).
ask_case([how,many,tugs,are,free],         tug_availability).
ask_case([what,tugs,are,available],        tug_availability).
ask_case(['what\'s',your,best,price],      best_price).
ask_case(['what\'s',your,best,offer],      best_price).
ask_case([what,is,your,best,price],        best_price).
ask_case(['what\'s',the,weather],          weather).
ask_case([what,is,the,weather],            weather).
ask_case(['how\'s',the,wind],              weather).
ask_case([what,berth,do,i,get],            berth_assignment).
ask_case([where,is,my,berth],              berth_assignment).
ask_case(['where\'s',my,berth],            berth_assignment).

test('ask parses to the expected topic', [forall(ask_case(Tokens, Topic))]) :-
    parse_move(Tokens, frame(Name, Elements)),
    assertion(Name == commerce_sell),
    assertion(Elements == [move=ask, topic=Topic]).

% =====================================================================
% The mention-guards: a slot that is NAMED but INVALID must fail the parse,
% never be silently dropped. Without these guards each of these parses as a
% bare price proposal and the player's request vanishes into phrase/3's Rest —
% and OntologyValidator cannot catch it, because the slot is ABSENT not malformed.
% =====================================================================

invalid_slot_case([i,will,give,you,2000,at,berth,9]).      % no berth_9 exists
invalid_slot_case([i,will,give,you,2000,at,berth,0]).      % berths are 1..4
invalid_slot_case([i,will,give,you,2000,at,berth,42]).
invalid_slot_case([i,will,give,you,2000,for,99,hours]).    % durations are 1..24
invalid_slot_case([i,will,give,you,2000,for,0,hours]).
invalid_slot_case([i,will,give,you,2000,for,25,hours]).    % just past the boundary
invalid_slot_case([we,offer,2200,by,'25:99']).             % not a clock time
invalid_slot_case([we,offer,2200,by,'19:75']).             % minutes out of range
invalid_slot_case([we,offer,2200,by,'10:5']).              % minutes not two digits ("10:05" is)
invalid_slot_case([i,will,give,you,5,hours]).              % a duration, NOT a EUR 5 price

test('a named-but-invalid slot fails the parse', [forall(invalid_slot_case(Tokens)), fail]) :-
    parse_move(Tokens, _).

% The guards must not over-reject: every boundary value still parses.
boundary_case([i,will,give,you,2000,at,berth,1], [move=propose, money=price(2000,eur), berth=berth_1]).
boundary_case([i,will,give,you,2000,at,berth,4], [move=propose, money=price(2000,eur), berth=berth_4]).
boundary_case([i,will,give,you,2000,for,1,hour], [move=propose, money=price(2000,eur), duration=1]).
boundary_case([i,will,give,you,2000,for,24,hours], [move=propose, money=price(2000,eur), duration=24]).
boundary_case([we,offer,2200,by,'00:00'], [move=propose, money=price(2200,eur), deadline='00:00']).
boundary_case([we,offer,2200,by,'23:59'], [move=propose, money=price(2200,eur), deadline='23:59']).
boundary_case([we,offer,2200,by,'9:30'], [move=propose, money=price(2200,eur), deadline='9:30']).  % 1-digit hour ok

test('boundary values still parse', [forall(boundary_case(Tokens, Expected))]) :-
    parse_move(Tokens, frame(commerce_sell, Elements)),
    assertion(Elements == Expected).

% =====================================================================
% Prefix-parse semantic inversions. Every bare move matches a one-word prefix, so if
% phrase/3's residue were ignored these would parse to the OPPOSITE of what the player
% said — and two of them would emit a BINDING ACCEPT_PROPOSAL for an offer just refused.
% parse_move/2 only accepts a parse whose residue is ignorable, so all of these miss and
% fall through to Rasa. Found by the task-16 adversarial review, not by the corpus.
% =====================================================================

inversion_case([ok,but,that,s,too,low]).              % would ACCEPT an offer called too low
inversion_case([yes,if,you,drop,to,1500]).            % conditional read as unconditional ACCEPT
inversion_case([no,'i\'ll',give,you,1500]).           % the 1500 counter-offer would vanish
inversion_case([no,thanks,'i\'ll',give,1500,instead]).
inversion_case([deal,but,only,for,3,h]).              % the 3-hour condition would vanish
inversion_case([deal,for,99,h]).
inversion_case([yes,at,berth,9]).
inversion_case([i,accept,but,at,berth,2]).            % the berth constraint would vanish
inversion_case([no,deal,unless,you,pay,3000]).
inversion_case([fine,'i\'ll',give,you,2500,for,5,h,at,berth,2]).
inversion_case([we,offer,2200,by,tomorrow]).          % the stated deadline would vanish

test('an utterance whose residue is meaningful does not parse',
     [forall(inversion_case(Tokens)), fail]) :-
    parse_move(Tokens, _).

% Non-vacuity for the block above: the residue rule must not simply reject everything with
% more than one token. Pure filler after a move is still ignorable.
filler_tail_case([deal,thanks],     [move=accept]).
filler_tail_case([yes,please],      [move=accept]).
filler_tail_case([no,deal,mate],    [move=reject]).
filler_tail_case([too,low,please],  [move=reject, reason=price_too_low]).

test('a pure-filler residue is still ignorable', [forall(filler_tail_case(Tokens, Expected))]) :-
    parse_move(Tokens, frame(commerce_sell, Elements)),
    assertion(Elements == Expected).

% =====================================================================
% Precision: things the grammar must refuse outright (-> Rasa fallback, task 14)
% =====================================================================

no_parse_case([]).                        % empty input
no_parse_case([asdf,qwer]).               % gibberish
no_parse_case([the,weather,is,nice]).     % declarative, not a move
no_parse_case([2000]).                    % bare fragment — 16-M2's ellipsis block owns it
no_parse_case([make,it,2200]).            % 16-M2 ellipsis
no_parse_case([200,more]).                % 16-M2 delta
no_parse_case([split,the,difference]).    % 16-M2 delta
no_parse_case([nothing,below,2000]).      % 16-M2 negation
no_parse_case([hold,all,tankers]).        % 16-M2 command
no_parse_case([i,will,give,you]).         % offer verb with no amount
no_parse_case([how,about]).               % counter cue with no amount
no_parse_case([what,berths]).             % question word with no known topic

test('out-of-grammar input does not parse', [forall(no_parse_case(Tokens)), fail]) :-
    parse_move(Tokens, _).

% =====================================================================
% Structural invariants
% =====================================================================

% Every move type must be reachable through the single documented entry point.
test('all five move types are reachable via negotiation_move//1') :-
    forall(member(Tokens-Move,
                  [ [i,will,give,you,2000]-propose,
                    [how,about,1800]-counter,
                    [deal]-accept,
                    [no,deal]-reject,
                    [what,berths,are,free]-ask ]),
           ( parse_move(Tokens, frame(commerce_sell, [move=M | _])),
             assertion(M == Move) )).

% `move` is ALWAYS the first element — Frame.fromProlog and FrameToAcl both read it, and
% FrameToAcl switches on it to pick the performative.
test('move is always the first element') :-
    forall(member(Tokens,
                  [ [i,will,give,you,2000,for,5,hours,at,berth,3],
                    [how,about,1800],
                    [deal],
                    [too,low],
                    [what,berths,are,free] ]),
           ( parse_move(Tokens, frame(commerce_sell, Elements)),
             Elements = [First | _],
             assertion(First = (move = _)) )).

% The grammar must be unambiguous: exactly ONE full-consumption parse per utterance.
% Ambiguity here would make the frame depend on clause order rather than on meaning.
test('the grammar admits exactly one parse per utterance') :-
    forall(member(Tokens,
                  [ [i,will,give,you,2000,for,5,hours,at,berth,3],
                    ['i\'ll',pay,1800,euros,for,berth,3],
                    [how,about,1800],
                    [1900,instead],
                    [deal],
                    [too,low],
                    [no,deal],
                    [what,berths,are,free],
                    [where,is,my,berth] ]),
           ( findall(F, phrase(negotiation_move(F), Tokens, []), Parses),
             sort(Parses, Distinct),
             assertion(Parses == Distinct),
             assertion(length(Parses, 1)) )).

:- end_tests(dcg).
