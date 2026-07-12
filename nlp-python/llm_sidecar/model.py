"""LLM loader + deterministic generation for the sidecar (task 13).

Hosts EITHER ``microsoft/Phi-4-mini-instruct`` (default, a causal LM) OR
``google/gemma-3-4b-it`` (alternative, a multimodal checkpoint) depending on the
resolved model key. The two models do NOT share a chat format, so we NEVER
hard-code turn tags — every prompt goes through the loaded model's own
``apply_chat_template`` (Phi-4: ``<|system|>…<|assistant|>``; Gemma-3:
``<start_of_turn>…<end_of_turn>``).

Determinism: generation uses ``do_sample=False`` (greedy) and passes NO
``temperature``/``top_p`` — with greedy decoding those are inert and transformers
warns about them (planning/13 "common mistakes").

Import policy: torch / transformers / llama_cpp / onnxruntime_genai are imported
LAZILY inside the loader methods, so ``import llm_sidecar.model`` is cheap and
dependency-free. The pytest suite mocks ``LLMModel`` and never triggers a real
load or download.

Backends (``LLM_QUANT``): ``none`` fp16/bf16 + ``int4`` bitsandbytes + ``gguf``
llama.cpp (task 13), plus ``onnx`` — ONNX Runtime GenAI CPU-INT4, the FAST CPU
path added in task 13b (Phi-4-only; see ``resolve_load_plan``).
"""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass

log = logging.getLogger("llm_sidecar.model")

# unsloth community variants for the quantized fallbacks (all verified to exist on
# the Hub 2026-07-12). We deliberately use these bnb/GGUF checkpoints and do NOT
# substitute other INT4 repos (e.g. a torchao ``pytorch/Phi-4-mini-instruct-INT4``)
# without re-verifying loader compatibility — planning/13 flagged that path.
_INT4_REPO = {
    "phi-4-mini": "unsloth/Phi-4-mini-instruct-bnb-4bit",  # pre-quantized 4-bit
    "gemma-3-4b": "google/gemma-3-4b-it",                  # runtime BitsAndBytes 4-bit
}
_GGUF_REPO = {
    "phi-4-mini": "unsloth/Phi-4-mini-instruct-GGUF",
    "gemma-3-4b": "unsloth/gemma-3-4b-it-GGUF",
}
def _pad_token_id(tok):
    """pad id from a tokenizer OR a processor (which nests its tokenizer), falling
    back to eos. AutoProcessor (Gemma) has no top-level pad_token_id."""
    inner = getattr(tok, "tokenizer", tok)
    pad = getattr(inner, "pad_token_id", None)
    if pad is None:
        pad = getattr(inner, "eos_token_id", None)
    return pad


def _phi4_prompt(messages: list[dict]) -> str:
    """Manual Phi-4-mini chat format — FALLBACK for the onnx backend ONLY, used
    only if the GenAI tokenizer's ``apply_chat_template`` is unavailable. Mirrors
    the model's tokenizer_config chat_template: each turn renders
    ``<|role|>content<|end|>`` then ``<|assistant|>`` opens the reply. Hard-coding
    these tags is safe HERE because the onnx backend hosts EXCLUSIVELY
    microsoft/Phi-4-mini-instruct-onnx (``resolve_load_plan`` rejects onnx for any
    other model) — unlike the transformers path, which stays template-agnostic so
    it also works for Gemma."""
    parts = [f"<|{m['role']}|>{m['content']}<|end|>" for m in messages]
    parts.append("<|assistant|>")
    return "".join(parts)


# Phi-4-mini ships custom modeling code on the Hub -> trust_remote_code=True.
_TRUST_REMOTE_CODE = {"phi-4-mini": True, "gemma-3-4b": False}
# Gemma-3-4B is an image-text-to-text checkpoint (loads via
# Gemma3ForConditionalGeneration + AutoProcessor, not AutoModelForCausalLM).
_MULTIMODAL = {"phi-4-mini": False, "gemma-3-4b": True}


@dataclass(frozen=True)
class LoadPlan:
    """The resolved 'how to load' — pure data, computed without any heavy import."""
    model_key: str
    quant: str
    device: str
    repo_id: str
    backend: str            # "causal_lm" | "multimodal" | "gguf" | "onnx"
    trust_remote_code: bool
    multimodal: bool


def resolve_load_plan(
    model_key: str, quant: str, device: str, base_repo_id: str,
    onnx_dir: str | None = None,
) -> LoadPlan:
    """Pure branch logic (no imports, no I/O) — unit-tested without weights."""
    quant = (quant or "none").lower()
    if quant not in {"none", "int4", "gguf", "onnx"}:
        raise ValueError(f"unknown LLM_QUANT {quant!r}; expected none|int4|gguf|onnx")
    if model_key not in _MULTIMODAL:
        raise ValueError(f"unknown model key {model_key!r}")
    multimodal = _MULTIMODAL[model_key]
    if quant == "onnx":
        # ONNX Runtime GenAI CPU-INT4 (task 13b) — the FAST CPU path. Phi-4-ONLY:
        # microsoft/Phi-4-mini-instruct-onnx ships only Phi-4 weights, so refuse
        # onnx for any other model rather than silently mis-loading a Gemma dir.
        if model_key != "phi-4-mini":
            raise ValueError(
                "LLM_QUANT=onnx is Phi-4-only (the ONNX repo hosts only phi-4-mini); "
                f"got model_key={model_key!r}"
            )
        if not onnx_dir:
            # the onnx backend loads a LOCAL filesystem dir, not an HF id — refuse to
            # silently fall back to a repo id that og.Model() would fail on obscurely.
            raise ValueError(
                "LLM_QUANT=onnx requires a local model dir (onnx_dir / LLM_ONNX_DIR)"
            )
        # repo_id carries the LOCAL onnx model dir for this backend (not an HF id).
        repo, backend = onnx_dir, "onnx"
    elif quant == "gguf":
        repo, backend = _GGUF_REPO[model_key], "gguf"
    elif quant == "int4":
        repo = _INT4_REPO[model_key]
        backend = "multimodal" if multimodal else "causal_lm"
    else:  # none / fp16 base
        repo = base_repo_id
        backend = "multimodal" if multimodal else "causal_lm"
    return LoadPlan(
        model_key=model_key,
        quant=quant,
        device=device,
        repo_id=repo,
        backend=backend,
        trust_remote_code=_TRUST_REMOTE_CODE[model_key],
        multimodal=multimodal,
    )


class LLMModel:
    """Loads the configured model once and serves deterministic ``generate`` calls.

    The HF pipeline / model is not reentrant, so ``generate`` is serialized by an
    instance lock.
    """

    def __init__(
        self,
        model_key: str,
        base_repo_id: str,
        quant: str = "none",
        device: str = "cpu",
        max_new_tokens: int = 120,
        gguf_file: str = "*Q4_K_M.gguf",
        onnx_dir: str | None = None,
        warmup: bool = True,
    ):
        self.plan = resolve_load_plan(model_key, quant, device, base_repo_id, onnx_dir=onnx_dir)
        self.max_new_tokens = max_new_tokens
        self.gguf_file = gguf_file
        self.ready = False
        self._lock = threading.Lock()
        self._tok = None       # AutoTokenizer (Phi-4) or AutoProcessor (Gemma-3)
        self._model = None     # transformers model (causal_lm / multimodal)
        self._llama = None     # llama_cpp.Llama (gguf backend)
        self._og = None        # onnxruntime_genai module (onnx backend)
        self._og_model = None  # og.Model (onnx backend)
        self._og_tok = None    # og.Tokenizer (onnx backend)
        self._eos_ids: set[int] = set()  # onnx: eos ids stripped from the output slice
        self._load()
        if warmup:
            self._warmup()
        self.ready = True
        log.info("model ready: key=%s repo=%s backend=%s device=%s",
                 self.plan.model_key, self.plan.repo_id, self.plan.backend, self.plan.device)

    # --- loading -------------------------------------------------------------
    def _load(self) -> None:
        if self.plan.backend == "onnx":
            self._load_onnx()
        elif self.plan.backend == "gguf":
            self._load_gguf()
        elif self.plan.backend == "multimodal":
            self._load_multimodal()
        else:
            self._load_causal_lm()

    def _load_causal_lm(self) -> None:
        """Phi-4-mini (default path). fp16/bf16 on CPU, or pre-quantized INT4 on CUDA."""
        from transformers import AutoModelForCausalLM, AutoTokenizer

        repo = self.plan.repo_id
        trc = self.plan.trust_remote_code
        self._tok = AutoTokenizer.from_pretrained(repo, trust_remote_code=trc)
        kwargs: dict = {"torch_dtype": "auto", "trust_remote_code": trc}
        if self.plan.quant == "int4":
            # INT4 (bitsandbytes) requires CUDA. The resolved repo for this path is
            # the pre-quantized unsloth checkpoint, which carries its own
            # quantization_config; device_map="auto" places it on the GPU.
            kwargs["device_map"] = "auto"
        else:
            kwargs["device_map"] = self.plan.device
        self._model = AutoModelForCausalLM.from_pretrained(repo, **kwargs)
        self._model.eval()

    def _load_multimodal(self) -> None:
        """Gemma-3-4B-IT (alternative). DEFERRED: Gemma is HF-gated (needs a license
        + token); implemented for completeness and exercised only at report-time
        A/B, never in CI. Loads the multimodal class + processor and drives it with
        text-only chat messages via the processor's own chat template."""
        from transformers import AutoProcessor, Gemma3ForConditionalGeneration

        repo = self.plan.repo_id
        self._tok = AutoProcessor.from_pretrained(repo)
        kwargs: dict = {"torch_dtype": "auto"}
        if self.plan.quant == "int4":
            from transformers import BitsAndBytesConfig
            kwargs["quantization_config"] = BitsAndBytesConfig(load_in_4bit=True)
            kwargs["device_map"] = "auto"
        else:
            kwargs["device_map"] = self.plan.device
        self._model = Gemma3ForConditionalGeneration.from_pretrained(repo, **kwargs)
        self._model.eval()

    def _load_gguf(self) -> None:
        """GGUF backend via llama-cpp-python (CPU-friendly quantized fallback).
        DEFERRED: requires the optional ``llama-cpp-python`` extra (commented in
        requirements-llm.txt); not installed or tested in CI."""
        from llama_cpp import Llama

        self._llama = Llama.from_pretrained(
            repo_id=self.plan.repo_id,
            filename=self.gguf_file,
            n_ctx=4096,
            verbose=False,
        )

    def _load_onnx(self) -> None:
        """ONNX Runtime GenAI CPU-INT4 backend (task 13b) — the FAST CPU path for
        Phi-4-mini. Loads the LOCAL cpu-int4 ONNX model dir (``plan.repo_id``, a
        filesystem path downloaded out-of-repo, never an HF id). onnxruntime_genai
        is imported lazily so ``import llm_sidecar.model`` stays torch/ORT-free and
        the pytest suite is hermetic."""
        import onnxruntime_genai as og

        model_dir = self.plan.repo_id
        self._og = og
        self._og_model = og.Model(model_dir)
        self._og_tok = og.Tokenizer(self._og_model)
        # eos ids for slicing the reply cleanly (genai_config: [200020, 199999]).
        # decode() has no skip_special_tokens flag, so we drop eos ids ourselves.
        try:
            self._eos_ids = {int(x) for x in self._og_tok.eos_token_ids}
        except Exception:  # noqa: BLE001 — tolerate API drift; genai_config still stops gen
            self._eos_ids = set()

    # --- generation ----------------------------------------------------------
    def _warmup(self) -> None:
        """One tiny greedy generation to trigger lazy kernel init before /health
        flips ready. Uses the model's OWN chat template (no hard-coded tags)."""
        with self._lock:
            self._run([{"role": "user", "content": "warmup"}], max_new_tokens=4)

    def generate(self, prompt: str, system: str | None = None) -> str:
        """Deterministic 2-3 sentence completion for ``prompt`` (optional system)."""
        messages: list[dict] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        with self._lock:
            return self._run(messages, self.max_new_tokens)

    def _run(self, messages: list[dict], max_new_tokens: int) -> str:
        if self.plan.backend == "onnx":
            return self._run_onnx(messages, max_new_tokens)
        if self.plan.backend == "gguf":
            return self._run_gguf(messages, max_new_tokens)
        return self._run_transformers(messages, max_new_tokens)

    def _run_transformers(self, messages: list[dict], max_new_tokens: int) -> str:
        import torch

        tok = self._tok
        # tokenize=True + return_dict=True works for BOTH a bare tokenizer (Phi-4)
        # and an AutoProcessor (Gemma-3): the processor's apply_chat_template
        # defaults to tokenize=False and would otherwise return a *str*. return_dict
        # also yields the attention_mask (silences the pad==eos warning). We slice
        # the prompt tokens off the output so the prompt is never echoed (equivalent
        # to return_full_text=False). add_generation_prompt opens the assistant turn.
        enc = tok.apply_chat_template(
            messages, add_generation_prompt=True, tokenize=True,
            return_dict=True, return_tensors="pt",
        )
        enc = {k: v.to(self._model.device) for k, v in enc.items()}
        input_len = enc["input_ids"].shape[-1]
        with torch.no_grad():
            output_ids = self._model.generate(
                **enc,
                max_new_tokens=max_new_tokens,
                do_sample=False,          # greedy — deterministic; NO temperature/top_p
                pad_token_id=_pad_token_id(tok),
            )
        new_tokens = output_ids[0][input_len:]
        return tok.decode(new_tokens, skip_special_tokens=True).strip()

    def _run_gguf(self, messages: list[dict], max_new_tokens: int) -> str:
        # llama.cpp applies the GGUF's embedded chat template. Greedy decoding in
        # llama.cpp is temperature=0.0 (a different backend from transformers — the
        # "no temperature" rule above is about the transformers .generate call).
        out = self._llama.create_chat_completion(
            messages=messages, max_tokens=max_new_tokens, temperature=0.0
        )
        return out["choices"][0]["message"]["content"].strip()

    def _run_onnx(self, messages: list[dict], max_new_tokens: int) -> str:
        """Deterministic greedy generation via ONNX Runtime GenAI (task 13b).

        do_sample=False → greedy argmax → byte-identical output (the genai_config
        already defaults to greedy; we set it explicitly so the guarantee does not
        depend on the shipped config). ``max_length`` is the TOTAL sequence budget
        (prompt + new), so we add the prompt length to honour ``max_new_tokens``.
        A fresh GeneratorParams/Generator per call is the ORT-GenAI pattern; the
        Model + Tokenizer are reused, and the whole call is serialized by the
        instance lock in ``generate``.
        """
        import numpy as np

        og = self._og
        input_ids = self._onnx_encode(messages)
        input_len = int(np.asarray(input_ids).shape[-1])

        params = og.GeneratorParams(self._og_model)
        params.set_search_options(
            do_sample=False,                       # greedy → deterministic
            max_length=input_len + max_new_tokens,
        )
        gen = og.Generator(self._og_model, params)
        gen.append_tokens(input_ids)
        while not gen.is_done():
            gen.generate_next_token()

        seq = gen.get_sequence(0)                  # full sequence: prompt + reply
        new = [int(t) for t in seq[input_len:] if int(t) not in self._eos_ids]
        if not new:
            return ""
        return self._og_tok.decode(np.asarray(new, dtype=np.int32)).strip()

    def _onnx_encode(self, messages: list[dict]):
        """Chat-template + tokenize for the ONNX tokenizer → an int32 id array.

        Prefer the GenAI tokenizer's own ``apply_chat_template`` (it applies the
        model's EMBEDDED Phi-4 template, so we stay tag-agnostic like the
        transformers path). Some ORT-GenAI builds return the rendered string and
        some return token ids — handle both. Fall back to the manual Phi-4 tag
        format only if the templating call is unavailable (Phi-4-only is correct
        for this backend; see ``_phi4_prompt``).
        """
        import json

        import numpy as np

        tok = self._og_tok
        messages_json = json.dumps(messages)
        try:
            templated = tok.apply_chat_template(messages_json, add_generation_prompt=True)
        except Exception:  # noqa: BLE001 — no usable embedded template on this build
            templated = _phi4_prompt(messages)
        if isinstance(templated, np.ndarray):
            return templated.astype(np.int32)
        return tok.encode(templated)
