package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The wallet balance moved. Published by {@code WalletLedger} on EVERY mutation (task 20)
 * — the live data path the HUD's wallet figure binds to, replacing task 18's static
 * {@code "—"} placeholder.
 *
 * <p>Carries the post-mutation {@code balance} as an ABSOLUTE figure, not just the delta,
 * so a subscriber that starts late or drops an event still renders a correct number — the
 * HUD must never have to accumulate deltas to know the balance (task 18's `HUDModel`
 * javadoc called out the absence of a starting figure as exactly why it could not).
 * {@code delta} and {@code source} are carried alongside for the notification/audit paths.
 *
 * @param balance        wallet balance AFTER this mutation (€)
 * @param delta          signed change (€) — positive income, negative expense
 * @param source         the income/expense source tag (e.g. {@code "berth_base"}, {@code "tug_job"})
 * @param vesselId       the vessel this money is attributable to, or {@code null} for port-wide costs
 * @param simTimeMillis  sim time of the mutation
 */
public record WalletChangedEvent(double balance, double delta, String source, String vesselId,
                                  long simTimeMillis) implements Event {
}
