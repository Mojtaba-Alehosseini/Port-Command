#!/usr/bin/env bash
# Regenerate NLU data from the authoring CSV, then train the Rasa NLU model.
# Uses the Python 3.10 Rasa venv (.venv-rasa). Expect < 5 min on CPU.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"                 # nlp-python/
PY="$HERE/.venv-rasa/Scripts/python"                  # Windows (Git Bash)
[ -e "$PY" ] || PY="$HERE/.venv-rasa/bin/python"      # POSIX
if [ ! -e "$PY" ]; then
  echo "error: .venv-rasa missing (see requirements-rasa.txt)." >&2; exit 1
fi
# nlu.yml + tests/test_holdout.yml are GENERATED — always regenerate from the CSV
# (single source of truth) so a stale hand edit can never train silently.
"$PY" "$HERE/csv_to_nlu.py"
cd "$HERE/rasa"
exec "$PY" -m rasa train --fixed-model-name portcmd_nlu
