#!/usr/bin/env bash
# Port Command Genova - the demo launcher (Linux/macOS, and Git Bash on Windows).
#
# Boots the three processes in the order the game needs them, health-checking between each:
#
#   1. Rasa NLU server      :5005   from nlp-python/.venv-rasa   (Python 3.10 - rasa 3.6.21
#                                    refuses 3.11, see BUILD_LOG task 12)
#   2. Flask LLM sidecar    :5006   from nlp-python/.venv        (Python 3.11, LLM_QUANT=onnx)
#   3. The Java game                ./gradlew run --args="--gui"
#
# TWO VENVS, ONE PER SERVICE. planning/26's sketch did `source .venv/bin/activate` once and ran
# both services from it; that predates the two-venv split and cannot work - rasa 3.6.21 will not
# install on 3.11, and the ONNX Runtime GenAI wheel the sidecar needs lives only in .venv. Each
# service is started from its OWN interpreter below, so nothing is activated at all.
#
# Teardown: `trap ... EXIT` fires on a normal exit, on `set -e`, and on Ctrl-C (the INT/TERM
# traps re-raise into it), so a single Ctrl-C stops all three. stop.sh is the manual escape
# hatch if this shell is ever killed with -9.
#
#   ./start.sh                       the window opens on the New Game dialog (S15): pick a
#                                    scenario or Free play (the sandbox); nothing runs before
#   ./start.sh --scenario busy_day   boot straight into a scenario
#   ./start.sh --profile             record logs/profile.jfr for 15 minutes
#   ./start.sh --no-services         game only (services already running)
#   ./start.sh --no-llm              skip the sidecar (explanations use the plain template)
#   ./start.sh --no-rasa             skip Rasa: the grammar alone reads what you type (S18b)
#   ./start.sh --jade-port 11099     if something else holds RMI port 1099
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
NLP="$ROOT/nlp-python"
GAME="$ROOT/port-command-genova"
LOGDIR="$GAME/logs"
PIDDIR="$LOGDIR/pids"
mkdir -p "$PIDDIR"

PROFILE=0
PROFILE_SECONDS=900
SCENARIO=""
JADE_PORT=1099
NO_SERVICES=0
NO_LLM=0
NO_RASA=0
while [ $# -gt 0 ]; do
  case "$1" in
    --profile)          PROFILE=1; shift ;;
    --profile-seconds)  PROFILE_SECONDS="$2"; shift 2 ;;
    --scenario)         SCENARIO="$2"; shift 2 ;;
    --jade-port)        JADE_PORT="$2"; shift 2 ;;
    --no-services)      NO_SERVICES=1; shift ;;
    --no-llm)           NO_LLM=1; shift ;;
    --no-rasa)          NO_RASA=1; shift ;;
    -h|--help)          sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

step() { printf '[start] %s\n' "$*" >&2; }

# Windows (Git Bash) venvs use Scripts/, POSIX ones use bin/.
RASA="$NLP/.venv-rasa/Scripts/rasa"
[ -e "$RASA" ] || RASA="$NLP/.venv-rasa/bin/rasa"
SIDECAR_PY="$NLP/.venv/Scripts/python.exe"
[ -e "$SIDECAR_PY" ] || SIDECAR_PY="$NLP/.venv/bin/python"
MODEL="$NLP/rasa/models/portcmd_nlu.tar.gz"

STARTED_PIDS=()

# Teardown. `setsid` is used at launch WHERE AVAILABLE so each service gets its own process
# group and `kill -- -PGID` reaches rasa's forked Sanic worker too; without it (stock Git Bash
# has no setsid) the children sit in this script's own group, `kill -- -$pid` fails with ESRCH,
# and the fallback `kill $pid` reaches only the direct child. Ctrl-C masks the difference (SIGINT
# goes to the whole foreground group anyway); a hard kill does not, which is what stop.sh's
# command-line pass is for. Dated note 2026-07-27 - the previous comment claimed a group kill the
# script never actually set up.
stop_all() {
  for pid in "${STARTED_PIDS[@]:-}"; do
    [ -n "${pid:-}" ] || continue
    step "stopping pid $pid..."
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done
  rm -f "$PIDDIR"/*.pid 2>/dev/null || true
  STARTED_PIDS=()
}
trap 'stop_all' EXIT
trap 'exit 130' INT     # -> runs the EXIT trap
trap 'exit 143' TERM

# A previous run killed with -9 leaves PID files behind. Reap them BEFORE the health checks,
# or those checks would pass against the OLD servers - a stale Rasa still answers /status,
# with the previous model loaded, which is the silent-wrong-result this script exists to stop.
reap_stale() {
  for f in "$PIDDIR"/*.pid; do
    [ -e "$f" ] || continue
    # "<pid> <comm>" - the command name is checked too, because pids are reused and the case
    # this exists for (a reboot, a power loss) is exactly when the recorded number belongs to
    # something else by now.
    old="$(cut -d' ' -f1 "$f" 2>/dev/null || true)"
    oldcomm="$(cut -d' ' -f2 "$f" 2>/dev/null || true)"
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
      nowcomm="$(ps -p "$old" -o comm= 2>/dev/null | tr -d ' ')"
      if [ -z "$oldcomm" ] || [ "$nowcomm" = "$oldcomm" ]; then
        step "reaping stale $(basename "$f" .pid) (pid $old) from a previous run"
        kill -- "-$old" 2>/dev/null || kill "$old" 2>/dev/null || true
      else
        step "pid $old has been recycled by '$nowcomm' - not killing it"
      fi
    fi
    rm -f "$f"
  done
}

# Polls until the URL answers 200. A refused or timed-out request is "not ready yet", and so is
# any other answer unless ANSWER_IS_FINAL is 1 (the sidecar answers 503 while its model loads).
#
#   wait_for_health NAME URL SOFT PID [HARD] [ANSWER_IS_FINAL] [EVERY]
#
# S12 (live-playtest F-1, backlog R-06): two windows, not one deadline. Up to SOFT seconds it
# polls quietly. After that, a service whose PID is still ALIVE is taken to be loading: the wait
# goes on, printing a "still loading" line every EVERY (default 15) s, up to HARD seconds. It
# gives up early only on evidence: the process exited, or (ANSWER_IS_FINAL=1) the server answered
# something other than 200. HARD defaults to SOFT, which is the old single deadline.
#
# ANSWER_IS_FINAL is for Rasa: it binds :5005 only AFTER loading the model (Sanic runs the
# before_server_start listener that loads it before it listens), and a failed load leaves it
# answering 409 on /status for good. Not for the sidecar: it answers 503 both while loading and
# after a failed load, so a live sidecar is no proof of progress and it keeps one deadline.
#
# Wall clock ($SECONDS), not a loop count: on Windows a refused connect costs ~2 s, so the old
# "180" iterations ran ~10 minutes there. --max-time bounds each request, so a server that
# accepts and never answers cannot hold the loop past HARD.
wait_for_health() {
  local name="$1" url="$2" soft="$3" pid="$4" hard="${5:-$3}" final="${6:-0}" every="${7:-15}"
  local start="$SECONDS" next="$3" elapsed code
  [ "$hard" -ge "$soft" ] || hard="$soft"
  if [ "$hard" -gt "$soft" ]; then
    step "waiting for $name on $url (up to ${hard}s while it is still running)..."
  else
    step "waiting for $name on $url (up to ${soft}s)..."
  fi
  while :; do
    if ! kill -0 "$pid" 2>/dev/null; then
      step "$name exited before becoming healthy - see $LOGDIR/$name.log"
      exit 1
    fi
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then step "$name is up."; return 0; fi
    if [ "$final" = "1" ] && [ -n "$code" ] && [ "$code" != "000" ]; then
      step "$name answered HTTP $code on $url instead of 200 (409 = its model did not load) - see $LOGDIR/$name.log"
      exit 1
    fi
    elapsed=$((SECONDS - start))
    if [ "$elapsed" -ge "$hard" ]; then
      step "$name did not become healthy on $url within ${hard}s - see $LOGDIR/$name.log"
      exit 1
    fi
    if [ "$elapsed" -ge "$next" ]; then
      step "still loading $name... ${elapsed}s elapsed, pid $pid alive (giving up at ${hard}s; log: $LOGDIR/$name.log)"
      next=$((elapsed + every))
    fi
    sleep 1
  done
}

if [ "$NO_SERVICES" -eq 0 ]; then
  # S18b (P13): a skipped service's venv is not needed, and not checked.
  [ "$NO_RASA" -eq 1 ] || [ -e "$RASA" ] || { echo "error: nlp-python/.venv-rasa missing. py -3.10 -m venv nlp-python/.venv-rasa && nlp-python/.venv-rasa/bin/pip install -r nlp-python/requirements-rasa.txt" >&2; exit 1; }
  [ "$NO_LLM" -eq 1 ] || [ -e "$SIDECAR_PY" ] || { echo "error: nlp-python/.venv missing. py -3.11 -m venv nlp-python/.venv && nlp-python/.venv/bin/pip install -r nlp-python/requirements-llm.txt" >&2; exit 1; }
  [ "$NO_RASA" -eq 1 ] || [ -f "$MODEL" ] || { echo "error: no trained Rasa model at $MODEL - run: bash nlp-python/train_rasa.sh" >&2; exit 1; }
fi
[ -f "$GAME/lib/jade-4.6.0.jar" ] || { echo "error: $GAME/lib/jade-4.6.0.jar missing (license: not committed). Download from https://jade.tilab.com/" >&2; exit 1; }

if [ "$NO_SERVICES" -eq 0 ]; then
  reap_stale

  # 1) Rasa, from its OWN 3.10 venv. setsid where available so the whole tree dies together.
  #    S18b (P13): unless --no-rasa.
  if [ "$NO_RASA" -eq 1 ]; then
    step "--no-rasa: skipping Rasa. Only the grammar reads what you type; the game says so."
  else
  step "booting Rasa on :5005 (.venv-rasa)..."
  ( cd "$NLP/rasa" && exec "$RASA" run --enable-api --port 5005 --model models/portcmd_nlu.tar.gz ) \
      > "$LOGDIR/rasa.log" 2>&1 &
  RASA_PID=$!
  STARTED_PIDS+=("$RASA_PID"); echo "$RASA_PID $(ps -p "$RASA_PID" -o comm= 2>/dev/null | tr -d ' ')" > "$PIDDIR/rasa.pid"
  # Cold start loads DIET + the featurizers off a cold page cache, and nobody has measured its
  # tail (backlog R-06). A single deadline failed twice: 120 s on 3 of 4 session-first boots
  # (2026-08-04), then 180 s on the first boot after a reboot (2026-09-21 playtest F-1: still
  # loading the model at +184 s; the warm re-run was up at +54 s). So (S12): quiet for 60 s,
  # then keep waiting - visibly - while the Rasa process is alive and not yet answering, up to
  # 600 s. It still fails fast if Rasa exits, or answers anything but 200 (a model that did not
  # load). A miss aborts the whole launch - the EXIT trap kills the Rasa we just started and the
  # sidecar below never runs.
  wait_for_health rasa "http://localhost:5005/status" 60 "$RASA_PID" 600 1
  fi

  # 2) The Flask LLM sidecar, from the OTHER (3.11) venv, on the fast ONNX-INT4 backend.
  #    LLM_QUANT=onnx is what makes an explanation take seconds instead of ~9 min (BUILD_LOG task
  #    13b), so the demo pins it -- but ONLY when the weights are actually on this machine. How
  #    many seconds (S12, live-playtest F-8): task 13b's "~17 s" was a sidecar-alone smoke with a
  #    short prompt. The production Hint prompt takes ~18 s on a quiet machine and 40 s+ under
  #    background load (measured 2026-09-21/30 and 2026-10-07), so the game shows the plain
  #    template at once and replaces it with the model's validated wording when that arrives.
  #
  #    They are ~2 GB and deliberately live OUTSIDE the repo, so a fresh clone does not have
  #    them. Pinning onnx unconditionally made the sidecar fail to load on such a clone, /health
  #    stay 503 for the full 180 s, and this script then abort and kill Rasa too -- on a machine
  #    whose README correctly says the game runs fine without the LLM. That is the
  #    20-minute-quickstart criterion broken by a fast-path optimisation. Probe first, using the
  #    same single source of truth start_llm uses.
  #    The probe must run FROM "$NLP", exactly as the sidecar itself is launched below
  #    (`cd "$NLP" && ... -m llm_sidecar.server`): `import llm_sidecar` resolves via the current
  #    directory (the package is not pip-installed), so from any other CWD - e.g. the repo root
  #    this script normally runs in - the import dies, 2>/dev/null hides the traceback, the blank
  #    answer read as "no", and the sidecar was silently skipped WITH the weights on disk
  #    (found live 2026-08-04 on the Windows twin; both launchers had it).
  HAVE_ONNX=0
  PROBE_ANSWER=""
  if [ "$NO_LLM" -eq 0 ]; then
    PROBE_ANSWER="$(cd "$NLP" && "$SIDECAR_PY" -c 'import llm_sidecar.config as c; print("yes" if c.onnx_model_available() else "no")' 2>/dev/null || true)"
    if [ "$PROBE_ANSWER" = "yes" ]; then HAVE_ONNX=1; fi
  fi
  if [ "$NO_LLM" -eq 1 ]; then
    step "--no-llm: skipping the sidecar. Assistant explanations use the plain template."
  elif [ "$HAVE_ONNX" -eq 0 ]; then
    if [ "$PROBE_ANSWER" = "no" ]; then
      step "no local ONNX model found -- skipping the LLM sidecar. The game runs fine:"
      step "  Assistant explanations fall back to the plain template (same figures)."
      step "  See docs/TROUBLESHOOTING.md to install the weights and get the fast path."
    else
      step "sidecar probe FAILED (import/venv error, not 'weights absent') -- skipping the sidecar."
      step "  The game still runs (plain-template explanations). Check nlp-python/.venv against requirements-llm.txt."
    fi
  else
    step "booting the Flask LLM sidecar on :5006 (.venv, LLM_QUANT=onnx)..."
    ( cd "$NLP" && LLM_QUANT=onnx exec "$SIDECAR_PY" -m llm_sidecar.server ) \
        > "$LOGDIR/llm-sidecar.log" 2>&1 &
    LLM_PID=$!
    STARTED_PIDS+=("$LLM_PID")
    echo "$LLM_PID $(ps -p "$LLM_PID" -o comm= 2>/dev/null | tr -d ' ')" > "$PIDDIR/llm-sidecar.pid"
    wait_for_health llm-sidecar "http://127.0.0.1:5006/health" 180 "$LLM_PID"
  fi
else
  step "--no-services: assuming Rasa and the sidecar are already running."
fi

GAME_ARGS="--gui"
[ -n "$SCENARIO" ] && GAME_ARGS="$GAME_ARGS --scenario $SCENARIO"
[ "$NO_RASA" -eq 1 ] && GAME_ARGS="$GAME_ARGS --no-rasa"
# -PjadePort, not -Djade.main.port: a -D on the gradlew command line lands on the Gradle DAEMON,
# and the forked application JVM does not inherit it, so the override would be silently ignored.
# build.gradle.kts forwards the project property as a systemProperty.
GRADLE_ARGS=(run "--args=$GAME_ARGS")
[ "$JADE_PORT" != "1099" ] && GRADLE_ARGS+=("-PjadePort=$JADE_PORT")
[ "$PROFILE" -eq 1 ] && GRADLE_ARGS+=(-Pjfr "-Pjfr.seconds=$PROFILE_SECONDS")

step "launching the game: gradlew ${GRADLE_ARGS[*]}"
cd "$GAME"
./gradlew "${GRADLE_ARGS[@]}"
# The EXIT trap stops both services from here.
