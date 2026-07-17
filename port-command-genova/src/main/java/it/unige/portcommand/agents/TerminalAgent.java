package it.unige.portcommand.agents;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.artifacts.BerthOccupancyUpdate;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.behaviours.coordination.HandleBerthRequestBehaviour;
import it.unige.portcommand.behaviours.coordination.ProcessCargoHandlingBehaviour;
import it.unige.portcommand.behaviours.coordination.RetractIfFloodBehaviour;
import it.unige.portcommand.persistence.Mementoable;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.util.SimClock;

/**
 * Terminal (two instances: container, general cargo). Owns berth occupancy +
 * crane allocation via {@link TerminalState}, handles berth REQUESTs, runs a
 * sim-clock cargo ticker, and retracts on flood. The live {@link PortStateArtifact}
 * and {@link SimClock} are injected through the {@code Object[]} args channel
 * (args[1], args[2]) — not the serializable {@code TerminalInitArgs} (ADR-03).
 */
public final class TerminalAgent extends PortCommandAgent implements Mementoable {

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

        // Task 22 restore: adopt persisted occupancy (already reconciled by the loader
        // against the restored vessel set) and push it to the shared artefact so the GUI
        // map shows restored berths immediately — no placeholder state after a load.
        TerminalStateDTO restored = optionalArgOfType(TerminalStateDTO.class);
        if (restored != null) {
            state.restoreOccupancies(restored.occupancies());
            for (BerthOccupancy occupancy : state.occupancies()) {
                portState.update(new BerthOccupancyUpdate(occupancy.berthId(), occupancy));
            }
            log.info("{}: restored {} berth occupancy record(s)", getLocalName(),
                    restored.occupancies().size());
        }

        addBehaviour(new HandleBerthRequestBehaviour(this, state, portState));
        addBehaviour(new ProcessCargoHandlingBehaviour(this, simClock, state, portState, getServiceLocator()));
        addBehaviour(new RetractIfFloodBehaviour(this, state, portState, getServiceLocator()));
    }

    /** The persisted terminal record (task 22): occupancies sorted by berth id (determinism rule). */
    public TerminalStateDTO snapshotDto() {
        return new TerminalStateDTO(terminalId, state.occupancies().stream()
                .sorted(java.util.Comparator.comparing(BerthOccupancy::berthId))
                .toList());
    }

    @Override
    public JsonNode snapshot() {
        return persistenceMapper().valueToTree(snapshotDto());
    }

    @Override
    public void restore(JsonNode node) {
        state.restoreOccupancies(
                persistenceMapper().convertValue(node, TerminalStateDTO.class).occupancies());
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
