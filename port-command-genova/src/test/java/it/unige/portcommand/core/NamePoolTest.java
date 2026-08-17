package it.unige.portcommand.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural contract of the walk-in name pool (S8, GOAL 3 / ADR-17): every entry must be
 * usable by the WHOLE NL chain — the digit guard, the whitespace canonicaliser, the
 * GRAMMAR_STOP window refusal, the MED length ladder and the Prolog atom quoting all have
 * veto power over a name, and a violating entry would silently degrade to exact-match-only
 * (or worse, capture grammar terminals). Also pins the pool's DISJOINTNESS from the scenario
 * JSONs' authored names — the in-session uniqueness argument needs it.
 */
class NamePoolTest {

    /**
     * Mirror of {@code RosterNameResolver.GRAMMAR_STOP} for THIS package's structural sweep.
     * The drift-proof pin lives in {@code nlp.RosterNameResolverTest} (S8 adversarial finding
     * 3): it checks every pool name against the REAL set, so a grown stop list that silently
     * disables a pool name fails THERE even if this mirror lags.
     */
    private static final Set<String> GRAMMAR_STOP = Set.of(
            "tanker", "tankers", "cargo", "ferry", "ferries", "cruise", "container",
            "vessel", "vessels", "ship", "ships", "berth", "dock", "quay", "pier",
            "tug", "tugs", "same", "the", "hours", "euros");

    @Test
    void everyPoolNameObeysTheNlContract() {
        assertTrue(NamePool.size() >= 40, "the pool must cover a long session's spawn count");
        assertEquals(NamePool.all().stream().distinct().count(), NamePool.size(), "no duplicates");
        for (String name : NamePool.all()) {
            assertTrue(name.chars().noneMatch(Character::isDigit),
                    "digit-free (the id guard would make it exact-only): " + name);
            assertEquals(String.join(" ", name.strip().split("\\s+")), name,
                    "single-space canonical (atomic_list_concat round-trip): " + name);
            assertTrue(name.length() >= 4, "MED MIN_LEN eligibility: " + name);
            assertTrue(name.chars().allMatch(c -> Character.isLetter(c) || c == ' '),
                    "plain letters + spaces only (tokeniser/quoteAtom-safe): " + name);
            for (String word : name.toLowerCase(Locale.ROOT).split(" ")) {
                assertFalse(GRAMMAR_STOP.contains(word),
                        "'" + word + "' is a grammar terminal — the resolver refuses such "
                                + "windows, making '" + name + "' unresolvable by MED");
            }
        }
    }

    @Test
    void deterministicIndexing_andCollisionFreeWindow() {
        assertEquals(NamePool.name(7, 1), NamePool.name(7, 1), "pure function");
        // A full window of consecutive ordinals under one offset never repeats a name.
        long distinct = java.util.stream.IntStream.rangeClosed(1, NamePool.size())
                .mapToObj(n -> NamePool.name(13, n)).distinct().count();
        assertEquals(NamePool.size(), distinct, "collision-free for size() consecutive spawns");
        // Wrap is defined, not an error.
        assertEquals(NamePool.name(13, 1), NamePool.name(13, 1 + NamePool.size()));
        // Any int offset is legal (floorMod).
        assertEquals(NamePool.name(-3, 1), NamePool.name(-3 + NamePool.size(), 1));
    }

    /** Authored scenario names must be pool-DISJOINT or an authored vessel could collide with
     * a drawn one in-session (the uniqueness argument in ADR-17's name-pool section). */
    @Test
    void scenarioAuthoredNames_areDisjointFromThePool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> scenarios = List.of("tutorial", "busy_day", "storm");
        int authored = 0;
        for (String key : scenarios) {
            JsonNode root = mapper.readTree(
                    getClass().getResource("/data/scenarios/" + key + ".json"));
            for (JsonNode event : root.path("events")) {
                JsonNode name = event.get("vesselName");
                if (name != null && !name.isNull()) {
                    authored++;
                    assertFalse(NamePool.all().contains(name.asText()),
                            key + " authors '" + name.asText() + "' which is IN the pool");
                    assertTrue(name.asText().chars().noneMatch(Character::isDigit),
                            "authored names obey the same digit rule: " + name.asText());
                }
            }
            for (JsonNode berth : root.path("initialState").path("berths")) {
                JsonNode name = berth.path("vessel").get("vesselName");
                if (name != null && !name.isNull()) {
                    authored++;
                    assertFalse(NamePool.all().contains(name.asText()),
                            key + " docks '" + name.asText() + "' which is IN the pool");
                }
            }
        }
        assertEquals(5, authored, "the five scripted walk-ins carry authored names (S8)");
    }
}
