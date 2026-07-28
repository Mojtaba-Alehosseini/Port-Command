package it.unige.portcommand.nlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.prolog.PrologException;
import org.jpl7.Term;

/**
 * A FrameNet-style semantic frame parsed out of a negotiation utterance by
 * {@code dcg_negotiation.pl} (PROJECT_DEFINITION.md §6.2). Full implementation of task 14's
 * stub — the record shape is unchanged (it is already consumed by {@link FrameToAcl} and
 * {@link NLPPipeline}); {@link #fromProlog} and {@link #toJson} are what task 16 adds.
 *
 * <p><b>Shape.</b> {@code frameName} is the frame itself, not the move:
 * <ul>
 *   <li>{@code commerce_sell} — the negotiation frame. The player SELLS berth/escort services
 *       and RECEIVES the money, so the player is the Seller and the vessel the Buyer.
 *       Roles: {@code move}, {@code money}, {@code duration}, {@code berth}, {@code deadline},
 *       {@code reason}, {@code topic}. Which of the five move types it is lives in the
 *       {@code move} element — {@link FrameToAcl} switches on that to pick the performative.</li>
 *   <li>{@code command} — 16-M2's imperative frame ({@code action}/{@code patient}/
 *       {@code quantifier}/{@code condition}); it carries no {@code move}.</li>
 * </ul>
 *
 * <p><b>Element order is preserved.</b> The grammar emits {@code move} first and then a canonical
 * slot order, and {@link #toJson} puts that order on the wire.
 */
public record Frame(String frameName, Map<String, Object> elements) {

    /** The {@code commerce_sell} element naming which of the five DCG move types this is. */
    public static final String MOVE = "move";

    /**
     * Defensive copy that KEEPS insertion order. Deliberately not {@code Map.copyOf} (which task
     * 14's stub used): {@code Map.copyOf} returns a {@code java.util.ImmutableCollections} map
     * whose iteration order is randomised by a per-JVM-launch salt, so the same frame would
     * serialise to different ACL content on different runs — and the decoded slot order would not
     * match the grammar's. Determinism of the wire format is a project-wide invariant.
     */
    public Frame {
        elements = elements == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    /**
     * Decodes the {@code frame(Name, [Key=Value, ...])} term {@code parse_move/2} binds.
     *
     * <p>Value decoding covers exactly the closed set of shapes the grammar can emit:
     * <ul>
     *   <li>{@code price(N, eur)} &rarr; {@code {amount=N, currency=EUR}} (planning/16 Step 16.4)
     *       — the currency atom is upper-cased to match {@code java.util.Currency} conventions
     *       and the ACL content the rest of the system already speaks;</li>
     *   <li>integer &rarr; {@link Long}, float &rarr; {@link Double}, atom &rarr; {@link String};</li>
     *   <li>a Prolog list &rarr; {@link List} of decoded values (16-M2's quantified patients);</li>
     *   <li>any other compound &rarr; its canonical term text, so an unmodelled shape degrades to
     *       a readable string rather than throwing at the boundary.</li>
     * </ul>
     *
     * @throws PrologException if the term is not a well-formed {@code frame/2}
     */
    public static Frame fromProlog(Term term) {
        if (term == null || !term.hasFunctor("frame", 2)) {
            throw new PrologException("expected frame/2, got: " + term);
        }
        Term nameTerm = term.arg(1);
        if (!nameTerm.isAtom()) {
            throw new PrologException("frame name must be an atom, got: " + nameTerm);
        }
        Term elementList = term.arg(2);
        if (!elementList.isList()) {
            throw new PrologException("frame elements must be a list, got: " + elementList);
        }
        Map<String, Object> elements = new LinkedHashMap<>();
        for (Term cell : elementList.listToTermArray()) {
            if (!cell.hasFunctor("=", 2)) {
                throw new PrologException("frame element must be Key=Value, got: " + cell);
            }
            Term key = cell.arg(1);
            if (!key.isAtom()) {
                throw new PrologException("frame element key must be an atom, got: " + key);
            }
            elements.put(key.name(), decodeValue(cell.arg(2)));
        }
        return new Frame(nameTerm.name(), elements);
    }

    private static Object decodeValue(Term t) {
        if (t.isInteger()) {
            return t.longValue();
        }
        if (t.isFloat()) {
            return t.doubleValue();
        }
        // isList() is checked BEFORE isAtom() on purpose: SWI-Prolog 7+ represents the empty
        // list as a dedicated constant, and JPL may report it as an atom named "[]". Checking
        // isAtom() first would decode an empty list to the STRING "[]" instead of an empty
        // List — invisible in M1 (no rule emits a list) but wrong for 16-M2's quantified
        // patients. A non-empty atom never satisfies isList(), so the reorder costs nothing.
        // FrameTest.emptyPrologListDecodesToAnEmptyListNotTheStringBrackets pins this.
        if (t.isList()) {
            List<Object> items = new ArrayList<>();
            for (Term item : t.listToTermArray()) {
                items.add(decodeValue(item));
            }
            return List.copyOf(items);
        }
        if (t.isAtom()) {
            return t.name();
        }
        if (t.hasFunctor("price", 2)) {
            Map<String, Object> money = new LinkedHashMap<>();
            money.put("amount", decodeValue(t.arg(1)));
            money.put("currency", t.arg(2).name().toUpperCase(Locale.ROOT));
            return money;
        }
        return t.toString();
    }

    /** JSON for ACL content — the shape {@link FrameToAcl} puts on the wire. */
    public String toJson() {
        return TerminalJson.write(elements);
    }

    /** The move type ({@code propose}/{@code counter}/{@code accept}/{@code reject}/{@code ask}),
     * or {@code null} for a frame that carries none (16-M2's {@code command}). */
    public String move() {
        Object move = elements.get(MOVE);
        return move == null ? null : String.valueOf(move);
    }

    /** @return the element under {@code key}, or {@code null} if absent. */
    public Object element(String key) {
        return elements.get(Objects.requireNonNull(key, "key"));
    }
}
