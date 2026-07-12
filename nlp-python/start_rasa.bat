@echo off
REM Launch the Port Command Genova Rasa NLU server on port 5005 (Windows).
REM Two-venv layout (task 12): the Rasa service runs on Python 3.10 (.venv-rasa).
setlocal
set "HERE=%~dp0"
set "RASA=%HERE%.venv-rasa\Scripts\rasa.exe"
if not exist "%RASA%" (
  echo error: .venv-rasa missing. Create it:
  echo   py -3.10 -m venv .venv-rasa ^&^& .venv-rasa\Scripts\pip install -r requirements-rasa.txt
  exit /b 1
)
cd /d "%HERE%rasa"
echo Rasa NLU -^> http://localhost:5005  (POST /model/parse). Ctrl+C to stop.
"%RASA%" run --enable-api --port 5005 --model models\portcmd_nlu.tar.gz
