package it.unige.portcommand.nlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DialogueCtx#from} maps a walk-in dialogue's observable snapshot fields into the context —
 * and ONLY those fields (P-04). The grep gate at the bottom keeps a future editor from reaching
 * into a hidden belief during context construction.
 */
class DialogueCtxTest {

    private static WalkInDialogueSnapshot snapshot(String id, String type, String berth, int hours,
                                                   double vesselOffer, double playerOffer, String name) {
        return new WalkInDialogueSnapshot(id, type, 10.0, 150.0, 30000, "general_cargo", berth, hours,
                vesselOffer, playerOffer, 2, 30.0, false, name);
    }

    @Test
    void fromMapsObservableFieldsToATypeTaggedRoster() {
        DialogueCtx ctx = DialogueCtx.from(List.of(
                snapshot("C001", "cargo_vessel", "berth_3", 5, 2000.0, 1500.0, "Genoa Star"),
                snapshot("T001", "tanker", "berth_2", 8, 5000.0, 0.0, "Carthago")), "C001");

        assertEquals(2, ctx.roster().size(), "recency order: the argument order IS the roster order");
        assertEquals("Genoa Star", ctx.roster().get(0).vesselName());
        assertEquals("C001", ctx.roster().get(0).id());
        assertEquals("tanker", ctx.roster().get(1).vesselType());
        assertEquals("berth_2", ctx.roster().get(1).lastBerth());
    }

    @Test
    void fromTakesTheStandingOfferFromTheFocusedDialogue() {
        DialogueCtx ctx = DialogueCtx.from(List.of(
                snapshot("C001", "cargo_vessel", "berth_3", 5, 2000.0, 1500.0, "Genoa Star"),
                snapshot("T001", "tanker", "berth_2", 8, 5000.0, 4800.0, "Carthago")), "C001");

        assertTrue(ctx.standingOffer().vesselOffer().isPresent());
        assertEquals(2000.0, ctx.standingOffer().vesselOffer().price());
        assertEquals(1500.0, ctx.standingOffer().playerOffer().price());
        assertEquals("berth_3", ctx.lastMentioned(), "the focused dialogue's berth is what 'that berth' resolves to");
    }

    @Test
    void fromTreatsAZeroOfferAsNoOfferOnThatSide() {
        // Round-1 opening: the vessel has proposed (5000), the player has not countered (0.0 sentinel).
        DialogueCtx ctx = DialogueCtx.from(
                List.of(snapshot("T001", "tanker", "berth_2", 8, 5000.0, 0.0, "Carthago")), "T001");

        assertTrue(ctx.standingOffer().vesselOffer().isPresent());
        assertFalse(ctx.standingOffer().playerOffer().isPresent(), "a 0.0 offer is 'no offer yet'");
    }

    @Test
    void fromWithNoFocusHasAnEmptyStandingOfferButStillARoster() {
        DialogueCtx ctx = DialogueCtx.from(
                List.of(snapshot("T001", "tanker", "berth_2", 8, 5000.0, 0.0, "Carthago")), null);

        assertFalse(ctx.hasStandingOffer());
        assertNull(ctx.lastMentioned());
        assertEquals(1, ctx.roster().size());
    }

    @Test
    void theLegacyThreeArgConstructorMapsOntoThePlayerSide() {
        var offer = new it.unige.portcommand.ontology.Offer(1800, 5, "berth_3", "v", "hm", 0L);
        DialogueCtx ctx = new DialogueCtx("conv-1", offer, null);

        assertTrue(ctx.hasStandingOffer(), "cancel routing depends on this");
        assertEquals(1800.0, ctx.standingOffer().playerOffer().price());
        assertTrue(ctx.roster().isEmpty());
    }

    /**
     * P-04 grep gate. Context construction must never read a walk-in's hidden beliefs. Targets the
     * ACCESSOR-CALL form ({@code .minAcceptablePrice(} …) so the class javadoc — which legitimately
     * NAMES the forbidden fields to explain what it avoids — does not trip the gate. The structural
     * guarantee (the sole data input, {@link WalkInDialogueSnapshot}, cannot expose them) is held by
     * {@code WalkInDialogueSnapshotPrivacyTest}; this is the belt-and-braces the session brief asks for.
     */
    @Test
    void ctxConstructionReadsNoHiddenBelief() throws IOException {
        String source = Files.readString(Path.of("src/main/java/it/unige/portcommand/nlp/DialogueCtx.java"));
        for (String accessor : List.of(".minAcceptablePrice(", ".targetPrice(", ".maxWaitMinutes(",
                ".personality(", ".minDurationHours(")) {
            assertFalse(source.contains(accessor),
                    "DialogueCtx must not read a hidden belief during context construction: " + accessor);
        }
    }
}
