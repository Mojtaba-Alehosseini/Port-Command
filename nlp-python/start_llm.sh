#!/usr/bin/env bash
# Launch the Port Command Genova Flask LLM sidecar on port 5006.
#
# Two-venv layout (task 12/13): the sidecar runs on Python 3.11 out of .venv
# (the Rasa service is the one on 3.10). Runs in the foreground; background it
# with `./start_llm.sh &` (the root start.sh and the Java bridge in task 14 spawn
# it that way). /health returns 503 until the model finishes loading (30-90 s on
# CPU at fp16); the caller polls it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"                 # nlp-python/
PY="$HERE/.venv/Scripts/python.exe"                   # Windows (Git Bash) venv layout
[ -e "$PY" ] || PY="$HERE/.venv/bin/python"           # POSIX venv layout
if [ ! -e "$PY" ]; then
  echo "error: .venv missing. Create it:" >&2
  echo "  py -3.11 -m venv .venv && .venv/Scripts/pip install -r requirements-llm.txt" >&2
  exit 1
fi
cd "$HERE"                                            # so `python -m llm_sidecar` resolves

# Auto-select the fast ONNX Runtime GenAI CPU-INT4 backend (task 13b) when a
# COMPLETE local ONNX model is present and LLM_QUANT is not pinned.
# config.onnx_model_available() is the single source of truth (path + size gate).
if [ -z "${LLM_QUANT:-}" ]; then
  if [ "$("$PY" -c 'import llm_sidecar.config as c; print("onnx" if c.onnx_model_available() else "none")' 2>/dev/null)" = "onnx" ]; then
    export LLM_QUANT=onnx
    echo "backend: ONNX Runtime GenAI CPU-INT4 (local model found) -- fast path" >&2
  else
    echo "backend: fp16/bf16 default (no local ONNX model; set LLM_QUANT to override)" >&2
  fi
else
  echo "backend: LLM_QUANT=$LLM_QUANT (pinned)" >&2
fi

echo "LLM sidecar -> http://127.0.0.1:5006  (GET /health, POST /explain). Ctrl+C to stop." >&2
exec "$PY" -m llm_sidecar.server
