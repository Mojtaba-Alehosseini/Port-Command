package it.unige.portcommand.persistence;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Memento contract every persistable agent implements (task 22, planning/22 §22.1):
 * {@link #snapshot()} serialises the agent's own beliefs to a {@link JsonNode} (the
 * bound form of its {@code persistence.dto} record), {@link #restore(JsonNode)} hydrates
 * them back. The polymorphic packaging (which DTO, where it sits in the save file) is
 * {@code GameStateBuilder}'s job — the agent only knows its own fields.
 *
 * <p><b>Restore flows through the args channel, not a live call:</b> the loader passes the
 * persisted DTO as a spawn argument (the task-05 {@code Object[]} channel) and the agent's
 * own {@code onSetup} calls {@link #restore(JsonNode)} on itself before attaching
 * behaviours. Nothing calls {@code restore} on an already-running agent.
 *
 * <p><b>Threading:</b> {@code snapshot()} is called with the game quiesced — for the
 * HarbourMaster, on its own agent thread (the save runs as an HM behaviour); for the
 * others, while the clock is paused / the settlement thread holds the money path — and
 * must read only thread-safe or effectively-settled state.
 */
public interface Mementoable {

    /** This agent's beliefs as the JSON tree of its {@code persistence.dto} record. */
    JsonNode snapshot();

    /** Hydrates beliefs from {@link #snapshot()}'s shape. Called from the agent's own setup. */
    void restore(JsonNode node);
}
