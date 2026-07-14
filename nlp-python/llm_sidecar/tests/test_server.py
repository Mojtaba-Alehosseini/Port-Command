"""Pytest for the Flask sidecar routes (task 13).

The real LLM is NEVER loaded here: LLM_AUTOLOAD=0 disables the import-time loader
and a MockLLMModel is injected into ``server.model``. The tests drive the ACTUAL
Flask routes (503-before-ready, 400 overlong/bad-body, the validated=false path,
500 on a generation error), not the mock in isolation.

Run:  nlp-python/.venv/Scripts/python -m pytest nlp-python/llm_sidecar/tests -q
"""
from __future__ import annotations

import os

# Must be set BEFORE importing config/server (config reads env once at import).
os.environ["LLM_AUTOLOAD"] = "0"
os.environ.setdefault("LLM_MODEL", "phi-4-mini")

import pytest  # noqa: E402

from llm_sidecar import config, server  # noqa: E402
from llm_sidecar.model import resolve_load_plan, _pad_token_id  # noqa: E402


class MockLLMModel:
    """Stands in for LLMModel — returns canned text instantly, records calls,
    and can be told to be not-ready or to raise on generate()."""

    def __init__(self, text: str = "Recommend accept at 2000 euros.", ready: bool = True,
                 raises: bool = False):
        self.text = text
        self.ready = ready
        self.raises = raises
        self.calls: list[tuple[str, str | None]] = []

    def generate(self, prompt: str, system: str | None = None) -> str:
        self.calls.append((prompt, system))
        if self.raises:
            raise RuntimeError("boom")
        return self.text


@pytest.fixture
def client():
    return server.app.test_client()


@pytest.fixture(autouse=True)
def _reset_model():
    """Each test starts with no model; restore afterwards."""
    server.model = None
    server._load_error = None
    server._model_loading.clear()
    yield
    server.model = None


def _install(mock: MockLLMModel):
    server.model = mock


# --- /health -----------------------------------------------------------------
def test_health_503_before_ready(client):
    r = client.get("/health")
    assert r.status_code == 503
    body = r.get_json()
    assert body["ready"] is False
    assert body["model"] == "microsoft/Phi-4-mini-instruct"
    assert body["quant"] == config.LLM_QUANT


def test_health_503_when_model_present_but_not_ready(client):
    _install(MockLLMModel(ready=False))
    r = client.get("/health")
    assert r.status_code == 503
    assert r.get_json()["ready"] is False


def test_health_200_after_ready(client):
    _install(MockLLMModel(ready=True))
    r = client.get("/health")
    assert r.status_code == 200
    assert r.get_json()["ready"] is True


def test_health_reports_load_error(client):
    server._load_error = "RuntimeError: disk full"
    r = client.get("/health")
    assert r.status_code == 503
    assert "disk full" in r.get_json()["error"]


# --- /explain: not ready -----------------------------------------------------
def test_explain_503_when_not_ready(client):
    r = client.post("/explain", json={"prompt": "Say hi"})
    assert r.status_code == 503
    assert r.get_json()["ready"] is False


# --- /explain: happy path ----------------------------------------------------
def test_explain_returns_canned_text(client):
    mock = MockLLMModel(text="Recommend accept at 2000 euros.")
    _install(mock)
    r = client.post("/explain", json={"prompt": "Explain the deal"})
    assert r.status_code == 200
    body = r.get_json()
    assert body["text"] == "Recommend accept at 2000 euros."
    assert body["validated"] is None            # validation not requested
    assert "gen_seconds" in body
    assert mock.calls == [("Explain the deal", None)]


def test_explain_passes_system_through(client):
    mock = MockLLMModel()
    _install(mock)
    r = client.post("/explain", json={"prompt": "u", "system": "s"})
    assert r.status_code == 200
    assert mock.calls == [("u", "s")]


# --- /explain: validation wiring (exercises the real validator) --------------
def test_explain_validated_true_when_output_clean(client):
    _install(MockLLMModel(text="Recommend accept at 2000 euros; berth 3 is clear."))
    r = client.post("/explain", json={
        "prompt": "x", "required_numbers": ["2000", "3"], "validate": True,
    })
    assert r.status_code == 200
    assert r.get_json()["validated"] is True


def test_explain_validated_false_on_missing_number(client):
    # output lacks the required 9999 -> validator returns false, but 200 + text
    # still returned so the Java caller can decide (planning/13 step 13.4).
    _install(MockLLMModel(text="Recommend accept at 2000 euros."))
    r = client.post("/explain", json={
        "prompt": "x", "required_numbers": ["9999"], "validate": True,
    })
    assert r.status_code == 200
    body = r.get_json()
    assert body["validated"] is False
    assert body["text"] == "Recommend accept at 2000 euros."


def test_explain_validated_false_on_fabricated_entity(client):
    _install(MockLLMModel(text="The tanker Poseidon should dock now."))
    r = client.post("/explain", json={
        "prompt": "x", "required_entities": [], "validate": True,
    })
    assert r.status_code == 200
    assert r.get_json()["validated"] is False


# --- /explain: request validation (400) --------------------------------------
def test_explain_400_on_missing_prompt(client):
    _install(MockLLMModel())
    r = client.post("/explain", json={"required_numbers": ["1"]})
    assert r.status_code == 400


def test_explain_400_on_empty_prompt(client):
    _install(MockLLMModel())
    assert client.post("/explain", json={"prompt": "   "}).status_code == 400


def test_explain_400_on_overlong_prompt(client):
    _install(MockLLMModel())
    big = "a" * (config.LLM_MAX_PROMPT_CHARS + 1)
    assert client.post("/explain", json={"prompt": big}).status_code == 400


def test_explain_400_on_non_object_body(client):
    _install(MockLLMModel())
    r = client.post("/explain", data="[1,2,3]", content_type="application/json")
    assert r.status_code == 400


def test_explain_400_on_bad_field_types(client):
    _install(MockLLMModel())
    r = client.post("/explain", json={"prompt": "x", "required_numbers": [1, 2]})
    assert r.status_code == 400  # numbers must be an array of *strings*


# --- /explain: generation error (500) ----------------------------------------
def test_explain_500_on_generation_error(client):
    _install(MockLLMModel(raises=True))
    r = client.post("/explain", json={"prompt": "x"})
    assert r.status_code == 500
    body = r.get_json()
    assert body["error"] == "generation failed"
    assert body["detail"] == "RuntimeError"   # sanitized: type only, no stack trace


# --- model resolution / branch selection (no weights loaded) -----------------
def test_both_models_resolvable():
    assert config.resolve_model_id("phi-4-mini") == "microsoft/Phi-4-mini-instruct"
    assert config.resolve_model_id("gemma-3-4b") == "google/gemma-3-4b-it"
    with pytest.raises(ValueError):
        config.resolve_model_id("llama-3")


def test_load_plan_branches_without_loading():
    phi = resolve_load_plan("phi-4-mini", "none", "cpu", "microsoft/Phi-4-mini-instruct")
    assert phi.backend == "causal_lm" and phi.trust_remote_code is True and phi.multimodal is False
    gem = resolve_load_plan("gemma-3-4b", "none", "cpu", "google/gemma-3-4b-it")
    assert gem.backend == "multimodal" and gem.multimodal is True
    assert resolve_load_plan("phi-4-mini", "int4", "cuda", "x").repo_id == "unsloth/Phi-4-mini-instruct-bnb-4bit"
    assert resolve_load_plan("phi-4-mini", "gguf", "cpu", "x").backend == "gguf"
    assert resolve_load_plan("phi-4-mini", "onnx", "cpu", "x", onnx_dir="/m").backend == "onnx"


def test_pad_token_id_from_tokenizer_processor_and_eos_fallback():
    class Tok:            # bare tokenizer with a pad id
        pad_token_id, eos_token_id = 7, 2

    class Proc:          # AutoProcessor nesting a tokenizer, no top-level pad id
        tokenizer = Tok()

    class NoPad:         # pad missing -> fall back to eos
        pad_token_id, eos_token_id = None, 9

    assert _pad_token_id(Tok()) == 7
    assert _pad_token_id(Proc()) == 7      # reads through .tokenizer
    assert _pad_token_id(NoPad()) == 9     # eos fallback
