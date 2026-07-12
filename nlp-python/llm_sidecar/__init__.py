"""Flask LLM sidecar (task 13) — Port Command Genova.

A small HTTP service on localhost:5006 that hosts a local instruction model
(microsoft/Phi-4-mini-instruct by default, google/gemma-3-4b-it alternative) and
turns a decision trace into a 2-3 sentence prose explanation for the Assistant
agent. See ``server.py`` for the HTTP surface and ``README.md`` for env vars.

Import policy: only ``model.py`` pulls in torch/transformers, and it does so
lazily inside ``LLMModel`` — importing this package (or ``server``/``validator``/
``config``) is cheap and dependency-light so the pytest suite stays hermetic.
"""
