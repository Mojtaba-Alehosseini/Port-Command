# Port ontology (`port_ontology.owl`)

The OWL ontology is the **single source of truth** for the domain vocabulary.
Two artefacts are *generated* from it and must never be hand-edited:

- `../prolog/port_ontology.pl` — Prolog facts (class/subclass/`vessel_type`/`cargo_class`,
  the `is_hazmat/1` clause, property domains/ranges, and the four berth defaults)
  consumed by the task-04 rule kernel.
- `../../../../../nlp-python/rasa/data/ontology_vocab.yml` — Rasa NER vocabulary
  consumed by the task-12 NLU pipeline.

Both carry an `AUTO-GENERATED … DO NOT EDIT` header. Change the OWL, then regenerate.

## Contents

~27 named classes / 12 properties, no individuals (berth instances are emitted as
Prolog `instance_of/2` facts by the converter, keeping the OWL clean):

| Group | Classes |
|---|---|
| Vessel (5) | `Tanker`, `ContainerVessel`, `CargoVessel`, `Ferry`, `CruiseShip` |
| Berth (4) | `DeepWaterBerth`, `ContainerBerth`, `GeneralCargoBerth`, `FerryBerth` |
| Cargo (6) | `HazmatCargo` → `HazmatClass1`/`HazmatClass3`; `GeneralCargo`, `LiquidBulk`, `ContainerizedCargo` |
| Service (1) | `Tug` (under `PortService`) |
| Weather (4) | `ClearWeather`, `Fog`, `Storm`, `HighWind` |
| People (1) | `Officer` (under `Person`) |
| Roots | `Vessel`, `Berth`, `Cargo`, `PortService`, `WeatherCondition`, `Person` |

PascalCase class/property names are normalised to `snake_case` atoms in Prolog
(`ContainerVessel` → `container_vessel`, `hasIMO` → `has_imo`).

`vessel_class_needs/2` is **not** emitted here — task 04 owns `rules_compatibility.pl`
and pins those rows alongside rule R5.

## Editing

Open in **Protégé Desktop 5.6.x** (new ontology IRI `http://unige.it/portcommand/ontology`),
or edit the RDF/XML by hand — it is mechanical. After any change, **regenerate** (below)
and re-run the gate (`./gradlew test` + `pytest nlp-python/test_ontology_to_assets.py`).

## Regenerating

From the repo root, using the Python 3.11 venv (see `../../../../README.md`):

```bash
nlp-python/.venv/Scripts/python nlp-python/ontology_to_assets.py \
    --owl   port-command-genova/src/main/resources/ontology/port_ontology.owl \
    --pl    port-command-genova/src/main/resources/prolog/port_ontology.pl \
    --vocab nlp-python/rasa/data/ontology_vocab.yml
```

The converter is deterministic (sorted output, fixed float repr): running it twice
yields byte-identical files.
