package it.unige.portcommand.prolog;

import java.util.regex.Pattern;

import it.unige.portcommand.core.RuleCatalogue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): every {@code name/arity} the rule catalogue shows the player exists, with
 * that arity, in the kernel as SWI-Prolog actually loaded it.
 *
 * <p>{@code core.RuleCatalogueTest} ties the catalogue to the rule files as TEXT (headers, module,
 * a clause of that name inside the rule's block); it cannot tell {@code compatible/2} from
 * {@code compatible/3}. This asks the engine. It lives here, in the same untagged lane and with
 * the same {@code init()} idiom as {@link PrologEngineTest}, because it needs JPL — and the
 * catalogue class itself must never be able to start Prolog.
 */
class RuleCatalogueKernelTest {

    /** A plain predicate indicator. Checked BEFORE the text is spliced into a goal. */
    private static final Pattern INDICATOR = Pattern.compile("[a-z][a-z0-9_]*/\\d+");

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    @Test
    void everyCataloguedPredicateExistsInTheLoadedKernelWithThatArity() {
        PrologEngine engine = PrologEngine.getInstance();
        int checked = 0;

        for (RuleCatalogue.Rule rule : RuleCatalogue.get().all()) {
            for (String indicator : rule.predicate().split(", ")) {
                assertTrue(INDICATOR.matcher(indicator).matches(),
                        () -> rule.id() + " predicate is not a name/arity indicator: " + indicator);
                assertTrue(engine.hasSolution("current_predicate(" + indicator + ")"),
                        () -> rule.id() + " names " + indicator + ", which the loaded kernel does not define");
                checked++;
            }
        }

        assertTrue(checked >= 31, "30 rules, and R14 names two predicates — got " + checked);
    }

    /** The control: the check above can fail. Same name, wrong arity, and a name nobody defines. */
    @Test
    void aWrongArityOrAnUnknownNameIsNotInTheKernel() {
        PrologEngine engine = PrologEngine.getInstance();

        assertTrue(engine.hasSolution("current_predicate(compatible/2)"));
        assertFalse(engine.hasSolution("current_predicate(compatible/9)"), "arity is part of the identity");
        assertFalse(engine.hasSolution("current_predicate(no_such_rule_predicate/1)"));
    }
}
