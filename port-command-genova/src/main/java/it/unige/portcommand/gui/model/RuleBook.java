package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.core.RuleCatalogue;

/**
 * S19 (playtest 5, PT43): what Help ▸ Rules (30) lists - "let the teacher see all the rules, not
 * only the ones cited in the log". Every rule of the Prolog kernel in its group (compatibility
 * R1–R8, escort R9–R14, weather R15–R20, customs R21–R24, priority R25–R30 - the catalogue's
 * modules), with its plain one-liner from {@link RuleCatalogue}, the Prolog file it lives in, and
 * how often this game's comm log cited it ({@link RuleCitations}). Pure: the window only draws it.
 */
public final class RuleBook {

    /** One rule: its id, its one-liner, its Prolog file and this game's citations. */
    public record Line(String ruleId, String text, String file, int usedThisGame) {
    }

    /** One group: {@code Compatibility (R1–R8)} and its rules in order. */
    public record Group(String title, List<Line> lines) {
    }

    private RuleBook() {
    }

    public static List<Group> groups(RuleCatalogue catalogue, Map<String, Integer> cited) {
        List<RuleCatalogue.Rule> rules = new ArrayList<>(catalogue.all());
        rules.sort(Comparator.comparing(RuleCatalogue.Rule::id, RuleCitations.BY_NUMBER));
        Map<String, List<RuleCatalogue.Rule>> byModule = new LinkedHashMap<>();
        for (RuleCatalogue.Rule rule : rules) {
            byModule.computeIfAbsent(rule.module(), module -> new ArrayList<>()).add(rule);
        }
        List<Group> groups = new ArrayList<>();
        byModule.forEach((module, inModule) -> {
            List<Line> lines = new ArrayList<>();
            for (RuleCatalogue.Rule rule : inModule) {
                lines.add(new Line(rule.id(), rule.text(), module + ".pl",
                        cited == null ? 0 : cited.getOrDefault(rule.id(), 0)));
            }
            groups.add(new Group(label(module) + " (" + inModule.get(0).id() + "–"
                    + inModule.get(inModule.size() - 1).id() + ")", List.copyOf(lines)));
        });
        return List.copyOf(groups);
    }

    /** {@code rules_compatibility} → {@code Compatibility}. */
    static String label(String module) {
        String name = module.startsWith("rules_") ? module.substring("rules_".length()) : module;
        return name.isEmpty() ? module : Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('_', ' ');
    }
}
