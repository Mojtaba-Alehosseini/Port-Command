package it.unige.portcommand.nlp;

import java.util.List;

/**
 * The fixed structured fallback offered when {@link NLPPipeline} cannot confidently route a
 * chat turn (PROJECT_DEFINITION.md §6.1 pipeline fallback). Five generic next-actions — not
 * context-prefilled (that would need per-dialogue state beyond this task's scope); task
 * 16/19 may replace this with a context-aware set once the DCG/context grammar lands.
 */
public final class ClarificationButtons {

    private ClarificationButtons() {
    }

    public static List<ButtonOption> defaultOptions() {
        return List.of(
                new ButtonOption("Make an offer", "propose_offer"),
                new ButtonOption("Accept the current offer", "accept_deal"),
                new ButtonOption("Reject the current offer", "reject_deal"),
                new ButtonOption("Check status", "query_status"),
                new ButtonOption("Talk to the assistant", "request_help"));
    }
}
