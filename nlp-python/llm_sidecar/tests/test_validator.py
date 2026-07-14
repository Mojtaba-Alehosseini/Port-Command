"""Pytest for the sidecar's HallucinationValidator (task 13).

Mirrors planning/10 § 10.4 (required-number presence + proper-noun negative
control) and the v1.1 numeric normalisation. Pure stdlib — no model, no weights.

Run:  nlp-python/.venv/Scripts/python -m pytest nlp-python/llm_sidecar/tests -q
"""
from __future__ import annotations

from pathlib import Path

import pytest

from llm_sidecar.validator import HallucinationValidator, load_known_entities

REPO = Path(__file__).resolve().parents[3]
VOCAB = REPO / "nlp-python" / "rasa" / "data" / "ontology_vocab.yml"

# A small static gazetteer for the focused unit tests; the corpus test below
# uses the real ontology_vocab.yml.
KNOWN = {"tanker", "container_vessel", "berth_1", "berth_3", "tug_2"}


@pytest.fixture
def v() -> HallucinationValidator:
    return HallucinationValidator(KNOWN)


# --- check 1: required numbers present ---------------------------------------
def test_all_required_numbers_present(v):
    assert v.validate("Recommend accept at 2000 for 5 hours.", ["2000", "5"], []) is True


def test_one_required_number_missing_fails(v):
    # 2200 is not in the output -> reject (the missing-number case)
    assert v.validate("Recommend accept at 2000 for 5 hours.", ["2000", "2200"], []) is False


def test_injected_wrong_number_is_rejected(v):
    # required 2000 but the output says 2050 (a changed/injected number).
    assert v.validate("Recommend accept at 2050 euros.", ["2000"], []) is False


def test_no_required_numbers_is_vacuously_ok(v):
    assert v.validate("Recommend holding the vessel offshore.", [], []) is True


# --- v1.1 numeric normalisation (the key fix) --------------------------------
@pytest.mark.parametrize("output, number", [
    ("The berth fee is €2,000 per call.", "2000"),   # currency + thousands comma
    ("The berth fee is 2 000 per call.", "2000"),    # thin/plain space thousands
    ("Acceptance probability is 85% today.", "85"),  # percent form
    ("Market average is €5,500 this week.", "5500"),
])
def test_numeric_normalisation_matches(v, output, number):
    assert v.validate(output, [number], []) is True


def test_normalisation_does_not_create_false_matches(v):
    # required 2000 must NOT be satisfied by 12000 (digit-boundary check)
    assert v.validate("The total came to 12000 overall.", ["2000"], []) is False


# --- check 2: proper-noun negative control -----------------------------------
def test_fabricated_vessel_name_is_rejected(v):
    # "Poseidon" is a proper noun absent from the gazetteer -> hallucination
    assert v.validate("The tanker Poseidon should dock now.", [], []) is False


def test_known_vessel_name_in_required_entities_passes(v):
    assert v.validate("The vessel Aurora should dock now.", [], ["Aurora"]) is True


def test_multiword_name_matches_token_wise(v):
    assert v.validate("Vessel MV Genova arrives soon.", [], ["MV Genova"]) is True


def test_sentence_initial_capital_is_exempt(v):
    # "Berth" leads the sentence AND is a common noun -> never flagged
    assert v.validate("Berth 3 is clear. The tanker may enter.", ["3"], []) is True


def test_capitalised_common_domain_noun_is_exempt(v):
    # mid-sentence "Tanker"/"Container" are common nouns, not names
    assert v.validate("We recommend the Tanker use Container berth 1.", ["1"], []) is True


def test_place_names_and_currency_codes_are_exempt(v):
    # "Genova" (the harbour) and "EUR" are legitimate, not fabricated names
    assert v.validate("The Port of Genova berth is clear.", [], []) is True
    assert v.validate("The fee is EUR 2000 total.", ["2000"], []) is True


def test_empty_output_is_rejected(v):
    assert v.validate("", ["2000"], []) is False
    assert v.validate("   ", [], []) is False


# --- known-entity loading from ontology_vocab.yml ----------------------------
def test_load_known_entities_from_ontology_vocab():
    known = load_known_entities(VOCAB)
    # vessel types + berths + cargo classes from the OWL-derived vocab...
    assert {"tanker", "container_vessel", "cruise_ship", "ferry", "cargo_vessel"} <= known
    assert {"berth_1", "berth_2", "berth_3", "berth_4"} <= known
    # ...plus the concrete AgentRoster tug ids (service_types only lists "tug")
    assert {"tug_1", "tug_2", "tug_3", "tug_4"} <= known


def test_missing_vocab_file_yields_empty_but_usable(tmp_path):
    known = load_known_entities(tmp_path / "nope.yml")
    # tug ids are still seeded even without the file
    assert known == {"tug_1", "tug_2", "tug_3", "tug_4"}
    # validator still runs; gazetteer is then just per-call required_entities
    val = HallucinationValidator(known)
    assert val.validate("The vessel Aurora is ready.", [], ["Aurora"]) is True
    assert val.validate("The vessel Poseidon is ready.", [], []) is False


def test_validator_built_from_real_vocab_accepts_domain_entities():
    val = HallucinationValidator(load_known_entities(VOCAB))
    # a plausible template-style sentence with only real figures + entities
    ok = val.validate(
        "Recommend accept at 2000 euros; berth 3 is clear for the tanker.",
        ["2000", "3"], [],
    )
    assert ok is True
