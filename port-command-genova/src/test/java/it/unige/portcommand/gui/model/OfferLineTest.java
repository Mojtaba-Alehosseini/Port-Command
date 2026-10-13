package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the line above a walk-in tab's transcript — their offer, the player's offer,
 * the round — as two rows. Pure model; the tabs are built through the registry, the way the chat
 * column builds them.
 */
class OfferLineTest {

    private static final String DIALOGUE = "nego-W001";
    private static final int ROUND_LIMIT = 6;

    private static VesselSpec spec() {
        return new VesselSpec("W001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
    }

    /** The vessel's opening or counter: {@code vesselOffer} for 6 h, stamped with {@code playerOffer}
     * (0.0 = the HarbourMaster knows of no player offer). */
    private static NegotiationOpenedEvent offer(int roundsUsed, double vesselOffer, double playerOffer) {
        return new NegotiationOpenedEvent(DIALOGUE, WalkInDialogueSnapshot.of(
                "W001", spec(), "berth_2", 6, vesselOffer, playerOffer, roundsUsed, 0.0, false));
    }

    private static NegotiationTabRegistry registryWithOpening(double vesselOffer) {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(offer(1, vesselOffer, 0.0));
        return registry;
    }

    private static OfferLine line(NegotiationTabRegistry registry) {
        return OfferLine.of(registry.tab(DIALOGUE), ROUND_LIMIT);
    }

    @Test
    void beforeThePlayerOffersItSaysSo() {
        OfferLine line = line(registryWithOpening(1436.0));

        assertEquals("Their offer €1436 · 6h", line.theirs());
        assertEquals("Your offer — · round 1 of 6", line.yoursAndRound());
        assertEquals("Their offer €1436 · 6h / Your offer — · round 1 of 6", line.text());
        assertFalse(line.text().contains("€0"), "no offer is not an offer of nothing: " + line.text());
        assertFalse(line.closed());
    }

    @Test
    void itShowsThePlayersPriceAndTheirOwnHours() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4);
        registry.onNegotiationOpened(offer(2, 1500.0, 1900.0)); // the vessel counters: round 2

        OfferLine line = line(registry);

        assertEquals("Their offer €1500 · 6h", line.theirs());
        assertEquals("Your offer €1900 · 4h", line.yours(), "the player's OWN hours, not the vessel's 6");
        assertEquals("round 2 of 6", line.round());
        assertEquals("Your offer €1900 · 4h · round 2 of 6", line.yoursAndRound());
        assertEquals("Their offer €1500 · 6h / Your offer €1900 · 4h · round 2 of 6", line.text());
    }

    @Test
    void aPriceOnlyOfferShowsTheHoursUnderDiscussion() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, null); // "make it 1900" names no hours

        assertEquals("Your offer €1900 · 6h", line(registry).yours(),
                "a price-only offer is heard at the hours on the table");
    }

    @Test
    void anOfferOnlyTheHarbourMasterStampedFallsBackToTheHoursUnderDiscussion() {
        // An autopilot counter never passes through the tab: the price arrives stamped on the
        // vessel's next snapshot and the tab has no hours of its own for it.
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.onNegotiationOpened(offer(2, 1500.0, 2100.0));

        assertEquals(null, registry.tab(DIALOGUE).lastPlayerHours(), "setup: the tab recorded no hours");
        assertEquals("Your offer €2100 · 6h · round 2 of 6", line(registry).yoursAndRound());
    }

    @Test
    void aRefusedFirstOfferReadsNoOfferAgain() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4, 7L);
        assertEquals("Your offer €1900 · 4h", line(registry).yours(), "setup: shown at the send");

        registry.onPlayerCommandRefused(new PlayerCommandRefusedEvent(DIALOGUE,
                PlayerCommandEvent.PlayerCommandKind.PROPOSE, 7L, 0.0, "berth_2 is incompatible with a ferry [R7]"));

        assertEquals("Your offer — · round 1 of 6", line(registry).yoursAndRound(),
                "the gate refused it, so no offer of the player's ever stood");
    }

    @Test
    void aClosedTabReadsNegotiationClosed() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4);
        registry.onNegotiationClosed(new NegotiationClosedEvent(DIALOGUE, "W001", Deal.Outcome.DEAL), 0L);

        OfferLine line = line(registry);

        assertTrue(line.closed());
        assertEquals("Negotiation closed.", line.text());
        assertEquals("Negotiation closed.", line.theirs(), "row 1 carries the whole line");
        assertEquals("", line.yoursAndRound(), "and row 2 is empty — no stray separator");
    }

    @Test
    void theTwoRowsCarryTheSameWordsAsTheLine() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        OfferLine before = line(registry);
        assertEquals(before.text(), before.theirs() + " / " + before.yoursAndRound());

        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4);
        OfferLine after = line(registry);
        assertEquals(after.text(), after.theirs() + " / " + after.yoursAndRound());
        assertEquals(after.yoursAndRound(), after.yours() + " · " + after.round());
    }

    @Test
    void theSeparatorsAreAMiddleDotAndAnEmDash() {
        // Built from code points, so a look-alike character (a bullet, a hyphen, an en dash) pasted
        // into either file cannot quietly satisfy the other pins in this class.
        String euro = String.valueOf((char) 0x20AC);
        String middleDot = String.valueOf((char) 0x00B7);
        String emDash = String.valueOf((char) 0x2014);
        OfferLine line = line(registryWithOpening(1436.0));

        assertEquals("Their offer " + euro + "1436 " + middleDot + " 6h", line.theirs());
        assertEquals("Your offer " + emDash, line.yours());
        assertEquals("Your offer " + emDash + " " + middleDot + " round 1 of 6", line.yoursAndRound());
    }

    @Test
    void theRoundComesFromTheSnapshotAndItsLimitFromTheCaller() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.onNegotiationOpened(offer(3, 1500.0, 0.0));

        assertEquals("round 3 of 8", OfferLine.of(registry.tab(DIALOGUE), 8).round());
        assertEquals("round 3 of 2", OfferLine.of(registry.tab(DIALOGUE), 2).round(),
                "the limit is printed as given — this model does not judge it");
    }

    @Test
    void pricesAreWholeEuros() {
        NegotiationTabRegistry registry = registryWithOpening(1436.4);
        registry.recordPlayerOffer(DIALOGUE, 1899.6, 4);

        OfferLine line = line(registry);

        assertEquals("Their offer €1436 · 6h", line.theirs());
        assertEquals("Your offer €1900 · 4h", line.yours());
    }

    @Test
    void theDigitsDoNotFollowTheDefaultLocale() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4);
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            // A locale whose default-format digits are not ASCII: without Locale.ROOT the price
            // would be printed in Thai digits on such a desktop.
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("th-TH-u-nu-thai"));

            assertEquals("Their offer €1436 · 6h / Your offer €1900 · 4h · round 1 of 6", line(registry).text());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    void itSaysNothingAboutTheTimeLeft() {
        // The m:ss counter beside this line is a bare number and is never explained (INVARIANTS).
        NegotiationTabRegistry registry = registryWithOpening(1436.0);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4);

        String text = line(registry).text().toLowerCase(Locale.ROOT);

        for (String word : new String[] {"remaining", "patience", "time", "wait", "left"}) {
            assertFalse(text.contains(word), "the offer line must not caption the counter: " + text);
        }
    }
}
