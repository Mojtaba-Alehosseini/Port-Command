"""Python hallucination validator for the sidecar (task 13).

Mirrors the task-10 Java ``HallucinationValidator`` contract (planning/10 § 10.4):
a required-token PRESENCE check plus a proper-noun NEGATIVE CONTROL against a
per-call gazetteer. Pure stdlib so the pytest suite is hermetic.

Two checks (both must pass):

  1. Required-number presence — every required number must appear in the output.
     v1.1 numeric normalisation is applied to BOTH sides first, so "€2,000",
     "2 000" and "2000" all match "2000" (planning/10 step 1). Without it a
     legitimately formatted number fails the contains-check and the template
     fallback fires on nearly every call.

  2. Proper-noun negative control — every capitalised, non-sentence-initial token
     in the output that is not a common English word must appear in the gazetteer
     (static ontology entities ∪ this call's ``required_entities``). Catches
     fabricated vessel / tug / berth names (the ChatBDI guard, PROJECT_DEFINITION
     § 6.4). Sentence-initial capitals and common English words are exempt.

SCOPE vs the Java validator: the Java side ALSO runs a positive-control
(planning/10 step 3) — every number in the output must be a member of
``rec.allFigures()``. That needs the full ``Recommendation`` object, which the
sidecar never receives (it gets only ``required_numbers``/``required_entities``
over HTTP), so the positive-control stays Java-side. The sidecar performs the
required-presence + negative-control subset, which is all its inputs allow. The
``validate`` flag is advisory anyway — the Java caller decides whether to use the
result (planning/13 step 13.4).
"""
from __future__ import annotations

import logging
import pathlib
import re
from typing import Iterable, Iterator

log = logging.getLogger("llm_sidecar.validator")

# Words that may appear Capitalised mid-sentence WITHOUT being proper nouns:
# common English function words + the domain's COMMON nouns (berth/tug/vessel and
# the five vessel TYPES — a capitalised "Tanker" is a common noun, not a fabricated
# name) + weekday / month names.
_COMMON_WORDS: frozenset[str] = frozenset({
    # articles / pronouns / conjunctions / prepositions / common verbs
    "the", "a", "an", "and", "or", "but", "if", "then", "for", "to", "of", "in",
    "on", "at", "by", "with", "from", "as", "is", "are", "was", "were", "be",
    "it", "its", "this", "that", "these", "those", "your", "you", "our", "we",
    "i", "he", "she", "they", "his", "her", "their", "no", "not", "yes", "so",
    "because", "due", "should", "will", "would", "can", "may", "now", "up", "down",
    # domain COMMON nouns (types + roles) — capitalised forms are NOT names
    "berth", "berths", "tug", "tugs", "vessel", "vessels", "ship", "ships",
    "tanker", "tankers", "container", "containers", "cargo", "ferry", "ferries",
    "cruise", "port", "harbour", "harbor", "master", "customs", "weather", "storm",
    "escort", "pilot", "priority", "market", "deal", "deals", "offer", "counteroffer",
    "hour", "hours", "minute", "minutes", "euro", "euros", "recommend", "recommended",
    "reasoning", "accept", "reject", "counter", "hold", "expedite", "clear",
    "clearance", "safe", "average", "value", "expected", "acceptance", "probability",
    "action", "proposed", "based", "recent", "hazmat", "class", "liquid", "bulk",
    "general", "containerized", "arrival", "arrivals", "eta", "reputation",
    # place names + currency codes that legitimately appear in the port's prose
    # (the harbour IS Genova) — not fabricated names, so exempt from the check
    "genova", "genoa", "italy", "italian", "ligurian", "mediterranean", "eur",
    # weekdays / months
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "january", "february", "march", "april", "may", "june", "july", "august",
    "september", "october", "november", "december",
})

# A "word" token: a letter followed by letters/underscore (keeps snake ids intact).
_WORD_RE = re.compile(r"[A-Za-z][A-Za-z_]*")
_SENT_SPLIT_RE = re.compile(r"[.!?]+")
_CURRENCY_AND_PERCENT = "€$£%"
# separators between digits: comma / apostrophe / any whitespace (\s covers NBSP)
_THOUSANDS_RE = re.compile(r"(?<=\d)[,'\s](?=\d)")
_SEP_RE = re.compile(r"[\s_\-]+")


def _strip_num_formatting(s: str) -> str:
    """Fold currency / percent / thousands formatting so figures compare equal.

    "€2,000" -> "2000", "2 000" -> "2000", "85%" -> "85". A separator is only
    removed when it sits BETWEEN two digits, so "5 hours" and "berth 3" are
    left untouched.
    """
    for ch in _CURRENCY_AND_PERCENT:
        s = s.replace(ch, "")
    return _THOUSANDS_RE.sub("", s)


def _normalise_entity(tok: str) -> str:
    """Fold an entity token to a comparison key: lowercase, strip separators.

    "Berth_3" / "Berth 3" / "berth3" all -> "berth3"; "Aurora" -> "aurora".
    """
    return _SEP_RE.sub("", tok.strip().lower())


class HallucinationValidator:
    """Gazetteer-bounded validator. ``known_entities`` is the static half of the
    gazetteer (ontology vessel types, berth ids, cargo classes, tug ids); each
    call adds that request's ``required_entities`` (specific vessel/tug names)."""

    def __init__(self, known_entities: Iterable[str]):
        self._gazetteer_base = self._build_gazetteer(known_entities)

    @staticmethod
    def _build_gazetteer(entities: Iterable[str]) -> set[str]:
        g: set[str] = set()
        for e in entities or ():
            if not e:
                continue
            g.add(_normalise_entity(e))
            # Also index each word so multi-word names ("MV Aurora") match token-wise.
            for w in _SEP_RE.split(str(e).strip()):
                if w:
                    g.add(_normalise_entity(w))
        g.discard("")
        return g

    def validate(
        self,
        llm_output: str,
        required_numbers: Iterable[str],
        required_entities: Iterable[str],
    ) -> bool:
        if not isinstance(llm_output, str) or not llm_output.strip():
            return False

        # ---- check 1: required numbers present (normalised, digit-bounded) ----
        norm_out = _strip_num_formatting(llm_output)
        for num in required_numbers or ():
            needle = _strip_num_formatting(str(num))
            if not needle:
                continue
            # digit boundaries so required "2000" is NOT satisfied by "12000".
            if not re.search(rf"(?<!\d){re.escape(needle)}(?!\d)", norm_out):
                log.debug("validator: required number %r missing from output", num)
                return False

        # ---- check 2: proper-noun negative control ---------------------------
        gazetteer = self._gazetteer_base | self._build_gazetteer(required_entities)
        for tok in self._proper_nouns(llm_output):
            key = _normalise_entity(tok)
            if key in _COMMON_WORDS:
                continue
            if key not in gazetteer:
                log.debug("validator: proper noun %r not in gazetteer", tok)
                return False
        return True

    @staticmethod
    def _proper_nouns(text: str) -> Iterator[str]:
        """Yield capitalised, non-sentence-initial word tokens from the output."""
        for sentence in _SENT_SPLIT_RE.split(text):
            words = _WORD_RE.findall(sentence)
            for i, w in enumerate(words):
                if i == 0:
                    continue  # sentence-initial capital: exempt (planning/10 step 4)
                if w[0].isupper():
                    yield w


def load_known_entities(vocab_path: str | pathlib.Path) -> set[str]:
    """Flatten ontology_vocab.yml into the validator's static known-entity set.

    Minimal hand parser (the file is machine-generated with a flat ``key:`` /
    ``  - item`` shape) — avoids a PyYAML dependency so the validator stays
    pure-stdlib. A missing / unreadable file falls back to just the tug ids: the
    validator still runs, and the gazetteer is then those plus the per-call
    ``required_entities``.
    """
    wanted = {"vessel_types", "berths", "cargo_classes", "service_types"}
    # The concrete tug ids come from AgentRoster, not the OWL vocab, so they are
    # known even if the file is absent — a legitimate "Tug 2" reference must never
    # be flagged as a fabricated name (service_types only lists the generic "tug").
    out: set[str] = {"tug_1", "tug_2", "tug_3", "tug_4"}
    try:
        text = pathlib.Path(vocab_path).read_text(encoding="utf-8")
    except OSError:
        log.warning("ontology vocab not found at %s; gazetteer limited to tug ids + "
                    "per-call entities", vocab_path)
        return out
    current: str | None = None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith((" ", "\t")) and line.rstrip().endswith(":"):
            current = line.rstrip()[:-1].strip()
        elif line.lstrip().startswith("- ") and current in wanted:
            out.add(line.split("- ", 1)[1].strip())
    return out
