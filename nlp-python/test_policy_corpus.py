"""Cross-check: the set_policy corpus vs PolicyParser's DSL (audit D-03, S6).

Run from the repo root: .venv/Scripts/python -m pytest nlp-python/test_policy_corpus.py

Before S6 the two were disjoint languages — 0 of 60 set_policy rows parsed — so a player who
typed exactly what the classifier was trained on was guaranteed the grammar-hint fallback.
The regenerated corpus makes a healthy majority valid DSL, keeps ~12 deliberately off-DSL
rows as honest negatives (they exercise the designed hint path), and marks each row in the
authoring CSV's ``dsl_valid`` column so this file can hold corpus and grammar to each other.
"""

from __future__ import annotations

import csv
from pathlib import Path

import pytest

import policy_dsl

REPO = Path(__file__).resolve().parents[1]
CSV = REPO / "nlp-python" / "rasa" / "data" / "nlu_authoring.csv"


@pytest.fixture(scope="module")
def set_policy_rows() -> list[dict]:
    with CSV.open(newline="", encoding="utf-8") as f:
        return [r for r in csv.DictReader(f) if r["intent"] == "set_policy"]


def test_a_healthy_majority_of_set_policy_rows_parse(set_policy_rows):
    """The claim-level gate: the corpus and the DSL are no longer disjoint languages."""
    parsing = [r for r in set_policy_rows if policy_dsl.parses(r["text"])]
    assert len(set_policy_rows) == 60, "set_policy stays at 60 rows (50 train + 10 holdout)"
    assert len(parsing) >= 45, (
        f"a healthy majority of set_policy rows must be valid PolicyParser DSL; "
        f"only {len(parsing)}/60 parse (audit D-03 measured 0/60 pre-S6)"
    )
    # The honest negatives must survive too — a 60/60 corpus would silently delete the
    # grammar-hint path's training signal.
    assert len(parsing) <= 55, (
        f"{len(parsing)}/60 parse — keep ~10 off-DSL rows as honest negatives"
    )


def test_dsl_valid_marks_agree_with_the_real_grammar(set_policy_rows):
    """Every row's dsl_valid mark must match what the (ported) grammar actually does."""
    for row in set_policy_rows:
        mark = row.get("dsl_valid", "")
        assert mark in ("y", "n"), (
            f"every set_policy row needs a dsl_valid mark, got {mark!r} for: {row['text']}"
        )
        actual = policy_dsl.parses(row["text"])
        assert actual == (mark == "y"), (
            f"dsl_valid={mark!r} but the grammar says parses={actual} for: {row['text']}"
        )


def test_no_other_intent_carries_a_dsl_mark():
    with CSV.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["intent"] != "set_policy":
                assert row.get("dsl_valid", "") == "", (
                    f"dsl_valid is a set_policy-only column, found {row['dsl_valid']!r} "
                    f"on {row['intent']}: {row['text']}"
                )


# --- the drift guard: PolicyParserTest's own vectors, verbatim ------------------------------
# If PolicyParser.java's grammar changes, its test vectors change with it — and this block
# goes red, which is the signal to re-sync policy_dsl.py. Drift beyond these six is
# undetected (module docstring states the residual).

@pytest.mark.parametrize("text", [
    "auto accept if price > 2200 for tankers",
    "auto reject if price < 1500",
    "auto counter +15% if price > 1000",
    "auto accept if price > 2000 and rounds_used >= 2",
])
def test_port_accepts_what_policyparsertest_accepts(text):
    assert policy_dsl.parses(text), text


@pytest.mark.parametrize("text", [
    "please accept tankers somehow",
    "auto accept if price > 2200 for submarines",
])
def test_port_rejects_what_policyparsertest_rejects(text):
    assert not policy_dsl.parses(text), text
