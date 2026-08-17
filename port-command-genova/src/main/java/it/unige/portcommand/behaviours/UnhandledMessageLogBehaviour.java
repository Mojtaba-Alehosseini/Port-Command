package it.unige.portcommand.behaviours;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import com.fasterxml.jackson.databind.JsonNode;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-agent catch-all: consumes and reports any game message no other behaviour on this agent
 * matches (2026-08-10, the backlog's "catch-all {@code MessageTemplate.not(...)} guard per agent",
 * which the audit calls the highest-value structural item — it would have caught BUG-01, BUG-03 and
 * A-01, three separate leaks each found only by a human noticing silence).
 *
 * <p>Lives in the UNCOUNTED parent {@code behaviours} package on purpose: {@code
 * BehaviourCatalogueTest} counts {@code .class} files in the five catalogued sub-packages against a
 * locked 51, so a new class in {@code behaviours/coordination} would fail the build. Same precedent
 * as {@code SimTickerBehaviour} and {@code SimClockTickPublisherBehaviour}.
 *
 * <h2>Why the ontology gate is not optional</h2>
 * The template is {@code and(MatchOntology(port_command_v1), not(handled))}. Two agents —
 * WeatherAgent and AssistantAgent — receive nothing at all, so their {@code not(handled)} is
 * {@code not(nothing)}, i.e. <em>match everything</em>. Without the gate they would swallow the
 * platform's own traffic, including the late DF replies {@code PortCommandAgent.registerDfService}
 * and {@code ServiceLocator.search} explicitly tolerate. Every game message carries the ontology by
 * construction ({@code MessageFactory.create}/{@code reply}); no AMS/DF message does.
 *
 * <h2>Why this cannot steal a live message</h2>
 * JADE offers no behaviour priority — the scheduler round-robins the ready queue, so registration
 * order buys nothing and this behaviour can win any pass. Safety therefore comes entirely from the
 * union each agent declares in {@code handledTemplate()}, which must cover every template that
 * agent can EVER match, including ones built later at runtime. Two cases need care and are handled
 * at the declaration sites: the HarbourMaster's {@code BidCollectorBehaviour} builds a per-cfpId
 * template, so the HarbourMaster excludes the whole CNP protocol tag; the vessels' per-conversation
 * templates are covered by conversation id, which each vessel can derive from its own name.
 *
 * <h2>De-duplicated WARN</h2>
 * The first occurrence of each distinct (performative, sender, intent) logs at WARN; repeats are
 * counted and logged at DEBUG. Without this a repeated unconsumed shape would repeat per vessel
 * per alert and bury the log during a storm. The message is always CONSUMED either way, which is
 * the point: an unmatched message otherwise sits in the agent's queue for the life of the JVM.
 * <em>History:</em> the three shapes that originally motivated this dedup were the HarbourMaster's
 * {@code tug_hold}, {@code weather_hold} and {@code weather_resume} INFORMs to vessels; since S8
 * (B-01, ADR-17) the two weather ones are protocol-tagged and CONSUMED by the vessels'
 * {@code HandleWeatherHoldBehaviour} — only {@code tug_hold} still lands here.
 */
public final class UnhandledMessageLogBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(UnhandledMessageLogBehaviour.class);

    private final MessageTemplate template;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final AtomicInteger consumed = new AtomicInteger();

    /**
     * @param handled every template this agent's other behaviours can match, or {@code null} for an
     *                agent that receives nothing (Weather, Assistant) — then every game message
     *                addressed to it is by definition unhandled.
     */
    public UnhandledMessageLogBehaviour(Agent agent, MessageTemplate handled) {
        super(agent);
        MessageTemplate gameMessages = MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY);
        this.template = handled == null
                ? gameMessages
                : MessageTemplate.and(gameMessages, MessageTemplate.not(handled));
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(template);
        if (msg == null) {
            block();
            return;
        }
        consumed.incrementAndGet();
        String sender = msg.getSender() == null ? "unknown" : msg.getSender().getLocalName();
        String performative = performativeName(msg);
        String intent = intentOf(msg);
        String key = performative + '|' + sender + '|' + intent;
        if (reported.add(key)) {
            log.warn("UNHANDLED {} from {} (conversation={}, intent={}) — consumed by the catch-all. "
                            + "No behaviour on {} matches it; further identical messages log at DEBUG.",
                    performative, sender, msg.getConversationId(), intent, myAgent.getLocalName());
        } else {
            log.debug("UNHANDLED {} from {} (repeat #{})", performative, sender, consumed.get());
        }
    }

    /** Test seam: how many unmatched messages this agent has swallowed. */
    public int consumedCount() {
        return consumed.get();
    }

    /** Test seam: how many DISTINCT (performative, sender, intent) shapes were reported at WARN. */
    public int distinctShapeCount() {
        return reported.size();
    }

    private static String performativeName(ACLMessage msg) {
        try {
            return ACLMessage.getPerformative(msg.getPerformative());
        } catch (RuntimeException e) {
            return "performative-" + msg.getPerformative();
        }
    }

    /** Best-effort: most game content is a JSON object with an {@code intent} or {@code event}. */
    private static String intentOf(ACLMessage msg) {
        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        if (content == null) {
            return "none";
        }
        JsonNode intent = content.get("intent");
        if (intent != null && intent.isTextual()) {
            return intent.asText();
        }
        JsonNode event = content.get("event");
        return event != null && event.isTextual() ? event.asText() : "none";
    }
}
