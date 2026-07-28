# Rasa intent-golden regression (task 25, 50 utterances disjoint from train + holdout)

| intent | passed | total |
|---|---|---|
| accept_deal | 5 | 5 |
| cancel_action | 5 | 5 |
| counter_offer | 5 | 5 |
| out_of_scope | 3 | 5 |
| propose_offer | 5 | 5 |
| query_status | 5 | 5 |
| reject_deal | 5 | 5 |
| request_help | 5 | 5 |
| set_constraint | 5 | 5 |
| set_policy | 4 | 5 |

**47/50 exact** (intent + every expected entity value); soft budget 5.

## Mismatches (flagged)

- [set_policy] Every tanker offer should get a counter :: intent set_constraint != set_policy
- [out_of_scope] good afternoon :: intent accept_deal != out_of_scope
- [out_of_scope] recommend a good restaurant nearby :: intent reject_deal != out_of_scope

## Over-extraction (reported, not gated)

- How much cargo is Maersk Genova carrying -> extra cargo_class=cargo
- When does Adriatic Pearl dock -> extra berth_id=dock
- Keep general cargo out of berth 3 -> extra time_expression=of
