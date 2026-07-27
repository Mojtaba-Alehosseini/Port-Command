@echo off
REM Superseded by the repo-root ..\start.bat (task 26, 2026-07-27).
REM
REM This script used to start the LLM sidecar and the game, but NOT Rasa — a launch through it
REM left the NLU cascade without its middle stage, so every sentence the DCG could not parse
REM fell to a clarification. Rather than leave a partial launcher next to a complete one, it
REM delegates to the root one, which boots Rasa (:5005, .venv-rasa) then the sidecar (:5006,
REM .venv, ONNX-INT4) then the game, and stops all three on Ctrl-C.
setlocal
call "%~dp0..\start.bat" %*
exit /b %ERRORLEVEL%
