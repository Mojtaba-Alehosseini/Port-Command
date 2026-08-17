package it.unige.portcommand.behaviours.auto_flow;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.TerminalJson;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vessel-side half of the weather hold (B-01, S8 — behaviour #52, ADR-17). The
 * HarbourMaster's {@code weather_hold}/{@code weather_resume} INFORMs (protocol-tagged since
 * S8; before that they fell into the vessel's catch-all and were IGNORED — the B-01 bug: the
 * hold released the escort but the vessel's own {@code TransitToBerthBehaviour} ticked to its
 * absolute deadline and docked mid-storm) now set/clear {@link BaseVesselAgent#weatherHeld()},
 * which the transit ticker consults to FREEZE its deadline (a sliding deadline — see
 * {@code TransitToBerthBehaviour}).
 *
 * <p>The flag is a MIRROR of the HarbourMaster's tracking flag, kept by these INFORMs during
 * live play and seeded from the persisted DTO on restore. A lost INFORM would desynchronise
 * the two (accepted, stated in ADR-17: intra-container delivery makes that negligible). The
 * flag is harmless outside TRANSIT: only the transit ticker reads it, so a hold that lands on
 * an already-docked vessel (the race the HM's monotone stage guard already tolerates) is inert.
 */
public final class HandleWeatherHoldBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleWeatherHoldBehaviour.class);

    /** Set by the HarbourMaster on both hold/resume INFORMs (HandleWeatherAlertBehaviour). */
    public static final String VESSEL_WEATHER_PROTOCOL = "port-command-vessel-weather";
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(VESSEL_WEATHER_PROTOCOL),
            MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    public HandleWeatherHoldBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        String event = content == null || content.get("event") == null
                ? null : content.get("event").asText();
        if ("weather_hold".equals(event)) {
            vessel.setWeatherHeld(true);
            log.info("{}: weather HOLD received -> transit suspended", myAgent.getLocalName());
        } else if ("weather_resume".equals(event)) {
            vessel.setWeatherHeld(false);
            log.info("{}: weather RESUME received -> transit continues", myAgent.getLocalName());
        } else {
            log.warn("{}: unrecognised vessel-weather event '{}' — ignoring", myAgent.getLocalName(), event);
        }
    }
}
