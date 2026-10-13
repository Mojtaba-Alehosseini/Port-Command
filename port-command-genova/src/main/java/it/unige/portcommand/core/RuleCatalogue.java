package it.unige.portcommand.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The 30 rules of the Prolog kernel, each as ONE plain-English line (S15): what the comm log
 * shows when the player clicks a rule id such as {@code [R7]}.
 *
 * <p>The texts live in {@code /data/rule_catalogue.json}, derived from {@code references.md} and
 * the {@code % RULE Rn:} headers of {@code prolog/rules_*.pl}; {@code RuleCatalogueTest} pins the
 * ids, modules and predicates to the kernel, so the two cannot drift. This class is text only —
 * it lives in {@code core} (no Swing, and deliberately NOT in {@code prolog}, the JPL facade:
 * reading a rule's description must never be able to start SWI-Prolog).
 *
 * <p>Immutable once loaded; {@link #get()} is safe from any thread.
 */
public final class RuleCatalogue {

    /** One rule: its id ({@code R7}), kernel module, predicate indicator(s) and one-line text. */
    public record Rule(String id, String module, String predicate, String text) {
    }

    public static final String RESOURCE = "/data/rule_catalogue.json";
    /** A text must fit two lines of the comm log's rule line at the column's 380 px minimum. */
    public static final int MAX_TEXT_CHARS = 110;

    private static RuleCatalogue instance;

    private final Map<String, Rule> byId;

    private RuleCatalogue(Map<String, Rule> byId) {
        this.byId = byId;
    }

    /** The shipped catalogue, loaded once. Fails fast if the resource is missing or invalid. */
    public static synchronized RuleCatalogue get() {
        if (instance == null) {
            try (InputStream in = RuleCatalogue.class.getResourceAsStream(RESOURCE)) {
                instance = load(in);
            } catch (IOException e) {
                throw new IllegalStateException("failed to load rule_catalogue.json", e);
            }
        }
        return instance;
    }

    /**
     * Parses a catalogue. Rejects what would make a rule line wrong on screen: a missing stream,
     * a duplicate id, a blank field, or a text that is not a single line.
     */
    static RuleCatalogue load(InputStream in) {
        if (in == null) {
            throw new IllegalStateException("rule_catalogue.json not found on classpath (/data/)");
        }
        // ALLOW_JAVA_COMMENTS so the data file can carry its provenance header (the
        // vessel_templates.json precedent).
        JsonMapper mapper = JsonMapper.builder().enable(JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
        List<Rule> rules;
        try {
            rules = mapper.readValue(in, new TypeReference<List<Rule>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse rule_catalogue.json", e);
        }
        Map<String, Rule> byId = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (isBlank(rule.id()) || isBlank(rule.module()) || isBlank(rule.predicate()) || isBlank(rule.text())) {
                throw new IllegalStateException("rule_catalogue.json: a rule has a blank field: " + rule);
            }
            if (rule.text().indexOf('\n') >= 0 || rule.text().indexOf('\r') >= 0) {
                throw new IllegalStateException("rule_catalogue.json: " + rule.id() + " is not one line");
            }
            if (byId.put(rule.id(), rule) != null) {
                throw new IllegalStateException("rule_catalogue.json: duplicate rule id " + rule.id());
            }
        }
        return new RuleCatalogue(byId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** The rule with that id, or empty for an unknown or null id. */
    public Optional<Rule> find(String ruleId) {
        return ruleId == null ? Optional.empty() : Optional.ofNullable(byId.get(ruleId));
    }

    public boolean contains(String ruleId) {
        return ruleId != null && byId.containsKey(ruleId);
    }

    /** Every rule, in file order (R1 … R30). */
    public List<Rule> all() {
        return List.copyOf(byId.values());
    }
}
