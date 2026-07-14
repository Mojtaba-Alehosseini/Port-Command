package it.unige.portcommand.behaviours.coordination;

import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.BerthOccupancy.Status;
import it.unige.portcommand.agents.TerminalState;
import it.unige.portcommand.artifacts.BerthOccupancyUpdate;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a docked vessel's cargo handling off the sim clock (one tick per
 * sim-minute). On each tick it self-advances each berth from PROVISIONAL→DOCKED at
 * the vessel's ETA and DOCKED→FREE at {@code expectedFreeAtSim}, INFORMing the
 * HarbourMaster on completion. The HM is resolved via {@link ServiceLocator} on the
 * agent's own thread (the only safe place for a DF search). Comparing
 * {@code nowSimMillis()} to {@code expectedFreeAtSim} (rather than counting ticks)
 * keeps it correct across pauses and clock-scale changes.
 */
public final class ProcessCargoHandlingBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(ProcessCargoHandlingBehaviour.class);
    private static final long TICK_SIM_MILLIS = 60_000L; // one simulated minute

    private final TerminalState state;
    private final PortStateArtifact portState;
    private final ServiceLocator locator;

    public ProcessCargoHandlingBehaviour(Agent agent, SimClock simClock, TerminalState state,
                                         PortStateArtifact portState, ServiceLocator locator) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.state = state;
        this.portState = portState;
        this.locator = locator;
    }

    @Override
    protected void onSimTick() {
        long now = simClock().nowSimMillis();
        for (BerthOccupancy occ : state.occupancies()) {
            if (occ.status() == Status.PROVISIONAL && now >= occ.dockedAtSim()) {
                state.confirmDocking(occ.berthId())
                        .ifPresent(docked -> portState.update(new BerthOccupancyUpdate(occ.berthId(), docked)));
            } else if (occ.status() == Status.DOCKED && now >= occ.expectedFreeAtSim()) {
                String vesselId = occ.vesselId();
                state.releaseBerth(occ.berthId()).ifPresent(freed -> {
                    portState.update(new BerthOccupancyUpdate(occ.berthId(), freed));
                    informHandlingComplete(vesselId, occ.berthId());
                });
            }
        }
    }

    private void informHandlingComplete(String vesselId, String berthId) {
        Optional<AID> hm = locator.findUnique("harbour-master");
        if (hm.isEmpty()) {
            log.warn("no harbour-master in DF; dropping handling_complete for berth {}", berthId);
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hm.get());
        inform.setContent(TerminalJson.write(Map.of(
                "notice", "handling_complete",
                "vessel", vesselId == null ? "unknown" : vesselId, // defensive: scenario-seeded berths (task 23)
                "berth", berthId)));
        myAgent.send(inform);
        log.info("INFORM handling_complete vessel={} berth={} -> harbour-master", vesselId, berthId);
    }
}
