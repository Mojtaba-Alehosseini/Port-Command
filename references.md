# References — Prolog rule kernel (R1–R30)

**Mandatory deliverable** (submitted proposal): every rule in the 30-rule Prolog
kernel mapped to the source it is meant to encode. One row per rule, across the
five modules (8 compatibility + 6 escort + 6 weather + 4 customs + 6 priority).

## How to read the **Status** column

No web research was performed while authoring this file (per the task-04
directive — legal numbers are not to be invented). The status of each row is
therefore one of:

- **`VERIFY`** — the rule *claims* a real-world / legal grounding (an IMO
  resolution, EU directive, Italian Decree 272/99 article, Autorità di Sistema
  Portuale del Mar Ligure Occidentale tariff/berth register, or a Coast Guard /
  Capitaneria di Porto ordinance). The *kind* of source is named, but the exact
  document, article, and number are **unconfirmed**. Do not cite as fact in the
  report until checked against the primary source.
- **`DESIGN`** — the rule is an internal design construct of this project (a
  composition of other rules, a taxonomy we defined, or a game-balance value).
  It has no external legal source and needs none; it is sourced to this project's
  `PROJECT_DEFINITION.md` § 5.

Grep convention: `grep -n VERIFY references.md` lists every pointer still owing a
primary-source check. A clean report has either confirmed each one (replacing
`VERIFY` with the precise citation) or consciously kept it flagged.

> Financial constants (e.g. the €5200/day fixed operating cost) are **not** part
> of the 30-rule kernel — they live in Java (`IncomeRules`/`ExpenseRules`, task 20)
> and are sourced there, not here.

---

## `rules_compatibility.pl` — R1–R8 (berth ↔ vessel fit)

| Rule | Predicate | Encodes | Value / basis | Source pointer | Status |
|------|-----------|---------|---------------|----------------|--------|
| R1 | `loa_fit/2` | LOA ≤ berth max length | per-berth `has_max_length` | AdSP Mar Ligure Occ. berth register / nautical charts (Portolano) | VERIFY |
| R2 | `draft_fit/2` | draft ≤ berth max draft | per-berth `has_max_draft` | AdSP berth depths / charted depths | VERIFY |
| R3 | `beam_fit/2` | beam ≤ berth max beam | per-type `has_beam`, per-berth `has_max_beam` | AdSP berth register | VERIFY |
| R4 | `fender_fit/2` | fender rating adequate for vessel | rank light<medium<heavy | PIANC fender guidelines + berth equipment list | VERIFY |
| R5 | `cargo_handling_fit/2` | berth provides every capability the type needs | `vessel_class_needs/2` | composition of R6 + capability taxonomy | DESIGN |
| R6 | `berth_provides/2` | berth-class → capability lookup | `berth_class_provides/2` | project capability taxonomy | DESIGN |
| R7 | `compatible/2` | composite: R1∧R2∧R3∧R4∧R5 | — | composition | DESIGN |
| R8 | `find_compatible_berths/2` | enumerate all compatible berths | — | composition | DESIGN |

> The fender-rank ordering (light<medium<heavy) and the per-type/per-berth fender
> assignments are a **DESIGN** simplification; the *existence* of a fender-rating
> compatibility constraint is the `VERIFY` claim (PIANC / berth equipment).

## `rules_escort.pl` — R9–R14 (tug escort + bid selection)

| Rule | Predicate | Encodes | Value / basis | Source pointer | Status |
|------|-----------|---------|---------------|----------------|--------|
| R9 | `base_tugs/2` | tug count by gross tonnage bracket | <20000→1, <50000→2, else 3 | harbour towage / pilotage scaling — Capitaneria di Porto Genova ordinance | VERIFY |
| R10 | `type_tug_minimum/2` | tanker minimum escort | tanker→2, others→0 | tanker approach-escort requirement — IMO / SOLAS + local tanker ordinance | VERIFY |
| R11 | `tugs_required/2` | max(tonnage base, type minimum) | — | composition of R9 + R10 | DESIGN |
| R12 | `score_bid/2` | bid score (higher better) | `Fuel*100 − Cost*0.1 − Eta` | project CNP scoring model (task-04 decision D5) | DESIGN |
| R13 | `best_n_bids/3` | top-N tugs by score, desc | tiebreaker: standard order of `Score-Tug` | composition | DESIGN |
| R14 | `take_first_n/3`, `extract_tugs/2` | list helpers for R13 | — | composition | DESIGN |

> The tonnage brackets (20000 / 50000 t) and the tanker-minimum of 2 are the
> `VERIFY` claims — real towage tariffs and tanker-escort rules scale with
> tonnage, but the exact thresholds here are project approximations pending the
> Capitaneria ordinance and AdSP towage tariff.

## `rules_weather.pl` — R15–R20 (operating limits)

| Rule | Predicate | Encodes | Value / basis | Source pointer | Status |
|------|-----------|---------|---------------|----------------|--------|
| R15 | `wind_within_limit/2` | wind under per-type limit | tanker 30, ferry/cruise 35, container 40, cargo 45 kn | crane/cargo-op wind limits — terminal operating manuals / OCIMF; Coast Guard wind-limit publication | VERIFY |
| R16 | `visibility_ok/1` | adequate visibility for ops | good / fair adequate; poor not | restricted-visibility ops — COLREGs Part B + VTS Genova operating rules | VERIFY |
| R17 | `swell_within_limit/1` | swell under operating limit | ≤ 4.0 m | berthing operability — PIANC / port ops manual | VERIFY |
| R18 | `threshold_crossed/2` | which canonical wind threshold crossed | enumerates 30/35/40/45 | derived from R15 values | DESIGN |
| R19 | `weather_state_unsafe/1` | a stormy state is unsafe | `stormy` | project weather-state label | DESIGN |
| R20 | `operation_safe/3` | composite: wind ∧ visibility | — | composition of R15 + R16 | DESIGN |

> The wind limits 30/35/40/45 kn (task-04 decision D4) are a **proposal**; the
> `VERIFY` claim is that per-vessel-type operational wind limits exist in terminal
> manuals / OCIMF guidance. Never the round 50 kn placeholder (the design rules).

## `rules_customs.pl` — R21–R24 (pre-clearance)

| Rule | Predicate | Encodes | Value / basis | Source pointer | Status |
|------|-----------|---------|---------------|----------------|--------|
| R21 | `clearance_ok/2` | valid (type, cargo) ∧ not blacklisted | dynamic `blacklisted_combo/2` | EU Union Customs Code (Reg. 952/2013) + Agenzia delle Dogane; Decree 272/99 (port ops) | VERIFY |
| R22 | `needs_inspection/1` | hazmat cargo needs inspection | `is_hazmat/1` | IMDG Code — dangerous-goods handling/inspection | VERIFY |
| R23 | `inspection_probability/3` | inspection likelihood | hazmat 0.8, else 0.2 | project risk model (game-balance values) | DESIGN |
| R24 | `high_risk/2` | clears but above-even inspection prob | prob ≥ 0.5 | composition of R23 | DESIGN |

> v1 folds flag/manifest checks into `blacklisted_combo/2` (a blacklisted pair
> stands in for a failed flag/manifest check); full flag/manifest predicates are
> Future Work (`PROJECT_DEFINITION.md` § 5.4). The 0.8/0.2 inspection
> probabilities are **DESIGN** game values, not a published inspection rate.

## `rules_priority.pl` — R25–R30 (operation priority)

| Rule | Predicate | Encodes | Value / basis | Source pointer | Status |
|------|-----------|---------|---------------|----------------|--------|
| R25 | `priority_rank/2` | rank of each priority class | emergency 1 … maintenance 6 | port traffic-priority scheme — Capitaneria di Porto / Decree 272/99 | VERIFY |
| R26 | `priority_class_of/2` | vessel → priority class (override / ferry / default) | `vessel_priority_override/2` | project bridge to the class taxonomy | DESIGN |
| R27 | `higher_priority/2` | A outranks B (lower rank) | — | composition of R25 | DESIGN |
| R28 | `preempts/2` | higher-priority preempts lower | — | composition of R27 | DESIGN |
| R29 | `emergency_overrides_all/1` | emergency preempts every other class | — | emergency / SAR priority — SOLAS Ch. V + port emergency plan | VERIFY |
| R30 | `effective_priority/2` | rank of a vessel's (overridden) class | — | composition of R26 + R25 | DESIGN |

> The six-class ordering (emergency > medical > scheduled-ferry >
> scheduled-arrival > departure > maintenance) is a **DESIGN** taxonomy
> (`PROJECT_DEFINITION.md` § 5); the `VERIFY` rows (R25, R29) claim that a real
> port traffic-priority scheme and an emergency-override principle exist in the
> Capitaneria regulation and SOLAS.

---

## Summary

| Module | Rules | `VERIFY` rows | `DESIGN` rows |
|--------|-------|---------------|---------------|
| compatibility | R1–R8 | R1, R2, R3, R4 | R5, R6, R7, R8 |
| escort | R9–R14 | R9, R10 | R11, R12, R13, R14 |
| weather | R15–R20 | R15, R16, R17 | R18, R19, R20 |
| customs | R21–R24 | R21, R22 | R23, R24 |
| priority | R25–R30 | R25, R29 | R26, R27, R28, R30 |
| **total** | **30** | **13** | **17** |

13 rows owe a primary-source check (`grep -n VERIFY references.md`). The 17
`DESIGN` rows are sourced to `PROJECT_DEFINITION.md` § 5 and need no external
citation.
