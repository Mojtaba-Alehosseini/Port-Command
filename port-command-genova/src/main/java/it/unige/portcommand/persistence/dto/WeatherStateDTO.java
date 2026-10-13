package it.unige.portcommand.persistence.dto;

import it.unige.portcommand.agents.WeatherSnapshot;

/**
 * The weather service's persisted state (task 22): just the current reading. The Markov
 * chain has no persistable cursor ({@code java.util.Random} exposes none) — evolution
 * after a load continues FROM this snapshot with fresh draws from the reseeded
 * {@code "weather"} stream, which is deterministic per save file (dated note,
 * planning/22). The broadcast behaviour's alert baseline needs no field of its own:
 * since S16 (2026-10-13) it IS this snapshot — the weather agent boots with it in its
 * cell and the broadcast compares its first post-load reading against it. Restoring
 * into a storm therefore still re-fires no alert (the reading has not changed) — held
 * vessels stay held (their {@code weatherHeld} flags ride the vessel DTOs) until the
 * weather lets them go — while a change in the first minutes after the load is
 * announced, and so is the end of a storm the game was loaded into. (Until S16 the
 * baseline was {@code null} until the first post-load broadcast, which made that first
 * broadcast silent whatever it carried.)
 */
public record WeatherStateDTO(WeatherSnapshot current) {
}
