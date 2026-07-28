#!/usr/bin/env bash
# Superseded by the repo-root ../start.sh (task 26, 2026-07-27).
#
# This script used to start the LLM sidecar and the game, but NOT Rasa — so a launch through it
# left the NLU cascade without its middle stage, and every typed sentence the DCG could not
# parse fell to a clarification. That is a bad thing to discover during a demo, so rather than
# leave a partial launcher lying next to a complete one, this delegates.
#
# The root launcher boots Rasa (:5005, from .venv-rasa) then the sidecar (:5006, from .venv with
# LLM_QUANT=onnx) then the game, health-checks between each, and stops all three on Ctrl-C.
exec "$(cd "$(dirname "$0")/.." && pwd)/start.sh" "$@"
