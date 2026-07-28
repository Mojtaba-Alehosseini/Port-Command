% dcg_negotiation.pl — the negotiation-move grammar (the joint-courses keystone).
%
% Parses the constrained English sublanguage of port negotiation into FrameNet-style
% Commerce_sell frames. Presented in the SDAI report as a Prolog artifact (DCG rules
% unify and backtrack over the same kernel) and in the NLP report as a grammar-based
% parser. See PROJECT_DEFINITION.md §6.2 and planning/16_dcg_parser.md.
%
% CONVENTIONS (all load-bearing — read before editing):
%   * NO ':- module(...)' declaration. Every .pl in this repo loads into `user`, and
%     JPL runs its goals unqualified against `user` (INVARIANTS.md "Prolog"). A module
%     declaration here would make parse_move/2 invisible to the Java bridge.
%   * NO '% RULE Rn:' headers in this file. That comment shape is the grep gate for the
%     30-rule kernel (PrologEngineTest.ruleKernelIsExactlyThirtyRules) — the kernel is
%     exactly R1–R30 across the five rules_*.pl modules and this file is not part of it.
%     Comments here use the '% DCG:' style instead.
%   * NO ':- use_module(library(dcg/basics))'. That library is for character/code-level
%     parsing (digits//1, number//1, string//1); we parse a list of already-tokenised
%     atoms and numbers handed over by the Java tokeniser (nlp/DcgTokenizer). Importing
%     it into the shared `user` module would only risk clashing with our own rules.
%     append/2 and friends autoload from library(lists).
%   * The tokeniser owns ALL normalisation (lowercasing, €2000 -> [2000, eur],
%     5h -> [5, h], 14:20 kept whole). Grammar rules must never call downcase_atom/2 or
%     re-normalise — duplicating it here would make case mismatches fail silently.
%
% FRAME SHAPE (the contract the Java decoder in nlp/Frame.java depends on):
%   frame(commerce_sell, [move=Move | Slots])
%   Move  ∈ {propose, counter, accept, reject, ask}   — always the FIRST element.
%   Slots is a canonically-ordered subset of:
%     money=price(Amount, Currency)   Currency ∈ {eur, usd}   (Commerce_sell "money")
%     duration=Hours                  1..24                   (Commerce_sell "duration")
%     berth=berth_1..berth_4                                  (Commerce_sell "goods")
%     deadline=Clock                  e.g. '19:30'            (Commerce_sell "deadline")
%     reason=Reason                   reject moves only
%     topic=Topic                     ask moves only
%   Omitted slots are absent, never bound to a placeholder — Frame.fromProlog maps the
%   list positionally-independently, but the order above is still a fixed contract so
%   the golden corpus can assert on it.
%
% PRECISION OVER RECALL. Every lexical set here is closed. Anything this grammar rejects
% falls through to the Rasa fallback (PROJECT_DEFINITION §6.1, task 14) — an over-
% generating grammar would silently steal utterances Rasa classifies better.

% --- Entry points ---

% DCG: the single entry the Java bridge, the PLUnit suite, and 16-M2 all call.
%
% phrase/3 with an unbound Rest per planning/16's "Hard constraints" — but the residue is
% INSPECTED, not discarded. Ignoring it inverts the meaning of ordinary utterances, because
% every bare move (accept/reject) matches a one-word prefix and stops:
%     "ok but that's too low"   -> accept_phrase matches [ok] -> ACCEPT_PROPOSAL
%     "Yes if you drop to 1500" -> accept_phrase matches [yes] -> ACCEPT_PROPOSAL
%     "No, I'll give you 1500"  -> reject_phrase matches [no]  -> the 1500 counter vanishes
% The first two BIND A DEAL the player just refused. This is the same silent-drop bug class
% the slot mention-guards below prevent, one level up — and an ACCEPT_PROPOSAL is binding.
% So a parse is only accepted when the residue is ignorable (empty, or pure filler); anything
% else falls through to Rasa, which is exactly the precision-over-recall policy planning/16
% §"Risks" states ("the Rasa fallback catches whatever the DCG rejects"). Found by the
% adversarial review probe, not by the corpus — see ADR-10.
%
% The trailing cut IS the "first-parse commitment" ambiguity policy (PROJECT_DEFINITION
% §6.2 v1.1) made explicit, and it makes parse_move/2 semidet — which is what the Java
% bridge wants (PrologEngine.oneSolution takes the first solution and discards the rest)
% and what keeps PLUnit from reporting "succeeded with choicepoint" on every case. It does
% NOT paper over ambiguity: test_dcg.pl asserts one-parse-per-utterance against bare
% phrase/3, below the cut, so a genuinely ambiguous grammar still fails the suite.
% Note the cut must come AFTER residue_ignorable/1, so a prefix parse with meaningful
% residue can BACKTRACK into another move type rather than committing to the wrong one.
% parse_move/2 — the empty-context entry, and the 16-M1 compatibility wrapper. Every M1 test calls
% this unchanged. An empty context (no standing offer, empty roster, nothing mentioned) makes the
% ctx-dependent phenomenon blocks (ellipsis/delta/anaphora/vocative) FAIL, so only the five plain
% move rules can match — exactly the M1 grammar. This is THE regression guard.
parse_move(Tokens, Frame) :-
    parse_move(Tokens, ctx(standing(none, none), [], none), Frame).

% parse_move/3 — the context-carrying entry (16-M2). Ctx = ctx(StandingOffer, Roster, LastMentioned)
% is built Java-side from WalkInDialogueSnapshot(s) observable fields only (nlp/DialogueCtxTerm,
% P-04). Same residue-inspection and first-parse cut as /2: the cut sits AFTER residue_ignorable so
% a prefix parse leaving meaningful residue backtracks into another move type or phenomenon block
% rather than committing to a wrong reading.
parse_move(Tokens, Ctx, Frame) :-
    phrase(negotiation_move(Frame, Ctx), Tokens, Rest),
    residue_ignorable(Rest),
    !.

% DCG: leftover tokens that carry no meaning. optional_filler//0 already eats trailing filler
% inside each move rule, so in practice Rest is [] — this stays tolerant as a second line of
% defence and to keep the rule's intent legible.
residue_ignorable([]).
residue_ignorable([W | Ws]) :-
    filler_word(W),
    residue_ignorable(Ws).

% --- Top nonterminal: a negotiation move carries the dialogue context (16-M2) ---
% Clause order is precedence (first-solution semantics).
%
% PLAIN (16-M1) MOVES FIRST, then the phenomenon blocks — "plain-before-phenomenon" is load-
% bearing: a plain counter ("how about 1800") must win over the ellipsis reading of the same
% tokens, and this ordering is what lets the phenomenon blocks stay the precision-first fallback
% (tried only once every plain move has missed). The five M1 rules are UNCHANGED and ignore the
% context argument — that is why the entire M1 corpus parses identically under parse_move/2.
%
% Among the phenomenon blocks, most-distinctive lead first so none shadows another: vocative owns
% the "Name :" / "tell …" frame, command an imperative verb, negation "nothing"/"not", delta its
% arithmetic cue, then ellipsis and anaphora. Each is added as its own alternative below its rule
% cluster. Every ctx-dependent block FAILS on the empty context, so parse_move/2 is pure M1.

negotiation_move(F, _Ctx) --> propose_move(F).
negotiation_move(F, _Ctx) --> counter_move(F).
negotiation_move(F, _Ctx) --> accept_move(F).
negotiation_move(F, _Ctx) --> reject_move(F).
negotiation_move(F, _Ctx) --> ask_move(F).
% 16-M2 phenomenon blocks (alternatives added next to each block's rules, further down):
negotiation_move(F, Ctx) --> vocative_move(F, Ctx).
negotiation_move(F, Ctx) --> command_move(F, Ctx).
negotiation_move(F, Ctx) --> negation_move(F, Ctx).
negotiation_move(F, Ctx) --> delta_move(F, Ctx).
negotiation_move(F, Ctx) --> ellipsis_move(F, Ctx).
negotiation_move(F, Ctx) --> anaphora_move(F, Ctx).

% negotiation_move//1 — 16-M1 compatibility: test_dcg's "exactly one parse per utterance" probe
% calls phrase(negotiation_move(F), …) directly. Delegating to the empty context keeps the
% phenomenon blocks inert, so that ambiguity check still sees only the five plain moves (unchanged).
negotiation_move(F) --> negotiation_move(F, ctx(standing(none, none), [], none)).

% --- Move rules ---

% DCG: propose — an opening offer. "I will give you 2000 for 5 hours at berth 3"
%      "I'll pay 1800 euros for berth 3"   "we offer 2200 for 6 hours by 19:30"
propose_move(frame(commerce_sell, [move=propose | Slots])) -->
    optional_lead,
    give_verb,
    optional_recipient,
    money_amount(Money),
    optional_duration(Duration),
    optional_berth(Berth),
    optional_deadline(Deadline),
    optional_filler,
    { append([[money=Money], Duration, Berth, Deadline], Slots) }.

% DCG: counter — a fresh proposal fronted by a counter cue. In FIPA terms a counter-offer
%      IS a new proposal (negotiation.Decision COUNTER carries the same rule), so this
%      differs from propose_move only by the cue and by not modelling a deadline.
%      "I'll only give you 1500"   "how about 1800"   "1900 instead"
counter_move(frame(commerce_sell, [move=counter | Slots])) -->
    optional_lead,
    counter_phrase,
    optional_give,
    money_amount(Money),
    optional_duration(Duration),
    optional_berth(Berth),
    optional_filler,
    { append([[money=Money], Duration, Berth], Slots) }.

% DCG: counter — trailing-cue form: "1900 instead", "1500 and that's my final offer".
counter_move(frame(commerce_sell, [move=counter | Slots])) -->
    optional_lead,
    optional_give,
    money_amount(Money),
    optional_duration(Duration),
    optional_berth(Berth),
    counter_phrase,
    optional_filler,
    { append([[money=Money], Duration, Berth], Slots) }.

% DCG: accept — a bare acceptance. Elements are exactly [move=accept]: an acceptance
%      carries no new terms, it ratifies the offer already on the table.
accept_move(frame(commerce_sell, [move=accept])) -->
    optional_lead,
    accept_phrase,
    accept_tail,
    optional_filler.

% DCG: stacked acceptance words — "yes, agreed", "ok deal", "yes fine". Still exactly ONE
% accept move: acceptance is idempotent, so there is no information to lose by folding them.
% This exists because parse_move/2 refuses a meaningful residue: without it, "yes, agreed"
% would match only [yes], leave [agreed] over, and miss.
%
% Deliberately NOT mirrored on reject_move: reject_phrase//1 carries a REASON, so folding
% "no, too low" would bind whichever reason matched FIRST (none, from the bare [no]) and
% silently discard price_too_low — re-creating the very slot-dropping bug the residue check
% exists to stop. Such utterances miss and fall through to Rasa, which classifies them
% reject_deal correctly (without the reason element). Precision over recall, as specified.
accept_tail --> accept_phrase, accept_tail.
accept_tail --> [].

% DCG: reject — a refusal, with the reason inferred from the lexical cue where the cue
%      carries one ("too low" -> price_too_low). A bare refusal ("no deal") omits the
%      reason slot entirely rather than binding it to a placeholder.
reject_move(frame(commerce_sell, Elements)) -->
    optional_lead,
    reject_phrase(Reason),
    optional_filler,
    { reject_elements(Reason, Elements) }.

% DCG: ask — a question about port state. "what berths are free"  "how many tugs do you have"
%      "where is my berth". No topic begins with a copula, so optional_copula can never
%      compete with topic_phrase for a token (checked: the grammar stays unambiguous).
ask_move(frame(commerce_sell, [move=ask, topic=Topic])) -->
    optional_lead,
    question_word,
    optional_copula,
    topic_phrase(Topic),
    optional_filler.

% --- Terminals: sentence frame ---

% DCG: optional sentence-initial subject/modal. The apostrophe forms are ONE fused token
% each — the tokeniser strips no apostrophes, so "I'll" arrives as the atom 'i\'ll'.
% Longest alternatives first; the empty clause LAST so the first solution is greediest.
optional_lead --> [i, will].
optional_lead --> [we, will].
optional_lead --> ['i\'ll'].
optional_lead --> ['we\'ll'].
optional_lead --> [let, me].
optional_lead --> [i].
optional_lead --> [we].
optional_lead --> [].

% DCG: the offer verb (closed set — this is what makes propose_move fail fast on a
% non-offer and hand control to the next move type).
give_verb --> [give].
give_verb --> [pay].
give_verb --> [offer].

% DCG: the recipient of the offer.
you_or_them --> [you].
you_or_them --> [them].

optional_recipient --> you_or_them.
optional_recipient --> [].

% DCG: "give you" is optional after a counter cue ("only give you 1500" vs "how about 1500").
optional_give --> give_verb, optional_recipient.
optional_give --> [].

% DCG: filler consumed greedily wherever it appears. MUST NOT contain any move cue —
% a cue listed here would be swallowed before its own move rule could see it.
optional_filler --> [W], { filler_word(W) }, optional_filler.
optional_filler --> [].

filler_word(please).
filler_word(thanks).
filler_word(cheers).
filler_word(mate).
filler_word(captain).
filler_word(so).
filler_word(well).
filler_word(then).
filler_word(now).

% --- Terminals: money ---

% DCG: the bare amount. Kept separate from the currency so 16-M2's delta block can reuse
% it for "200 more" without dragging a currency in.
% The `\+ time_unit` lookahead is the price/duration disambiguator in the other direction:
% without it "give you 5 hours" binds 5 as a EUR price and leaves "hours" in Rest. This
% mirrors PreprocessRegex.PRICE_BARE_FOLLOWED_BY_HOUR_UNIT (task 14), which already
% applies the same rule on the regex pre-pass. A number followed by an hour unit is a
% duration, never a price — if that leaves the utterance unparseable, Rasa is the fallback.
price_term(N) --> [N], { number(N) }, \+ time_unit.

% DCG: currency. The tokeniser has already mapped € -> eur and $ -> usd; the word forms
% survive verbatim. The empty clause defaults to EUR and MUST stay last.
optional_currency(eur) --> [eur].
optional_currency(eur) --> [euros].
optional_currency(eur) --> [euro].
optional_currency(usd) --> [usd].
optional_currency(usd) --> [dollars].
optional_currency(usd) --> [dollar].
optional_currency(eur) --> [].

% DCG: amount + currency -> the canonical price/2 compound (planning/16 Step 16.4:
% Frame.fromProlog decodes price(N, eur) to {amount=N, currency=EUR}).
money_amount(price(N, Currency)) -->
    price_term(N),
    optional_currency(Currency).

% --- Terminals: duration ---

% DCG: the hour count, bounded 1..24 (the same range ontology/Offer enforces). This bound
% is the price/duration disambiguator: a bare 2000 can never bind a duration, so
% money_amount and duration_phrase cannot fight over the same token.
duration_count(D) --> [D], { integer(D), D >= 1, D =< 24 }.

time_unit --> [h].
time_unit --> [hours].
time_unit --> [hour].
time_unit --> [hrs].

optional_for --> [for].
optional_for --> [].

% DCG: "for 5 hours" / "5 h" -> the plain integer 5.
duration_phrase(D) -->
    optional_for,
    duration_count(D),
    time_unit.

optional_duration([duration=D]) --> duration_phrase(D).
optional_duration([]) --> \+ duration_mention.

% DCG: "a duration was attempted here". The empty clause above is guarded by \+ this, so an
% OUT-OF-RANGE duration FAILS the parse instead of silently dropping the slot: without the
% guard, "give you 2000 for 99 hours" parses as a bare 2000 proposal and the player's
% 99-hour request vanishes into Rest. OntologyValidator cannot catch that — the slot is
% ABSENT, not malformed. (Verified in swipl: SWI translates `\+ NT` in a DCG body to
% `\+ phrase(NT, S0, _), S = S0` — zero consumption, so the empty clause stays empty.)
duration_mention --> optional_for, [N], { number(N) }, time_unit.

% --- Terminals: berth ---

% DCG: only four berths exist (berth_1..berth_4), so the number is bounded 1..4.
berth_number(N) --> [N], { integer(N), N >= 1, N =< 4 }.

% DCG: the preposition MUST include `for`. "I'll pay 1800 euros for berth 3" reaches here
% with `for` unconsumed (duration_phrase already failed on `berth`), so without `for` the
% berth slot would be silently dropped.
berth_prep --> [at].
berth_prep --> [to].
berth_prep --> [for].
berth_prep --> [in].
berth_prep --> [].

berth_phrase(Berth) -->
    berth_prep,
    [berth],
    berth_number(N),
    { atom_concat(berth_, N, Berth) }.

optional_berth([berth=B]) --> berth_phrase(B).
optional_berth([]) --> \+ berth_mention.

% DCG: "a berth was named here" — same guard rationale as duration_mention above. Only
% berth_1..berth_4 exist, so "give you 2000 at berth 9" must FAIL (-> clarification, where
% the player learns berth 9 is not a berth) rather than quietly become a berth-less offer
% that the HarbourMaster then assigns a berth for on its own.
berth_mention --> berth_prep, [berth].

% --- Terminals: deadline ---

% DCG: "by 19:30" / "before 19:30". The tokeniser keeps a clock time as ONE token, so the
% ':' is what distinguishes a deadline from a bare number.
optional_deadline([deadline=T]) --> [by], [T], { is_clock(T) }.
optional_deadline([deadline=T]) --> [before], [T], { is_clock(T) }.
optional_deadline([]) --> \+ deadline_mention.

% DCG: "a clock time was attempted here". Scoped to tokens that actually contain ':' so an
% unmodelled "by tomorrow" is merely left in Rest (harmless), while a malformed "by 25:99"
% FAILS the parse rather than being dropped — same guard rationale as berth/duration.
deadline_mention --> [by], [T], { has_colon(T) }.
deadline_mention --> [before], [T], { has_colon(T) }.

has_colon(T) :-
    atom(T),
    sub_atom(T, _, 1, _, ':').

% DCG: a real hh:mm clock. The tokeniser keeps "14:20" as ONE token (it strips punctuation
% but preserves ':'), so splitting on the colon here is safe. Range-checked because
% is_clock is the only thing standing between "by 25:99" and a nonsense deadline element.
% Minutes must be written with two digits: "10:05" is a clock, "10:5" is not (it FAILS the
% parse -> clarification, precision-first) — otherwise a downstream hh:mm reader mis-reads the
% non-canonical atom, and "10:5" is as likely a typo as a genuine 10:05. The hour stays 1-or-2
% digits so an everyday "9:30" still parses.
is_clock(T) :-
    has_colon(T),
    sub_atom(T, Before, 1, After, ':'),
    After =:= 2,                     % minutes are exactly two digits ("10:05", never "10:5")
    sub_atom(T, 0, Before, _, HourAtom),
    sub_atom(T, _, After, 0, MinuteAtom),
    atom_number(HourAtom, H),
    atom_number(MinuteAtom, M),
    integer(H), H >= 0, H =< 23,
    integer(M), M >= 0, M =< 59.

% --- Terminals: counter cues ---

% DCG: the lexemes that mark a re-proposal. Deliberately absent: "make it" — 16-M2's
% ellipsis block owns that form ("make it 2200" must complete duration/berth from the
% standing offer, which needs the dialogue context this milestone does not carry).
counter_phrase --> [i, can, only, do].
counter_phrase --> [how, about].
counter_phrase --> [what, about].
counter_phrase --> [instead].
counter_phrase --> [only].

% --- Terminals: acceptance ---

% DCG: bare acceptances. Multi-token forms first so a prefix never wins early.
accept_phrase --> [sounds, good].
accept_phrase --> [you, got, it].
accept_phrase --> [that, works].
accept_phrase --> [deal].
accept_phrase --> [agreed].
accept_phrase --> [agree].
accept_phrase --> [accept].
accept_phrase --> [accepted].
accept_phrase --> [yes].
accept_phrase --> [okay].
accept_phrase --> [ok].
accept_phrase --> [fine].
accept_phrase --> [done].

% --- Terminals: rejection ---

% DCG: refusals, carrying an inferred reason where the cue implies one. Multi-token forms
% MUST precede their prefixes — [no, deal] before [no], or "no deal" parses as a bare
% rejection leaving "deal" in Rest (and phrase/3 would accept that silently).
reject_phrase(price_too_low)      --> [too, low].
reject_phrase(price_too_low)      --> [too, cheap].
reject_phrase(price_too_high)     --> [too, high].
reject_phrase(price_too_high)     --> [too, expensive].
reject_phrase(duration_too_short) --> [too, short].
reject_phrase(duration_too_long)  --> [too, long].
reject_phrase(none)               --> [no, deal].
reject_phrase(none)               --> [not, interested].
reject_phrase(none)               --> [forget, it].
reject_phrase(none)               --> [rejected].
reject_phrase(none)               --> [reject].
reject_phrase(none)               --> [refuse].
reject_phrase(none)               --> [pass].
reject_phrase(none)               --> [no].

reject_elements(none, [move=reject]) :- !.
reject_elements(Reason, [move=reject, reason=Reason]).

% --- Terminals: questions ---

question_word --> ['what\'s'].
question_word --> ['where\'s'].
question_word --> ['how\'s'].
question_word --> [what].
question_word --> [which].
question_word --> [where].
question_word --> [how].
question_word --> [are].
question_word --> [is].
question_word --> [do].

% DCG: the copula between the question word and the topic ("where IS my berth"). Separate
% from question_word so the two compose freely without listing every pair.
optional_copula --> [is].
optional_copula --> [are].
optional_copula --> [].

% DCG: the closed set of things the player may ask about. LONGEST FORMS FIRST — and each form
% must span the whole topic, because parse_move/2 refuses a meaningful residue: matching only
% [free, berths] of "what free berths do you have" would leave "do you have" over and miss.
% (Mirrors the tug forms below, which already carry their own "do you have" variant.)
topic_phrase(free_berths)      --> [free, berths, do, you, have].
topic_phrase(free_berths)      --> [berths, do, you, have].
topic_phrase(free_berths)      --> [berths, are, free].
topic_phrase(free_berths)      --> [berths, are, available].
topic_phrase(free_berths)      --> [free, berths].
topic_phrase(free_berths)      --> [berths, available].
topic_phrase(tug_availability) --> [many, tugs, do, you, have].
topic_phrase(tug_availability) --> [many, tugs, are, free].
topic_phrase(tug_availability) --> [tugs, are, available].
topic_phrase(tug_availability) --> [tugs, do, you, have].
topic_phrase(best_price)       --> [your, best, price].
topic_phrase(best_price)       --> [your, best, offer].
topic_phrase(best_price)       --> [best, price].
topic_phrase(best_price)       --> [best, offer].
topic_phrase(weather)          --> [the, weather].
topic_phrase(weather)          --> [the, wind].
topic_phrase(berth_assignment) --> [berth, do, i, get].
topic_phrase(berth_assignment) --> [my, berth].

% ============================================================================
% 16-M2 — context-carrying grammar: six phenomenon blocks
% ============================================================================
% Ctx = ctx(StandingOffer, Roster, LastMentioned), built Java-side from observable
% WalkInDialogueSnapshot fields (P-04). Each block is (a) a rule cluster, (b) a PURE
% resolution predicate, (c) an explicit residue policy: the parse_move/3 residue cut
% already refuses meaningful leftovers, and every block additionally FAILS rather than
% guesses on an unresolvable or ambiguous reference — so the utterance falls through to
% Rasa (precision over recall, PROJECT_DEFINITION §6.2 v1.1). Every ctx-dependent block
% fails on the empty context, which is what keeps parse_move/2 identical to 16-M1.

% --- Shared context accessors (pure) ---
ctx_standing(ctx(S, _, _), S).
ctx_roster(ctx(_, R, _), R).
ctx_last_mentioned(ctx(_, _, L), L).

% offer_price(+Offer, -Price): the price of a concrete offer(Price,Dur,Berth). Fails on `none`
% or a price-less offer — the sentinel that makes delta/midpoint FAIL rather than invent a number.
offer_price(offer(P, _, _), P) :- number(P).

% reference_offer(+StandingOffer, -Offer): the offer a bare fragment revises — the player's last
% bid if present, else the vessel's ask. Fails when neither side has offered (-> clarification).
reference_offer(standing(_, P), P) :- P = offer(_, _, _), !.
reference_offer(standing(V, _), V) :- V = offer(_, _, _).

% complete_from(+RefOffer, +GivenSlots, -Slots): fill money/duration/berth left unspecified in
% GivenSlots from RefOffer, in canonical order. A price is always required (given, or a number in
% Ref) — a counter with no price is meaningless, so its absence FAILS. duration/berth are simply
% absent when neither given nor available (never a placeholder).
complete_from(offer(RP, RD, RB), Given, Slots) :-
    resolve_slot(money, Given, price(RP, eur), number(RP), MoneySlot), MoneySlot \== [],
    resolve_slot(duration, Given, RD, integer(RD), DurSlot),
    resolve_slot(berth, Given, RB, berth_atom(RB), BerthSlot),
    append([MoneySlot, DurSlot, BerthSlot], Slots).

% resolve_slot(+Key, +Given, +RefValue, +RefValid, -Slot): [Key=V] from Given if present; else
% [Key=RefValue] if the reference value passes RefValid; else []. Pure and deterministic.
resolve_slot(Key, Given, _, _, [Key=V]) :- memberchk(Key=V, Given), !.
resolve_slot(Key, _, RefValue, RefValid, [Key=RefValue]) :- call(RefValid), !.
resolve_slot(_, _, _, _, []).

berth_atom(B) :- atom(B), B \== none.

% --- Block 1: ellipsis — a bare fragment completed from the standing offer ---
% "make it 2200" / "2200" / "make it 5 hours" / "same but 5 hours" / "same but 2000". Missing slots
% are copied from the reference offer. No standing offer -> reference_offer fails -> FAIL (never
% invents slots). move=counter: a revised offer is a fresh proposal.
ellipsis_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    make_it_lead, money_amount(M), ellipsis_more_duration(DurGiven),
    { ctx_standing(Ctx, S), reference_offer(S, Ref),
      append([money=M], DurGiven, Given), complete_from(Ref, Given, Slots) }.
ellipsis_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    make_it_lead, duration_phrase(D),
    { ctx_standing(Ctx, S), reference_offer(S, Ref), complete_from(Ref, [duration=D], Slots) }.
ellipsis_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    [same, but], money_amount(M),
    { ctx_standing(Ctx, S), reference_offer(S, Ref), complete_from(Ref, [money=M], Slots) }.
ellipsis_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    [same, but], duration_phrase(D),
    { ctx_standing(Ctx, S), reference_offer(S, Ref), complete_from(Ref, [duration=D], Slots) }.

make_it_lead --> [make, it].
make_it_lead --> [].

ellipsis_more_duration([duration=D]) --> duration_phrase(D).
ellipsis_more_duration([]) --> [].

% --- Block 2: delta — arithmetic over the standing offer ---
% "200 more" / "200 less" / "10 percent more" / "10 percent less" -> adjust the reference price.
% "meet me in the middle" / "split the difference" -> midpoint of BOTH offers (why ctx carries
% both). Missing referent, or a subtraction below zero, FAILS -> clarification.
delta_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    price_term(N), [more],
    { delta_apply(Ctx, add(N), Slots) }.
delta_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    price_term(N), [less],
    { delta_apply(Ctx, sub(N), Slots) }.
delta_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    price_term(N), [percent, more],
    { delta_apply(Ctx, pct(N, +1), Slots) }.
delta_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    price_term(N), [percent, less],
    { delta_apply(Ctx, pct(N, -1), Slots) }.
delta_move(frame(commerce_sell, [move=counter | Slots]), Ctx) -->
    midpoint_phrase,
    { ctx_standing(Ctx, S), midpoint(S, Mid), reference_offer(S, Ref),
      complete_from(Ref, [money=price(Mid, eur)], Slots) }.

% delta_apply(+Ctx, +Op, -Slots): compute the new price from the reference offer, complete the rest.
delta_apply(Ctx, Op, Slots) :-
    ctx_standing(Ctx, S), reference_offer(S, Ref), offer_price(Ref, P0),
    delta_price(Op, P0, P1), P1 > 0,
    complete_from(Ref, [money=price(P1, eur)], Slots).

delta_price(add(N), P0, P1)      :- P1 is P0 + N.
delta_price(sub(N), P0, P1)      :- P1 is P0 - N.
delta_price(pct(N, Sign), P0, P1) :- P1 is round(P0 * (100 + Sign * N) / 100).

% midpoint(+StandingOffer, -Mid): needs BOTH sides priced; fails if either is missing.
midpoint(standing(V, P), Mid) :- offer_price(V, PV), offer_price(P, PP), Mid is round((PV + PP) / 2).

midpoint_phrase --> [meet, me, in, the, middle].
midpoint_phrase --> [meet, in, the, middle].
midpoint_phrase --> [split, the, difference].

% --- Block 3: negation — a polarity-bearing constraint ---
% "nothing below 2000" / "nothing above 3000" / "not for less than 6 hours" / "not for more than
% 8 hours". A constraint the player sets on the whole negotiation (move=constrain -> REQUEST).
% Only these closed forms match, so a double negation ("not nothing below …") simply FAILS.
% Ctx-free (a constraint needs no referent) but lives in the ctx grammar as negation_move//2.
negation_move(frame(commerce_sell, [move=constrain, polarity=negative, bound=below, money=price(N, eur)]), _) -->
    [nothing, below], price_term(N).
negation_move(frame(commerce_sell, [move=constrain, polarity=negative, bound=above, money=price(N, eur)]), _) -->
    [nothing, above], price_term(N).
negation_move(frame(commerce_sell, [move=constrain, polarity=negative, bound=below, duration=D]), _) -->
    [not, for, less, than], duration_count(D), time_unit.
negation_move(frame(commerce_sell, [move=constrain, polarity=negative, bound=above, duration=D]), _) -->
    [not, for, more, than], duration_count(D), time_unit.

% --- Block 4: anaphora — resolve a demonstrative / pronoun / definite-description referent ---
% "<price> for that berth" / "<price> for the same berth" -> berth = last-mentioned; none -> FAIL.
anaphora_move(frame(commerce_sell, [move=counter, money=price(N, eur), berth=B]), Ctx) -->
    money_amount(price(N, eur)), berth_prep, berth_demonstrative,
    { ctx_last_mentioned(Ctx, B), berth_atom(B) }.
% "accept the tanker" / "reject the tanker" -> address the decision to the UNIQUE vessel of that
% type. Two tankers (or none) -> resolve_vessel_by_type FAILS -> clarification. Never guesses.
anaphora_move(frame(commerce_sell, [move=accept, addressee=Id]), Ctx) -->
    accept_phrase, [the], vessel_type_word(Type), { resolve_vessel_by_type(Ctx, Type, Id) }.
anaphora_move(frame(commerce_sell, [move=reject, addressee=Id]), Ctx) -->
    reject_bare, [the], vessel_type_word(Type), { resolve_vessel_by_type(Ctx, Type, Id) }.
% "accept it" / "reject it" -> the pronoun demands a live antecedent (a standing offer). None -> FAIL.
anaphora_move(frame(commerce_sell, [move=accept]), Ctx) -->
    accept_phrase, [it], { ctx_standing(Ctx, S), S \= standing(none, none) }.
anaphora_move(frame(commerce_sell, [move=reject]), Ctx) -->
    reject_bare, [it], { ctx_standing(Ctx, S), S \= standing(none, none) }.

berth_demonstrative --> [that, berth].
berth_demonstrative --> [the, same, berth].
berth_demonstrative --> [same, berth, as, before].

reject_bare --> [reject].
reject_bare --> [no].

% resolve_vessel_by_type(+Ctx, +Type, -Id): the id of the roster vessel of Type, iff exactly one
% exists. [] (none) or [_,_|_] (ambiguous) both FAIL — the two-tanker case the block must refuse.
% `T == Type` (identity, not unification) so a roster entry with an unbound type field cannot match
% every query — a real ctx never has one (DialogueCtxTerm emits concrete atoms), but the resolver
% stays precision-first on any input.
resolve_vessel_by_type(Ctx, Type, Id) :-
    atom(Type),
    ctx_roster(Ctx, R),
    findall(I, (member(dialogue(I, T, _, _, _), R), T == Type), Ids),
    Ids = [Id].

% --- Block 5: vocative — bind an addressee, route between concurrent dialogues ---
% "Genoa Star : <inner move>"   — everything before the ':' names the addressee.
% "tell the tanker <inner move>" — definite description by type.
% The inner move is any negotiation move; the resolved addressee is appended to its frame. An
% unresolvable name or an ambiguous type FAILS (the clarification IS the routing question).
vocative_move(F2, Ctx) -->
    name_phrase(NameAtoms), [':'], negotiation_move(Inner, Ctx),
    { resolve_name(NameAtoms, Ctx, Id), add_addressee(Inner, Id, F2) }.
vocative_move(F2, Ctx) -->
    [tell], [the], vessel_type_word(Type), negotiation_move(Inner, Ctx),
    { resolve_vessel_by_type(Ctx, Type, Id), add_addressee(Inner, Id, F2) }.

% One-or-more tokens up to (not including) the ':' separator. The required ':' after it fixes the
% boundary, so the collected atoms are exactly the name.
name_phrase([W]) --> [W], { W \== ':' }.
name_phrase([W | Ws]) --> [W], { W \== ':' }, name_phrase(Ws).

% resolve_name(+NameAtoms, +Ctx, -Id): the id of the roster vessel whose (lowercased, space-joined)
% name equals NameAtoms, iff unique. None or ambiguous -> FAIL. `N == Name` (identity) so an unbound
% roster name field cannot spuriously match — precision-first regardless of how the ctx was built.
resolve_name(NameAtoms, Ctx, Id) :-
    atomic_list_concat(NameAtoms, ' ', Name),
    ctx_roster(Ctx, R),
    findall(I, (member(dialogue(I, _, N, _, _), R), N == Name), Ids),
    Ids = [Id].

% add_addressee(+InnerFrame, +Id, -Framed): attach the addressee. Constrained to commerce_sell — you
% address a NEGOTIATION move to a specific walk-in; a fleet command ("hold all tankers") is not
% addressed to one vessel, so "Genoa Star: hold all tankers" is a category error that FAILS here and
% falls through to Rasa (precision over recall), rather than emitting a command tagged to one ship.
add_addressee(frame(commerce_sell, Elements), Id, frame(commerce_sell, Elements2)) :-
    append(Elements, [addressee=Id], Elements2).

% --- Block 6: command — imperative, quantified NPs fan out to N receivers ---
% "hold all tankers until the wind drops" / "send two tugs to the Carthago". A quantifier is
% REQUIRED (all/any/numeral), which is what keeps "cancel the tug" (no quantifier) falling through
% to Rasa exactly as in 16-M1. FrameToAcl.buildAll fans the resolved patient set out to N receivers.
command_move(frame(command, Elements), Ctx) -->
    imperative_verb(Action), quantified_patient(QSlots), command_tail(TailSlots, Ctx),
    { append([[action=Action], QSlots, TailSlots], Elements) }.

imperative_verb(hold) --> [hold].
imperative_verb(send) --> [send].
imperative_verb(dispatch) --> [dispatch].
imperative_verb(cancel) --> [cancel].
imperative_verb(clear) --> [clear].
imperative_verb(route) --> [route].

quantified_patient([quantifier=all, patient=P]) --> [all], patient_word(P).
quantified_patient([quantifier=any, patient=P]) --> [any], patient_word(P).
quantified_patient([quantifier=N, patient=P]) --> numeral(N), patient_word(P).

% a named target ("to the Carthago") OR a condition ("until the wind drops"), never both — matching
% the two canonical forms. A named target resolves against the roster; unresolved -> FAIL.
command_tail([target=Id], Ctx) --> target_prep, [the], name_phrase(Name), { resolve_name(Name, Ctx, Id) }.
command_tail([condition=C], _) --> command_condition(C).
command_tail([], _) --> [].

target_prep --> [to].
target_prep --> [for].

command_condition(until_wind_drop)  --> [until, the, wind, drops].
command_condition(until_storm_pass) --> [until, the, storm, passes].
command_condition(when_wind_drops)  --> [when, the, wind, drops].

patient_word(tanker)           --> [tankers].
patient_word(tanker)           --> [tanker].
patient_word(tug)              --> [tugs].
patient_word(tug)              --> [tug].
patient_word(container_vessel) --> [container, vessels].
patient_word(container_vessel) --> [container, vessel].
patient_word(cargo_vessel)     --> [cargo, vessels].
patient_word(cargo_vessel)     --> [cargo, vessel].
patient_word(ferry)            --> [ferries].
patient_word(ferry)            --> [ferry].
patient_word(cruise_ship)      --> [cruise, ships].
patient_word(cruise_ship)      --> [cruise, ship].

numeral(1) --> [one].
numeral(2) --> [two].
numeral(3) --> [three].
numeral(N) --> [N], { integer(N), N >= 1 }.

% vessel_type_word (definite-description head, shared by anaphora + vocative).
vessel_type_word(tanker)           --> [tanker].
vessel_type_word(container_vessel) --> [container, vessel].
vessel_type_word(container_vessel) --> [container].
vessel_type_word(cargo_vessel)     --> [cargo, vessel].
vessel_type_word(cargo_vessel)     --> [cargo].
vessel_type_word(ferry)            --> [ferry].
vessel_type_word(cruise_ship)      --> [cruise, ship].
vessel_type_word(cruise_ship)      --> [cruise].
