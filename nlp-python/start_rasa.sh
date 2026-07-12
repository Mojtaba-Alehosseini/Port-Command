#!/usr/bin/env bash
# Launch the Port Command Genova Rasa NLU server on port 5005.
#
# Two-venv layout (task 12): the Rasa service runs on Python 3.10 out of
# .venv-rasa (rasa 3.6.21 forbids 3.11). Runs in the foreground; background it
# with `./start_rasa.sh &` (the Java bridge in task 14 spawns it as a subprocess).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"                 # nlp-python/
RASA="$HERE/.venv-rasa/Scripts/rasa"                  # Windows (Git Bash) venv layout
[ -e "$RASA" ] || RASA="$HERE/.venv-rasa/bin/rasa"    # POSIX venv layout
if [ ! -e "$RASA" ]; then
  echo "error: .venv-rasa missing. Create it:" >&2
  echo "  py -3.10 -m venv .venv-rasa && .venv-rasa/Scripts/pip install -r requirements-rasa.txt" >&2
  exit 1
fi
cd "$HERE/rasa"
echo "Rasa NLU -> http://localhost:5005  (POST /model/parse). Ctrl+C to stop." >&2
exec "$RASA" run --enable-api --port 5005 --model models/portcmd_nlu.tar.gz
