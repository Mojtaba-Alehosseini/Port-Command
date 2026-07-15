package it.unige.portcommand.harbourmaster;

/** One income event feeding {@link WalletLedger} — {@code DayRolloverCoordinator} (task 24) constructs these. */
public record IncomeEvent(double amount, String source, String vesselId, long simTime) {
}
