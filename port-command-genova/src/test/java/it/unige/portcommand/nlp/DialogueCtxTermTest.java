package it.unige.portcommand.nlp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DialogueCtxTerm} renders a context to the Prolog {@code ctx(…)} term the grammar expects,
 * and — critically — escapes every data-derived atom so a hostile vessel name cannot inject a goal.
 */
class DialogueCtxTermTest {

    @Test
    void theEmptyContextRendersTheNeutralTerm() {
        assertEquals("ctx(standing(none, none), [], none)", DialogueCtxTerm.toPrologTerm(DialogueCtx.NONE));
    }

    @Test
    void offersRosterAndLastMentionedRenderInFull() {
        DialogueCtx ctx = new DialogueCtx("C001",
                new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"),
                        new DialogueCtx.OfferView(1500.0, 5, "berth_3")),
                List.of(new DialogueCtx.RosterEntry("C001", "cargo_vessel", "Genoa Star", "berth_3", 5)),
                "berth_3");

        assertEquals("ctx(standing(offer(2000, 5, berth_3), offer(1500, 5, berth_3)), "
                        + "[dialogue('C001', cargo_vessel, 'genoa star', berth_3, 5)], berth_3)",
                DialogueCtxTerm.toPrologTerm(ctx),
                "ids stay verbatim (quoted for the uppercase head); the name is lower-cased to match tokens");
    }

    @Test
    void anAbsentOfferSideAndDurationRenderAsNone() {
        DialogueCtx ctx = new DialogueCtx(null,
                new DialogueCtx.StandingOffer(DialogueCtx.OfferView.NONE, new DialogueCtx.OfferView(1500.0, null, null)),
                List.of(), null);

        assertEquals("ctx(standing(none, offer(1500, none, none)), [], none)",
                DialogueCtxTerm.toPrologTerm(ctx));
    }

    @Test
    void wholePricesRenderAsIntegersNotFloats() {
        DialogueCtx ctx = new DialogueCtx(null,
                new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"), DialogueCtx.OfferView.NONE),
                List.of(), null);
        // 2000.0 -> "2000": the grammar's integer arithmetic and the tokeniser's bare integers must unify.
        assertTrue(DialogueCtxTerm.toPrologTerm(ctx).contains("offer(2000, 5, berth_3)"));
        assertFalse(DialogueCtxTerm.toPrologTerm(ctx).contains("2000.0"));
    }

    @Test
    void aHostileVesselNameIsEscapedAndCannotInjectAGoal() {
        DialogueCtx ctx = new DialogueCtx(null, DialogueCtx.StandingOffer.NONE,
                List.of(new DialogueCtx.RosterEntry("id1", "tanker", "x'), fail, assertz(pwned), ('", "berth_1", 5)),
                null);

        String term = DialogueCtxTerm.toPrologTerm(ctx);

        // The quote is backslash-escaped, so the injected "'), … , ('" stays INSIDE one quoted atom
        // rather than closing dialogue(…)/ctx(…) early. The term is still a single well-formed ctx(…).
        // (id1 is a valid unquoted atom, so it renders bare; only the hostile name gets quoted.)
        assertTrue(term.contains("\\'"), "the quote in the name must be escaped: " + term);
        assertTrue(term.startsWith("ctx(standing(none, none), [dialogue(id1, tanker, '"), term);
        assertTrue(term.endsWith("berth_1, 5)], none)"), term);
    }
}
