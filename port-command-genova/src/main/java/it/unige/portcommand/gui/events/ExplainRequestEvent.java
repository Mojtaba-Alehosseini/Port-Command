package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Fired by the GUI "Why?" button (task 19) to ask the Assistant to regenerate a past decision's
 * explanation. {@code ExplainEventBehaviour} (planning/10 §10.8b) subscribes and looks the
 * decision up in its small bounded recommendation cache. See {@link HintButtonEvent}'s
 * ownership note.
 *
 * <p>S10 (bug hunt, hunter C finding 3): the event now carries the DIALOGUE id alongside the
 * decision id — every reply used to be published with {@code decisionId}
 * ({@code dialogueId + "-rN"}) as the {@code AssistantChatEvent} address, which the tab
 * registry's exact-match lookup silently dropped. Latent (no producer exists yet — the Why
 * button was never built), fixed before one is: the reply must address the TAB, the lookup
 * must address the CACHE.
 *
 * @param decisionId the id of a previously-issued {@code Recommendation} (the cache key)
 * @param dialogueId the tab the reply belongs in ({@code "nego-<vesselId>"})
 */
public record ExplainRequestEvent(String decisionId, String dialogueId) implements Event {
}
