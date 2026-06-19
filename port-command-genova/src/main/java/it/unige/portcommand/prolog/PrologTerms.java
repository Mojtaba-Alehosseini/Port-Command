package it.unige.portcommand.prolog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jpl7.Term;

/**
 * Stateless helpers for building Prolog goal strings and decoding JPL
 * {@link Term}s into Java types. No JPL query is run here — this is pure
 * string/term marshalling, callable without the engine lock.
 */
public final class PrologTerms {

    /** A token that needs no quoting: lowercase-initial, then word chars. */
    private static final Pattern UNQUOTED_ATOM = Pattern.compile("[a-z][a-zA-Z0-9_]*");

    private PrologTerms() {
    }

    /**
     * Renders {@code s} as a Prolog atom token: bare when it already matches the
     * unquoted-atom grammar, single-quoted (with escapes) otherwise. Guards goal
     * construction against an id that contains a quote, space, or uppercase head.
     */
    public static String quoteAtom(String s) {
        if (s == null) {
            throw new PrologException("cannot render a null atom");
        }
        if (UNQUOTED_ATOM.matcher(s).matches()) {
            return s;
        }
        return "'" + escape(s) + "'";
    }

    /** Escapes backslashes and single quotes for embedding inside {@code '...'}. */
    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Decodes a Prolog list term into a Java list of element names. Atoms map to
     * their name; non-atom elements fall back to their canonical term text.
     *
     * @throws PrologException if the term is not a proper list
     */
    public static List<String> toStringList(Term t) {
        if (t == null || !t.isList()) {
            throw new PrologException("expected a Prolog list, got: " + t);
        }
        String[] atoms = Term.atomListToStringArray(t);
        if (atoms != null) {
            return List.of(atoms);
        }
        // Mixed / non-atom elements: walk the cells and stringify each.
        Term[] cells = t.listToTermArray();
        List<String> out = new ArrayList<>(cells.length);
        for (Term cell : cells) {
            out.add(cell.isAtom() ? cell.name() : cell.toString());
        }
        return out;
    }

    /**
     * Decodes a numeric term to a double, accepting both {@code Float} and
     * {@code Integer} terms (JPL promotes whole numbers to {@code Integer}).
     */
    public static double toDouble(Term t) {
        if (t == null) {
            throw new PrologException("expected a number, got null");
        }
        if (t.isFloat()) {
            return t.doubleValue();
        }
        if (t.isInteger()) {
            return (double) t.longValue();
        }
        throw new PrologException("expected a number, got " + t.typeName() + ": " + t);
    }

    /**
     * Decodes an integer term to an int. Refuses a {@code Float} term — a
     * fractional value where an integer was expected is a rule bug, not a cast.
     */
    public static int toInt(Term t) {
        if (t == null) {
            throw new PrologException("expected an integer, got null");
        }
        if (t.isInteger()) {
            return t.intValue();
        }
        throw new PrologException("expected an integer, got " + t.typeName() + ": " + t);
    }

    /**
     * Encodes tug bids as a Prolog list of {@code bid(TugId, Cost, Eta, Fuel)}
     * compound terms for {@code best_n_bids/3} (RULE R13).
     */
    public static String bidsToPrologList(List<TugBid> bids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bids.size(); i++) {
            TugBid b = bids.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("bid(")
              .append(quoteAtom(b.tugId())).append(", ")
              .append(b.cost()).append(", ")
              .append(b.etaMinutes()).append(", ")
              .append(b.fuelState()).append(")");
        }
        return sb.append("]").toString();
    }
}
