#!/usr/bin/env python3
"""Convert the authoring spreadsheet into Rasa NLU YAML (task 12).

``nlu_authoring.csv`` (columns: ``intent, text, annotated_text, split``) is the
committed source of truth for the training corpus. This converter regenerates
two Rasa NLU files from it — never hand-edit those:

  * ``rasa/data/nlu.yml``            <- rows with ``split == "train"``
  * ``rasa/tests/test_holdout.yml``  <- rows with ``split == "test"`` (the F1 gate)

Editing utterances in a spreadsheet is far easier than in nested YAML, and this
one-way generation guarantees the held-out set stays disjoint from training: the
converter FAILS LOUDLY if any normalised utterance appears in both splits, or is
duplicated within a split, or carries an unknown intent.

Pure standard library (csv, re, argparse, pathlib) so it runs under the 3.11
tooling venv with no third-party deps — the Rasa import is NOT needed to gate it.

Usage (from ``nlp-python/``)::

    .venv/Scripts/python csv_to_nlu.py           # regenerate in place
    .venv/Scripts/python csv_to_nlu.py --check   # verify on disk == freshly generated
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

# The 9 canonical intents (PROJECT_DEFINITION 6.3). Order fixes YAML block order.
INTENTS = [
    "propose_offer", "counter_offer", "accept_deal", "reject_deal", "query_status",
    "set_constraint", "set_policy", "request_help", "cancel_action",
]
SPLITS = {"train", "test"}

HERE = Path(__file__).resolve().parent
DEFAULT_CSV = HERE / "rasa" / "data" / "nlu_authoring.csv"
DEFAULT_NLU = HERE / "rasa" / "data" / "nlu.yml"
DEFAULT_HOLDOUT = HERE / "rasa" / "tests" / "test_holdout.yml"

_HEADER = (
    "# AUTO-GENERATED FROM data/nlu_authoring.csv BY csv_to_nlu.py. DO NOT EDIT.\n"
    "# Edit the authoring CSV and regenerate (see nlp-python/README.md).\n"
)


class CorpusError(ValueError):
    """Raised on a malformed corpus (bad intent/split, duplicate, or leakage)."""


def normalise(text: str) -> str:
    """Case/punctuation-insensitive key for duplicate + leakage detection.

    ``"How about 2200 THEN!"`` and ``"how about 2200 then"`` collide; different
    numbers (``2200`` vs ``2400``) do not — those are legitimate variants.
    """
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def read_rows(csv_path: Path) -> list[dict]:
    with csv_path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        expected = {"intent", "text", "annotated_text", "split"}
        if set(reader.fieldnames or []) != expected:
            raise CorpusError(f"{csv_path.name}: columns {reader.fieldnames} != {sorted(expected)}")
        rows = [dict(r) for r in reader]
    if not rows:
        raise CorpusError(f"{csv_path.name}: no data rows")
    return rows


def validate(rows: list[dict]) -> None:
    """Fail loudly on unknown intent/split, empty text, in-split dupes, or
    train/holdout leakage (normalised)."""
    seen: dict[str, dict[str, str]] = {"train": {}, "test": {}}
    for n, r in enumerate(rows, start=2):  # +2: 1-based, past the header
        intent, split = r["intent"], r["split"]
        text, ann = (r["text"] or "").strip(), (r["annotated_text"] or "").strip()
        if intent not in INTENTS:
            raise CorpusError(f"row {n}: unknown intent {intent!r}")
        if split not in SPLITS:
            raise CorpusError(f"row {n}: split {split!r} not in {sorted(SPLITS)}")
        if not text or not ann:
            raise CorpusError(f"row {n}: empty text/annotated_text")
        key = normalise(text)
        if key in seen[split]:
            raise CorpusError(
                f"row {n}: duplicate within {split!r}: {text!r} collides with "
                f"{seen[split][key]!r}"
            )
        seen[split][key] = text
    leak = set(seen["train"]) & set(seen["test"])
    if leak:
        examples = ", ".join(sorted(leak)[:5])
        raise CorpusError(
            f"train/holdout LEAKAGE: {len(leak)} utterance(s) in both splits "
            f"(normalised): {examples}"
        )


def render_yaml(rows: list[dict], split: str) -> str:
    """Rasa 3.x NLU YAML for one split, examples grouped by canonical intent."""
    out = [_HEADER.rstrip("\n"), 'version: "3.1"', "nlu:"]
    for intent in INTENTS:
        examples = [r["annotated_text"].strip() for r in rows
                    if r["intent"] == intent and r["split"] == split]
        if not examples:
            continue
        out.append(f"- intent: {intent}")
        out.append("  examples: |")
        out += [f"    - {ex}" for ex in examples]
    return "\n".join(out) + "\n"


def generate(csv_path: Path) -> tuple[str, str]:
    rows = read_rows(csv_path)
    validate(rows)
    return render_yaml(rows, "train"), render_yaml(rows, "test")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", type=Path, default=DEFAULT_CSV, help="authoring CSV")
    ap.add_argument("--nlu", type=Path, default=DEFAULT_NLU, help="output nlu.yml")
    ap.add_argument("--holdout", type=Path, default=DEFAULT_HOLDOUT, help="output holdout")
    ap.add_argument("--check", action="store_true",
                    help="verify on-disk files match a fresh generation; do not write")
    args = ap.parse_args(argv)

    nlu_text, holdout_text = generate(args.csv)

    if args.check:
        ok = True
        for path, fresh in ((args.nlu, nlu_text), (args.holdout, holdout_text)):
            current = path.read_text(encoding="utf-8") if path.exists() else None
            if current != fresh:
                print(f"STALE: {path} differs from a fresh generation", file=sys.stderr)
                ok = False
        if not ok:
            return 1
        print("csv_to_nlu --check: nlu.yml and test_holdout.yml are up to date")
        return 0

    args.nlu.parent.mkdir(parents=True, exist_ok=True)
    args.holdout.parent.mkdir(parents=True, exist_ok=True)
    args.nlu.write_text(nlu_text, encoding="utf-8", newline="\n")
    args.holdout.write_text(holdout_text, encoding="utf-8", newline="\n")

    n_train = nlu_text.count("\n    - ")
    n_test = holdout_text.count("\n    - ")
    print(f"Wrote {args.nlu} ({n_train} train examples)")
    print(f"Wrote {args.holdout} ({n_test} holdout examples)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
