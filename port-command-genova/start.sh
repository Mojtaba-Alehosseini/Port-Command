#!/usr/bin/env bash
# Port Command Genova launcher (task 01: smoke test only).
# Later tasks will also start the Rasa server and the Flask LLM sidecar.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew run
