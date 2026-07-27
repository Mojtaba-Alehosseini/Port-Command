#!/usr/bin/env bash
# Port Command Genova — stop every process the launcher may have left running.
#
# The normal path never needs this: start.sh's EXIT trap stops both services when the game
# exits, including on Ctrl-C. This is the escape hatch for a shell killed with -9 — and the
# thing to run when ":5005 already in use" or the misleading null-container NPE from a JADE
# port-1099 clash shows up (docs/TROUBLESHOOTING.md).
#
# Two passes: the PID files start.sh wrote (precise), then a pkill by FULL command line (the
# safety net). pkill -f matches the command line, never the bare image name, so it cannot take
# out an unrelated python or java the user has open.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PIDDIR="$ROOT/port-command-genova/logs/pids"
killed=0

for f in "$PIDDIR"/*.pid; do
  [ -e "$f" ] || continue
  pid="$(cat "$f" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "[stop] killing $(basename "$f" .pid) (pid $pid)"
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    killed=$((killed + 1))
  fi
  rm -f "$f"
done

# The REAL main class is it.unige.portcommand.Main — planning/26's stop.sh sketch named a
# `PortCommandMain` that has never existed in this repo (dated correction 2026-07-27).
for pat in 'llm_sidecar' '\.venv-rasa.*rasa' 'it\.unige\.portcommand\.Main'; do
  if pkill -f "$pat" 2>/dev/null; then
    echo "[stop] killed processes matching /$pat/"
    killed=$((killed + 1))
  fi
done

if [ "$killed" -eq 0 ]; then echo "[stop] nothing was running."; else echo "[stop] done."; fi
