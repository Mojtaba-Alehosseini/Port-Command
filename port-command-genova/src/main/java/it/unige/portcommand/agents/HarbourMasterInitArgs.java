package it.unige.portcommand.agents;

/**
 * HarbourMaster startup config: opening wallet balance and reputation. Read at
 * {@code args[0]} like the other agents' init-args records, but deliberately NOT
 * part of the sealed {@link InitArgs} hierarchy — it is always constructed directly
 * in Java (by {@code AgentRoster} or a test), never (de)serialised from scenario JSON.
 */
public record HarbourMasterInitArgs(double startingWallet, double startingReputation) {
}
