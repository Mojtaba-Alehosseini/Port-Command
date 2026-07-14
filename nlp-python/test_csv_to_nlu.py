"""Pytest for csv_to_nlu.py (task 12).

Two layers:
  * unit — synthetic CSVs in tmp_path exercise split routing, the duplicate +
    train/holdout leakage guard (it must FAIL LOUDLY), unknown-intent/column
    rejection, and round-trip counts.
  * corpus — the committed nlu_authoring.csv must validate, split 450/90 at
    50/10 per intent, and annotate entities using ONLY the canonical vocab
    (ontology_vocab.yml values + the tug ids from AgentRoster). This is the
    guard against entity-name drift across the 8 entities.

Run from the repo root with the 3.11 venv:
    nlp-python/.venv/Scripts/python -m pytest nlp-python/test_csv_to_nlu.py -v
"""
from __future__ import annotations

import csv
import re
from collections import Counter
from pathlib import Path

import pytest

import csv_to_nlu as c2n  # noqa: E402  (import after pytest sets sys.path)

REPO = Path(__file__).resolve().parents[1]
CSV = REPO / "nlp-python" / "rasa" / "data" / "nlu_authoring.csv"
VOCAB = REPO / "nlp-python" / "rasa" / "data" / "ontology_vocab.yml"

# free-text entities carry no canonical value constraint
FREE_ENTITIES = {"price_expression", "time_expression", "vessel_name"}
TUG_IDS = {"tug_1", "tug_2", "tug_3", "tug_4"}  # bootstrap/AgentRoster ("tug_" + i)

_DICT = re.compile(r'\{"entity": "([^"]+)", "value": "([^"]+)"\}')
_SIMPLE = re.compile(r"\]\((\w+)\)")


# --- helpers -----------------------------------------------------------------
def write_csv(path: Path, rows: list[tuple[str, str, str, str]]) -> Path:
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["intent", "text", "annotated_text", "split"])
        w.writerows(rows)
    return path


def parse_vocab_block(name: str) -> set[str]:
    """Minimal parser for the flat ontology_vocab.yml (no pyyaml dependency)."""
    items: set[str] = set()
    current = None
    for line in VOCAB.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" ") and line.rstrip().endswith(":"):
            current = line.rstrip()[:-1]
        elif line.lstrip().startswith("- ") and current == name:
            items.add(line.split("- ", 1)[1].strip())
    return items


# --- unit: split routing -----------------------------------------------------
def test_split_routes_train_and_test(tmp_path):
    csv_path = write_csv(tmp_path / "a.csv", [
        ("accept_deal", "Deal", "Deal", "train"),
        ("accept_deal", "Absolutely", "Absolutely", "test"),
        ("reject_deal", "No", "No", "train"),
    ])
    nlu, holdout = c2n.generate(csv_path)
    assert "- Deal" in nlu and "- No" in nlu
    assert "- Deal" not in holdout
    assert "- Absolutely" in holdout and "- Absolutely" not in nlu
    # counts: 2 train examples, 1 holdout
    assert nlu.count("\n    - ") == 2
    assert holdout.count("\n    - ") == 1


def test_intents_render_in_canonical_order(tmp_path):
    csv_path = write_csv(tmp_path / "b.csv", [
        ("reject_deal", "No", "No", "train"),
        ("propose_offer", "Offer 2000", "Offer [2000](price_expression)", "train"),
    ])
    nlu, _ = c2n.generate(csv_path)
    # propose_offer precedes reject_deal in INTENTS, so it must come first
    assert nlu.index("propose_offer") < nlu.index("reject_deal")


# --- unit: the guard must fire -----------------------------------------------
def test_leakage_between_train_and_holdout_raises(tmp_path):
    csv_path = write_csv(tmp_path / "c.csv", [
        ("accept_deal", "Deal", "Deal", "train"),
        ("accept_deal", "deal!", "deal!", "test"),  # normalises to "deal" -> leak
    ])
    with pytest.raises(c2n.CorpusError, match="LEAKAGE"):
        c2n.generate(csv_path)


def test_near_duplicate_leakage_is_caught_after_normalisation(tmp_path):
    csv_path = write_csv(tmp_path / "c2.csv", [
        ("counter_offer", "How about 2200 then", "How about [2200](price_expression) then", "train"),
        ("counter_offer", "how about   2200,  THEN", "how about 2200 then", "test"),
    ])
    with pytest.raises(c2n.CorpusError, match="LEAKAGE"):
        c2n.generate(csv_path)


def test_different_numbers_are_not_duplicates(tmp_path):
    # same template, different price -> legitimately distinct, must NOT raise
    csv_path = write_csv(tmp_path / "c3.csv", [
        ("counter_offer", "How about 2200 then", "How about [2200](price_expression) then", "train"),
        ("counter_offer", "How about 2400 then", "How about [2400](price_expression) then", "test"),
    ])
    nlu, holdout = c2n.generate(csv_path)
    assert nlu.count("\n    - ") == 1 and holdout.count("\n    - ") == 1


def test_duplicate_within_split_raises(tmp_path):
    csv_path = write_csv(tmp_path / "d.csv", [
        ("accept_deal", "Deal", "Deal", "train"),
        ("accept_deal", "DEAL.", "DEAL.", "train"),
    ])
    with pytest.raises(c2n.CorpusError, match="duplicate within"):
        c2n.generate(csv_path)


def test_unknown_intent_raises(tmp_path):
    csv_path = write_csv(tmp_path / "e.csv", [("book_berth", "hi", "hi", "train")])
    with pytest.raises(c2n.CorpusError, match="unknown intent"):
        c2n.generate(csv_path)


def test_unknown_split_raises(tmp_path):
    csv_path = write_csv(tmp_path / "f.csv", [("accept_deal", "Deal", "Deal", "dev")])
    with pytest.raises(c2n.CorpusError, match="split"):
        c2n.generate(csv_path)


def test_empty_text_raises(tmp_path):
    csv_path = write_csv(tmp_path / "g.csv", [("accept_deal", "", "", "train")])
    with pytest.raises(c2n.CorpusError, match="empty"):
        c2n.generate(csv_path)


def test_bad_columns_raise(tmp_path):
    path = tmp_path / "h.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow(["intent", "utterance", "split"])  # wrong schema
    with pytest.raises(c2n.CorpusError, match="columns"):
        c2n.generate(path)


def test_normalise():
    assert c2n.normalise("How about 2200 THEN!") == "how about 2200 then"
    assert c2n.normalise("  Deal.  ") == "deal"
    assert c2n.normalise("2000€") == "2000"


# --- corpus: the committed authoring CSV -------------------------------------
@pytest.fixture(scope="module")
def rows() -> list[dict]:
    return c2n.read_rows(CSV)


def test_committed_csv_validates():
    c2n.generate(CSV)  # must not raise (no dupes, no leakage, all intents valid)


def test_corpus_counts_450_90(rows):
    by_split = Counter(r["split"] for r in rows)
    assert by_split == {"train": 450, "test": 90}


def test_corpus_50_10_per_intent(rows):
    per = Counter((r["intent"], r["split"]) for r in rows)
    for intent in c2n.INTENTS:
        assert per[(intent, "train")] == 50, intent
        assert per[(intent, "test")] == 10, intent


def test_entity_values_are_canonical(rows):
    """Every annotated entity value stays within the ontology vocab (+ tug ids).

    This is the guard against entity-name drift: a hand edit that introduces
    `berth_5`, `pleasure`, or an off-vocab cargo class fails here.
    """
    allowed = {
        "vessel_class": parse_vocab_block("vessel_types"),
        "berth_id": parse_vocab_block("berths"),
        "cargo_class": parse_vocab_block("cargo_classes"),
        "hazmat_code": {"hazmat_class_1", "hazmat_class_3"},
        "tug_name": TUG_IDS,
    }
    assert allowed["vessel_class"] and allowed["berth_id"] and allowed["cargo_class"]
    for r in rows:
        for entity, value in _DICT.findall(r["annotated_text"]):
            assert entity in allowed, f"row {r['text']!r}: entity {entity} has no vocab"
            assert value in allowed[entity], f"row {r['text']!r}: {entity}={value} off-vocab"


def test_only_the_eight_entities_appear(rows):
    eight = {"vessel_name", "vessel_class", "berth_id", "tug_name",
             "time_expression", "price_expression", "cargo_class", "hazmat_code"}
    used = set()
    for r in rows:
        used.update(e for e, _ in _DICT.findall(r["annotated_text"]))
        used.update(_SIMPLE.findall(r["annotated_text"]))
    assert used <= eight, f"unexpected entities: {used - eight}"


def test_check_mode_matches_on_disk(capsys):
    # the committed nlu.yml/test_holdout.yml must equal a fresh generation
    rc = c2n.main(["--check"])
    assert rc == 0, capsys.readouterr().err
