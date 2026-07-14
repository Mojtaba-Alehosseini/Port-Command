package it.unige.portcommand.nlp;

import java.util.Map;

/**
 * Stub of the DCG-parsed negotiation-move frame (FrameNet {@code Commerce_sell} roles:
 * seller/buyer/goods/money/duration/deadline/move — PROJECT_DEFINITION.md §6.2). Full
 * implementation is task 16's; this task only needs the record shape so {@link DCGParser}
 * and {@link FrameToAcl} can be wired end-to-end ahead of the real grammar.
 *
 * <p>{@code frameName} carries the DCG move type ({@code propose}/{@code counter}/
 * {@code accept}/{@code reject}/{@code ask}) that {@link FrameToAcl} switches on.
 */
public record Frame(String frameName, Map<String, Object> elements) {

    public Frame {
        elements = elements == null ? Map.of() : Map.copyOf(elements);
    }
}
