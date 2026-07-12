"""Environment + settings resolution for the Flask LLM sidecar (task 13).

Pure stdlib — importing this module never pulls in torch/transformers, so the
pytest suite (which mocks the model) stays fast and hermetic.

Model choice precedence: env ``LLM_MODEL`` > ``settings.json`` (key ``llm.model``)
> built-in default (``phi-4-mini``). ``settings.json`` is owned by task 15 and is
TOLERATED ABSENT here — a missing or malformed file silently falls back to the
default. This module deliberately does NOT create ``settings.json``.
"""
from __future__ import annotations

import json
import os
import pathlib

# --- model registry ----------------------------------------------------------
# settings.json key -> full HuggingFace repo id (the fp16 base checkpoint).
# model.py maps these to the unsloth quantized variants when LLM_QUANT != none.
MODEL_REGISTRY: dict[str, str] = {
    "phi-4-mini": "microsoft/Phi-4-mini-instruct",
    "gemma-3-4b": "google/gemma-3-4b-it",
}
DEFAULT_MODEL_KEY = "phi-4-mini"

# settings.json lives under the Java resources tree (task 15 creates it). Resolve
# relative to this file so it works regardless of the CWD the sidecar starts in.
_REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
_SETTINGS_PATH = (
    _REPO_ROOT / "port-command-genova" / "src" / "main" / "resources" / "data" / "settings.json"
)


def resolve_model_id(model_key: str) -> str:
    """Map a settings key to its full HF repo id. Raises on an unknown key."""
    try:
        return MODEL_REGISTRY[model_key]
    except KeyError:
        raise ValueError(
            f"unknown LLM model key {model_key!r}; expected one of {sorted(MODEL_REGISTRY)}"
        ) from None


def _model_key_from_settings() -> str:
    try:
        data = json.loads(_SETTINGS_PATH.read_text(encoding="utf-8"))
        key = data.get("llm", {}).get("model")
        return key if key in MODEL_REGISTRY else DEFAULT_MODEL_KEY
    except Exception:  # noqa: BLE001 — absent/malformed settings.json is expected
        return DEFAULT_MODEL_KEY


def _env_flag(name: str, default: str) -> bool:
    return os.getenv(name, default).strip().lower() in {"1", "true", "yes", "on"}


# --- resolved configuration (read once at import) ----------------------------
LLM_MODEL_KEY = os.getenv("LLM_MODEL", _model_key_from_settings())
if LLM_MODEL_KEY not in MODEL_REGISTRY:
    raise ValueError(
        f"unknown LLM_MODEL {LLM_MODEL_KEY!r}; expected one of {sorted(MODEL_REGISTRY)}"
    )
LLM_MODEL = MODEL_REGISTRY[LLM_MODEL_KEY]           # full HF repo id (fp16 base)

LLM_DEVICE = os.getenv("LLM_DEVICE", "cpu")         # "cpu" | "cuda"
LLM_QUANT = os.getenv("LLM_QUANT", "none").lower()  # "none" | "int4" | "gguf" | "onnx"
LLM_HOST = os.getenv("LLM_HOST", "127.0.0.1")       # bind localhost, not 0.0.0.0
LLM_PORT = int(os.getenv("LLM_PORT", "5006"))
LLM_MAX_NEW_TOKENS = int(os.getenv("LLM_MAX_NEW_TOKENS", "120"))
LLM_MAX_PROMPT_CHARS = int(os.getenv("LLM_MAX_PROMPT_CHARS", "4096"))
LLM_LOG_LEVEL = os.getenv("LLM_LOG_LEVEL", "INFO").upper()
LLM_LOG_FILE = os.getenv("LLM_LOG_FILE", "")        # "" => console only
# GGUF quant file glob (llama-cpp-python's from_pretrained picks the match).
LLM_GGUF_FILE = os.getenv("LLM_GGUF_FILE", "*Q4_K_M.gguf")

# ONNX Runtime GenAI CPU-INT4 backend (task 13b — the fast CPU path). Weights live
# OUTSIDE the repo (never committed); downloaded by hand into this default dir.
# LLM_ONNX_DIR overrides. start_llm.{sh,bat} auto-select LLM_QUANT=onnx when a
# local model is present (onnx_model_available()) AND LLM_QUANT is unset, so the
# portable default stays LLM_QUANT=none.
_DEFAULT_ONNX_DIR = (
    pathlib.Path.home() / ".cache" / "port-command-genova"
    / "Phi-4-mini-instruct-onnx" / "cpu_and_mobile" / "cpu-int4-rtn-block-32-acc-level-4"
)
LLM_ONNX_DIR = os.getenv("LLM_ONNX_DIR", str(_DEFAULT_ONNX_DIR))


def onnx_model_available() -> bool:
    """True when a COMPLETE local ONNX model dir is present. Pure filesystem check
    (no heavy import), so the launchers can call it to decide whether to
    auto-select the onnx backend.

    Requires ``genai_config.json`` + ``model.onnx`` AND ~1 GB of weights on disk:
    a marker-file check alone is unsafe because the tiny config/tokenizer files
    land BEFORE the multi-GB ``model.onnx.data`` during download — a partial dir
    would otherwise read as ready and the launcher would boot a broken backend."""
    d = pathlib.Path(LLM_ONNX_DIR)
    if not (d / "genai_config.json").is_file() or not (d / "model.onnx").is_file():
        return False
    try:
        total = sum(p.stat().st_size for p in d.iterdir() if p.is_file())
    except OSError:
        return False
    return total >= 1_000_000_000  # real int4 model ≈ 4.9 GB; a config-only stub ≈ 22 MB


# Auto-start the background model loader at import time. Tests set "0" so that
# importing the server never touches torch or downloads weights.
LLM_AUTOLOAD = _env_flag("LLM_AUTOLOAD", "1")

# ontology_vocab.yml supplies the validator's static known-entity gazetteer.
LLM_VOCAB_PATH = os.getenv(
    "LLM_VOCAB_PATH",
    str(_REPO_ROOT / "nlp-python" / "rasa" / "data" / "ontology_vocab.yml"),
)
