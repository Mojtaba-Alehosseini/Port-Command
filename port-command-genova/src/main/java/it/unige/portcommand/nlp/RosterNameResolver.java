package it.unige.portcommand.nlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MinimumEditDistance MED} fuzzy layer over the DCG's roster-name resolution
 * (audit P2-1, S6): a typo'd vessel name in the player's tokens is rewritten to the canonical
 * roster name BEFORE the Prolog goal is built, so {@code resolve_name/3}'s deliberate
 * identity match ({@code N == Name}) stays untouched. PROJECT_DEFINITION §2.2's
 * "Marsk Genoba" → "Maersk Genova" example is pinned at unit level
 * ({@code RosterNameResolverTest}); the END-TO-END parses are the two typo goldens in
 * {@code dcg_goldens.jsonl} ("Genoa Sttar: deal", "send two tugs to the Carthagoo").
 *
 * <p><b>Positional windows, not a sliding scan.</b> The grammar consumes a name at exactly two
 * sites ({@code dcg_negotiation.pl}): the vocative prefix — every token before the first
 * {@code ':'} — and the command target after {@code to the} / {@code for the} (both preps are
 * {@code target_prep} clauses). Site B's span is NOT exclusively a name position, though —
 * {@code no to the tanker} and {@code 2000 for the same berth} put ordinary grammar terminals
 * there — so windows containing a {@link #GRAMMAR_STOP} word refuse outright (S6 adversarial
 * finding 6: a roster vessel two edits from "tanker" would otherwise capture a fleet command,
 * the exact wrong-vessel bind the tie-refusal exists to stop).
 *
 * <p><b>Guards, in order:</b> an EXACT roster match always wins untouched; windows containing
 * {@code ':'} or a numeric token refuse (a numeric rewrite would re-emit bare and unify with
 * price terminals); {@code MIN_LEN} applies to BOTH the window and the candidate name; the
 * distance budget ladders with length ({@code k=1} for 4–5 chars, {@code k=2} from 6 — the
 * min of both sides' budgets), so short names cannot false-match; roster names that are
 * digit-bearing (id-shaped, "WALKIN-3" — one edit apart from each other, the silent
 * wrong-vessel class) or whose whitespace is non-canonical (double spaces would never
 * round-trip through {@code atomic_list_concat/3}'s single-space join) are skipped; and a
 * tie between two DIFFERENT names REFUSES rather than picking one (Moji's call, S6): the
 * Prolog resolver deliberately fails on ambiguity, and a most-recent-wins pick would
 * re-introduce exactly the wrong-vessel bind the D-05 cross-tab guard exists to stop.
 *
 * <p>Scope note (S6, decided): production roster names are today the {@code WALKIN-<n>} ids
 * ({@code VesselSpec} has no name field), and the digit-name guard above keeps ids
 * exact-match-only — so this layer is inert in live play BY GUARD, not by accident, until
 * vessels get real names (planned together with the two-step vocative work in the D-05
 * session). The goldens and fixtures carry human names and demonstrate the behaviour end
 * to end.
 */
final class RosterNameResolver {

    private static final Logger log = LoggerFactory.getLogger(RosterNameResolver.class);

    /** Below this joined length (window or candidate), only exact matches count. */
    static final int MIN_LEN = 4;

    /** Same shape as {@code DcgTokenizer.NUMBER} — kept private there; a rewrite must never
     * produce or consume a token this matches (it would re-emit bare, not quoted). */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * Grammar terminals that can legally occupy a site-B span ({@code dcg_negotiation.pl}'s
     * {@code vessel_type_word}, {@code berth_demonstrative}, {@code patient_word} and their
     * modifiers). A window containing ANY of these refuses: "no to the tanker" and
     * "2000 for the same berth" are grammar, not names, and a roster vessel two edits from
     * "tanker" must not capture them (S6 adversarial finding 6). Cost: a vessel NAMED with
     * one of these words is exact-match-only — acceptable, and stated here.
     */
    private static final java.util.Set<String> GRAMMAR_STOP = java.util.Set.of(
            "tanker", "tankers", "cargo", "ferry", "ferries", "cruise", "container",
            "vessel", "vessels", "ship", "ships", "berth", "dock", "quay", "pier",
            "tug", "tugs", "same", "the", "hours", "euros");

    private RosterNameResolver() {
    }

    /**
     * Returns {@code tokens} with at most one name window rewritten to its canonical roster
     * form, or the list unchanged when nothing (or nothing unambiguous) qualifies.
     */
    static List<String> resolve(List<String> tokens, DialogueCtx ctx) {
        if (ctx == null || ctx.roster().isEmpty() || tokens.isEmpty()) {
            return tokens;
        }
        // Site A: the vocative prefix — everything before the first ':' (name_phrase refuses
        // ':' itself, so the grammar's window is exactly this prefix).
        int colon = tokens.indexOf(":");
        if (colon >= 1) {
            List<String> rewritten = rewriteWindow(tokens, 0, colon, ctx);
            if (rewritten != tokens) {
                return rewritten;
            }
        }
        // Site B: the command target — target_prep [the] name_phrase runs to the end of the
        // utterance. First marker wins; one rewrite per turn (the grammar consumes one name).
        for (int i = 0; i + 2 < tokens.size(); i++) {
            String t = tokens.get(i);
            if (("to".equals(t) || "for".equals(t)) && "the".equals(tokens.get(i + 1))) {
                List<String> rewritten = rewriteWindow(tokens, i + 2, tokens.size(), ctx);
                if (rewritten != tokens) {
                    return rewritten;
                }
            }
        }
        return tokens;
    }

    private static List<String> rewriteWindow(List<String> tokens, int from, int to, DialogueCtx ctx) {
        List<String> window = tokens.subList(from, to);
        if (window.isEmpty()) {
            return tokens;
        }
        for (String t : window) {
            if (":".equals(t) || NUMBER.matcher(t).matches() || GRAMMAR_STOP.contains(t)) {
                return tokens;
            }
        }
        String joined = String.join(" ", window);

        // Candidate names: canonical (lowercased, single-space) roster names, skipping the
        // shapes that could never round-trip through the ctx term / grammar identity check.
        List<String> names = new ArrayList<>();
        for (DialogueCtx.RosterEntry entry : ctx.roster()) {
            String name = entry.vesselName();
            if (name == null) {
                continue;
            }
            String canonical = name.toLowerCase(Locale.ROOT).strip();
            if (canonical.isEmpty() || !canonical.equals(String.join(" ", canonical.split("\\s+")))) {
                continue; // non-canonical whitespace never survives atomic_list_concat's join
            }
            if (canonical.chars().anyMatch(Character::isDigit)) {
                // Digit-bearing names are id-shaped ("WALKIN-3"), not prose names, and they are
                // one edit apart from each other — fuzzing toward them is exactly the silent
                // wrong-vessel bind this class refuses (S6 adversarial finding 7: a bare
                // "walkin:" was distance 2 from walkin-3 and would have rewritten). Ids stay
                // exact-match-only, which also keeps the live-play story honest: with today's
                // id-only roster names this layer is inert until the D-05 name pool lands.
                continue;
            }
            if (canonical.equals(joined)) {
                return tokens; // exact match: the grammar resolves it itself, hands off
            }
            names.add(canonical);
        }

        int windowBudget = budget(joined.length());
        if (windowBudget == 0) {
            return tokens;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String name : names) {
            int k = Math.min(windowBudget, budget(name.length()));
            if (k == 0 || Math.abs(name.length() - joined.length()) > k) {
                continue;
            }
            int d = MinimumEditDistance.distance(joined, name);
            if (d > k) {
                continue;
            }
            if (d < bestDistance) {
                bestDistance = d;
                best = name;
                tie = false;
            } else if (d == bestDistance && !name.equals(best)) {
                tie = true;
            }
        }
        if (best == null || tie) {
            if (tie) {
                log.debug("MED refuses '{}': two roster names tie at distance {}", joined, bestDistance);
            }
            return tokens;
        }
        List<String> out = new ArrayList<>(tokens.subList(0, from));
        out.addAll(List.of(best.split(" ")));
        out.addAll(tokens.subList(to, tokens.size()));
        log.debug("MED rewrite: '{}' -> '{}' (distance {})", joined, best, bestDistance);
        return List.copyOf(out);
    }

    /** The length-laddered distance budget: 0 below {@link #MIN_LEN}, 1 for 4–5, 2 from 6. */
    private static int budget(int length) {
        if (length < MIN_LEN) {
            return 0;
        }
        return length <= 5 ? 1 : 2;
    }
}
