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

    public synchronized void register(PolicyRule rule) {
        rules.add(rule);
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
}
