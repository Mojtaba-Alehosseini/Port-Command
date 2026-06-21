package it.unige.portcommand.behaviours.coordination;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.BerthRequest;
import it.unige.portcommand.agents.TerminalState;
import it.unige.portcommand.artifacts.BerthOccupancyUpdate;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminal-side handler for berth-assignment REQUESTs from the HarbourMaster.
 * Replies CONFIRM (with the assigned crane) or one of five REFUSE reasons. The
 * malformed guard runs first so a bad payload never reaches the compatibility or
 * state logic. All replies go through {@link MessageFactory#reply} so the
 * {@code port_command_v1}/{@code json} envelope and conversation id are preserved.
 */
public final class HandleBerthRequestBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleBerthRequestBehaviour.class);
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    private final TerminalState state;
    private final PortStateArtifact portState;

    public HandleBerthRequestBehaviour(Agent agent, TerminalState state, PortStateArtifact portState) {
        super(agent);
        this.state = state;
        this.portState = portState;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        log.info("REQUEST received from {}", msg.getSender().getLocalName());

        BerthRequest req = TerminalJson.readOrNull(msg.getContent(), BerthRequest.class);
        if (req == null || !valid(req)) {
            refuse(msg, "malformed", null);
            return;
        }
        if (!state.manages(req.berthId())) {
            refuse(msg, "wrong_terminal", null);
            return;
        }
        if (!PrologQueries.isCompatible(req.berthId(), req.vesselType(), req.draft(), req.length(), req.tonnage())) {
            refuse(msg, "incompatible", null);
            return;
        }
        TerminalState.Result result =
                state.requestBerth(req.berthId(), req.vesselId(), req.etaSim(), req.durationHours());
        if (!result.confirmed()) {
            Long freeAt = "berth_busy".equals(result.reason())
                    ? state.berth(req.berthId()).map(BerthOccupancy::expectedFreeAtSim).orElse(null)
                    : null;
            refuse(msg, result.reason(), freeAt);
            return;
        }
        state.berth(req.berthId())
                .ifPresent(occ -> portState.update(new BerthOccupancyUpdate(req.berthId(), occ)));
        confirm(msg, req.berthId(), result.craneId());
    }

    private boolean valid(BerthRequest r) {
        return notBlank(r.vesselId()) && notBlank(r.vesselType()) && notBlank(r.berthId())
                && r.draft() > 0 && r.length() > 0 && r.tonnage() > 0 && r.durationHours() > 0;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private void confirm(ACLMessage original, String berthId, int craneId) {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.CONFIRM);
        reply.setContent(TerminalJson.write(Map.of("berth_id", berthId, "crane_assigned", craneId)));
        myAgent.send(reply);
        log.info("CONFIRM berth={} crane={} -> {}", berthId, craneId, original.getSender().getLocalName());
    }

    private void refuse(ACLMessage original, String reason, Long freeAtSim) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reason", reason);
        if (freeAtSim != null) {
            content.put("free_at_sim", freeAtSim);
        }
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.REFUSE);
        reply.setContent(TerminalJson.write(content));
        myAgent.send(reply);
        log.info("REFUSE {} -> {}", reason, original.getSender().getLocalName());
    }
}
