package it.unige.portcommand.artifacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;

/**
 * Thread-safe, registration-ordered store of player-typed autopilot policies (planning/10
 * §10.5). One instance per Assistant; {@code PolicyParseBehaviour} registers on every
 * {@code PolicyParsedEvent}, {@code AutopilotExecuteBehaviour} reads via {@link #firstMatching}
 * before falling back to the EV algorithm.
 */
public final class PolicyRegistryArtifact {

    private final List<PolicyRule> rules = new ArrayList<>();

    /**
     * Adds {@code rule} at the end of the list - unless the same sentence is already in it.
     *
     * <p>S18a (ADR-23, decision N11): "the same sentence" is the player's own text, compared
     * without regard to case or spacing. A second copy could never match anything the first had
     * not ({@link #firstMatching} stops at the first), so dropping it changes no decision; it
     * only keeps the list the player is shown, and every save, free of repeats. A DIFFERENT rule
     * is always added, and never reordered: which rule comes first is the player's to see and to
     * clear, not the registry's to decide.
     *
     * @return {@code true} if the rule was added, {@code false} if it was already there
     */
    public synchronized boolean register(PolicyRule rule) {
        String sentence = normalised(rule.trigger());
        for (PolicyRule existing : rules) {
            if (normalised(existing.trigger()).equals(sentence)) {
                return false;
            }
        }
        rules.add(rule);
        return true;
    }

    private static String normalised(String trigger) {
        return trigger.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** The first registered rule (registration order) whose predicate matches {@code snapshot}. */
    public synchronized Optional<PolicyRule> firstMatching(WalkInDialogueSnapshot snapshot) {
        for (PolicyRule rule : rules) {
            if (rule.predicate().test(snapshot)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** All registered rules, in registration order (read-only snapshot; test/HUD introspection). */
    public synchronized List<PolicyRule> all() {
        return List.copyOf(rules);
    }

    /**
     * Drops every registered rule (task 22 load): the loader re-parses the save file's
     * persisted trigger strings through the live {@code nlp.PolicyParser} path, which
     * re-registers each rule here with a freshly built predicate.
     */
    public synchronized void clear() {
        rules.clear();
    }
}
