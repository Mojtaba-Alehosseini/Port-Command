package it.unige.portcommand.behaviours;

import it.unige.portcommand.behaviours.negotiation.WithdrawalBehaviour;
import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The withdrawal-reason → canonical {@link Deal.Outcome} mapping (task-07 §164).
 * Lives in the parent {@code behaviours} package, NOT {@code behaviours.negotiation} —
 * a test class in a catalogue-counted sub-package shadows {@code BehaviourCatalogueTest}'s
 * {@code getResource} scan and miscounts the package.
 */
class WithdrawalBehaviourTest {

    @Test
    void mapsEachReasonToItsCanonicalOutcome() {
        assertEquals(Deal.Outcome.WITHDRAW_PRICE, WithdrawalBehaviour.outcomeFor("over_priced"));
        assertEquals(Deal.Outcome.TIMEOUT, WithdrawalBehaviour.outcomeFor("timeout"));
        assertEquals(Deal.Outcome.PLAYER_REFUSED, WithdrawalBehaviour.outcomeFor("player_refused"));
    }

    @Test
    void unknownReasonThrows() {
        assertThrows(IllegalArgumentException.class, () -> WithdrawalBehaviour.outcomeFor("bogus"));
    }
}
