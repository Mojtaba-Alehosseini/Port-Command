package it.unige.portcommand.agents;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.behaviours.coordination.HandleBerthRequestBehaviour;
import it.unige.portcommand.behaviours.coordination.ProcessCargoHandlingBehaviour;
import it.unige.portcommand.behaviours.coordination.RetractIfFloodBehaviour;
import it.unige.portcommand.util.SimClock;

/**
 * Terminal (two instances: container, general cargo). Owns berth occupancy +
 * crane allocation via {@link TerminalState}, handles berth REQUESTs, runs a
 * sim-clock cargo ticker, and retracts on flood. The live {@link PortStateArtifact}
 * and {@link SimClock} are injected through the {@code Object[]} args channel
 * (args[1], args[2]) — not the serializable {@code TerminalInitArgs} (ADR-03).
 */
public final class TerminalAgent extends PortCommandAgent {

    private String terminalId;
    private TerminalState state;

    @Override
    protected void registerServices() {
        registerDfService("terminal", getLocalName());
    }

    @Override
    protected void onSetup() {
        InitArgs.TerminalInitArgs args = initArg(InitArgs.TerminalInitArgs.class);
        if (args == null) {
            throw new IllegalStateException(
                    "TerminalAgent " + getLocalName() + " requires TerminalInitArgs at args[0]");
        }
        this.terminalId = args.terminalId();
        this.state = new TerminalState(args.managedBerths(), args.cranesTotal());

        PortStateArtifact portState = argAt(1, PortStateArtifact.class);
        SimClock simClock = argAt(2, SimClock.class);

        addBehaviour(new HandleBerthRequestBehaviour(this, state, portState));
        addBehaviour(new ProcessCargoHandlingBehaviour(this, simClock, state, portState, getServiceLocator()));
        addBehaviour(new RetractIfFloodBehaviour(this, state, portState, getServiceLocator()));
    }

    private <T> T argAt(int index, Class<T> type) {
        Object[] a = getArguments();
        if (a == null || a.length <= index || a[index] == null) {
            throw new IllegalStateException("TerminalAgent " + getLocalName() + " requires "
                    + type.getSimpleName() + " at args[" + index + "]");
        }
        return type.cast(a[index]);
    }

    String terminalId() {
        return terminalId;
    }

    TerminalState state() {
        return state;
    }
}
