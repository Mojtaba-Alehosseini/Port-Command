package it.unige.portcommand.nlp;

import java.util.concurrent.CompletableFuture;

/**
 * The GUI's seam onto {@link NLPPipeline#processChatInput} (task 19): a functional
 * interface rather than a direct dependency on the concrete {@code final} pipeline
 * class, so {@code ChatPanel}/{@code DialogueTabView} stay trivially testable with a
 * lambda stub instead of needing a real Rasa/DCG stack or a Mockito mock of a final
 * class. Production wiring is the method reference {@code nlpPipeline::processChatInput}.
 */
@FunctionalInterface
public interface ChatInputProcessor {

    CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx);

    /**
     * S8 (D-05 two-step): the cross-tab form — {@code ctxByVesselId} lets the pipeline re-focus
     * the context on a vocative's ADDRESSEE and re-parse, so "Carthago: make it 2200" completes
     * from CARTHAGO's standing offer, not the focused tab's. The default ignores the resolver
     * (lambda stubs in GUI tests keep their exact old behaviour); production overrides both
     * methods to reach {@code NLPPipeline}'s 3-arg overload.
     */
    default CompletableFuture<PipelineResult> process(String text, DialogueCtx ctx,
            java.util.function.Function<String, DialogueCtx> ctxByVesselId) {
        return process(text, ctx);
    }
}
