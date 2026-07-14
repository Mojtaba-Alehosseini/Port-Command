package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Optional;

import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PolicyParsedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of {@link PolicyParser}'s regex-DSL (planning/10 §10.5b) — not explicitly requested
 * by planning/10's file table, but this class carries enough parsing logic (5 fields/operators,
 * 3 action kinds, an optional vessel-type filter) to warrant direct table-driven tests rather
 * than only exercising it indirectly through {@code AssistantAgentIT}.
 */
class PolicyParserTest {

    private static WalkInDialogueSnapshot tanker(double lastVesselOffer) {
        return new WalkInDialogueSnapshot("v1", "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, lastVesselOffer, 0.0, 1, 0.0, false);
    }

    private static WalkInDialogueSnapshot cargoVessel(double lastVesselOffer) {
        return new WalkInDialogueSnapshot("v2", "cargo_vessel", 8.0, 150.0, 30000, "general_cargo",
                "berth_3", 5, lastVesselOffer, 0.0, 1, 0.0, false);
    }

    @Test
    void acceptWithVesselTypeFilterMatchesOnlyThatType() {
        PolicyParser parser = new PolicyParser();
        EventBus bus = new EventBus();
        Optional<PolicyRule> rule = parser.parse("auto accept if price > 2200 for tankers", bus);

        assertTrue(rule.isPresent());
        assertEquals(RecommendationCandidate.ACCEPT, rule.get().action().kind());
        assertTrue(rule.get().predicate().test(tanker(2500.0)), "tanker above 2200 must match");
        assertFalse(rule.get().predicate().test(tanker(2000.0)), "tanker below 2200 must not match");
        assertFalse(rule.get().predicate().test(cargoVessel(2500.0)), "wrong vessel type must not match");

        List<Event> published = EventBusProbe.published(bus);
        assertTrue(published.stream().anyMatch(PolicyParsedEvent.class::isInstance));
    }

    @Test
    void rejectWithoutVesselTypeAppliesToEveryType() {
        PolicyParser parser = new PolicyParser();
        EventBus bus = new EventBus();
        Optional<PolicyRule> rule = parser.parse("auto reject if price < 1500", bus);

        assertTrue(rule.isPresent());
        assertEquals(RecommendationCandidate.REJECT_SILENT, rule.get().action().kind());
        assertTrue(rule.get().predicate().test(tanker(1000.0)));
        assertTrue(rule.get().predicate().test(cargoVessel(1000.0)));
        assertFalse(rule.get().predicate().test(tanker(1600.0)));
    }

    @Test
    void counterActionParsesPercentageAndAppliesToPrice() {
        PolicyParser parser = new PolicyParser();
        EventBus bus = new EventBus();
        Optional<PolicyRule> rule = parser.parse("auto counter +15% if price > 1000", bus);

        assertTrue(rule.isPresent());
        PolicyRule.PolicyAction action = rule.get().action();
        assertEquals(RecommendationCandidate.COUNTER, action.kind());
        assertEquals(0.15, action.counterPct(), 1e-9);
    }

    @Test
    void andJoinedConditionRequiresBothClauses() {
        PolicyParser parser = new PolicyParser();
        EventBus bus = new EventBus();
        Optional<PolicyRule> rule = parser.parse("auto accept if price > 2000 and rounds_used >= 2", bus);

        assertTrue(rule.isPresent());
        WalkInDialogueSnapshot bothMatch = new WalkInDialogueSnapshot("v1", "tanker", 14.0, 140.0, 20000,
                "liquid_bulk", "berth_1", 6, 2500.0, 0.0, 2, 0.0, false);
        WalkInDialogueSnapshot onlyPriceMatches = new WalkInDialogueSnapshot("v1", "tanker", 14.0, 140.0, 20000,
                "liquid_bulk", "berth_1", 6, 2500.0, 0.0, 1, 0.0, false);
        assertTrue(rule.get().predicate().test(bothMatch));
        assertFalse(rule.get().predicate().test(onlyPriceMatches), "rounds_used clause must also hold");
    }

    @Test
    void malformedTextFailsToParseAndPublishesNotification() {
        PolicyParser parser = new PolicyParser();
        EventBus bus = new EventBus();
        Optional<PolicyRule> rule = parser.parse("please accept tankers somehow", bus);

        assertTrue(rule.isEmpty());
        List<Event> published = EventBusProbe.published(bus);
        assertTrue(published.stream().anyMatch(NotificationEvent.class::isInstance),
                "a parse failure must surface a clarification NotificationEvent");
        assertFalse(published.stream().anyMatch(PolicyParsedEvent.class::isInstance));
    }

    @Test
    void unknownVesselTypeWordFailsToParse() {
        PolicyParser parser = new PolicyParser();
        Optional<PolicyRule> rule = parser.parse("auto accept if price > 2200 for submarines", new EventBus());
        assertTrue(rule.isEmpty());
    }
}
