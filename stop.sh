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
  # start.sh writes "<pid> <comm>", so the whole line is not a pid - parse BOTH fields exactly as
  # start.sh's own reap_stale does (2026-08-10, audit P1-11). Reading the line whole made
  # `kill -0 "$pid"` fail on every entry, so pass 1 silently never killed anything and the pkill
  # net below was doing all the work. stop.ps1 already parsed it correctly.
  #
  # The command-name check is NOT optional now that pass 1 actually fires: this kills a process
  # GROUP off a recorded number, and stale PID files only outlive a `kill -9` or a reboot - which
  # is exactly when the OS has recycled that pid onto something else. Same guard, same reason, as
  # start.sh's reap_stale and stop.ps1's three-field identity match.
  pid="$(cut -d' ' -f1 "$f" 2>/dev/null || true)"
  oldcomm="$(cut -d' ' -f2 "$f" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    nowcomm="$(ps -p "$pid" -o comm= 2>/dev/null | tr -d ' ')"
    if [ -z "$oldcomm" ] || [ "$nowcomm" = "$oldcomm" ]; then
      echo "[stop] killing $(basename "$f" .pid) (pid $pid)"
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
      killed=$((killed + 1))
    else
      echo "[stop] pid $pid has been recycled by '$nowcomm' - not killing it"
    fi
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
