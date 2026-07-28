# Wild-set evaluation (task 25 / PROJECT_DEFINITION §13.11)

Full cascade: preprocess -> DCG -> Rasa -> confidence gate -> clarification.
Rasa NLU server: UP.

Utterances are LLM paraphrases of player task cards, hand-filtered; the generator had prior sight of the repository, so these figures are an UPPER bound on true out-of-distribution performance (see docs/testing.md).

## Headline

| metric | value | of |
|---|---|---|
| coverage | 74.5% (82/110) | utterances that should route |
| precision | 69.5% (57/82) | utterances actually routed |
| clarification recall | 63.2% (12/19) | utterances that should ask |
| end-to-end accuracy | 53.5% (69/129) | all utterances |
| **false-bind rate** | 36.8% (7/19) | utterances that should ask — **emitted a binding ACL instead** |

False binds (the worst failure mode — precision cannot see these):

- va bene, deal -> ACCEPT_PROPOSAL
- assolutamente no, non se ne parla -> REJECT_PROPOSAL
- so? -> ACCEPT_PROPOSAL
- yes on condition the stay is three hours -> QUERY_REF
- the ligurian sea is rough this time of year -> QUERY_REF
- give me a second -> REQUEST
- how much does a tug cost per hour and can I get two -> REQUEST

## Per task card

| card | correct | total | accuracy |
|---|---|---|---|
| open_offer | 1 | 9 | 11.1% |
| italian_out_of_scope | 4 | 6 | 66.7% |
| counter_higher | 0 | 10 | 0.0% |
| split_difference | 0 | 4 | 0.0% |
| change_duration | 1 | 4 | 25.0% |
| accept | 8 | 11 | 72.7% |
| reject | 6 | 12 | 50.0% |
| ask_berths | 4 | 5 | 80.0% |
| ask_tugs | 4 | 4 | 100.0% |
| ask_weather | 3 | 3 | 100.0% |
| ask_vessel | 3 | 5 | 60.0% |
| ask_best_price | 1 | 3 | 33.3% |
| set_floor | 5 | 5 | 100.0% |
| constrain | 6 | 6 | 100.0% |
| hold_weather | 4 | 4 | 100.0% |
| dispatch_tugs | 0 | 3 | 0.0% |
| help | 4 | 4 | 100.0% |
| cancel | 2 | 4 | 50.0% |
| withdraw | 1 | 2 | 50.0% |
| address_specific | 2 | 5 | 40.0% |
| anaphora | 1 | 4 | 25.0% |
| negation | 1 | 3 | 33.3% |
| ambiguous | 8 | 13 | 61.5% |

## Every utterance

| id | card | utterance | expected | actual | |
|---|---|---|---|---|---|
| w001 | open_offer | Let's start at 1800 for six hours | PROPOSE | clarification | MISS |
| w002 | open_offer | i can do 2000, 5 hours, berth 3 | PROPOSE | clarification | MISS |
| w003 | open_offer | How does 2400 for 8 hours sound to you | PROPOSE | clarification | MISS |
| w004 | open_offer | I make you a price of 2000 for five hours | PROPOSE | clarification | MISS |
| w005 | italian_out_of_scope | Proposta: 2200 euro per 6 ore | clarification | clarification | ok |
| w006 | open_offer | we are prepared to offer two thousand euros | PROPOSE | REJECT_PROPOSAL | MISS |
| w007 | open_offer | OFFER 2500 FOR 10 HOURS | PROPOSE | PROPOSE | ok |
| w008 | open_offer | put us down for 1900 at the third berth | PROPOSE | clarification | MISS |
| w009 | open_offer | Would you take 1650 for four hours? | PROPOSE | clarification | MISS |
| w010 | open_offer | my offer is 2050 and that's a fair number | PROPOSE | clarification | MISS |
| w011 | counter_higher | push it up to 2000 | PROPOSE | clarification | MISS |
| w012 | counter_higher | a bit more, say 1800 | PROPOSE | clarification | MISS |
| w013 | counter_higher | 300 more and we have a deal | PROPOSE | ACCEPT_PROPOSAL | MISS |
| w014 | counter_higher | can you stretch to 1900 | PROPOSE | clarification | MISS |
| w015 | counter_higher | I need at least 1750 to make this work | PROPOSE | clarification | MISS |
| w016 | counter_higher | bump that by 10% | PROPOSE | clarification | MISS |
| w017 | counter_higher | realistically 1700 is my ceiling | PROPOSE | REQUEST | MISS |
| w018 | counter_higher | counter: 1850 | PROPOSE | clarification | MISS |
| w019 | counter_higher | that's low, try 2100 | PROPOSE | clarification | MISS |
| w020 | counter_higher | we could go to 1950 but no higher | PROPOSE | clarification | MISS |
| w021 | split_difference | let's just split it down the middle | PROPOSE | clarification | MISS |
| w022 | split_difference | meet me halfway and we're done | PROPOSE | REQUEST | MISS |
| w023 | split_difference | let's land between our two numbers | PROPOSE | clarification | MISS |
| w024 | split_difference | somewhere in the middle works for me | PROPOSE | ACCEPT_PROPOSAL | MISS |
| w025 | change_duration | same price but only 4 hours | PROPOSE | clarification | MISS |
| w026 | change_duration | make it 8 hours | PROPOSE | PROPOSE | ok |
| w027 | change_duration | we need the berth for twelve hours, not five | PROPOSE | clarification | MISS |
| w028 | change_duration | can we shorten it to 3 hours | PROPOSE | clarification | MISS |
| w029 | accept | yeah go on then | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w030 | accept | alright, book it | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w031 | accept | that's fine by us | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w032 | italian_out_of_scope | va bene, deal | clarification | ACCEPT_PROPOSAL | MISS |
| w033 | accept | I'm happy with that number | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w034 | accept | ok fine whatever, agreed | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w035 | accept | sign it off | ACCEPT_PROPOSAL | REJECT_PROPOSAL | MISS |
| w036 | accept | we accept your terms | ACCEPT_PROPOSAL | QUERY_REF | MISS |
| w037 | accept | sure why not | ACCEPT_PROPOSAL | REJECT_PROPOSAL | MISS |
| w038 | accept | consider it agreed | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w039 | accept | that's grand, proceed | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w040 | accept | perfect, thank you | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w041 | reject | no chance at that price | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w042 | reject | that's an insult, honestly | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w043 | reject | we're not taking it | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w044 | reject | you are undervaluing the berth badly | REJECT_PROPOSAL | REQUEST | MISS |
| w045 | italian_out_of_scope | assolutamente no, non se ne parla | clarification | REJECT_PROPOSAL | MISS |
| w046 | reject | sorry mate, can't do it | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w047 | reject | that number is nowhere near enough | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w048 | reject | we are turning this one down | REJECT_PROPOSAL | clarification | MISS |
| w049 | reject | I'd rather leave the berth empty | REJECT_PROPOSAL | QUERY_REF | MISS |
| w050 | reject | no way, the stay is far too long | REJECT_PROPOSAL | QUERY_REF | MISS |
| w051 | ask_berths | which quays have space right now | QUERY_REF | REQUEST | MISS |
| w052 | ask_berths | got any berths open? | QUERY_REF | QUERY_REF | ok |
| w053 | ask_berths | show me the free berths | QUERY_REF | QUERY_REF | ok |
| w054 | ask_berths | is berth 2 taken | QUERY_REF | QUERY_REF | ok |
| w055 | ask_berths | how many spaces are left | QUERY_REF | QUERY_REF | ok |
| w056 | ask_tugs | how many tugs can I call on | QUERY_REF | QUERY_REF | ok |
| w057 | ask_tugs | are the tugs busy | QUERY_REF | QUERY_REF | ok |
| w058 | ask_tugs | tug availability please | QUERY_REF | QUERY_REF | ok |
| w059 | ask_tugs | where is tug 3 | QUERY_REF | QUERY_REF | ok |
| w060 | ask_weather | how's the sea looking | QUERY_REF | QUERY_REF | ok |
| w061 | ask_weather | what's the wind doing | QUERY_REF | QUERY_REF | ok |
| w062 | ask_weather | is the weather going to be a problem | QUERY_REF | QUERY_REF | ok |
| w063 | italian_out_of_scope | che tempo fa | clarification | clarification | ok |
| w064 | ask_vessel | how deep does she sit | QUERY_REF | REQUEST | MISS |
| w065 | ask_vessel | what is Genoa Star carrying | QUERY_REF | QUERY_REF | ok |
| w066 | ask_vessel | when is the Carthago due in | QUERY_REF | QUERY_REF | ok |
| w067 | ask_vessel | how long is that ship | QUERY_REF | QUERY_REF | ok |
| w068 | ask_vessel | give me a rundown of what's inbound | QUERY_REF | REQUEST | MISS |
| w069 | ask_best_price | what's the lowest you'd go | QUERY_REF | QUERY_REF | ok |
| w070 | ask_best_price | where would you actually settle | QUERY_REF | clarification | MISS |
| w071 | ask_best_price | come on, what do you really want to pay | QUERY_REF | REQUEST | MISS |
| w072 | set_floor | from now on don't take anything under 2000 | REQUEST | REQUEST | ok |
| w073 | set_floor | my minimum is 1800, always | REQUEST | REQUEST | ok |
| w074 | set_floor | auto accept anything above 2500 | REQUEST | REQUEST | ok |
| w075 | set_floor | standing rule: counter every first offer | REQUEST | REQUEST | ok |
| w076 | set_floor | never go below 1900 euros for a tanker | REQUEST | REQUEST | ok |
| w077 | constrain | keep the tankers out of the inner basin | REQUEST | REQUEST | ok |
| w078 | constrain | no cruise ships tonight please | REQUEST | REQUEST | ok |
| w079 | constrain | berth 4 is closed until further notice | REQUEST | REQUEST | ok |
| w080 | constrain | don't let any hazmat cargo in | REQUEST | REQUEST | ok |
| w081 | constrain | block the ferries from berth 1 | REQUEST | REQUEST | ok |
| w082 | hold_weather | keep every tanker alongside while it blows | REQUEST | REQUEST | ok |
| w083 | hold_weather | nobody moves until this storm passes | REQUEST | REQUEST | ok |
| w084 | hold_weather | keep everything alongside for now | REQUEST | REQUEST | ok |
| w085 | hold_weather | no ferry moves for now | REQUEST | REQUEST | ok |
| w086 | dispatch_tugs | send two tugs out | REQUEST | QUERY_REF | MISS |
| w087 | dispatch_tugs | I want three tugs on that one | REQUEST | QUERY_REF | MISS |
| w088 | dispatch_tugs | dispatch a couple of tugs to the tanker | REQUEST | QUERY_REF | MISS |
| w089 | help | what do I actually type here | REQUEST | REQUEST | ok |
| w090 | help | first time playing, any pointers | REQUEST | REQUEST | ok |
| w091 | italian_out_of_scope | aiuto | clarification | clarification | ok |
| w092 | help | list everything I'm allowed to do | REQUEST | REQUEST | ok |
| w093 | help | im lost, what now | REQUEST | REQUEST | ok |
| w094 | cancel | forget I said anything | CANCEL | REQUEST | MISS |
| w095 | cancel | cancel the tug I ordered | CANCEL | CANCEL | ok |
| w096 | cancel | scratch that | CANCEL | clarification | MISS |
| w097 | cancel | undo the last thing | CANCEL | CANCEL | ok |
| w098 | italian_out_of_scope | annulla tutto | clarification | clarification | ok |
| w099 | withdraw | I'm pulling out of this negotiation | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w100 | withdraw | forget this deal, we're done talking | REJECT_PROPOSAL | clarification | MISS |
| w101 | address_specific | Genoa Star gets a yes from me | ACCEPT_PROPOSAL | clarification | MISS |
| w102 | address_specific | let the tanker know we are refusing | REJECT_PROPOSAL | REQUEST | MISS |
| w103 | address_specific | Carthago, you have a deal | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w104 | address_specific | Genoa Star: 1900 for 5 hours | PROPOSE | PROPOSE | ok |
| w105 | address_specific | say no to the cargo vessel | REJECT_PROPOSAL | REQUEST | MISS |
| w106 | anaphora | 1800 on the berth we discussed | PROPOSE | QUERY_REF | MISS |
| w107 | anaphora | go with that one | ACCEPT_PROPOSAL | ACCEPT_PROPOSAL | ok |
| w108 | anaphora | turn it down | REJECT_PROPOSAL | clarification | MISS |
| w109 | anaphora | keep the berth, move the money to 2000 | PROPOSE | REQUEST | MISS |
| w110 | negation | my floor is 2000, full stop | REQUEST | clarification | MISS |
| w111 | negation | not for less than six hours | REQUEST | REJECT_PROPOSAL | MISS |
| w112 | negation | I won't go under 1800 on this one | REQUEST | REQUEST | ok |
| w113 | ambiguous | hmm | clarification | clarification | ok |
| w114 | ambiguous | so? | clarification | ACCEPT_PROPOSAL | MISS |
| w115 | ambiguous | maybe, let me think about it | clarification | clarification | ok |
| w116 | ambiguous | what would you do in my position | clarification | clarification | ok |
| w117 | ambiguous | fine in principle though the money is short | clarification | clarification | ok |
| w118 | ambiguous | agreed provided you come down to 1500 | clarification | clarification | ok |
| w119 | ambiguous | yes on condition the stay is three hours | clarification | QUERY_REF | MISS |
| w120 | ambiguous | the ligurian sea is rough this time of year | clarification | QUERY_REF | MISS |
| w121 | ambiguous | asdkjh | clarification | clarification | ok |
| w122 | ambiguous | berth 9 then | clarification | clarification | ok |
| w123 | ambiguous | give me a second | clarification | REQUEST | MISS |
| w124 | ambiguous | how much does a tug cost per hour and can I get two | clarification | REQUEST | MISS |
| w125 | ambiguous | either 1800 now or 2200 tomorrow, your call | clarification | clarification | ok |
| w126 | reject | That number is out of the question | REJECT_PROPOSAL | REJECT_PROPOSAL | ok |
| w127 | reject | The price is way beyond us | REJECT_PROPOSAL | QUERY_REF | MISS |
| w128 | reject | That's below what we can take | REJECT_PROPOSAL | clarification | MISS |
| w129 | constrain | Deny entry to cruise ships | REQUEST | REQUEST | ok |
