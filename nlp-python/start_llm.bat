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

REM Auto-select the fast ONNX Runtime GenAI CPU-INT4 backend (task 13b) when a
REM COMPLETE local ONNX model is present and the user has not pinned LLM_QUANT.
REM config.onnx_model_available() is the single source of truth (path + size gate).
if not "%LLM_QUANT%"=="" goto :launch
"%PY%" -c "import llm_sidecar.config as c; print('onnx' if c.onnx_model_available() else 'none')" > "%TEMP%\pc_onnx_backend.txt" 2>nul
set /p AUTO_QUANT=<"%TEMP%\pc_onnx_backend.txt"
del "%TEMP%\pc_onnx_backend.txt" 2>nul
if "%AUTO_QUANT%"=="onnx" (
  set "LLM_QUANT=onnx"
  echo backend: ONNX Runtime GenAI CPU-INT4 ^(local model found^) -- fast path
) else (
  echo backend: fp16/bf16 default ^(no local ONNX model; set LLM_QUANT to override^)
)

:launch
echo LLM sidecar -^> http://127.0.0.1:5006  (GET /health, POST /explain). Ctrl+C to stop.
"%PY%" -m llm_sidecar.server
