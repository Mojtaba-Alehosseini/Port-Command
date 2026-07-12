#!/usr/bin/env python
"""MANUAL real-model smoke for the LLM sidecar (task 13, step 13.7).

NOT collected by pytest (no ``test_`` prefix) and never run in the commit hook —
it loads real weights and takes tens of seconds. Run it by hand AFTER starting
the server, to (a) prove /health goes 503 -> 200, (b) prove determinism (the same
prompt twice returns byte-identical text), (c) prove the validator catches an
injected wrong number end-to-end, and (d) record load time + generation latency
(the figure that sizes task 14's LLMBridge timeout).

Usage:
    # terminal 1
    cd nlp-python && ./start_llm.bat            # or ./start_llm.sh
    # terminal 2 — run from the nlp-python/ directory (so `-m` resolves the package)
    cd nlp-python && .venv/Scripts/python -m llm_sidecar.tests.smoke_real_model
    # (this file imports only urllib, so the plain-path form works from anywhere too:
    #    nlp-python/.venv/Scripts/python nlp-python/llm_sidecar/tests/smoke_real_model.py )
    # options: --host 127.0.0.1 --port 5006 --boot-timeout 180

Uses only the stdlib (urllib) — no `requests` dependency.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

# A representative ~80-token explanation prompt (the shape task 10 will send).
SAMPLE_PROMPT = (
    "Recommend accept at 2000 euros. Reasoning: market average for a tanker over "
    "5 hours is 5500 euros (stddev 800, based on 12 recent deals). The proposed "
    "action has acceptance probability 85% and expected value 6200 euros. Berth 3 "
    "is clear. Explain this recommendation in 2-3 sentences without changing any "
    "number or name."
)


def _post(base: str, path: str, payload: dict, timeout: float = 120.0):
    req = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, json.loads(r.read())


def _get(base: str, path: str, timeout: float = 10.0):
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def wait_ready(base: str, boot_timeout: float) -> float:
    """Poll /health until 200; return seconds waited. Raises on timeout."""
    t0 = time.perf_counter()
    saw_503 = False
    while time.perf_counter() - t0 < boot_timeout:
        try:
            code, body = _get(base, "/health")
        except urllib.error.URLError:
            time.sleep(1.0)
            continue
        if code == 503:
            saw_503 = True
            print(f"  /health 503 (loading={body.get('loading')}) ...")
            time.sleep(3.0)
            continue
        if code == 200 and body.get("ready"):
            waited = time.perf_counter() - t0
            print(f"  /health 200 ready=true after ~{waited:.1f}s "
                  f"(saw 503 first: {saw_503})")
            return waited
        time.sleep(1.0)
    raise TimeoutError(f"model not ready within {boot_timeout}s")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5006)
    ap.add_argument("--boot-timeout", type=float, default=180.0)
    args = ap.parse_args()
    base = f"http://{args.host}:{args.port}"

    print(f"[1/4] waiting for {base}/health ...")
    load_wait = wait_ready(base, args.boot_timeout)

    print("[2/4] determinism: same prompt x3 ...")
    latencies, texts = [], []
    for i in range(3):
        t0 = time.perf_counter()
        code, body = _post(base, "/explain", {"prompt": SAMPLE_PROMPT})
        dt = time.perf_counter() - t0
        assert code == 200, (code, body)
        latencies.append(dt)
        texts.append(body["text"])
        print(f"  run {i + 1}: {dt:.2f}s  server_gen={body.get('gen_seconds')}s  "
              f"chars={len(body['text'])}")
    identical = len(set(texts)) == 1
    print(f"  byte-identical across 3 runs: {identical}")
    if not identical:
        print("  !! NON-DETERMINISTIC — investigate do_sample/temperature", file=sys.stderr)

    print("[3/4] validator: injected wrong number ...")
    # required 2000 but ask the model for output; force validate. If the model
    # echoes 2000 it passes; the true end-to-end catch is a required number the
    # output cannot contain:
    code, body = _post(base, "/explain", {
        "prompt": SAMPLE_PROMPT,
        "required_numbers": ["999999"],   # cannot appear -> validated must be false
        "validate": True,
    })
    assert code == 200, (code, body)
    print(f"  validated={body['validated']} (expected False)")
    catch_ok = body["validated"] is False

    print("[4/4] summary")
    print(json.dumps({
        "load_seconds": round(load_wait, 1),
        "explain_latency_seconds": [round(x, 2) for x in latencies],
        "explain_latency_mean_s": round(sum(latencies) / len(latencies), 2),
        "output_chars": len(texts[0]),
        "deterministic": identical,
        "validator_catches_wrong_number": catch_ok,
    }, indent=2))
    return 0 if (identical and catch_ok) else 1


if __name__ == "__main__":
    raise SystemExit(main())
