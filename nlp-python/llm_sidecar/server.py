"""Flask sidecar HTTP surface (task 13): ``GET /health`` and ``POST /explain``.

``@app.before_first_request`` was removed in Flask 2.3, so the model is loaded by
a daemon thread kicked off at IMPORT time (the module-init pattern), guarded by
``config.LLM_AUTOLOAD`` so the pytest suite never triggers a real load or
download. ``/health`` reports 503 until warmup completes; the Java LLMBridge
(task 14) polls it and must not block.

Run it with::  python -m llm_sidecar.server   (from the nlp-python/ directory)
"""
from __future__ import annotations

import logging
import threading
import time
from logging.handlers import RotatingFileHandler

from flask import Flask, jsonify, request

from llm_sidecar import config
from llm_sidecar.model import LLMModel
from llm_sidecar.validator import HallucinationValidator, load_known_entities

log = logging.getLogger("llm_sidecar.server")

app = Flask(__name__)

# --- module state ------------------------------------------------------------
model: LLMModel | None = None
_model_lock = threading.Lock()
_model_loading = threading.Event()
_load_error: str | None = None
_load_started = False

# The validator is cheap + stateless — build it eagerly from the ontology vocab.
validator = HallucinationValidator(load_known_entities(config.LLM_VOCAB_PATH))

_logging_configured = False


def configure_logging() -> None:
    """Idempotently attach console (and optional rotating-file) handlers to the
    package logger. Kept off the root logger so it does not fight pytest capture."""
    global _logging_configured
    if _logging_configured:
        return
    logger = logging.getLogger("llm_sidecar")
    logger.setLevel(getattr(logging, config.LLM_LOG_LEVEL, logging.INFO))
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    sh = logging.StreamHandler()
    sh.setFormatter(fmt)
    logger.addHandler(sh)
    if config.LLM_LOG_FILE:
        fh = RotatingFileHandler(config.LLM_LOG_FILE, maxBytes=1_000_000, backupCount=3)
        fh.setFormatter(fmt)
        logger.addHandler(fh)
    logger.propagate = False
    _logging_configured = True


# --- model loading -----------------------------------------------------------
def _ensure_model_loaded() -> None:
    """Load the model once. Safe under concurrent /health probes and re-entry."""
    global model, _load_error
    if model is not None and model.ready:
        return
    with _model_lock:
        if model is not None and model.ready:
            return
        try:
            _model_loading.set()
            _load_error = None
            log.info("loading model %s (key=%s quant=%s device=%s) ...",
                     config.LLM_MODEL, config.LLM_MODEL_KEY, config.LLM_QUANT, config.LLM_DEVICE)
            t0 = time.perf_counter()
            m = LLMModel(
                model_key=config.LLM_MODEL_KEY,
                base_repo_id=config.LLM_MODEL,
                quant=config.LLM_QUANT,
                device=config.LLM_DEVICE,
                max_new_tokens=config.LLM_MAX_NEW_TOKENS,
                gguf_file=config.LLM_GGUF_FILE,
                onnx_dir=config.LLM_ONNX_DIR,
            )
            model = m  # publish only once fully warmed up
            log.info("model ready in %.1fs", time.perf_counter() - t0)
        except Exception as exc:  # noqa: BLE001 — surface load failures via /health
            _load_error = f"{type(exc).__name__}: {exc}"
            log.exception("model load failed")
        finally:
            _model_loading.clear()


def start_background_load() -> threading.Thread:
    """Spawn the daemon loader thread so /health can report progress during load."""
    global _load_started
    _load_started = True
    t = threading.Thread(target=_ensure_model_loaded, name="llm-loader", daemon=True)
    t.start()
    return t


# --- request validation helpers ----------------------------------------------
def _is_str_list(x: object) -> bool:
    return isinstance(x, list) and all(isinstance(i, str) for i in x)


# --- routes ------------------------------------------------------------------
@app.get("/health")
def health():
    ready = model is not None and model.ready
    body = {
        "ready": ready,
        "model": config.LLM_MODEL,
        "model_key": config.LLM_MODEL_KEY,
        "quant": config.LLM_QUANT,
        "device": config.LLM_DEVICE,
        "port": config.LLM_PORT,
        "loading": _model_loading.is_set(),
        "max_new_tokens": config.LLM_MAX_NEW_TOKENS,
    }
    if _load_error and not ready:
        body["error"] = _load_error
    return jsonify(body), (200 if ready else 503)


@app.post("/explain")
def explain():
    if model is None or not model.ready:
        return jsonify({"error": "model not ready", "ready": False}), 503

    data = request.get_json(force=True, silent=True)  # force: tolerate a missing header
    if not isinstance(data, dict):
        return jsonify({"error": "body must be a JSON object"}), 400

    prompt = data.get("prompt")
    if not isinstance(prompt, str) or not prompt.strip():
        return jsonify({"error": "'prompt' must be a non-empty string"}), 400
    if len(prompt) > config.LLM_MAX_PROMPT_CHARS:
        return jsonify({"error": f"'prompt' exceeds {config.LLM_MAX_PROMPT_CHARS} chars"}), 400

    system = data.get("system")
    if system is not None and not isinstance(system, str):
        return jsonify({"error": "'system' must be a string"}), 400

    required_numbers = data.get("required_numbers", [])
    required_entities = data.get("required_entities", [])
    if not _is_str_list(required_numbers) or not _is_str_list(required_entities):
        return jsonify({"error": "'required_numbers'/'required_entities' must be arrays of strings"}), 400

    do_validate = bool(data.get("validate", False))

    t0 = time.perf_counter()
    try:
        text = model.generate(prompt, system=system)
    except Exception as exc:  # noqa: BLE001 — never leak a stack trace to the client
        log.exception("generation failed")
        return jsonify({"error": "generation failed", "detail": type(exc).__name__}), 500
    gen_s = time.perf_counter() - t0

    # validated: True/False when checked, None when the caller did not request it.
    validated: bool | None = None
    if do_validate:
        validated = validator.validate(text, required_numbers, required_entities)

    log.info("/explain prompt_chars=%d gen_chars=%d gen_s=%.2f validate=%s ok=%s",
             len(prompt), len(text), gen_s, do_validate, validated)
    return jsonify({"text": text, "validated": validated, "gen_seconds": round(gen_s, 3)}), 200


def main() -> None:
    configure_logging()
    if not _load_started:  # module-init already started it when LLM_AUTOLOAD=1
        start_background_load()
    log.info("LLM sidecar -> http://%s:%d  (GET /health, POST /explain)",
             config.LLM_HOST, config.LLM_PORT)
    # threaded=True so /health stays responsive while a generate runs (generation
    # itself is serialized by the model's own lock).
    app.run(host=config.LLM_HOST, port=config.LLM_PORT, threaded=True)


# Module-init: kick off the load at import so /health reports progress even when
# served via a WSGI runner that imports `app` without calling main(). Tests set
# LLM_AUTOLOAD=0 to keep import cheap and hermetic.
if config.LLM_AUTOLOAD:
    configure_logging()
    start_background_load()


if __name__ == "__main__":
    main()
