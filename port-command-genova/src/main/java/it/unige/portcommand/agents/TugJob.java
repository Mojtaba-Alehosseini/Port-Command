package it.unige.portcommand.agents;

import it.unige.portcommand.ontology.Position;
import jade.core.AID;

/**
 * The escort assignment a tug is currently executing — the value behind
 * {@code TugAgent.currentJob()}. Set when an {@code ACCEPT_PROPOSAL} is honoured,
 * cleared on completion (return-to-base) or on {@code CANCEL}. {@code currentJob != null}
 * IS the "busy" gate (task 08 §8.2).
 *
 * <p>Holds the escort destination ({@code berthPosition}, delivered by the
 * HarbourMaster in the ACCEPT), the {@code client} (the ACCEPT sender) to INFORM on
 * {@code escort_complete}, and the CNP {@code conversationId} so that completion
 * notification is correlated back to the Contract Net session (task 11/15). The
 * vessel <em>pickup</em> point is not held here — the transit leg consumes it
 * directly from the tug's own remembered bid, so the tug never reads a vessel agent's
 * live state.
 */
public record TugJob(String vesselId, Position berthPosition, AID client, String conversationId) {
}
