package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.prolog.PrologException;
import org.jpl7.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Frame#fromProlog} decoding (planning/16 Step 16.4 + its acceptance criterion).
 *
 * <p>Terms are built by parsing real Prolog source with {@link Term#textToTerm}, not by hand-
 * assembling JPL objects: the point is to decode what {@code dcg_negotiation.pl} actually emits,
 * and a hand-built term could encode a shape the grammar never produces. ({@code Util.textToTerm}
 * is the same call but {@code @Deprecated} in JPL 7 — javap-checked.)
 */
class FrameTest {

    private static Term term(String prologSource) {
        return Term.textToTerm(prologSource);
    }

    @Test
    void decodesTheAcceptanceCriterionFrame() {
        Frame frame = Frame.fromProlog(term(
                "frame(commerce_sell, [move=propose, money=price(2000, eur), duration=5, berth=berth_3])"));

        assertEquals("commerce_sell", frame.frameName());
        assertEquals("propose", frame.move());
        assertEquals(Map.of("amount", 2000L, "currency", "EUR"), frame.element("money"));
        assertEquals(5L, frame.element("duration"));
        assertEquals("berth_3", frame.element("berth"));
    }

    /** The literal acceptance criterion: "Frame.fromProlog decodes price(N, eur) to
     * {amount=N, currency=EUR}" — note the currency is upper-cased. */
    @Test
    void priceDecodesToAmountAndUpperCaseCurrency() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [money=price(1800, eur)])"));

        assertEquals(Map.of("amount", 1800L, "currency", "EUR"), frame.element("money"));
    }

    @Test
    void usdPriceDecodesToUpperCaseCurrency() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [money=price(1500, usd)])"));

        assertEquals(Map.of("amount", 1500L, "currency", "USD"), frame.element("money"));
    }

    @Test
    void decimalPriceDecodesToADouble() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [money=price(1800.50, eur)])"));

        Map<?, ?> money = assertInstanceOf(Map.class, frame.element("money"));
        assertEquals(1800.50, (Double) money.get("amount"), 1e-9);
    }

    @Test
    void elementOrderIsPreserved() {
        Frame frame = Frame.fromProlog(term(
                "frame(commerce_sell, [move=propose, money=price(2000, eur), duration=5, berth=berth_3])"));

        assertEquals(List.of("move", "money", "duration", "berth"),
                List.copyOf(frame.elements().keySet()),
                "the grammar emits a canonical slot order; the decoder must not reshuffle it");
    }

    /**
     * The record's defensive copy must not be {@code Map.copyOf}: that returns an immutable map
     * whose iteration order the JDK randomises with a per-JVM-launch salt, so the SAME frame would
     * serialise to different ACL content between runs. Constructing directly (not via fromProlog)
     * because this is a property of the record itself.
     */
    @Test
    void constructorPreservesInsertionOrderSoTheWireFormatIsDeterministic() {
        Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        ordered.put("move", "propose");
        ordered.put("money", 2000L);
        ordered.put("duration", 5L);
        ordered.put("berth", "berth_3");

        Frame frame = new Frame("commerce_sell", ordered);

        assertEquals(List.of("move", "money", "duration", "berth"), List.copyOf(frame.elements().keySet()));
        assertEquals("{\"move\":\"propose\",\"money\":2000,\"duration\":5,\"berth\":\"berth_3\"}",
                frame.toJson(), "ACL content must be byte-stable for a given frame");
    }

    @Test
    void elementsAreDefensivelyCopiedAndUnmodifiable() {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("move", "accept");
        Frame frame = new Frame("commerce_sell", source);

        source.put("money", 9999L); // must not leak into the frame

        assertEquals(Map.of("move", "accept"), frame.elements());
        assertThrows(UnsupportedOperationException.class, () -> frame.elements().put("x", "y"));
    }

    @Test
    void bareAcceptFrameDecodesToJustTheMove() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [move=accept])"));

        assertEquals(Map.of("move", "accept"), frame.elements());
    }

    @Test
    void rejectReasonDecodesToAnAtomString() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [move=reject, reason=price_too_low])"));

        assertEquals("reject", frame.move());
        assertEquals("price_too_low", frame.element("reason"));
    }

    @Test
    void deadlineClockDecodesToItsAtomText() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [move=propose, deadline='19:30'])"));

        assertEquals("19:30", frame.element("deadline"));
    }

    /**
     * The 16-M2 command frame carries no {@code move}. {@link Frame#move()} must return null
     * rather than throw — {@link FrameToAcl} branches on the frame name for exactly this case.
     */
    @Test
    void commandFrameHasNoMove() {
        Frame frame = Frame.fromProlog(term("frame(command, [action=hold, quantifier=all])"));

        assertEquals("command", frame.frameName());
        assertNull(frame.move());
        assertEquals("hold", frame.element("action"));
    }

    /**
     * Guards the isList()-before-isAtom() ordering in decodeValue: SWI-Prolog 7+ represents the
     * empty list as a dedicated constant that JPL may report as an atom named "[]". Decoding it
     * as the STRING "[]" would be invisible in 16-M1 (no rule emits a list) and wrong for
     * 16-M2's quantified patients.
     */
    @Test
    void emptyPrologListDecodesToAnEmptyListNotTheStringBrackets() {
        Frame frame = Frame.fromProlog(term("frame(command, [patient=[]])"));

        assertEquals(List.of(), frame.element("patient"),
                "an empty list must decode to an empty List, never to the string \"[]\"");
    }

    @Test
    void listValueDecodesToAListOfDecodedItems() {
        Frame frame = Frame.fromProlog(term("frame(command, [patient=[tanker, ferry]])"));

        assertEquals(List.of("tanker", "ferry"), frame.element("patient"));
    }

    @Test
    void unmodelledCompoundDegradesToItsTermTextRatherThanThrowing() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [move=ask, weird=foo(bar, baz)])"));

        assertTrue(String.valueOf(frame.element("weird")).contains("foo"),
                "an unmodelled shape must degrade to readable text, not blow up the boundary");
    }

    @Test
    void aNumericQuantifierDecodesToLongAndAnAllQuantifierToString() {
        assertEquals(2L, Frame.fromProlog(term("frame(command, [action=send, quantifier=2, patient=tug])"))
                .element("quantifier"));
        assertEquals("all", Frame.fromProlog(term("frame(command, [action=hold, quantifier=all, patient=tanker])"))
                .element("quantifier"));
    }

    @Test
    void theNewM2NegotiationElementsDecodeAsStrings() {
        Frame frame = Frame.fromProlog(term(
                "frame(commerce_sell, [move=constrain, polarity=negative, bound=below, money=price(2000, eur)])"));

        assertEquals("constrain", frame.move());
        assertEquals("negative", frame.element("polarity"));
        assertEquals("below", frame.element("bound"));
        assertEquals(Map.of("amount", 2000L, "currency", "EUR"), frame.element("money"));
    }

    /**
     * Determinism, extended to the 16-M2 command frame: the grammar's element order is preserved and
     * {@link Frame#toJson} is byte-stable, exactly as for a commerce_sell frame — the wire format is
     * a project-wide invariant regardless of frame name (INVARIANTS.md).
     */
    @Test
    void commandFrameElementOrderAndJsonAreStable() {
        Frame frame = Frame.fromProlog(term(
                "frame(command, [action=hold, quantifier=all, patient=tanker, condition=until_wind_drop])"));

        assertEquals(List.of("action", "quantifier", "patient", "condition"),
                List.copyOf(frame.elements().keySet()));
        assertEquals("{\"action\":\"hold\",\"quantifier\":\"all\",\"patient\":\"tanker\",\"condition\":\"until_wind_drop\"}",
                frame.toJson());
    }

    @Test
    void aBoundAddresseeDecodesAsAStringAndKeepsItsPlaceLast() {
        Frame frame = Frame.fromProlog(term("frame(commerce_sell, [move=accept, addressee='C001'])"));

        assertEquals("accept", frame.move());
        assertEquals("C001", frame.element("addressee"));
    }

    // --- malformed input ---------------------------------------------------------------------

    @Test
    void nullTermThrows() {
        assertThrows(PrologException.class, () -> Frame.fromProlog(null));
    }

    @Test
    void nonFrameTermThrows() {
        assertThrows(PrologException.class, () -> Frame.fromProlog(term("offer(2000, 5)")));
    }

    @Test
    void frameWithNonListElementsThrows() {
        assertThrows(PrologException.class, () -> Frame.fromProlog(term("frame(commerce_sell, oops)")));
    }

    @Test
    void frameElementThatIsNotAKeyValuePairThrows() {
        assertThrows(PrologException.class,
                () -> Frame.fromProlog(term("frame(commerce_sell, [move=propose, loose_atom])")));
    }

    // --- JSON --------------------------------------------------------------------------------

    @Test
    void toJsonCarriesTheDecodedElements() {
        Frame frame = Frame.fromProlog(term(
                "frame(commerce_sell, [move=propose, money=price(2000, eur), berth=berth_3])"));

        String json = frame.toJson();

        assertTrue(json.contains("\"move\":\"propose\""), json);
        assertTrue(json.contains("\"amount\":2000"), json);
        assertTrue(json.contains("\"currency\":\"EUR\""), json);
        assertTrue(json.contains("\"berth\":\"berth_3\""), json);
    }
}
