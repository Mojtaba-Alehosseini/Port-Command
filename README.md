# Port Command Genova

Academic simulation game for the joint **SDAI + NLP** MSc coursework, Università di Genova,
AY 2025/26. You are the Harbour Master: contracted ships arrive on schedule, walk-in ships haggle
with you in English, tugs bid for escort work in a Contract Net, and a Prolog rule kernel has the
final say on every binding decision.

**JADE 4.6.0** (multi-agent) · **SWI-Prolog 10.0.2 via JPL 7** (30 rules + a DCG grammar) ·
**Rasa OSS 3.6.21** (10 intents) · **Phi-4-mini** in a Flask sidecar · **Swing** · Java 21 · Gradle.

---

## Quickstart (≤ 20 minutes)

**1. Install the prerequisites.**

| Tool | Version | Note |
|---|---|---|
| JDK | **Temurin 21 (LTS)** | The Gradle toolchain pins 21; your PATH `java` may differ. |
| SWI-Prolog | **10.0.2** | Ships `jpl.jar` and the native JPL bridge. |
| Python | **3.10 _and_ 3.11** | Two venvs — see step 3. Never 3.12+. |

Set `SWI_HOME_DIR` to the SWI-Prolog install root and put its `bin/` on `PATH` (Windows needs this
so the OS loader can resolve `jpl.dll`'s dependency `libswipl.dll`).

**2. Stage the JADE jar.** JADE is not on Maven Central (licence). Download `JADE-bin-4.6.0.zip`
from <https://jade.tilab.com/> and copy the `jade.jar` inside it to
`port-command-genova/lib/jade-4.6.0.jar`. It is git-ignored — do not commit it.

**3. Create the two Python venvs.** They are not interchangeable: rasa 3.6.21 declares
`Requires-Python <3.11`, and the ONNX runtime the LLM sidecar uses is installed only in the 3.11 one.

```bash
py -3.10 -m venv nlp-python/.venv-rasa && nlp-python/.venv-rasa/Scripts/pip install -r nlp-python/requirements-rasa.txt
```

```bash
py -3.11 -m venv nlp-python/.venv && nlp-python/.venv/Scripts/pip install -r nlp-python/requirements-llm.txt
```

**4. Train the Rasa model** (one-off, ~3 minutes on CPU; the model is git-ignored).

```bash
bash nlp-python/train_rasa.sh
```

**5. Run it.** From the repo root — this boots Rasa on :5005, then the LLM sidecar on :5006, then
the game, health-checking between each, and stops all three when you close the window or press
Ctrl-C.

**Windows:**

```bash
start.bat
```

**Linux / macOS / Git Bash:**

```bash
./start.sh
```

**6. Start the tutorial.** The window opens on the **New Game** dialog, and no game runs until you
choose one there: pick **Tutorial — Calm Morning**, press **Start**, and follow the five steps (or
launch straight into it with `start.bat -Scenario tutorial` / `./start.sh --scenario tutorial`).
The dialog's fourth choice, **Free play**, is the open-ended sandbox. Cancel leaves an empty
window; **Game → New Game…** or **Load Game…** starts a game from there. The first boot after a
reboot can spend a few minutes loading Rasa's model; the console says `still loading rasa...`
meanwhile.

**The window.** Three columns of text under the top bar — there is no map. Left: **Waiting for
you**, the vessels negotiating with you, the one closest to leaving first (click a line to open
its chat tab), and the **Port** table — who holds each berth and until when, the berth queue, the
tugs and the weather. Middle: one chat tab per walk-in, with both sides' offers above the
conversation, a grey example of what you can type in the empty input, and **Send / Hint / Accept /
Reject**; a small `(DCG)` or `(Rasa 0.97)` after each of your messages shows which parser read it.
Right: the **comm log**, every message the HarbourMaster sent or received, with the filters
**All / This vessel / Rules / Weather**, a **Raw** switch that shows the real FIPA fields, and
clickable rule ids such as `[R7]` that show the rule in plain words. The column borders drag.

**Pace.** A Free play game day is 60 real minutes at ×1; switch to ×2 or ×4 from the top bar or
with Ctrl+1 / Ctrl+2 / Ctrl+4, and set the day length itself (5 minutes to 2 hours) in Settings. A
walk-in waits for your answer in real seconds — its chat tab and its line under *Waiting for you*
show a live counter that no speed setting changes — and **Esc** pauses the game but leaves all
three columns usable, so you can stop the clock, read, think and type.

**Optional — the LLM.** The game runs fine without it, and the launcher checks: if the weights are
not on this machine it says so and starts without the sidecar, and Assistant explanations fall back
to a plain template carrying the same figures. To get the model's English instead, download the
Phi-4-mini ONNX CPU-INT4 weights into
`%USERPROFILE%\.cache\port-command-genova\Phi-4-mini-instruct-onnx\` — the launcher then detects
them and pins `LLM_QUANT=onnx` (an explanation takes ~20–45 s on a laptop CPU, so Hint shows the
plain template at once and swaps in the model's validated wording when it arrives; the fp16 path is
~9 minutes and unusable).

**Stuck?** → [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md). It covers the two-venv split,
JPL's `libswipl` PATH requirement, the JADE port-1099 clash, the Rasa cold start, and the sidecar's
real memory levers.

---

## Other ways to run it

```bash
./start.sh --scenario storm      # boot straight into a scenario: tutorial | busy_day | storm
```

```bash
./profile.sh                     # same, plus JVM Flight Recorder -> logs/profile.jfr
```

```bash
cd port-command-genova && ./gradlew run --args="--smoke"
```

`--smoke` plays all three scenarios headlessly and checks the one criterion no single test can
reach: that all 10 FIPA performatives appear across them combined. It prints a per-scenario table
and exits non-zero if any is missing.

```bash
./stop.sh                        # escape hatch if a launcher window was killed hard
```

---

## Architecture in one paragraph

Ten long-lived agents (HarbourMaster, two Terminals, four Tugs, Customs, Weather, Assistant) plus one
short-lived agent per vessel run in a single JADE Main Container. The **HarbourMaster is the hub and
the player's proxy — there is no `PlayerAgent`**; every binding decision it makes queries Prolog
first, and its own `send`/`receive` are the comm log's single instrumentation point. Prolog is a
**library, never an agent**: 30 rules across 5 modules (compatibility, escort, weather, customs,
priority) consulted through JPL from inside behaviours, plus a DCG grammar that parses negotiation
moves. Typed English goes DCG-first; what the grammar refuses falls to Rasa, and what Rasa is not
confident about becomes a clarification rather than a guess. The Swing GUI never touches an agent
directly — it publishes and subscribes on an `EventBus`.

## Docs

| | |
|---|---|
| [`PROJECT_DEFINITION.md`](PROJECT_DEFINITION.md) | **The contract.** What is built, and the § 13 acceptance criteria. |
| [`MASTER_PLAN.md`](MASTER_PLAN.md) | The roadmap and task dependencies. |
| [`INVARIANTS.md`](INVARIANTS.md) | The rules a change must not break (counts, ownership, threading). |
| [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) | The 10-minute oral demo, beat by beat. |
| [`docs/DEMO_CHECKLIST.md`](docs/DEMO_CHECKLIST.md) | The 10-row readiness walkthrough, with evidence per row. |
| [`docs/testing.md`](docs/testing.md) | How to run the suite; what every NLP golden file measures. |
| [`docs/PERFORMANCE_BASELINE.md`](docs/PERFORMANCE_BASELINE.md) | Heap, GC, message rates — the regression baseline. |
| [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Symptom → fix. |
| [`docs/BUGS_FOUND.md`](docs/BUGS_FOUND.md) | The task-26 bug bash, triaged. |
| [`docs/POST_DEMO_BACKLOG.md`](docs/POST_DEMO_BACKLOG.md) | Everything deliberately not fixed, with reasons. |
| [`docs/measurements/`](docs/measurements/README.md) | Committed copies of the suite reports every number above is read from. |
| [`port-command-genova/README.md`](port-command-genova/README.md) | Build-level detail for the Gradle module. |
| [`nlp-python/README.md`](nlp-python/README.md) | The Rasa corpus, the converter, and the sidecar. |
| `job1_*.md` … `job5_*.md` (in `_archive/`) | Historical design rationale — context, not authority. |

## Tests

```bash
cd port-command-genova && ./gradlew check
```

Both lanes plus the JaCoCo gate (≥ 60 % line coverage outside `gui.**`); budget ~30 minutes with the
NLP services up, ~15 without. The Python side:

```bash
nlp-python/.venv/Scripts/python -m pytest nlp-python
```

The three NLP regression suites **skip** rather than fail when their service is down, so `check` is
green on a machine without them. Run it once with Rasa and the sidecar up before trusting the NLP
numbers — a skipped test proves nothing. Details in [`docs/testing.md`](docs/testing.md).
