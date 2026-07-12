"""Prompt constants for the sidecar (task 13).

For this design the *content* of the prompt is built upstream in Java (task 10's
prompt builder) and handed to ``POST /explain`` as a plain ``prompt`` string (plus
an optional ``system`` string). The sidecar then wraps it in chat ``messages`` and
applies the LOADED MODEL'S OWN chat template via ``apply_chat_template`` — it never
hard-codes turn tags, because Phi-4 (``<|system|>…<|assistant|>``) and Gemma-3
(``<start_of_turn>…<end_of_turn>``) use different formats (see ``model.py``).

``DEFAULT_SYSTEM_PROMPT`` is the recommended system instruction (mirrors
planning/10 § 10.3). It is provided here so task 14's LLMBridge can send it as the
``system`` field; the sidecar does not force it (a request without ``system`` is
generated as a bare user turn).
"""
from __future__ import annotations

# The anti-hallucination system instruction. The paired HallucinationValidator
# (validator.py) is the guard that enforces this after generation.
DEFAULT_SYSTEM_PROMPT = (
    "You are the harbour-master's assistant. Explain the recommended action in "
    "2-3 sentences of clear, professional English. NEVER add, change, or omit any "
    "numbers, prices, percentages, durations, or named entities (berths, vessels). "
    "If unsure, repeat the input verbatim."
)
