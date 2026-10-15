package it.unige.portcommand.bootstrap;

import it.unige.portcommand.agents.InitArgs;

/** Test-only bridge to {@link AgentRoster}'s package-private spawn args (S19, PT39). */
public final class AgentRosterProbe {

    private AgentRosterProbe() {
    }

    public static InitArgs.TugInitArgs tugInitArgs(String tugId) {
        return AgentRoster.tugInitArgs(tugId);
    }
}
