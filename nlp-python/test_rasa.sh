#!/usr/bin/env bash
# Evaluate the trained Rasa NLU model.
#   1) HOLDOUT (the acceptance gate): intent weighted-F1 >= 0.85 on the 90
#      disjoint utterances in tests/test_holdout.yml. This is the ONLY pass/fail.
#      Entity (NER) F1 from the same run is reported, NOT gated.
#   2) 5-fold CROSS-VALIDATION: the in-development noise-floor check (slower —
#      retrains DIET 5x). Informational only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PY="$HERE/.venv-rasa/Scripts/python"
[ -e "$PY" ] || PY="$HERE/.venv-rasa/bin/python"
cd "$HERE/rasa"
MODEL="models/portcmd_nlu.tar.gz"

echo "== Holdout acceptance gate (intent F1 >= 0.85) =="
"$PY" -m rasa test nlu --nlu tests/test_holdout.yml --model "$MODEL" --out results/holdout
"$PY" - <<'PYEOF'
import json, sys, pathlib
ip = pathlib.Path("results/holdout/intent_report.json")
if not ip.exists():
    sys.exit("FAIL: results/holdout/intent_report.json missing")
intent_f1 = json.load(ip.open())["weighted avg"]["f1-score"]
verdict = "PASS" if intent_f1 >= 0.85 else "FAIL"
print(f"\n  HOLDOUT intent weighted-F1 = {intent_f1:.4f}  [{verdict} vs 0.85 gate]")
ep = pathlib.Path("results/holdout/DIETClassifier_report.json")
if ep.exists():
    entity_f1 = json.load(ep.open())["weighted avg"]["f1-score"]
    print(f"  HOLDOUT entity weighted-F1 = {entity_f1:.4f}  [reported, NOT gated]")
sys.exit(0 if intent_f1 >= 0.85 else 1)
PYEOF

echo ""
echo "== 5-fold cross-validation (in-dev noise floor; informational) =="
"$PY" -m rasa test nlu --nlu data/nlu.yml --cross-validation --folds 5 --out results/cv
echo "Confusion matrices + reports written under rasa/results/ (gitignored)."
