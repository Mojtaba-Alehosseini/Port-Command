"""Pytest for the ONNX Runtime GenAI backend (task 13b).

HERMETIC: no real ``onnxruntime_genai`` load, no weights. Fake og.Model /
Tokenizer / Generator objects exercise the greedy loop, prompt-slicing,
eos-stripping, the ``max_length`` budget, and the ``apply_chat_template`` →
manual-Phi-4 fallback. The pure branch logic (``resolve_load_plan`` onnx path) is
covered here too. Nothing in this file imports torch / transformers / ORT-GenAI.

Run:  nlp-python/.venv/Scripts/python -m pytest nlp-python/llm_sidecar/tests -q
"""
from __future__ import annotations

import os
import threading

os.environ["LLM_AUTOLOAD"] = "0"  # belt-and-braces; this file never imports server

import numpy as np  # noqa: E402
import pytest  # noqa: E402

from llm_sidecar import model as m  # noqa: E402


# --- pure branch logic (no objects at all) -----------------------------------
def test_onnx_plan_is_phi4_only_and_carries_local_dir():
    p = m.resolve_load_plan("phi-4-mini", "onnx", "cpu",
                            "microsoft/Phi-4-mini-instruct", onnx_dir="/models/onnx")
    assert p.backend == "onnx"
    assert p.repo_id == "/models/onnx"          # a LOCAL dir, not an HF repo id
    assert p.multimodal is False
    # the ONNX repo hosts only Phi-4 — refuse onnx for any other model
    with pytest.raises(ValueError):
        m.resolve_load_plan("gemma-3-4b", "onnx", "cpu", "google/gemma-3-4b-it",
                            onnx_dir="/x")
    # onnx loads a LOCAL dir — refuse to silently fall back to an HF id
    with pytest.raises(ValueError):
        m.resolve_load_plan("phi-4-mini", "onnx", "cpu", "microsoft/Phi-4-mini-instruct")


def test_phi4_prompt_format():
    s = m._phi4_prompt([{"role": "system", "content": "S"},
                        {"role": "user", "content": "U"}])
    assert s == "<|system|>S<|end|><|user|>U<|end|><|assistant|>"


# --- fake ORT-GenAI objects --------------------------------------------------
class _FakeParams:
    def __init__(self):
        self.opts: dict = {}

    def set_search_options(self, **kw):
        self.opts.update(kw)


class _FakeGen:
    """Emits ``new_tokens`` in one shot; get_sequence returns prompt + new."""

    def __init__(self, new_tokens):
        self._new = list(new_tokens)
        self._i = 0
        self._appended: list[int] = []

    def append_tokens(self, ids):
        self._appended = [int(x) for x in ids]

    def is_done(self):
        return self._i >= len(self._new)

    def generate_next_token(self):
        self._i += 1

    def get_sequence(self, idx):
        return np.array(self._appended + self._new, dtype=np.int32)


class _FakeOg:
    def __init__(self, new_tokens):
        self._new = new_tokens
        self.last_params: _FakeParams | None = None

    def GeneratorParams(self, model):
        self.last_params = _FakeParams()
        return self.last_params

    def Generator(self, model, params):
        return _FakeGen(self._new)


class _FakeTok:
    """Fake ORT-GenAI Tokenizer: 3 prompt tokens; echoes ids on decode."""

    def __init__(self, raise_on_template=False, template_returns_ids=False):
        self.raise_on_template = raise_on_template
        self.template_returns_ids = template_returns_ids
        self.captured: dict = {}

    def apply_chat_template(self, messages_json, *, add_generation_prompt):
        self.captured["messages_json"] = messages_json
        self.captured["agp"] = add_generation_prompt
        if self.raise_on_template:
            raise RuntimeError("no embedded template")
        if self.template_returns_ids:
            return np.array([10, 11, 12], dtype=np.int32)
        return "TEMPLATED"

    def encode(self, s):
        self.captured["encoded"] = s
        return np.array([10, 11, 12], dtype=np.int32)  # 3 prompt tokens

    def decode(self, ids):
        return "reply:" + ",".join(str(int(x)) for x in ids)


def _onnx_model(og, tok, *, eos, max_new_tokens=120):
    """Wire an LLMModel to the onnx backend WITHOUT running _load (no real
    onnxruntime_genai, no weights) — __new__ then set only the fields _run_onnx uses."""
    mdl = m.LLMModel.__new__(m.LLMModel)
    mdl.plan = m.resolve_load_plan("phi-4-mini", "onnx", "cpu",
                                   "microsoft/Phi-4-mini-instruct", onnx_dir="/x")
    mdl.max_new_tokens = max_new_tokens
    mdl._lock = threading.Lock()
    mdl._eos_ids = set(eos)
    mdl._og = og
    mdl._og_model = object()
    mdl._og_tok = tok
    mdl.ready = True
    return mdl


def test_run_onnx_slices_prompt_strips_eos_and_honours_max_length():
    og = _FakeOg(new_tokens=[100, 101, 200020])   # two real tokens + eos
    tok = _FakeTok()
    mdl = _onnx_model(og, tok, eos={199999, 200020}, max_new_tokens=120)
    out = mdl.generate("Explain the deal", system="Sys")
    # prompt [10,11,12] sliced off; eos 200020 dropped -> decode([100,101])
    assert out == "reply:100,101"
    # max_length is the TOTAL budget = input_len(3) + max_new_tokens(120); greedy
    assert og.last_params.opts["max_length"] == 123
    assert og.last_params.opts["do_sample"] is False
    # messages carried the system turn + opened the assistant turn
    assert tok.captured["agp"] is True
    assert '"role": "system"' in tok.captured["messages_json"]


def test_run_onnx_is_deterministic_same_input_same_output():
    out1 = _onnx_model(_FakeOg([100, 101]), _FakeTok(), eos=set()).generate("x")
    out2 = _onnx_model(_FakeOg([100, 101]), _FakeTok(), eos=set()).generate("x")
    assert out1 == out2 == "reply:100,101"


def test_run_onnx_falls_back_to_phi4_template_on_apply_error():
    tok = _FakeTok(raise_on_template=True)
    _onnx_model(_FakeOg([100]), tok, eos=set()).generate("Hello", system="Sys")
    # the fallback built the manual Phi-4 string and passed it to encode()
    assert tok.captured["encoded"] == "<|system|>Sys<|end|><|user|>Hello<|end|><|assistant|>"


def test_run_onnx_accepts_tokenizer_that_returns_ids_directly():
    tok = _FakeTok(template_returns_ids=True)
    out = _onnx_model(_FakeOg([100, 101]), tok, eos=set()).generate("x")
    assert "encoded" not in tok.captured   # apply_chat_template returned ids -> no encode()
    assert out == "reply:100,101"


def test_run_onnx_empty_generation_returns_empty_string():
    # model emits only eos -> nothing survives the slice
    assert _onnx_model(_FakeOg([200020]), _FakeTok(), eos={200020}).generate("x") == ""
