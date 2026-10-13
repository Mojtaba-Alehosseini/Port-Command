package it.unige.portcommand.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.unige.portcommand.core.RuleCatalogue.Rule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the rule catalogue — the one plain-English line the comm log shows when the
 * player clicks {@code [R7]} — cannot drift from the kernel it describes.
 *
 * <p>The texts are hand-written, so nothing but these tests ties them to the 30 rules: the ids
 * are pinned to the {@code % RULE Rn:} headers of {@code prolog/rules_*.pl} (the same headers
 * {@code PrologEngineTest.ruleKernelIsExactlyThirtyRules} counts, read from the classpath the
 * same way), each rule to the module and the clause it names, and each predicate cell to its row
 * in {@code references.md}. No Prolog engine is started here; the arities are proved against the
 * loaded kernel by {@code prolog.RuleCatalogueKernelTest}.
 */
class RuleCatalogueTest {

    private static final Pattern RULE_HEADER = Pattern.compile("^% RULE (R\\d+):");

    /** The five rule modules, as {@code PrologEngineTest} lists them. */
    private static final List<String> RULE_MODULES = List.of(
            "/prolog/rules_compatibility.pl",
            "/prolog/rules_customs.pl",
            "/prolog/rules_escort.pl",
            "/prolog/rules_priority.pl",
            "/prolog/rules_weather.pl");

    /** One rule as the kernel states it: the module file holding its header, and the lines from
     * that header up to the next header (or the end of the file). */
    private record KernelRule(String module, List<String> lines) {
    }

    /** Rule id to its block, in file order, read from the five modules on the classpath. */
    private static Map<String, KernelRule> kernel() throws Exception {
        Map<String, KernelRule> rules = new LinkedHashMap<>();
        for (String resource : RULE_MODULES) {
            String module = resource.substring(resource.lastIndexOf('/') + 1, resource.length() - ".pl".length());
            Path path = Path.of(RuleCatalogueTest.class.getResource(resource).toURI());
            List<String> block = null;
            for (String line : Files.readAllLines(path)) {
                Matcher header = RULE_HEADER.matcher(line.trim());
                if (header.find()) {
                    block = new ArrayList<>();
                    KernelRule previous = rules.put(header.group(1), new KernelRule(module, block));
                    assertNull(previous, () -> "duplicate header for " + header.group(1));
                }
                if (block != null) {
                    block.add(line);
                }
            }
        }
        return rules;
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String rule(String id, String text) {
        return "{\"id\":\"" + id + "\",\"module\":\"rules_compatibility\",\"predicate\":\"compatible/2\","
                + "\"text\":\"" + text + "\"}";
    }

    // ------------------------------------------------------------------ ids

    @Test
    void idsAreExactlyR1ToR30InOrder() {
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            expected.add("R" + i);
        }

        assertEquals(expected, RuleCatalogue.get().all().stream().map(Rule::id).toList(),
                "30 rules (design rule 8), in kernel order");
    }

    @Test
    void idsEqualTheKernelRuleHeaders() throws Exception {
        Map<String, KernelRule> kernel = kernel();
        assertEquals(30, kernel.size(), "the kernel itself must still have 30 '% RULE Rn:' headers");

        assertEquals(kernel.keySet(), byId().keySet(),
                "every rule header has a catalogue entry and every entry a header");
    }

    // ------------------------------------------------------------------ module + predicate

    @Test
    void eachRuleNamesTheModuleWhoseFileHoldsItsHeader() throws Exception {
        Map<String, KernelRule> kernel = kernel();

        for (Rule rule : RuleCatalogue.get().all()) {
            KernelRule inKernel = kernel.get(rule.id());
            assertNotNull(inKernel, () -> rule.id() + " has no header in the kernel");
            assertEquals(inKernel.module(), rule.module(),
                    () -> rule.id() + " is catalogued under the wrong module");
        }
    }

    @Test
    void eachPredicateIsDefinedInsideItsRuleBlock() throws Exception {
        Map<String, KernelRule> kernel = kernel();

        for (Rule rule : RuleCatalogue.get().all()) {
            KernelRule inKernel = kernel.get(rule.id());
            assertNotNull(inKernel, () -> rule.id() + " has no header in the kernel");
            for (String indicator : rule.predicate().split(", ")) {
                String name = indicator.substring(0, indicator.indexOf('/'));
                // A clause head starts in column 0: "compatible(Berth, Vessel) :-", or a fact.
                assertTrue(inKernel.lines().stream().anyMatch(line -> line.startsWith(name + "(")),
                        () -> rule.id() + " names " + indicator + ", but no clause of " + name
                                + " sits between its header and the next in " + inKernel.module() + ".pl");
            }
        }
    }

    @Test
    void predicatesCarryArities() {
        Pattern indicators = Pattern.compile("\\w+/\\d(, \\w+/\\d)*");

        for (Rule rule : RuleCatalogue.get().all()) {
            assertTrue(indicators.matcher(rule.predicate()).matches(),
                    () -> rule.id() + " predicate must read name/arity[, name/arity]: " + rule.predicate());
        }
        assertEquals("take_first_n/3, extract_tugs/2", RuleCatalogue.get().find("R14").orElseThrow().predicate(),
                "R14 is the one rule made of two helper predicates");
    }

    @Test
    void everyModuleIsOneOfTheFiveRuleModules() {
        List<String> modules = RULE_MODULES.stream()
                .map(r -> r.substring(r.lastIndexOf('/') + 1, r.length() - ".pl".length())).toList();

        for (Rule rule : RuleCatalogue.get().all()) {
            assertTrue(modules.contains(rule.module()), () -> rule.id() + " has an unknown module " + rule.module());
        }
    }

    // ------------------------------------------------------------------ texts

    @Test
    void everyTextIsOnePlainSentence() {
        for (Rule rule : RuleCatalogue.get().all()) {
            String text = rule.text();
            assertFalse(text.isBlank(), () -> rule.id() + " has no text");
            assertEquals(text.trim(), text, () -> rule.id() + " text has leading or trailing whitespace");
            assertTrue(text.length() <= RuleCatalogue.MAX_TEXT_CHARS,
                    () -> rule.id() + " text is " + text.length() + " chars; the rule line fits "
                            + RuleCatalogue.MAX_TEXT_CHARS);
            assertTrue(text.endsWith("."), () -> rule.id() + " text must end with a full stop: " + text);
            assertTrue(text.chars().allMatch(c -> c >= 0x20 && c <= 0x7E),
                    () -> rule.id() + " text must be printable ASCII on one line (no tab, CR or LF): " + text);
            assertFalse(text.contains("_"),
                    () -> rule.id() + " text must be plain English, not a Prolog atom: " + text);
        }
    }

    // ------------------------------------------------------------------ lookup

    @Test
    void findAndContains() {
        RuleCatalogue catalogue = RuleCatalogue.get();

        Rule r7 = catalogue.find("R7").orElseThrow();
        assertEquals("A vessel fits a berth only if length, draft, beam, fenders and cargo handling all fit.",
                r7.text());
        assertEquals("rules_compatibility", r7.module());
        assertEquals("compatible/2", r7.predicate());

        assertTrue(catalogue.contains("R1"));
        assertTrue(catalogue.contains("R30"));
        assertFalse(catalogue.contains("R31"));
        assertFalse(catalogue.contains("R0"));
        assertFalse(catalogue.contains("r7"), "ids are case-sensitive");
        assertFalse(catalogue.contains(null));
        assertEquals(Optional.empty(), catalogue.find("R31"));
        assertEquals(Optional.empty(), catalogue.find(null));
    }

    @Test
    void getReturnsTheSameCatalogueEveryTimeAndItsListCannotBeModified() {
        assertSame(RuleCatalogue.get(), RuleCatalogue.get(), "loaded once");
        assertThrows(UnsupportedOperationException.class,
                () -> RuleCatalogue.get().all().add(new Rule("R99", "m", "p/1", "t.")));
    }

    // ------------------------------------------------------------------ loading

    @Test
    void aValidCatalogueLoadsAndMayCarryACommentHeader() {
        RuleCatalogue loaded = RuleCatalogue.load(stream("// provenance header, as the shipped file has\n["
                + rule("R1", "First.") + "," + rule("R2", "Second.") + "]"));

        assertEquals(List.of("R1", "R2"), loaded.all().stream().map(Rule::id).toList(), "file order");
        assertEquals("Second.", loaded.find("R2").orElseThrow().text());
    }

    @Test
    void missingOrInvalidResourceFailsFast() {
        assertThrows(IllegalStateException.class, () -> RuleCatalogue.load(null),
                "a missing resource must not become an empty catalogue");
        assertThrows(IllegalStateException.class,
                () -> RuleCatalogue.load(stream("[" + rule("R1", "First.") + "," + rule("R1", "Again.") + "]")),
                "a duplicate id");
        assertThrows(IllegalStateException.class,
                () -> RuleCatalogue.load(stream("[" + rule("R1", "   ") + "]")), "a blank text");
        assertThrows(IllegalStateException.class,
                () -> RuleCatalogue.load(stream("[{\"id\":\"R1\",\"module\":\"rules_compatibility\","
                        + "\"predicate\":\"compatible/2\"}]")), "a missing text");
        assertThrows(IllegalStateException.class,
                () -> RuleCatalogue.load(stream("[" + rule("R1", "Line one.\\nLine two.") + "]")),
                "a text that is not one line");
        assertThrows(IllegalStateException.class, () -> RuleCatalogue.load(stream("[{\"id\":")),
                "malformed JSON");
    }

    // ------------------------------------------------------------------ references.md

    /**
     * {@code references.md} (repo root, outside the Gradle module) is the document both course
     * reports cite for the kernel; the catalogue's predicate column must be its predicate column.
     * The exists-guard is the {@code WildSetIndependenceTest} precedent: a moved file must fail
     * this test, not let it pass having compared nothing.
     */
    @Test
    void everyRuleHasARowInReferencesMd() throws Exception {
        Path references = Path.of("..", "references.md");
        assertTrue(Files.exists(references), () -> "references.md not found at " + references.toAbsolutePath()
                + " — fix this path rather than letting the cross-check pass vacuously");

        // | R7 | `compatible/2` | composite: R1∧R2∧R3∧R4∧R5 | — | composition | DESIGN |
        Pattern row = Pattern.compile("^\\| (R\\d+) \\| ([^|]*) \\|");
        Map<String, String> predicateCellById = new LinkedHashMap<>();
        for (String line : Files.readAllLines(references, StandardCharsets.UTF_8)) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                String previous = predicateCellById.put(m.group(1), m.group(2).replace("`", "").trim());
                assertNull(previous, () -> "references.md has two rows for " + m.group(1));
            }
        }

        assertEquals(30, predicateCellById.size(), "one row per rule");
        for (Rule rule : RuleCatalogue.get().all()) {
            assertEquals(predicateCellById.get(rule.id()), rule.predicate(),
                    () -> rule.id() + ": the catalogue's predicate must equal references.md's (backticks aside)");
        }
        assertEquals(byId().keySet(), predicateCellById.keySet(),
                "and references.md lists no rule the catalogue lacks");
    }

    private static Map<String, Rule> byId() {
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (Rule rule : RuleCatalogue.get().all()) {
            rules.put(rule.id(), rule);
        }
        return rules;
    }
}
