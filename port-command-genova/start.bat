@echo off
REM Port Command Genova launcher (task 01: smoke test only).
REM Later tasks will also start the Rasa server and the Flask LLM sidecar.
cd /d "%~dp0"
call gradlew.bat run
