package it.unige.portcommand.harbourmaster;

/** One expense event feeding {@link WalletLedger} — {@code DayRolloverCoordinator} (task 24) constructs these. */
public record ExpenseEvent(double amount, String source, String vesselId, long simTime) {
}
