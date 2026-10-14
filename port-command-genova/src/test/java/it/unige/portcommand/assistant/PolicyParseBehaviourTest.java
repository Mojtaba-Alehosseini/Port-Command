package it.unige.portcommand.assistant;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.behaviours.assistant.PolicyParseBehaviour;
import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PolicyClearRequestEvent;
import it.unige.portcommand.gui.events.PolicyListEvent;
import it.unige.portcommand.gui.events.PolicyParsedEvent;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry's voice (S18a, ADR-23, decision N11): every change to the autopilot policies is
 * answered with the list as it now stands, a new world announces its own, and "Clear policies"
 * empties it. The handlers are called directly — they are what the bus calls — so nothing here
 * waits on a background delivery; the two subscriptions are checked by counting them.
 *
 * <p>It lives beside the other Assistant tests and not in {@code behaviours.assistant}: a class in
 * that test package shadows the production one for {@code BehaviourCatalogueTest}'s scan.
 */
class PolicyParseBehaviourTest {

    private static final String TANKERS = "auto accept if price > 6000 for tankers";
    private static final String CHEAP = "auto reject if price < 1000";

    private static PolicyRule rule(String text) {
        return new PolicyParser().parseQuietly(text).orElseThrow(() -> new AssertionError("setup: " + text));
    }

    private final EventBus bus = new EventBus();
    private final PolicyRegistryArtifact registry = new PolicyRegistryArtifact();
    private final List<PolicyListEvent> lists = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private final PolicyParseBehaviour behaviour = new PolicyParseBehaviour(null, registry, bus);

    PolicyParseBehaviourTest() {
        bus.subscribe(PolicyListEvent.class, lists::add, DeliveryMode.CALLER_THREAD);
        bus.subscribe(NotificationEvent.class, n -> notices.add(n.text()), DeliveryMode.CALLER_THREAD);
    }

    private List<String> lastList() {
        return lists.get(lists.size() - 1).descriptions();
    }

    /** A loaded game's policies are in the registry before the Assistant boots; a new game's
     * registry is empty. Either way the window hears it from the Assistant as it starts. */
    @Test
    void aStartingAssistantAnnouncesTheListItHolds() {
        registry.register(rule(TANKERS));

        behaviour.action();

        assertEquals(1, lists.size());
        assertEquals(List.of(rule(TANKERS).description()), lastList());
        behaviour.cancelSubscription();
    }

    @Test
    void anEmptyRegistryIsAnnouncedAsEmptyNotLeftUnsaid() {
        behaviour.action();

        assertEquals(List.of(), lastList(), "the previous world's list must not be left standing");
        behaviour.cancelSubscription();
    }

    @Test
    void settingAPolicyAnswersWithTheListAsItNowStands() {
        behaviour.onPolicyParsed(new PolicyParsedEvent(rule(TANKERS)));
        behaviour.onPolicyParsed(new PolicyParsedEvent(rule(CHEAP)));

        assertEquals(List.of(rule(TANKERS).description(), rule(CHEAP).description()), lastList(),
                "in the order they are consulted");
        assertEquals(List.of("Policy registered: " + TANKERS, "Policy registered: " + CHEAP), notices);
    }

    /** The second copy is not kept, and the player is told why nothing was added. */
    @Test
    void theSamePolicyAgainIsSaidToBeAlreadySet() {
        behaviour.onPolicyParsed(new PolicyParsedEvent(rule(TANKERS)));
        behaviour.onPolicyParsed(new PolicyParsedEvent(rule(TANKERS)));

        assertEquals(1, registry.all().size());
        assertEquals("Policy already set: " + TANKERS, notices.get(1));
        assertEquals(2, lists.size(), "the list is answered either way - the form is waiting for it");
        assertEquals(1, lastList().size());
    }

    @Test
    void clearEmptiesTheRegistryAndSaysSo() {
        behaviour.onPolicyParsed(new PolicyParsedEvent(rule(TANKERS)));
        behaviour.onPolicyParsed(new PolicyParsedEvent(rule(CHEAP)));

        behaviour.onClearRequested(new PolicyClearRequestEvent());

        assertEquals(List.of(), registry.all());
        assertEquals(List.of(), lastList());
        assertEquals("Policies cleared: 2.", notices.get(notices.size() - 1));

        behaviour.onClearRequested(new PolicyClearRequestEvent());
        assertEquals("There were no policies to clear.", notices.get(notices.size() - 1));
        assertEquals(List.of(), lastList());
    }

    /** The bus outlives the agent: a respawned Assistant must not find its predecessor still
     * listening on either event (a second listener would clear, or register, twice). */
    @Test
    void bothSubscriptionsLiveAndDieWithTheBehaviour() {
        assertEquals(0, EventBusProbe.subscriberCount(bus, PolicyParsedEvent.class));
        assertEquals(0, EventBusProbe.subscriberCount(bus, PolicyClearRequestEvent.class));

        behaviour.action();
        assertEquals(1, EventBusProbe.subscriberCount(bus, PolicyParsedEvent.class));
        assertEquals(1, EventBusProbe.subscriberCount(bus, PolicyClearRequestEvent.class));
        // Not the window's event: the session's one hook says the list again on the first paint,
        // and FreePlaySessionIT pins that hook as the only GuiReadyEvent subscriber.
        assertEquals(0, EventBusProbe.subscriberCount(bus, GuiReadyEvent.class));

        behaviour.cancelSubscription();
        assertEquals(0, EventBusProbe.subscriberCount(bus, PolicyParsedEvent.class));
        assertEquals(0, EventBusProbe.subscriberCount(bus, PolicyClearRequestEvent.class));
        assertTrue(lists.size() >= 1, "and it did announce on the way in");
    }

    @Test
    void cancellingBeforeItEverRanIsHarmless() {
        behaviour.cancelSubscription();
    }
}
