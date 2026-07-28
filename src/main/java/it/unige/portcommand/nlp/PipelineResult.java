package it.unige.portcommand.nlp;

import java.util.List;

import jade.lang.acl.ACLMessage;

/**
 * Outcome of {@link NLPPipeline#processChatInput}. Exactly three shapes — a chat turn is
 * always routed, needs a clarification, or failed; callers switch exhaustively, no default
 * branch needed.
 */
public sealed interface PipelineResult {

    /**
     * The turn resolved to a ready-to-send ACL message.
     *
     * @param addressee the vessel id a 16-M2 vocative bound ("Genoa Star: …" / "tell the tanker …"),
     *                  or {@code null} for an unaddressed turn. Surfaced so the HarbourMaster / ChatPanel
     *                  can route between concurrent Busy-Day walk-ins (UI tabs are task 19); the ACL
     *                  itself carries the conversation id, this is the resolved routing target.
     */
    record Routed(ACLMessage msg, String addressee) implements PipelineResult {

        /** An unaddressed routed turn (no vocative). */
        public Routed(ACLMessage msg) {
            this(msg, null);
        }
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
