package it.unige.portcommand.persistence.dto;

import it.unige.portcommand.agents.WeatherSnapshot;

/**
 * The weather service's persisted state (task 22): just the current reading. The Markov
 * chain has no persistable cursor ({@code java.util.Random} exposes none) — evolution
 * after a load continues FROM this snapshot with fresh draws from the reseeded
 * {@code "weather"} stream, which is deterministic per save file (dated note,
 * planning/22). The broadcast behaviour's alert baseline is likewise not persisted:
 * it re-seeds from the first post-load reading, and {@code WeatherAlertPolicy} treats a
 * null baseline as "no transition", so restoring into a storm re-fires no alert — held
 * vessels stay held (their {@code weatherHeld} flags ride the vessel DTOs) until a
 * genuine clear.
 */
public record WeatherStateDTO(WeatherSnapshot current) {
}
