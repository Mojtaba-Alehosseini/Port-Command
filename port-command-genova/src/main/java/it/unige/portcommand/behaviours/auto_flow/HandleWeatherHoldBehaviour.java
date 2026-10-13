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
 * The vessel's receiver for the HarbourMaster's MOVEMENT ORDERS (behaviour #52; B-01, S8,
 * ADR-17 — widened in S16, ADR-20).
 *
 * <p>It began as the vessel-side half of the weather hold: before S8 the HarbourMaster's
 * {@code weather_hold}/{@code weather_resume} INFORMs fell into the vessel's catch-all and were
 * ignored, and the ship docked in mid-storm. Since S16 a ship moves only when it is told to, and
 * every such order arrives here, on the one protocol tag ({@link #VESSEL_WEATHER_PROTOCOL} — the
 * name is kept because the comm log's Weather view and its tests are keyed on it):
 *
 * <ul>
 *   <li>{@code proceed_to_berth} — leave the channel entry and make the channel leg, which lasts
 *       {@code transit_sim_millis}. Sent once the escort is on station, customs has cleared the
 *       ship, the berth is open and the movement gate finds the weather safe.</li>
 *   <li>{@code weather_hold} — stop: a ship under way goes back to the channel entry, a ship
 *       already waiting keeps waiting.</li>
 *   <li>{@code weather_resume} — the hold is over and the escort has been hired again. News
 *       only: the ship still waits for its {@code proceed_to_berth}.</li>
 *   <li>{@code cleared_to_depart} — a served ship may leave its berth. It asked with
 *       {@code service_complete} and waits at the berth until this arrives.</li>
 *   <li>{@code turned_away} — customs refused the ship; it leaves without docking.</li>
 * </ul>
 *
 * <p>The vessel holds no "held" flag of its own any more (the pre-S16 mirror of the
 * HarbourMaster's tracking flag): where it is — waiting, moving, docked — is its flow phase, and
 * the orders move it between them ({@code BaseVesselAgent.onProceedToBerth} / {@code onHold} /
 * {@code onClearedToDepart}).
 */
public final class HandleWeatherHoldBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleWeatherHoldBehaviour.class);

    /** Set by the HarbourMaster on every movement order it sends a vessel. */
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
        if (event == null) {
            log.warn("{}: movement order without an event — ignoring", myAgent.getLocalName());
            return;
        }
        switch (event) {
            case "proceed_to_berth" -> {
                long transit = content.hasNonNull("transit_sim_millis")
                        ? content.get("transit_sim_millis").asLong()
                        : TransitToBerthBehaviour.DEFAULT_TRANSIT_SIM_MILLIS;
                log.info("{}: PROCEED received -> channel leg of {} sim-s", myAgent.getLocalName(),
                        transit / 1000L);
                vessel.onProceedToBerth(transit);
            }
            case "weather_hold" -> {
                log.info("{}: weather HOLD received -> waiting at the channel entry", myAgent.getLocalName());
                vessel.onHold();
            }
            case "weather_resume" ->
                    log.info("{}: weather RESUME received -> waiting for the order to proceed",
                            myAgent.getLocalName());
            case "cleared_to_depart" -> {
                log.info("{}: cleared to depart", myAgent.getLocalName());
                vessel.onClearedToDepart();
            }
            case "turned_away" -> {
                log.info("{}: turned away ({}) -> leaving without docking", myAgent.getLocalName(),
                        content.hasNonNull("reason") ? content.get("reason").asText() : "no reason given");
                myAgent.doDelete();
            }
            default -> log.warn("{}: unrecognised movement order '{}' — ignoring",
                    myAgent.getLocalName(), event);
        }
    }
}
