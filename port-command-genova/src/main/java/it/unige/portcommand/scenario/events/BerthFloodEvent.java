package it.unige.portcommand.scenario.events;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.scenario.GameContext;
import jade.core.AID;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted berth flood — <b>the live DISCONFIRM trigger</b> (task 26, 2026-07-27).
 *
 * <p><b>Why this exists.</b> PROJECT_DEFINITION §13 and planning/26 both state that DISCONFIRM is
 * produced "via the scripted Busy-Day terminal-flood", and
 * {@code behaviours.coordination.RetractIfFloodBehaviour} has implemented the terminal half since
 * task 06. But nothing in the shipped game ever sent the flood INFORM that behaviour waits for —
 * only {@code TerminalAgentIT} did. So the "all 10 performatives across the three scenarios"
 * acceptance criterion was, in the live game, unreachable: 9 of 10. This event is the missing
 * half, and it is deliberately the smallest one — no new behaviour class (the catalogue stays at
 * 51), no new agent, no gameplay rule. It sends the message the canon already says is sent.
 *
 * <p><b>The exchange.</b> The alert is relayed BY the HarbourMaster, because the harbour master is
 * the port authority that would issue a berth-closure notice, and because the comm log's only
 * instrumentation point is the HM's own {@code sendLogged}/{@code receiveLogged} pair (INVARIANTS,
 * task 17). Relaying it there makes both halves of the exchange visible to the player and to the
 * end-of-day performative tally:
 *
 * <pre>
 *   harbour-master --INFORM {event:flood, berth_id}--&gt; every terminal   (broadcast; the
 *                                                                        non-owning terminal
 *                                                                        consumes and ignores)
 *   terminal_x     --DISCONFIRM {notice:retracted, berth_id}--&gt; harbour-master
 * </pre>
 *
 * <p>The berth is released terminal-side by {@code RetractIfFloodBehaviour}. The HarbourMaster
 * logs the retraction and warns the player ({@code AutoFlowDispatcherBehaviour}); it does not
 * re-plan the affected vessel — that is a gameplay change, filed in
 * {@code docs/POST_DEMO_BACKLOG.md} rather than smuggled into a hardening task.
 */
public record BerthFloodEvent(long simTimeSeconds, String berthId) implements ScriptedEvent {

    private static final Logger log = LoggerFactory.getLogger(BerthFloodEvent.class);

    @Override
    public void apply(GameContext ctx) {
        if (ctx.directory() == null) {
            log.warn("scripted berth flood: no agent directory — skipping {}", berthId);
            return;
        }
        List<HarbourMasterAgent> hms = ctx.directory().byType(HarbourMasterAgent.class);
        if (hms.isEmpty()) {
            log.warn("scripted berth flood: no HarbourMaster in the directory — skipping {}", berthId);
            return;
        }
        HarbourMasterAgent hm = hms.get(0);
        // addBehaviour from another thread is the JADE-sanctioned way to run code on an agent's
        // own thread (same pattern as TugRefuelCompleteEvent). The handler touches only the
        // captured final reference, never myAgent — task 19's nulled-myAgent lesson.
        hm.addBehaviour(new OneShotBehaviour(hm) {
            @Override
            public void action() {
                List<AID> terminals = hm.serviceLocator().findAll("terminal");
                if (terminals.isEmpty()) {
                    log.warn("scripted berth flood: no terminal registered in DF — {} not retracted", berthId);
                    return;
                }
                ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
                terminals.forEach(inform::addReceiver);
                inform.setContent(TerminalJson.write(Map.of("event", "flood", "berth_id", berthId)));
                hm.sendLogged(inform);
                log.info("scripted berth flood: INFORM flood {} -> {} terminal(s)", berthId, terminals.size());
            }
        });
        ctx.eventBus().publish(new NotificationEvent(
                "Flooding reported at " + berthId.replace('_', ' ') + " — the terminal is clearing it.",
                NotificationEvent.Severity.WARNING, ctx.simClock().nowSimMillis()));
    }
}
