package it.unige.portcommand.behaviours.coordination;

import java.util.Map;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugJob;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Position;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second escort leg: shepherds the vessel from the pickup point to its assigned
 * berth. The destination is the berth carried in the ACCEPT ({@code currentJob}) —
 * the HarbourMaster is authoritative about it. The tug never reads a vessel agent's
 * live state; it publishes its own position each tick to {@link it.unige.portcommand.artifacts.PortStateArtifact}.
 *
 * <p>On arrival it INFORMs the client ({@code escort_complete}) and hands off to
 * {@link ReturnToBaseBehaviour}: work + next behaviour FIRST, {@code removeBehaviour(this)}
 * LAST (nulls {@code myAgent}).
 *
 * <p>Note (task 08 doc-truth): task 06's {@code PortStateArtifact} carries no
 * per-vessel position channel yet, so "subscribe to the artefact for the vessel
 * position" is not wired here — the escort tracks the HM-assigned berth instead. When
 * a vessel-position channel lands (task 18), the leg can follow the live vessel.
 */
public final class EscortToBerthBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(EscortToBerthBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;

    private final TugAgent tug;
    private final Position berth;
    private long lastSimMillis;

    public EscortToBerthBehaviour(TugAgent tug) {
        super(tug, tug.simClock(), TICK_SIM_MILLIS);
        this.tug = tug;
        TugJob job = tug.currentJob();
        this.berth = (job != null && job.berthPosition() != null) ? job.berthPosition() : tug.position();
        this.lastSimMillis = tug.simClock().nowSimMillis();
    }

    @Override
    protected void onSimTick() {
        long now = simClock().nowSimMillis();
        double dtSimSeconds = (now - lastSimMillis) / 1000.0;
        lastSimMillis = now;
        if (dtSimSeconds <= 0.0) {
            return;
        }
        boolean arrived = tug.advanceToward(berth, dtSimSeconds);
        tug.pushState();
        if (arrived) {
            informEscortComplete();
            log.info("{} delivered vessel to berth {} -> returning to base", tug.tugId(), berth);
            tug.setStatus(TugStatus.RETURNING);
            ReturnToBaseBehaviour ret = new ReturnToBaseBehaviour(tug);
            tug.setActiveMovement(ret);
            tug.addBehaviour(ret);
            tug.removeBehaviour(this); // LAST: nulls myAgent
        }
    }

    private void informEscortComplete() {
        TugJob job = tug.currentJob();
        if (job == null || job.client() == null) {
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(job.client());
        if (job.conversationId() != null) {
            inform.setConversationId(job.conversationId()); // correlate completion to the CNP session
        }
        inform.setContent(TerminalJson.write(Map.of(
                "event", "escort_complete", "vessel_id", String.valueOf(job.vesselId()))));
        tug.send(inform);
    }
}
