#!/usr/bin/env python3
"""Derive Prolog facts + Rasa vocabulary from the OWL ontology (task 02).

The OWL file is the single source of truth. This converter regenerates two
artefacts (never hand-edit them):

  * ``port_ontology.pl``   - class/subclass/vessel_type/cargo_class facts, the
    ``is_hazmat/1`` clause, property domains/ranges, and the four berth defaults
    consumed by the task-04 rule kernel.
  * ``ontology_vocab.yml`` - vessel/berth/cargo/service vocabulary + a static
    negotiation-verb list consumed by the task-12 Rasa pipeline.

Determinism: every emitted section is sorted and floats use a fixed repr, so
running twice yields byte-identical output (task-02 acceptance criterion).

``vessel_class_needs/2`` is intentionally NOT emitted here: task 04 owns
``rules_compatibility.pl`` and pins those rows alongside rule R5.

Requires Python 3.10/3.11 (never 3.12+) and owlready2==0.46.

Usage (from the repo root, via the 3.11 venv)::

    nlp-python/.venv/Scripts/python ontology_to_assets.py \\
        --owl   port-command-genova/src/main/resources/ontology/port_ontology.owl \\
        --pl    port-command-genova/src/main/resources/prolog/port_ontology.pl \\
        --vocab nlp-python/rasa/data/ontology_vocab.yml
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

import owlready2

# --- snake_case normalisation -------------------------------------------------
# Order matters: split acronym boundaries (IMOReader -> IMO_Reader) first, then
# camel boundaries (aB -> a_B), then letter->digit boundaries (Class1 -> Class_1).
_ACRONYM = re.compile(r"(?<=[A-Z])(?=[A-Z][a-z])")
_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_DIGIT = re.compile(r"(?<=[A-Za-z])(?=[0-9])")


def to_snake(name: str) -> str:
    """``ContainerVessel`` -> ``container_vessel``; ``hasIMO`` -> ``has_imo``."""
    s = _ACRONYM.sub("_", name)
    s = _CAMEL.sub("_", s)
    s = _DIGIT.sub("_", s)
    return s.lower()


# --- static data (NOT OWL individuals; keeps the OWL clean) -------------------
# Berth defaults: real Genova quay tariff data (Job 2 section 2.2).
# These numbers are the ontology *source of truth* for berth dimensions: the OWL
# carries no berth individuals (only class/property schema), so the values live
# here and are emitted verbatim into port_ontology.pl. Change them HERE, never in
# the generated .pl.
# Task 07b (v1.1, 2026-07-04): berth_2 has_max_length 250->340 (cruise up to 330 m
# and containers up to 300 m must fit) and berth_4 has_max_beam 22->26 (ferry beam
# 25 must fit) — reconciles the kernel to vessel_templates.json so every ship type
# has >=1 compatible berth (test_spawnability.pl gate; MASTER_PLAN S8 item 20;
# PROJECT_DEFINITION S5.2/S7.5). All other berth dimensions unchanged.
BERTH_DEFAULTS = {
    "berth_1": {"has_max_draft": 22.0, "has_max_length": 350.0, "has_max_beam": 50.0},
    "berth_2": {"has_max_draft": 14.0, "has_max_length": 340.0, "has_max_beam": 40.0},
    "berth_3": {"has_max_draft": 12.0, "has_max_length": 200.0, "has_max_beam": 32.0},
    "berth_4": {"has_max_draft": 9.0, "has_max_length": 150.0, "has_max_beam": 26.0},
}
# Each berth maps to a *berth subclass* (subclass of berth in the OWL), never the
# bare ``berth`` root, so the task-04 berth-class rules (R3) can fire.
BERTH_INSTANCE_OF = {
    "berth_1": "deep_water_berth",
    "berth_2": "container_berth",
    "berth_3": "general_cargo_berth",
    "berth_4": "ferry_berth",
}
# Per-vessel-type beam, so beam-fit rule R3 can compare to each berth has_max_beam.
VESSEL_BEAMS = {
    "tanker": 20.0,
    "container_vessel": 32.0,
    "cargo_vessel": 23.0,
    "ferry": 25.0,
    "cruise_ship": 35.0,
}
# Static list for the Rasa NLU task; semantic order, not sorted.
NEGOTIATION_VERBS = ["propose", "offer", "give", "pay", "accept", "refuse", "reject"]

# owlready2 maps xsd:string->str, xsd:decimal->float, xsd:integer->int, xsd:boolean->bool.
_XSD = {str: "string", float: "decimal", int: "integer", bool: "boolean"}


def _pl_float(value: float) -> str:
    """Canonical Prolog float literal: ``22.0`` (never ``22``)."""
    return repr(float(value))


def _range_atom(r) -> str:
    """Map a datatype range (python type) to an xsd atom, or an object range
    (class) to its snake_case atom."""
    if r in _XSD:
        return _XSD[r]
    name = getattr(r, "name", None)
    if name:
        return to_snake(name)
    return str(r)


def load_ontology(owl_path) -> owlready2.Ontology:
    """Load the OWL file into a fresh, isolated owlready2 world (no global state)."""
    uri = "file://" + Path(owl_path).resolve().as_posix()
    return owlready2.World().get_ontology(uri).load()


def _named_parents(cls):
    """Direct named superclasses of *cls*, excluding owl:Thing and restrictions."""
    for parent in cls.is_a:
        if isinstance(parent, owlready2.ThingClass) and parent.name and parent.name != "Thing":
            yield parent


def _transitive_subtypes(classes, root):
    """snake atoms of every class that has *root* among its ancestors (excl. root)."""
    if root is None:
        return []
    return sorted(
        to_snake(c.name) for c in classes if c is not root and root in c.ancestors()
    )


def build_model(onto: owlready2.Ontology) -> dict:
    """Extract every fact the renderers need, fully sorted for determinism."""
    classes = list(onto.classes())
    by_name = {c.name: c for c in classes}

    class_atoms = sorted(to_snake(c.name) for c in classes)

    edges = set()
    for c in classes:
        child = to_snake(c.name)
        for parent in _named_parents(c):
            edges.add((child, to_snake(parent.name)))
    subclass_edges = sorted(edges)

    vessel_types = _transitive_subtypes(classes, by_name.get("Vessel"))
    cargo_classes = _transitive_subtypes(classes, by_name.get("Cargo"))
    service_types = _transitive_subtypes(classes, by_name.get("PortService"))

    domains, ranges = set(), set()
    for p in list(onto.data_properties()) + list(onto.object_properties()):
        pn = to_snake(p.name)
        for d in p.domain:
            if getattr(d, "name", None):
                domains.add((pn, to_snake(d.name)))
        for r in p.range:
            ranges.add((pn, _range_atom(r)))

    return {
        "class_atoms": class_atoms,
        "subclass_edges": subclass_edges,
        "vessel_types": vessel_types,
        "cargo_classes": cargo_classes,
        "service_types": service_types,
        "prop_domains": sorted(domains),
        "prop_ranges": sorted(ranges),
    }


def render_prolog(model: dict) -> str:
    """Render the deterministic ``port_ontology.pl`` text."""
    out: list[str] = []
    out.append("% AUTO-GENERATED FROM port_ontology.owl BY ontology_to_assets.py. DO NOT EDIT.")
    out.append("% Source of truth: src/main/resources/ontology/port_ontology.owl")
    out.append("% Regenerate: see port-command-genova/README.md 'Regenerating the ontology Prolog file'.")

    out.append("")
    out.append("% --- Dynamic predicates ---")
    out.append("% default/3 and instance_of/2 are dynamic so the task-04 PrologQueries facade can")
    out.append("% assertz/retract transient per-vessel facts (under the engine lock) around a query.")
    out.append("% Declared before their facts load so SWI treats those facts as dynamic clauses.")
    out.append(":- dynamic default/3.")
    out.append(":- dynamic instance_of/2.")

    out.append("")
    out.append("% --- Classes ---")
    out += [f"class({a})." for a in model["class_atoms"]]

    out.append("")
    out.append("% --- Subclass relations (direct edges) ---")
    out += [f"subclass_of({c}, {p})." for c, p in model["subclass_edges"]]

    out.append("")
    out.append("% --- Vessel types (rule guards match vessel_type/1, never class/1) ---")
    out += [f"vessel_type({a})." for a in model["vessel_types"]]

    out.append("")
    out.append("% --- Cargo classes (transitive subclasses of cargo) ---")
    out += [f"cargo_class({a})." for a in model["cargo_classes"]]

    out.append("")
    out.append("% --- Hazmat predicate (single owner; task 09 must NOT redefine it) ---")
    out.append("is_hazmat(C) :- subclass_of(C, hazmat_cargo).")

    out.append("")
    out.append("% --- Property domains ---")
    out += [f"property_domain({p}, {c})." for p, c in model["prop_domains"]]

    out.append("")
    out.append("% --- Property ranges ---")
    out += [f"property_range({p}, {r})." for p, r in model["prop_ranges"]]

    out.append("")
    out.append("% --- Berth defaults (Genova quay tariff data; see task 02) ---")
    for berth in sorted(BERTH_DEFAULTS):
        for prop in ("has_max_draft", "has_max_length", "has_max_beam"):
            out.append(f"default({berth}, {prop}, {_pl_float(BERTH_DEFAULTS[berth][prop])}).")

    out.append("")
    out.append("% --- Per-vessel-type beam defaults (beam-fit rule R3, task 04) ---")
    for vt in sorted(VESSEL_BEAMS):
        out.append(f"default({vt}, has_beam, {_pl_float(VESSEL_BEAMS[vt])}).")

    out.append("")
    out.append("% --- Berth instances (each names a berth subclass, never bare berth) ---")
    for berth in sorted(BERTH_INSTANCE_OF):
        out.append(f"instance_of({berth}, {BERTH_INSTANCE_OF[berth]}).")

    out.append("")
    out.append("% --- Load guard ---")
    out.append("ontology_loaded :- true.")

    return "\n".join(out) + "\n"


def render_vocab(model: dict) -> str:
    """Render the deterministic ``ontology_vocab.yml`` text."""
    def block(key: str, items) -> list[str]:
        return [f"{key}:"] + [f"  - {it}" for it in items]

    out: list[str] = []
    out.append("# AUTO-GENERATED FROM port_ontology.owl BY ontology_to_assets.py. DO NOT EDIT.")
    out.append("# Rasa NER vocabulary (consumed by task 12). Source of truth: the OWL ontology.")
    out += block("vessel_types", model["vessel_types"])
    out += block("berths", sorted(BERTH_INSTANCE_OF))
    out += block("cargo_classes", model["cargo_classes"])
    out += block("service_types", model["service_types"])
    out += block("verbs_for_negotiation", NEGOTIATION_VERBS)
    return "\n".join(out) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--owl", required=True, type=Path, help="input OWL file")
    ap.add_argument("--pl", required=True, type=Path, help="output Prolog file")
    ap.add_argument("--vocab", required=True, type=Path, help="output Rasa vocab YAML")
    args = ap.parse_args(argv)

    model = build_model(load_ontology(args.owl))
    args.pl.parent.mkdir(parents=True, exist_ok=True)
    args.vocab.parent.mkdir(parents=True, exist_ok=True)
    args.pl.write_text(render_prolog(model), encoding="utf-8", newline="\n")
    args.vocab.write_text(render_vocab(model), encoding="utf-8", newline="\n")

    print(
        f"Wrote {args.pl} "
        f"({len(model['class_atoms'])} classes, "
        f"{len(model['vessel_types'])} vessel types, "
        f"{len(model['cargo_classes'])} cargo classes)"
    )
    print(f"Wrote {args.vocab}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
