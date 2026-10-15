package it.unige.portcommand.gui.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import it.unige.portcommand.gui.events.CommLogEvent;

/**
 * S19 (playtest 5, PT43): how often each rule was cited in this game's comm log - the "used this
 * game: N" of Help ▸ Rules (30), and the counts the readable export ends with. Counted as messages
 * arrive, so a row the log has since let go of (it keeps 500) still counts; a new world starts at
 * zero ({@link #reset}). A message counts once per rule it cites, in its paraphrase or its content
 * - "granted (compatible [R7])" with {@code "reason": "compatible [R7]"} is one citation of R7.
 * Only ids the rule catalogue knows count. Pure: no Swing.
 */
public final class RuleCitations {

    /** R8 before R12: by the rule's number. */
    public static final Comparator<String> BY_NUMBER =
            Comparator.comparingInt(RuleCitations::number).thenComparing(Comparator.naturalOrder());

    private final Map<String, Integer> counts = new HashMap<>();

    /** Counts the rules {@code event} cites - in its paraphrase or its content, each rule once. */
    public void count(CommLogEvent event, Predicate<String> known) {
        Set<String> cited = new LinkedHashSet<>();
        for (String text : new String[] {event.paraphrase(), event.content()}) {
            if (text != null) {
                RuleRefs.find(text, known).forEach(ref -> cited.add(ref.ruleId()));
            }
        }
        cited.forEach(ruleId -> counts.merge(ruleId, 1, Integer::sum));
    }

    /** How many messages cited {@code ruleId}; 0 for a rule never cited. */
    public int count(String ruleId) {
        return counts.getOrDefault(ruleId, 0);
    }

    /** Every rule cited at least once, with its count, in rule order. */
    public Map<String, Integer> all() {
        Map<String, Integer> ordered = new TreeMap<>(BY_NUMBER);
        ordered.putAll(counts);
        return Collections.unmodifiableMap(ordered);
    }

    /** A new world: nothing cited yet. */
    public void reset() {
        counts.clear();
    }

    private static int number(String ruleId) {
        try {
            return Integer.parseInt(ruleId.substring(1));
        } catch (RuntimeException notARuleId) {
            return Integer.MAX_VALUE;
        }
    }
}
