@echo off
REM Port Command Genova - demo launcher (Windows). Boots Rasa (:5005, .venv-rasa) then the
REM Flask LLM sidecar (:5006, .venv, ONNX-INT4) then the game, health-checking between each,
REM and stops both services when the game exits - including on Ctrl-C.
REM
REM The real work is in start.ps1. This file exists because plain cmd.exe cannot do the one
REM thing that matters here: on Ctrl-C it asks "Terminate batch job (Y/N)?" and, on Y, skips
REM every remaining line - so a cmd-only teardown block leaks two servers holding :5005 and
REM :5006, and the next launch then talks to the OLD ones. PowerShell's try/finally runs.
REM
REM   start.bat                          tutorial/sandbox via the New Game dialog
REM   start.bat -Scenario busy_day       boot straight into a scenario
REM   start.bat -Profile                 record logs/profile.jfr for 15 minutes
REM   start.bat -NoServices              game only (services already running)
REM   start.bat -NoLlm                   skip the sidecar (plain-template explanations)
REM   start.bat -JadePort 11099          if something else holds RMI port 1099
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
exit /b %ERRORLEVEL%
