@echo off
REM Port Command Genova - stop Rasa, the LLM sidecar and the game JVM (Windows).
REM The normal path never needs this: start.bat tears both services down when the game exits,
REM including on Ctrl-C. Run this after a hard kill of the launcher window, or when ":5005
REM already in use" / the JADE port-1099 clash NPE appears (docs/TROUBLESHOOTING.md).
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
exit /b %ERRORLEVEL%
