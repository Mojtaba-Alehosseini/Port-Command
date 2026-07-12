@echo off
REM Launch the Port Command Genova Flask LLM sidecar on port 5006 (Windows).
REM Two-venv layout (task 12/13): the sidecar runs on Python 3.11 (.venv). /health
REM returns 503 until the model finishes loading (30-90 s on CPU at fp16).
setlocal
set "HERE=%~dp0"
set "PY=%HERE%.venv\Scripts\python.exe"
if not exist "%PY%" (
  echo error: .venv missing. Create it:
  echo   py -3.11 -m venv .venv ^&^& .venv\Scripts\pip install -r requirements-llm.txt
  exit /b 1
)
cd /d "%HERE%"
echo LLM sidecar -^> http://127.0.0.1:5006  (GET /health, POST /explain). Ctrl+C to stop.
"%PY%" -m llm_sidecar.server
