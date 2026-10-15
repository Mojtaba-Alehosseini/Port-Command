## Individual Project for the Symbolic and Distributed AI and Natural Language Processing courses, University of Genova, IT

## Author: Mojtaba Alehosseini

### Teacher: Viviana Mascardi

### Project delivered on October 2026

# Port Command Genova

A single-player strategy game: you are the Harbour Master of the port of Genova. Contracted ships
arrive on schedule, walk-in ships haggle with you in English, tugs bid for escort work in a Contract
Net, and a Prolog rule kernel has the final say on every binding decision.

The project has two parts:

- **A multi-agent part (Symbolic and Distributed AI):** a JADE 4.6.0 multi-agent system whose agents
  talk with FIPA-ACL messages, gated by 30 SWI-Prolog rules (via JPL 7).
- **A natural language part (NLP):** a Prolog DCG grammar for negotiation moves, a Rasa OSS 3.6.21
  intent classifier as fallback, an OWL ontology shared by Prolog and Rasa, and a small local language
  model (Phi-4-mini) that explains the Assistant's advice, checked by a hallucination validator.

Java 21 · Gradle · Swing GUI.

---

## Quickstart

**1. Install the prerequisites.**

| Tool | Version | Note |
|---|---|---|
| JDK | **Temurin 21 (LTS)** | The Gradle toolchain pins 21; your PATH `java` may differ. |
| SWI-Prolog | **10.0.2** | Ships `jpl.jar` and the native JPL bridge. |
| Python | **3.10 _and_ 3.11** | Two venvs, see step 3. Never 3.12+. |

Set `SWI_HOME_DIR` to the SWI-Prolog install root and put its `bin/` on `PATH` (Windows needs this
so the OS loader can resolve `jpl.dll`'s dependency `libswipl.dll`).

**2. Stage the JADE jar.** JADE is not on Maven Central (licence). Download `JADE-bin-4.6.0.zip`
from <https://jade.tilab.com/> and copy the `jade.jar` inside it to
`port-command-genova/lib/jade-4.6.0.jar`. It is git-ignored; do not commit it.

**3. Create the two Python venvs.** They are not interchangeable: rasa 3.6.21 declares
`Requires-Python <3.11`, and the ONNX runtime the LLM sidecar uses is installed only in the 3.11 one.

```bash
py -3.10 -m venv nlp-python/.venv-rasa && nlp-python/.venv-rasa/Scripts/pip install -r nlp-python/requirements-rasa.txt
```

```bash
py -3.11 -m venv nlp-python/.venv && nlp-python/.venv/Scripts/pip install -r nlp-python/requirements-llm.txt
```

**4. Train the Rasa model** (one-off, about 3 minutes on CPU; the model is git-ignored).

```bash
bash nlp-python/train_rasa.sh
```

**5. Run it.** From the repo root. This boots Rasa on :5005, then the LLM sidecar on :5006, then
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

**6. Start the tutorial.** The window opens on the **New Game** dialog: pick **Tutorial - Calm
Morning**, press **Start**, and follow the five steps (or launch straight into it with
`start.bat -Scenario tutorial` / `./start.sh --scenario tutorial`). **Free play** is the open-ended
game. The first boot after a reboot can spend a few minutes loading Rasa's model; the console says
`still loading rasa...` meanwhile.

**The window.** Three columns of text under the top bar.

- **Left:** *Waiting for you* (the vessels negotiating with you, the one closest to leaving first)
  and the **Port** table: who holds each berth and until when, the berth queue and the tugs. A ship
  that has a berth but is not moving yet says why, with the rule that decided it, for example
  `waiting: customs inspection [R22,R24]` or `held (weather) [R15,R19]`.
- **Middle:** one chat tab per walk-in ship, with both sides' offers and **Send / Hint / Accept /
  Reject**. A small `(DCG)` or `(Rasa 0.97)` after each of your messages shows which parser read it.
  **Hint** asks the Assistant for advice, with the expected value and the rules behind it. With
  **autopilot** on (Settings) the Assistant negotiates for you, and **Take over** gives the tab back.
- **Right:** the **comm log**, every FIPA message the HarbourMaster sent or received, with filters
  and clickable rule ids such as `[R7]` that show the rule in plain words.

**The day and the score.** The top bar shows the day's profit against the target and a score out of
100 (four parts of 25: money, walk-ins handled, safety and reputation). At midnight a window shows
the day's report. A scenario is one day long; Free play runs for up to 30 days.

**Pace.** A Free play day is 60 real minutes at x1; switch to x2 or x4 from the top bar or with
Ctrl+1 / Ctrl+2 / Ctrl+4, and set the day length (5 minutes to 2 hours) in Settings. **Esc** pauses
the game.

**Optional: the LLM.** The game runs without it: if the weights are not on this machine the
launcher says so, starts without the sidecar, and explanations fall back to a plain template with the
same figures. To use the model, download the Phi-4-mini ONNX CPU-INT4 weights into
`%USERPROFILE%\.cache\port-command-genova\Phi-4-mini-instruct-onnx\`; the launcher detects them.

---

## Other ways to run it

```bash
./start.sh --scenario storm      # boot straight into a scenario: tutorial | busy_day | storm
```

```bash
cd port-command-genova && ./gradlew run --args="--smoke"
```

`--smoke` plays all three scenarios headlessly and checks that all 10 FIPA performatives appear
across them. It prints a per-scenario table and exits non-zero if any is missing.

```bash
./stop.sh                        # stops Rasa and the sidecar if a launcher window was killed hard
```

---

## Architecture in one paragraph

Ten long-lived agents (HarbourMaster, two Terminals, four Tugs, Customs, Weather, Assistant) plus one
short-lived agent per vessel run in a single JADE Main Container. The **HarbourMaster is the hub and
the player's proxy**; every binding decision it makes queries Prolog first. Prolog is a **library,
not an agent**: 30 rules across 5 modules (compatibility, escort, weather, customs, priority),
consulted through JPL from inside behaviours, plus a DCG grammar that parses negotiation moves.
Typed English goes to the DCG first; what the grammar refuses goes to Rasa, and what Rasa is not
confident about becomes a clarification question rather than a guess. The Swing GUI never touches an
agent directly; it publishes and subscribes on an `EventBus`.

## Repository layout

| Path | Content |
|---|---|
| `port-command-genova/` | The Java/Gradle game: agents, behaviours, Prolog rules and DCG grammar, ontology, GUI, tests. See its [README](port-command-genova/README.md). |
| `nlp-python/` | The Rasa project, the OWL-to-Prolog/Rasa converter, and the LLM sidecar. See its [README](nlp-python/README.md). |
| `references.md` | The source behind each of the 30 Prolog rules (R1-R30). |
| `start.*` / `stop.*` | Launchers for Windows (`.bat`, `.ps1`) and Linux/macOS (`.sh`). |

## Troubleshooting

- **`UnsatisfiedLinkError` / `jpl.dll` not found:** `%SWI_HOME_DIR%\bin` must be on `PATH`. After
  changing environment variables run `./gradlew --stop` and retry.
- **Rasa fails to install:** it needs the Python **3.10** venv (`.venv-rasa`), not the 3.11 one.
- **Port 1099 in use:** a JADE JVM from an earlier run is still alive; close stray `java` processes.
- **Rasa is slow on the first start:** loading the model can take a few minutes after a reboot.

## Tests

```bash
cd port-command-genova && ./gradlew check
```

```bash
nlp-python/.venv/Scripts/python -m pytest nlp-python
```

The NLP regression suites **skip** rather than fail when their service is down, so run them once
with Rasa and the sidecar up before trusting the NLP numbers.
