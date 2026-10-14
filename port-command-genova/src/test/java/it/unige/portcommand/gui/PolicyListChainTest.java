package it.unige.portcommand.gui;

import java.util.List;

import javax.swing.SwingUtilities;

import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.behaviours.assistant.PolicyParseBehaviour;
import it.unige.portcommand.gui.events.PolicyClearRequestEvent;
import it.unige.portcommand.gui.events.PolicyListEvent;
import it.unige.portcommand.gui.model.PolicyListModel;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playtest 4, PT19: "the policy list is empty after reopening Settings". The whole chain on one
 * bus, as the running game wires it - the Settings form, the policy parser, the Assistant's
 * {@link PolicyParseBehaviour} with its real background delivery, the {@code PolicyListEvent}, the
 * window's list model fed on the EDT, and a SECOND form opened on that model - with no window.
 * S18a tested every link on its own; nothing tested that they connect.
 */
class PolicyListChainTest {

    private PolicyParseBehaviour assistant;

    @AfterEach
    void stopTheAssistant() {
        if (assistant != null) {
            assistant.cancelSubscription();
        }
    }

    /** Pumps the EDT until {@code condition} holds or the deadline passes (design rule 5). */
    private static boolean onTheEdtWithin(long millis, java.util.function.BooleanSupplier condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            boolean[] holds = new boolean[1];
            SwingUtilities.invokeAndWait(() -> holds[0] = condition.getAsBoolean());
            if (holds[0]) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    @Timeout(20)
    void aPolicySetInSettingsIsListedWhenSettingsIsOpenedAgain() throws Exception {
        EventBus bus = new EventBus();
        PolicyListModel windowModel = new PolicyListModel();
        // The window's own subscription (MainWindow), the same delivery mode.
        bus.subscribe(PolicyListEvent.class, e -> windowModel.set(e.descriptions()), DeliveryMode.EDT);
        // Every clear request that is sent, as it is sent.
        List<PolicyClearRequestEvent> clears = new java.util.concurrent.CopyOnWriteArrayList<>();
        bus.subscribe(PolicyClearRequestEvent.class, clears::add, DeliveryMode.CALLER_THREAD);
        // The Assistant, as AssistantAgent starts it.
        assistant = new PolicyParseBehaviour(null, new PolicyRegistryArtifact(), bus);
        assistant.action();

        SettingsForm first = new SettingsForm(bus, "NORMAL", 3_600L, true, "microsoft/Phi-4-mini-instruct", false,
                windowModel, () -> { });
        first.policyFieldForTest().setText(PolicyParser.GRAMMAR_EXAMPLE);
        first.clickSetPolicyForTest();
        assertTrue(first.policyFeedbackForTest().contains("Policy set"), first.policyFeedbackForTest());
        String expected = new PolicyParser().parseQuietly(PolicyParser.GRAMMAR_EXAMPLE).orElseThrow().description();

        assertTrue(onTheEdtWithin(10_000, () -> windowModel.descriptions().equals(List.of(expected))),
                () -> "the window's list never heard of the policy: " + windowModel.descriptions());
        first.removeNotify(); // the dialog closes

        SettingsForm reopened = new SettingsForm(bus, "NORMAL", 3_600L, true, "microsoft/Phi-4-mini-instruct", false,
                windowModel, () -> { });
        assertTrue(reopened.policyListForTest().contains("1. "), "reopened Settings: " + reopened.policyListForTest());
        assertEquals(1, windowModel.listenerCount(), "only the open form listens");

        // PT19's real cause: the list empties only on a clear the player confirms.
        reopened.confirmClearForTest(count -> false);
        SwingUtilities.invokeAndWait(() -> reopened.clearPoliciesButtonForTest().doClick());
        assertTrue(clears.isEmpty(), "an unconfirmed clear sends nothing");
        assertEquals(List.of(expected), windowModel.descriptions());
        reopened.confirmClearForTest(count -> true);
        SwingUtilities.invokeAndWait(() -> reopened.clearPoliciesButtonForTest().doClick());
        assertEquals(1, clears.size(), "the confirmed clear is sent once");
        assertTrue(onTheEdtWithin(10_000, () -> windowModel.descriptions().isEmpty()),
                () -> "a confirmed clear empties the list: " + windowModel.descriptions());
        assertTrue(reopened.policyFeedbackForTest().contains("Policies cleared."), reopened.policyFeedbackForTest());
    }
}
