package it.unige.portcommand.artifacts;

import java.util.List;

import it.unige.portcommand.nlp.PolicyParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry's own contract (S18a, ADR-23, decision N11). The first rule that matches wins
 * ({@code firstMatching}), so what is IN the list, and in what order, is the whole of a player's
 * standing instructions - which is why the Settings form now shows the list, and why the same
 * sentence typed twice must not appear in it twice.
 */
class PolicyRegistryArtifactTest {

    private static PolicyRule rule(String text) {
        return new PolicyParser().parseQuietly(text).orElseThrow(() -> new AssertionError("setup: " + text));
    }

    /** Born red: pressing "Set policy" twice left two identical rows behind, and every save
     * carried both. The second one could never match anything the first had not. */
    @Test
    void theSamePolicyIsNotRegisteredTwice() {
        PolicyRegistryArtifact registry = new PolicyRegistryArtifact();

        registry.register(rule("auto accept if price > 6000 for tankers"));
        registry.register(rule("auto accept if price > 6000 for tankers"));
        registry.register(rule("  AUTO accept   if price > 6000 for tankers "));

        assertEquals(1, registry.all().size(), "one sentence, one rule - whatever its spacing or case");
    }

    /** A DIFFERENT rule is always kept, in the order it was set: precedence is the player's to
     * see and to clear, never the registry's to rearrange. */
    @Test
    void differentPoliciesAreKeptInTheOrderTheyWereSet() {
        PolicyRegistryArtifact registry = new PolicyRegistryArtifact();

        registry.register(rule("auto accept if price > 6000 for tankers"));
        registry.register(rule("auto accept if price > 5000 for tankers"));
        registry.register(rule("auto reject if price < 1000"));

        assertEquals(List.of("auto accept if price > 6000 for tankers",
                        "auto accept if price > 5000 for tankers", "auto reject if price < 1000"),
                registry.all().stream().map(PolicyRule::trigger).toList());
    }

    @Test
    void clearForgetsEverything() {
        PolicyRegistryArtifact registry = new PolicyRegistryArtifact();
        registry.register(rule("auto reject if price < 1000"));

        registry.clear();

        assertEquals(List.of(), registry.all());
        registry.register(rule("auto reject if price < 1000"));
        assertEquals(1, registry.all().size(), "and a cleared rule can be set again");
    }
}
