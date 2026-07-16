package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import it.unige.portcommand.prolog.PrologTerms;

/**
 * Renders a {@link DialogueCtx} as the Prolog term {@code dcg_negotiation.pl}'s {@code parse_move/3}
 * expects: {@code ctx(standing(VesselOffer, PlayerOffer), Roster, LastMentioned)}. The inverse of the
 * frame decoder — the context flows Java&rarr;Prolog, the frame Prolog&rarr;Java.
 *
 * <p><b>Injection safety.</b> Every atom that originates from data — a vessel id, type, berth, and
 * especially the free-text vessel <em>name</em> — is rendered through {@link PrologTerms#quoteAtom},
 * the same escaper {@link DcgTokenizer#toPrologList} uses for tokens. A vessel maliciously named
 * {@code x'), fail, ('} cannot terminate the {@code ctx(…)} term early and inject a goal; it becomes
 * an inert quoted atom. Numbers are emitted bare. {@code DialogueCtxTermTest} pins this.
 *
 * <p><b>Name normalisation.</b> The roster name is lower-cased so it matches the lower-cased tokens
 * the tokeniser produces ("Genoa Star" &rarr; {@code [genoa, star]}), which is what the grammar's
 * {@code resolve_name/3} joins and compares against. Ids are NOT lower-cased — they must round-trip
 * verbatim as the frame's {@code addressee}, which routes the outgoing ACL.
 */
public final class DialogueCtxTerm {

    private static final String NONE = "none";

    private DialogueCtxTerm() {
    }

    /** @return {@code ctx(standing(VesselOffer, PlayerOffer), Roster, LastMentioned)} */
    public static String toPrologTerm(DialogueCtx ctx) {
        return "ctx(" + standing(ctx.standingOffer())
                + ", " + roster(ctx.roster())
                + ", " + atomOrNone(ctx.lastMentioned()) + ")";
    }

    private static String standing(DialogueCtx.StandingOffer s) {
        return "standing(" + offer(s.vesselOffer()) + ", " + offer(s.playerOffer()) + ")";
    }

    private static String offer(DialogueCtx.OfferView o) {
        if (o == null || !o.isPresent()) {
            return NONE;
        }
        return "offer(" + number(o.price()) + ", " + intOrNone(o.durationHours())
                + ", " + atomOrNone(o.berthId()) + ")";
    }

    private static String roster(List<DialogueCtx.RosterEntry> roster) {
        return roster.stream().map(DialogueCtxTerm::dialogue).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String dialogue(DialogueCtx.RosterEntry e) {
        return "dialogue(" + atomOrNone(e.id())
                + ", " + atomOrNone(e.vesselType())
                + ", " + nameAtom(e.vesselName())
                + ", " + atomOrNone(e.lastBerth())
                + ", " + intOrNone(e.lastDuration()) + ")";
    }

    private static String nameAtom(String name) {
        return name == null ? NONE : PrologTerms.quoteAtom(name.toLowerCase(Locale.ROOT));
    }

    private static String atomOrNone(String atom) {
        return atom == null ? NONE : PrologTerms.quoteAtom(atom);
    }

    /** Whole values emit as integers ({@code 2000}, not {@code 2000.0}) so they unify with the
     * grammar's integer arithmetic and the tokeniser's bare integer prices. */
    private static String number(Double d) {
        if (d == null) {
            return NONE;
        }
        return d == Math.floor(d) && !d.isInfinite() ? Long.toString(d.longValue()) : d.toString();
    }

    private static String intOrNone(Integer i) {
        return i == null ? NONE : i.toString();
    }
}
