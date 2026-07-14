@echo off
REM Port Command Genova launcher.
REM Starts the Flask LLM sidecar (task 13) in the BACKGROUND (a new window) — its
REM model loads while the game boots; /health returns 503 until ready, so the game
REM never blocks on it. Then runs the game. Rasa is started separately via
REM nlp-python\start_rasa.bat.
setlocal
cd /d "%~dp0"
set "LLM=%~dp0..\nlp-python\start_llm.bat"
if exist "%LLM%" (
  echo Starting LLM sidecar -^> http://127.0.0.1:5006 (loads model in background)...
  start "port-command-llm-sidecar" /d "%~dp0..\nlp-python" cmd /c start_llm.bat
) else (
  echo note: LLM sidecar launcher not found - explanations fall back to template text.
)
call gradlew.bat run
