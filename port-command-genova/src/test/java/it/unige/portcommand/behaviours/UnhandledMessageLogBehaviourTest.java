package it.unige.portcommand.behaviours;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Template-composition unit tests for the per-agent catch-all (the backlog's
 * {@code MessageTemplate.not(...)} guard). Lives in the PARENT {@code behaviours} package, not a
 * catalogued sub-package — {@code BehaviourCatalogueTest} counts {@code .class} files there and a
 * test class would be miscounted against the locked 51 (INVARIANTS).
 *
 * <p>These assert the TEMPLATE, headlessly and without a JADE platform, because the template is
 * what makes the catch-all safe: it can win any scheduler pass, so anything it matches that a real
 * handler also matches is a stolen message.
 *
 * <p>These are TEMPLATE-COMPOSITION tests over synthetic unions — they do not evaluate any agent's
 * real {@code handledTemplate()}, and no test yet drives a live agent's catch-all end to end. The
 * completeness of each real union was established by enumerating every {@code receive} site in
 * {@code src/main} against it, by hand and in adversarial review, not by a test.
 */
class UnhandledMessageLogBehaviourTest {

    /** Rebuilds exactly what the behaviour's constructor composes, without needing an Agent. */
    private static MessageTemplate catchAllFor(MessageTemplate handled) {
        MessageTemplate gameMessages = MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY);
        return handled == null ? gameMessages
                : MessageTemplate.and(gameMessages, MessageTemplate.not(handled));
    }

    private static ACLMessage gameMessage(int performative) {
        ACLMessage m = MessageFactory.create(performative);
        m.setContent(TerminalJson.write(Map.of("intent", "whatever")));
        return m;
    }

    @Test
    void aMessageTheAgentAlreadyHandlesIsNeverMatched() {
        MessageTemplate handled = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
        assertFalse(catchAllFor(handled).match(gameMessage(ACLMessage.REQUEST)),
                "the catch-all must never match what a real handler matches — JADE has no "
                        + "behaviour priority, so an overlap is a stolen message");
    }

    @Test
    void anUnhandledGameMessageIsMatched() {
        MessageTemplate handled = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
        assertTrue(catchAllFor(handled).match(gameMessage(ACLMessage.QUERY_REF)),
                "a game message no template claims is exactly what this exists to catch");
    }

    @Test
    void platformTrafficIsNeverMatchedEvenWhenTheAgentHandlesNothing() {
        // WeatherAgent and AssistantAgent receive nothing, so their union is null and the
        // not(...) degenerates to "match everything". The ontology gate is the only thing keeping
        // the catch-all off the DF/AMS replies PortCommandAgent.registerDfService and
        // ServiceLocator.search deliberately tolerate arriving late.
        ACLMessage dfReply = new ACLMessage(ACLMessage.INFORM);
        dfReply.setOntology("FIPA-Agent-Management");
        dfReply.setProtocol("fipa-request");

        assertFalse(catchAllFor(null).match(dfReply),
                "platform traffic must never be swallowed by an agent that handles nothing");
        assertTrue(catchAllFor(null).match(gameMessage(ACLMessage.INFORM)),
                "but a real game message addressed to such an agent IS unhandled by definition");
    }

    @Test
    void aBareAclMessageWithNoOntologyIsIgnored() {
        // Anything not built through MessageFactory is by definition not ours.
        assertFalse(catchAllFor(null).match(new ACLMessage(ACLMessage.PROPOSE)),
                "a message with no ontology set is not a port_command_v1 message");
    }

    @Test
    void theHarbourMastersCnpExclusionKeepsLiveBidsSafe() {
        // The HarbourMaster's union includes the whole CNP protocol tag precisely because
        // BidCollectorBehaviour builds its template per-cfpId at runtime and cannot be named
        // statically. Without the exclusion the catch-all could win a scheduler pass against an
        // in-window bid and eat it.
        MessageTemplate hmUnion = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchProtocol("fipa-contract-net"));
        ACLMessage bid = gameMessage(ACLMessage.PROPOSE);
        bid.setProtocol("fipa-contract-net");
        bid.setConversationId("cfp-whatever");

        assertFalse(catchAllFor(hmUnion).match(bid),
                "a Contract Net bid must be invisible to the catch-all at all times");
    }
}
