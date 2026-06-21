package it.unige.portcommand.agents;

import java.util.List;

/**
 * Terminal (two instances: container, general cargo). Stub: registers DF
 * {@code terminal} and reads its {@link InitArgs.TerminalInitArgs}. Behaviours
 * (berth-request handling, cargo flow) are attached by task 06.
 */
public final class TerminalAgent extends PortCommandAgent {

    private String terminalId;
    private List<String> managedBerths = List.of();
    private int cranesTotal;

    @Override
    protected void registerServices() {
        registerDfService("terminal", getLocalName());
    }

    @Override
    protected void onSetup() {
        InitArgs.TerminalInitArgs args = initArg(InitArgs.TerminalInitArgs.class);
        if (args != null) {
            this.terminalId = args.terminalId();
            this.managedBerths = args.managedBerths();
            this.cranesTotal = args.cranesTotal();
        }
    }

    String terminalId() {
        return terminalId;
    }

    List<String> managedBerths() {
        return managedBerths;
    }

    int cranesTotal() {
        return cranesTotal;
    }
}
