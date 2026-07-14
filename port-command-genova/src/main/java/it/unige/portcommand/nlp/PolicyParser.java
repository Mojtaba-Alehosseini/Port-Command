package it.unige.portcommand.nlp;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.artifacts.PolicyRule.PolicyAction;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PolicyParsedEvent;
import it.unige.portcommand.util.EventBus;

/**
 * Parses a player-typed autopilot policy expression into a typed {@link PolicyRule} using a
 * small regex-DSL (planning/10 §10.5b) — NOT a second Rasa pipeline (explicitly cut,
 * PROJECT_DEFINITION §9 item 6; {@code NLPPipeline} routes {@code set_policy} text here
 * verbatim, ignoring Rasa's entities on that path).
 *
 * <p>Grammar (informal):
 * <pre>
 * POLICY      := "auto" ACTION "if" CONDITION ["for" VESSEL_TYPE]
 * ACTION      := "accept" | "reject" | "counter" "+"? INT "%"
 * CONDITION   := FIELD OP NUMBER ("and" CONDITION)?
 * FIELD       := "price" | "duration" | "rounds_used"
 * OP          := ">" | "<" | ">=" | "<=" | "="
 * VESSEL_TYPE := "tankers" | "container_vessels" | "cargo_vessels" | "ferries" | "cruise_ships"
 * </pre>
 */
public final class PolicyParser {

    private static final Pattern TOP_LEVEL = Pattern.compile(
            "^auto\\s+(.+?)\\s+if\\s+(.+?)(?:\\s+for\\s+(\\S+))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTER_ACTION = Pattern.compile(
            "^counter\\s*\\+?\\s*(\\d+)\\s*%$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITION_CLAUSE = Pattern.compile(
            "^(price|duration|rounds_used)\\s*(>=|<=|>|<|=)\\s*(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND_SPLIT = Pattern.compile("\\s+and\\s+", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> VESSEL_TYPE_PLURALS = Map.of(
            "tankers", "tanker",
            "container_vessels", "container_vessel",
            "cargo_vessels", "cargo_vessel",
            "ferries", "ferry",
            "cruise_ships", "cruise_ship");

    /**
     * Parses {@code text}. On success, publishes {@link PolicyParsedEvent} (consumed by
     * {@code PolicyParseBehaviour}) and returns the rule. On failure, publishes a clarification
     * {@link NotificationEvent} and returns empty — there is no throw path.
     */
    public Optional<PolicyRule> parse(String text, EventBus eventBus) {
        Optional<PolicyRule> rule = tryParse(text);
        if (rule.isPresent()) {
            eventBus.publish(new PolicyParsedEvent(rule.get()));
        } else {
            eventBus.publish(new NotificationEvent(
                    "Could not parse policy: " + text, NotificationEvent.Severity.WARNING, 0L));
        }
        return rule;
    }

    private Optional<PolicyRule> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalised = text.trim().replaceAll("\\s+", " ");
        Matcher top = TOP_LEVEL.matcher(normalised);
        if (!top.matches()) {
            return Optional.empty();
        }
        Optional<PolicyAction> action = parseAction(top.group(1).trim());
        if (action.isEmpty()) {
            return Optional.empty();
        }
        Optional<Predicate<WalkInDialogueSnapshot>> condition = parseCondition(top.group(2).trim());
        if (condition.isEmpty()) {
            return Optional.empty();
        }
        Predicate<WalkInDialogueSnapshot> predicate = condition.get();

        String vesselTypeWord = top.group(3);
        if (vesselTypeWord != null) {
            Optional<String> canonical = canonicalVesselType(vesselTypeWord);
            if (canonical.isEmpty()) {
                return Optional.empty();
            }
            predicate = predicate.and(snap -> snap.vesselType().equals(canonical.get()));
        }
        return Optional.of(new PolicyRule(text, predicate, action.get()));
    }

    private static Optional<PolicyAction> parseAction(String actionText) {
        if (actionText.equalsIgnoreCase("accept")) {
            return Optional.of(PolicyAction.accept());
        }
        if (actionText.equalsIgnoreCase("reject")) {
            return Optional.of(PolicyAction.rejectSilent());
        }
        Matcher counter = COUNTER_ACTION.matcher(actionText);
        if (counter.matches()) {
            double pct = Double.parseDouble(counter.group(1)) / 100.0;
            return Optional.of(PolicyAction.counter(pct));
        }
        return Optional.empty();
    }

    private static Optional<Predicate<WalkInDialogueSnapshot>> parseCondition(String conditionText) {
        Predicate<WalkInDialogueSnapshot> combined = null;
        for (String clause : AND_SPLIT.split(conditionText)) {
            Matcher m = CONDITION_CLAUSE.matcher(clause.trim());
            if (!m.matches()) {
                return Optional.empty();
            }
            Predicate<WalkInDialogueSnapshot> clausePredicate = fieldPredicate(
                    m.group(1).toLowerCase(Locale.ROOT), m.group(2), Double.parseDouble(m.group(3)));
            combined = combined == null ? clausePredicate : combined.and(clausePredicate);
        }
        return Optional.ofNullable(combined);
    }

    private static Predicate<WalkInDialogueSnapshot> fieldPredicate(String field, String op, double value) {
        ToDoubleFunction<WalkInDialogueSnapshot> accessor = switch (field) {
            case "price" -> WalkInDialogueSnapshot::lastVesselOffer;
            case "duration" -> snap -> snap.durationHours();
            case "rounds_used" -> snap -> snap.roundsUsed();
            default -> throw new IllegalStateException("unreachable: field regex restricts to 3 values");
        };
        return switch (op) {
            case ">" -> snap -> accessor.applyAsDouble(snap) > value;
            case "<" -> snap -> accessor.applyAsDouble(snap) < value;
            case ">=" -> snap -> accessor.applyAsDouble(snap) >= value;
            case "<=" -> snap -> accessor.applyAsDouble(snap) <= value;
            // Exact float equality is fragile against a computed offer; a small epsilon keeps
            // "price = 2200" usable without changing the DSL's documented operator set.
            case "=" -> snap -> Math.abs(accessor.applyAsDouble(snap) - value) < 1e-6;
            default -> throw new IllegalStateException("unreachable: op regex restricts to 5 values");
        };
    }

    private static Optional<String> canonicalVesselType(String word) {
        return Optional.ofNullable(VESSEL_TYPE_PLURALS.get(word.toLowerCase(Locale.ROOT)));
    }
}
