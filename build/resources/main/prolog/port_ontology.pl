% AUTO-GENERATED FROM port_ontology.owl BY ontology_to_assets.py. DO NOT EDIT.
% Source of truth: src/main/resources/ontology/port_ontology.owl
% Regenerate: see port-command-genova/README.md 'Regenerating the ontology Prolog file'.

% --- Dynamic predicates ---
% default/3 and instance_of/2 are dynamic so the task-04 PrologQueries facade can
% assertz/retract transient per-vessel facts (under the engine lock) around a query.
% Declared before their facts load so SWI treats those facts as dynamic clauses.
:- dynamic default/3.
:- dynamic instance_of/2.

% --- Classes ---
class(berth).
class(cargo).
class(cargo_vessel).
class(clear_weather).
class(container_berth).
class(container_vessel).
class(containerized_cargo).
class(cruise_ship).
class(deep_water_berth).
class(ferry).
class(ferry_berth).
class(fog).
class(general_cargo).
class(general_cargo_berth).
class(hazmat_cargo).
class(hazmat_class_1).
class(hazmat_class_3).
class(high_wind).
class(liquid_bulk).
class(officer).
class(person).
class(port_service).
class(storm).
class(tanker).
class(tug).
class(vessel).
class(weather_condition).

% --- Subclass relations (direct edges) ---
subclass_of(cargo_vessel, vessel).
subclass_of(clear_weather, weather_condition).
subclass_of(container_berth, berth).
subclass_of(container_vessel, vessel).
subclass_of(containerized_cargo, cargo).
subclass_of(cruise_ship, vessel).
subclass_of(deep_water_berth, berth).
subclass_of(ferry, vessel).
subclass_of(ferry_berth, berth).
subclass_of(fog, weather_condition).
subclass_of(general_cargo, cargo).
subclass_of(general_cargo_berth, berth).
subclass_of(hazmat_cargo, cargo).
subclass_of(hazmat_class_1, hazmat_cargo).
subclass_of(hazmat_class_3, hazmat_cargo).
subclass_of(high_wind, weather_condition).
subclass_of(liquid_bulk, cargo).
subclass_of(officer, person).
subclass_of(storm, weather_condition).
subclass_of(tanker, vessel).
subclass_of(tug, port_service).

% --- Vessel types (rule guards match vessel_type/1, never class/1) ---
vessel_type(cargo_vessel).
vessel_type(container_vessel).
vessel_type(cruise_ship).
vessel_type(ferry).
vessel_type(tanker).

% --- Cargo classes (transitive subclasses of cargo) ---
cargo_class(containerized_cargo).
cargo_class(general_cargo).
cargo_class(hazmat_cargo).
cargo_class(hazmat_class_1).
cargo_class(hazmat_class_3).
cargo_class(liquid_bulk).

% --- Hazmat predicate (single owner; task 09 must NOT redefine it) ---
is_hazmat(C) :- subclass_of(C, hazmat_cargo).

% --- Property domains ---
property_domain(has_cargo_class, vessel).
property_domain(has_contracted_fee, vessel).
property_domain(has_contracted_hours, vessel).
property_domain(has_draft, vessel).
property_domain(has_imo, vessel).
property_domain(has_length, vessel).
property_domain(has_max_draft, berth).
property_domain(has_max_length, berth).
property_domain(has_tonnage, vessel).
property_domain(is_compatible_with, vessel).
property_domain(requires_escort, vessel).
property_domain(requires_hazmat_clearance, vessel).

% --- Property ranges ---
property_range(has_cargo_class, cargo).
property_range(has_contracted_fee, decimal).
property_range(has_contracted_hours, integer).
property_range(has_draft, decimal).
property_range(has_imo, string).
property_range(has_length, decimal).
property_range(has_max_draft, decimal).
property_range(has_max_length, decimal).
property_range(has_tonnage, integer).
property_range(is_compatible_with, berth).
property_range(requires_escort, boolean).
property_range(requires_hazmat_clearance, boolean).

% --- Berth defaults (Genova quay tariff data; see task 02) ---
default(berth_1, has_max_draft, 22.0).
default(berth_1, has_max_length, 350.0).
default(berth_1, has_max_beam, 50.0).
default(berth_2, has_max_draft, 14.0).
default(berth_2, has_max_length, 340.0).
default(berth_2, has_max_beam, 40.0).
default(berth_3, has_max_draft, 12.0).
default(berth_3, has_max_length, 200.0).
default(berth_3, has_max_beam, 32.0).
default(berth_4, has_max_draft, 9.0).
default(berth_4, has_max_length, 150.0).
default(berth_4, has_max_beam, 26.0).

% --- Per-vessel-type beam defaults (beam-fit rule R3, task 04) ---
default(cargo_vessel, has_beam, 23.0).
default(container_vessel, has_beam, 32.0).
default(cruise_ship, has_beam, 35.0).
default(ferry, has_beam, 25.0).
default(tanker, has_beam, 20.0).

% --- Berth instances (each names a berth subclass, never bare berth) ---
instance_of(berth_1, deep_water_berth).
instance_of(berth_2, container_berth).
instance_of(berth_3, general_cargo_berth).
instance_of(berth_4, ferry_berth).

% --- Load guard ---
ontology_loaded :- true.
