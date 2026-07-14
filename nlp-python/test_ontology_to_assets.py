"""Pytest for ontology_to_assets.py (task 02).

Runs the converter on the committed OWL and asserts the generated Prolog/vocab
contain every fact the downstream tasks (04 rules, 12 Rasa) hardcode against,
plus the determinism guarantee (run twice -> byte-identical).

Run from the repo root with the 3.11 venv:
    nlp-python/.venv/Scripts/python -m pytest nlp-python/test_ontology_to_assets.py -v
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
OWL = REPO / "port-command-genova" / "src" / "main" / "resources" / "ontology" / "port_ontology.owl"
SCRIPT = REPO / "nlp-python" / "ontology_to_assets.py"

import ontology_to_assets as ota  # noqa: E402  (import after sys.path is set by pytest)


@pytest.fixture(scope="module")
def pl_text() -> str:
    onto = ota.load_ontology(OWL)
    return ota.render_prolog(ota.build_model(onto))


@pytest.fixture(scope="module")
def vocab_text() -> str:
    onto = ota.load_ontology(OWL)
    return ota.render_vocab(ota.build_model(onto))


# --- snake_case normalisation -------------------------------------------------

@pytest.mark.parametrize("pascal,snake", [
    ("ContainerVessel", "container_vessel"),
    ("CruiseShip", "cruise_ship"),
    ("DeepWaterBerth", "deep_water_berth"),
    ("GeneralCargoBerth", "general_cargo_berth"),
    ("HazmatClass3", "hazmat_class_3"),
    ("HazmatClass1", "hazmat_class_1"),
    ("hasIMO", "has_imo"),
    ("hasMaxDraft", "has_max_draft"),
    ("isCompatibleWith", "is_compatible_with"),
    ("HighWind", "high_wind"),
])
def test_to_snake(pascal, snake):
    assert ota.to_snake(pascal) == snake


# --- class facts --------------------------------------------------------------

@pytest.mark.parametrize("atom", [
    "tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship",
    "vessel", "berth", "cargo", "port_service", "weather_condition",
    "deep_water_berth", "container_berth", "general_cargo_berth", "ferry_berth",
    "hazmat_cargo", "hazmat_class_1", "hazmat_class_3",
    "general_cargo", "liquid_bulk", "containerized_cargo",
    "tug", "clear_weather", "fog", "storm", "high_wind", "person", "officer",
])
def test_class_fact_present(pl_text, atom):
    assert f"class({atom})." in pl_text


def test_class_count_is_27(pl_text):
    assert pl_text.count("\nclass(") + (1 if pl_text.startswith("class(") else 0) == 27


# --- subclass edges (direct) --------------------------------------------------

@pytest.mark.parametrize("child,parent", [
    ("tanker", "vessel"),
    ("cruise_ship", "vessel"),
    ("deep_water_berth", "berth"),
    ("general_cargo_berth", "berth"),
    ("hazmat_cargo", "cargo"),
    ("hazmat_class_3", "hazmat_cargo"),
    ("tug", "port_service"),
    ("storm", "weather_condition"),
    ("officer", "person"),
])
def test_subclass_edge(pl_text, child, parent):
    assert f"subclass_of({child}, {parent})." in pl_text


# --- vessel_type/1 (guards) ---------------------------------------------------

@pytest.mark.parametrize("vt", ["tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship"])
def test_vessel_type_present(pl_text, vt):
    assert f"vessel_type({vt})." in pl_text


@pytest.mark.parametrize("notvt", ["cargo", "berth", "vessel", "storm", "tug", "hazmat_cargo"])
def test_vessel_type_absent_for_non_vessels(pl_text, notvt):
    # exact-line check: 'vessel_type(cargo).' must NOT appear,
    # but 'vessel_type(cargo_vessel).' (substring of nothing here) is fine.
    assert f"vessel_type({notvt})." not in pl_text


# --- cargo_class/1 ------------------------------------------------------------

@pytest.mark.parametrize("cc", [
    "hazmat_cargo", "hazmat_class_1", "hazmat_class_3",
    "general_cargo", "liquid_bulk", "containerized_cargo",
])
def test_cargo_class_present(pl_text, cc):
    assert f"cargo_class({cc})." in pl_text


def test_cargo_class_count_is_6(pl_text):
    assert pl_text.count("cargo_class(") == 6


def test_cargo_root_not_a_cargo_class(pl_text):
    assert "cargo_class(cargo)." not in pl_text


# --- is_hazmat clause (single owner) -----------------------------------------

def test_is_hazmat_clause(pl_text):
    assert "is_hazmat(C) :- subclass_of(C, hazmat_cargo)." in pl_text


# --- berth defaults + beams + instances --------------------------------------

@pytest.mark.parametrize("line", [
    "default(berth_1, has_max_draft, 22.0).",
    "default(berth_1, has_max_length, 350.0).",
    "default(berth_1, has_max_beam, 50.0).",
    "default(berth_2, has_max_draft, 14.0).",
    "default(berth_2, has_max_length, 340.0).",  # task 07b: 250 -> 340 (cruise/container fit)
    "default(berth_3, has_max_draft, 12.0).",
    "default(berth_4, has_max_draft, 9.0).",
    "default(berth_4, has_max_beam, 26.0).",      # task 07b: 22 -> 26 (ferry beam 25 fits)
    "default(tanker, has_beam, 20.0).",
    "default(container_vessel, has_beam, 32.0).",
    "default(cruise_ship, has_beam, 35.0).",
    "instance_of(berth_1, deep_water_berth).",
    "instance_of(berth_2, container_berth).",
    "instance_of(berth_3, general_cargo_berth).",
    "instance_of(berth_4, ferry_berth).",
])
def test_default_and_instance_lines(pl_text, line):
    assert line in pl_text


def test_no_bare_berth_root_instance(pl_text):
    for n in (1, 2, 3, 4):
        assert f"instance_of(berth_{n}, berth)." not in pl_text


# --- property domains/ranges --------------------------------------------------

@pytest.mark.parametrize("line", [
    "property_domain(has_draft, vessel).",
    "property_domain(has_max_draft, berth).",
    "property_domain(has_cargo_class, vessel).",
    "property_range(has_draft, decimal).",
    "property_range(has_tonnage, integer).",
    "property_range(has_imo, string).",
    "property_range(requires_escort, boolean).",
    "property_range(has_cargo_class, cargo).",
    "property_range(is_compatible_with, berth).",
])
def test_property_lines(pl_text, line):
    assert line in pl_text


# --- header / footer ----------------------------------------------------------

def test_header_and_footer(pl_text):
    assert pl_text.startswith("% AUTO-GENERATED FROM port_ontology.owl")
    assert "DO NOT EDIT" in pl_text.splitlines()[0]
    assert "ontology_loaded :- true." in pl_text


def test_dynamic_declarations_for_transient_facts(pl_text):
    # Task 04 asserts/retracts transient default/3 + instance_of/2 facts, so the
    # generated ontology must declare them dynamic (before the facts load).
    assert ":- dynamic default/3." in pl_text
    assert ":- dynamic instance_of/2." in pl_text
    # The dynamic decls must precede the first default/instance_of fact.
    assert pl_text.index(":- dynamic default/3.") < pl_text.index("default(berth_1,")
    assert pl_text.index(":- dynamic instance_of/2.") < pl_text.index("instance_of(berth_1,")


def test_vessel_class_needs_deferred(pl_text):
    # Task 04 owns vessel_class_needs/2; the converter must NOT emit it (task 02 decision).
    assert "vessel_class_needs(" not in pl_text


# --- vocab --------------------------------------------------------------------

@pytest.mark.parametrize("key", [
    "vessel_types", "berths", "cargo_classes", "service_types", "verbs_for_negotiation",
])
def test_vocab_key_present_and_nonempty(vocab_text, key):
    lines = vocab_text.splitlines()
    idx = next((i for i, ln in enumerate(lines) if ln.rstrip() == f"{key}:"), None)
    assert idx is not None, f"missing key {key}"
    # the next line must be a list item ('  - ...')
    assert idx + 1 < len(lines) and lines[idx + 1].lstrip().startswith("- "), f"{key} is empty"


@pytest.mark.parametrize("verb", ["propose", "offer", "give", "pay", "accept", "refuse", "reject"])
def test_vocab_negotiation_verbs(vocab_text, verb):
    assert f"  - {verb}" in vocab_text


def test_vocab_service_types_has_tug(vocab_text):
    assert "  - tug" in vocab_text


# --- CLI + determinism (acceptance: run twice -> diff empty) ------------------

def test_cli_generates_and_is_deterministic(tmp_path):
    pl1 = tmp_path / "a.pl"
    vocab1 = tmp_path / "a.yml"
    pl2 = tmp_path / "b.pl"
    vocab2 = tmp_path / "b.yml"

    def run(pl, vocab):
        r = subprocess.run(
            [sys.executable, str(SCRIPT), "--owl", str(OWL), "--pl", str(pl), "--vocab", str(vocab)],
            capture_output=True, text=True,
        )
        assert r.returncode == 0, r.stderr
        return pl.read_bytes(), vocab.read_bytes()

    pl_a, vocab_a = run(pl1, vocab1)
    pl_b, vocab_b = run(pl2, vocab2)
    assert pl_a == pl_b, "Prolog output is not deterministic across runs"
    assert vocab_a == vocab_b, "vocab output is not deterministic across runs"
    # and it matches the in-process render (committed artefact == what CLI writes)
    onto = ota.load_ontology(OWL)
    assert pl_a.decode("utf-8") == ota.render_prolog(ota.build_model(onto))
