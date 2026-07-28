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
}
