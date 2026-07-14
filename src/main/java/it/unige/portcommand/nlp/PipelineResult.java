package it.unige.portcommand.nlp;

import java.util.List;

import jade.lang.acl.ACLMessage;

/**
 * Outcome of {@link NLPPipeline#processChatInput}. Exactly three shapes — a chat turn is
 * always routed, needs a clarification, or failed; callers switch exhaustively, no default
 * branch needed.
 */
public sealed interface PipelineResult {

    /** The turn resolved to a ready-to-send ACL message. */
    record Routed(ACLMessage msg) implements PipelineResult {
    }

    /** The turn was too ambiguous (low confidence, DCG miss + Rasa miss/timeout, or a
     * structural intent the DCG failed to parse); offer the fixed fallback buttons. */
    record NeedsClarification(List<ButtonOption> buttons) implements PipelineResult {
    }

    /** A service-layer failure (not a timeout — those resolve to {@code NeedsClarification})
     * that could not be routed at all. */
    record Error(String reason) implements PipelineResult {
    }
}
