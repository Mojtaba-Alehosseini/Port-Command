# Port Command Genova

A single-player port-management game: a **JADE 4.6.0** multi-agent system gated by
a **SWI-Prolog 10.0.2** rule kernel (in-process via **JPL 7**), with a **Rasa 3.16**
NLU layer and a local **LLM** (Phi-4-mini). Java 21, Gradle (Kotlin DSL), Swing GUI.

This directory is the buildable Gradle module. The authoritative specs live one
level up: `../PROJECT_DEFINITION.md` (the what), `../MASTER_PLAN.md` (the how),
`the design rules` (operating rules).

> **Task 01 status:** scaffolding + a JADE/JPL smoke test only. No game logic,
> GUI, or NLP yet.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **Temurin 21 (LTS)** | The Gradle toolchain pins 21; your PATH `java` may differ — that's fine. |
| Gradle | wrapper (**8.10.2**) | Use `./gradlew` / `gradlew.bat`; never a system Gradle. |
| SWI-Prolog | **10.0.2** | System install. Ships `jpl.jar` + the native JPL bridge. |
| Python | 3.10 / 3.11 | Not needed for task 01; required by later NLP tasks. |

Install SWI-Prolog: Windows MSI from <https://www.swi-prolog.org/Download.html>,
macOS `brew install swi-prolog`, Linux `apt install swi-prolog`.

## One-time setup

1. **Stage the JADE jar.** JADE 4.6.0 is not on Maven Central (license). Download
   `JADE-bin-4.6.0.zip` from <https://jade.tilab.com/>, and copy the `jade.jar`
   inside it to `lib/jade-4.6.0.jar`. The jar is git-ignored — do not commit it.

2. **Point the build at SWI-Prolog and expose the native bridge.** Set
   `SWI_HOME_DIR` to your install root and put its `bin/` on `PATH`, so the OS
   loader can resolve `jpl.dll` and its dependency `libswipl.dll`.

   **Windows (persistent):**
   ```powershell
   [Environment]::SetEnvironmentVariable("SWI_HOME_DIR", "C:\Program Files\swipl", "User")
   # Then add  C:\Program Files\swipl\bin  to PATH via System Properties > Environment Variables.
   ```
   **Linux / macOS:**
   ```bash
   export SWI_HOME_DIR=/usr/lib/swi-prolog          # /opt/homebrew/lib/swipl on macOS
   export LD_LIBRARY_PATH="$SWI_HOME_DIR/lib/x86_64-linux:$LD_LIBRARY_PATH"
   ```

   If `SWI_HOME_DIR` is unset, the build falls back to the platform default
   (`C:/Program Files/swipl`, `/usr/lib/swi-prolog`, or `/opt/homebrew/lib/swipl`).
   The build also injects `SWI_HOME_DIR` + the native dir into the test/run JVM,
   so a stale Gradle daemon environment won't break the bind.

3. **Verify the toolchain resolves** the three native artefacts:
   ```
   ./gradlew printEnv
   ```

## Build & run

```
./gradlew build                         # compile + tests
./gradlew run                           # boots JADE + JPL smoke test, prints success, exits 0
./gradlew test --tests "*SmokeTestIT"   # the JADE + JPL integration test
```

A successful `./gradlew run` logs `SmokeAgent smoke up`, `JPL consult(...) -> true`,
`JPL member(2, [1,2,3]) -> true`, and `=== Smoke test pass ===`.

## Troubleshooting

- **`UnsatisfiedLinkError` / `jpl.dll` not found:** make sure `%SWI_HOME_DIR%\bin`
  is on `PATH`. If you just changed env vars, run `./gradlew --stop` to drop the
  stale daemon, then retry.
- **Toolchain error (no Java 21):** install Temurin 21 (Gradle auto-detects it);
  otherwise set `org.gradle.java.installations.paths` in `gradle.properties`.
- **Port 1099 in use:** a lingering JADE JVM may hold it; kill stray `java` processes.
