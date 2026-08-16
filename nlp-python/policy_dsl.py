"""A checker for PolicyParser's regex DSL, ported for corpus cross-checking (audit D-03, S6).

This is the repo's FIRST hand-maintained duplication of Java logic, and it says so: the four
patterns and the plural map below are mechanical ports of
``port-command-genova/src/main/java/it/unige/portcommand/nlp/PolicyParser.java`` (TOP_LEVEL,
COUNTER_ACTION, CONDITION_CLAUSE, AND_SPLIT, VESSEL_TYPE_PLURALS). The drift guard is
``test_policy_corpus.py``'s vector block, which mirrors PolicyParserTest's exact valid/invalid
strings — if the Java grammar moves and those vectors change, the port goes red there. Drift
BEYOND those vectors is undetected; keep the two files in sync by hand.

Grammar (PolicyParser's javadoc):

    POLICY      := "auto" ACTION "if" CONDITION ["for" VESSEL_TYPE]
    ACTION      := "accept" | "reject" | "counter" "+"? INT "%"
    CONDITION   := FIELD OP NUMBER ("and" CONDITION)?
    FIELD       := "price" | "duration" | "rounds_used"
    OP          := ">" | "<" | ">=" | "<=" | "="
    VESSEL_TYPE := "tankers" | "container_vessels" | "cargo_vessels" | "ferries" | "cruise_ships"
"""

from __future__ import annotations

import re

# PolicyParser.java TOP_LEVEL (CASE_INSENSITIVE there; re.I here).
_TOP_LEVEL = re.compile(r"^auto\s+(.+?)\s+if\s+(.+?)(?:\s+for\s+(\S+))?$", re.IGNORECASE)
# PolicyParser.java COUNTER_ACTION.
_COUNTER_ACTION = re.compile(r"^counter\s*\+?\s*(\d+)\s*%$", re.IGNORECASE)
# PolicyParser.java CONDITION_CLAUSE.
_CONDITION_CLAUSE = re.compile(
    r"^(price|duration|rounds_used)\s*(>=|<=|>|<|=)\s*(\d+(?:\.\d+)?)$", re.IGNORECASE)
# PolicyParser.java AND_SPLIT.
_AND_SPLIT = re.compile(r"\s+and\s+", re.IGNORECASE)

# PolicyParser.java VESSEL_TYPE_PLURALS (keys only — the checker needs membership, not the
# canonical mapping).
_VESSEL_TYPE_PLURALS = {
    "tankers",
    "container_vessels",
    "cargo_vessels",
    "ferries",
    "cruise_ships",
}


def parses(text: str) -> bool:
    """True iff ``text`` is a valid utterance of PolicyParser's DSL (mirrors tryParse)."""
    if not text:
        return False
    # Mirror tryParse's normalisation exactly: trim + collapse ALL whitespace runs to one
    # space (Java: text.trim().replaceAll("\s+", " ")). Without the collapse a newline or
    # double space inside a condition diverges from Java (adversarial finding 14).
    text = " ".join(text.split())
    top = _TOP_LEVEL.match(text)
    if not top:
        return False
    action, condition, vessel_type = top.group(1), top.group(2), top.group(3)

    action = action.strip().lower()
    if action not in ("accept", "reject") and not _COUNTER_ACTION.match(action):
        return False

    for clause in _AND_SPLIT.split(condition.strip()):
        if not _CONDITION_CLAUSE.match(clause.strip()):
            return False

    if vessel_type is not None and vessel_type.lower() not in _VESSEL_TYPE_PLURALS:
        return False
    return True
