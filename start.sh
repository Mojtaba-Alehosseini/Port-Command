#!/usr/bin/env bash
# Port Command Genova launcher.
# Starts the Flask LLM sidecar (task 13) in the BACKGROUND — its model loads
# while the game boots, and /health returns 503 until it is ready (the Java
# LLMBridge in task 14 polls it, so the game never blocks on it). Then runs the
# game in the foreground. The Rasa NLU server is started separately via
# nlp-python/start_rasa.sh.
set -euo pipefail
cd "$(dirname "$0")"                          # port-command-genova/
ROOT="$(cd .. && pwd)"

LLM="$ROOT/nlp-python/start_llm.sh"
if [ -f "$LLM" ]; then
  echo "Starting LLM sidecar -> http://127.0.0.1:5006 (loads model in background)..."
  bash "$LLM" &
  LLM_PID=$!
  # Stop the sidecar when the game exits.
  trap '[ -n "${LLM_PID:-}" ] && kill "$LLM_PID" 2>/dev/null || true' EXIT
else
  echo "note: $LLM not found — skipping sidecar (explanations fall back to template text)."
fi

./gradlew run
