package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the rule ids a piece of comm-log text cites (S15, 2026-10-12), so the panel can underline
 * them and the "Rules" preset can select the rows that carry one.
 *
 * <p>An id counts ONLY inside a bracket group — {@code [R7]}, {@code [R25,R26,R27,R30]} — and only
 * if the caller's predicate knows it (in production {@code RuleCatalogue.get()::contains}). That
 * is the one shape every producer writes (the grant's {@code reason}, the customs and weather
 * citations), and it is narrow on purpose: the vessel id {@code WALKIN-R7}, a bracketed
 * {@code [WALKIN-R7]} and the line's own {@code [08:14:03]} timestamp must never become links,
 * and {@code [R7,R31]} links R7 alone because there is no rule 31 to show.
 *
 * <p>Pure text in, offsets out. No Swing/AWT, no catalogue dependency of its own.
 */
public final class RuleRefs {

    /**
     * One cited rule id. {@code [start, end)} covers the id itself ({@code R7}) — not the
     * brackets, not the comma — so a click on the punctuation between two ids hits neither.
     */
    public record Ref(int start, int end, String ruleId) {
    }

    /**
     * A whole citation: an opening bracket, one or more ids separated by a comma with at most one
     * whitespace character after it ({@code [R15, R16]} is accepted), a closing bracket. Three
     * digits at most — the kernel has 30 rules.
     */
    private static final Pattern GROUP = Pattern.compile("\\[(R\\d{1,3}(?:,\\s?R\\d{1,3})*)\\]");

    private static final Pattern ID = Pattern.compile("R\\d{1,3}");

    private RuleRefs() {
    }

    /**
     * Every known rule id cited in {@code text}, in text order. {@code null} or empty text cites
     * nothing. An id the predicate does not know is skipped without spoiling its neighbours.
     */
    public static List<Ref> find(String text, Predicate<String> known) {
        Objects.requireNonNull(known, "known");
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Ref> refs = new ArrayList<>();
        Matcher group = GROUP.matcher(text);
        while (group.find()) {
            // Offsets from a region-bound matcher are still offsets into the whole text, which is
            // what the caller needs to place an underline.
            Matcher id = ID.matcher(text).region(group.start(1), group.end(1));
            while (id.find()) {
                String ruleId = id.group();
                if (known.test(ruleId)) {
                    refs.add(new Ref(id.start(), id.end(), ruleId));
                }
            }
        }
        return List.copyOf(refs);
    }

    /** Whether {@code text} cites at least one known rule id. */
    public static boolean citesAny(String text, Predicate<String> known) {
        return !find(text, known).isEmpty();
    }

    /**
     * The reference under a text offset: start-inclusive, end-exclusive, so the offset just past
     * the last character of an id is NOT a hit. Empty for a null list or a miss.
     */
    public static Optional<Ref> at(List<Ref> refs, int offset) {
        if (refs == null) {
            return Optional.empty();
        }
        for (Ref ref : refs) {
            if (ref.start() <= offset && offset < ref.end()) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }
}
