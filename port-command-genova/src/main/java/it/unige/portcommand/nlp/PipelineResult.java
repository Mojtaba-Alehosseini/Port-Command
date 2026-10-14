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
     * @param provenance which stage produced this result and with what confidence (S15) — what
     *                   the chat shows as {@code (DCG)} / {@code (Rasa 0.97)}. Reporting only;
     *                   never {@code null} ({@link ParseProvenance#UNKNOWN} when not stated).
     */
    record Routed(ACLMessage msg, String addressee, ParseProvenance provenance) implements PipelineResult {

        public Routed {
            provenance = provenance == null ? ParseProvenance.UNKNOWN : provenance;
        }

        /** An unaddressed routed turn (no vocative), source not stated. */
        public Routed(ACLMessage msg) {
            this(msg, null, ParseProvenance.UNKNOWN);
        }

        /** A routed turn whose source is not stated (test doubles, pre-S15 callers). */
        public Routed(ACLMessage msg, String addressee) {
            this(msg, addressee, ParseProvenance.UNKNOWN);
        }
    }

    /**
     * The turn was too ambiguous (low confidence, DCG miss + Rasa miss/timeout, or a
     * structural intent the DCG failed to parse); offer the fixed fallback buttons.
     *
     * @param message a specific reason to show INSTEAD of the generic "I didn't get that" line, or
     *                {@code null} for the generic one. Added 2026-07-28 for the D-05 vocative guard,
     *                which must name both vessels — "I didn't get that" would be actively
     *                misleading there, because the pipeline understood the sentence perfectly and
     *                declined it on purpose. Mirrors {@link Routed}'s optional-addressee shape.
     * @param aboutVesselId S18a (ADR-23): the vessel the refused move was FOR, when that is not
     *                the vessel of the tab it was typed in (a vocative); {@code null} otherwise.
     *                The chat takes the message's worked example from that vessel's tab - an
     *                example that is safe for the tab on screen can be far below another
     *                vessel's offer.
     */
    record NeedsClarification(List<ButtonOption> buttons, String message, String aboutVesselId)
            implements PipelineResult {

        /** The generic clarification (no specific reason). */
        public NeedsClarification(List<ButtonOption> buttons) {
            this(buttons, null, null);
        }

        /** A specific reason, about the vessel of the tab it was typed in. */
        public NeedsClarification(List<ButtonOption> buttons, String message) {
            this(buttons, message, null);
        }
    }

    /** A service-layer failure (not a timeout — those resolve to {@code NeedsClarification})
     * that could not be routed at all. */
    record Error(String reason) implements PipelineResult {
    }
}
