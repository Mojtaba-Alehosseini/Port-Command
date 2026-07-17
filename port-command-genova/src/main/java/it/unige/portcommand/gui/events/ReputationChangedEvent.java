package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Reputation moved. Published by {@code ReputationLedger} on every mutation (task 20) — the
 * live data path the HUD's reputation figure binds to, replacing task 18's static
 * {@code "—"} placeholder. Absolute-{@code score}-plus-{@code delta} for the same reason
 * {@link WalletChangedEvent} carries an absolute balance.
 *
 * <p>{@code score} is the CLAMPED post-mutation value (0..100), so {@code score} does not
 * always equal {@code previous + delta} — at a clamp boundary the applied change is smaller
 * than {@code delta}. {@code delta} is the REQUESTED change, kept verbatim so the
 * notification text ("Reputation −2") matches the canonical rule table rather than the
 * clamped remainder.
 *
 * @param score          reputation AFTER this mutation, clamped to 0..100
 * @param delta          the signed change requested by the rule that fired (pre-clamp)
 * @param reason         the canonical {@code ReputationRules} tag that fired (e.g. {@code "deal_closed"})
 * @param vesselId       the vessel this change is attributable to, or {@code null} for port-wide
 * @param simTimeMillis  sim time of the mutation
 */
public record ReputationChangedEvent(double score, double delta, String reason, String vesselId,
                                      long simTimeMillis) implements Event {
}
