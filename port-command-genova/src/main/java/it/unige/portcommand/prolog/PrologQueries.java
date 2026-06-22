package it.unige.portcommand.prolog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jpl7.Term;

import static it.unige.portcommand.prolog.PrologTerms.quoteAtom;

/**
 * Typed Java surface over the Prolog rule kernel — one method per binding
 * decision an agent needs. Each method builds a goal string, runs it through the
 * {@link PrologEngine} singleton, and decodes the result to a Java type.
 *
 * <p><b>Transient-fact pattern.</b> Queries about a specific vessel assert that
 * vessel's facts under a unique id, run the rule, then retract — all inside one
 * {@link PrologEngine#inLock} critical section so the assert→query→retract triple
 * is atomic and the fact base is left clean (the cleanup runs in a {@code finally}).
 * Facts are asserted {@code user:}-qualified by the engine, matching where the
 * rules live. Such queries are NEVER cached (they depend on asserted state).
 *
 * <p><b>Caching.</b> Only the genuinely pure lookups — {@link #priorityRank} and
 * {@link #inspectionProbability} (static fact base, no asserts) — go through the
 * engine cache. {@link #clearanceOk} is uncached because it reads the dynamic
 * blacklist.
 */
public final class PrologQueries {

    private PrologQueries() {
    }

    private static PrologEngine engine() {
        return PrologEngine.getInstance();
    }

    /** A fresh, collision-free temp-vessel atom (valid unquoted: lowercase hex). */
    private static String tempVesselId() {
        return "tmp_v_" + UUID.randomUUID().toString().replace("-", "");
    }

    // --- Compatibility (R1–R8) ---------------------------------------------

    /**
     * True iff {@code vesselType} can berth at {@code berthId}. Asserts the
     * vessel's length/draft/tonnage and type, runs {@code compatible/2}, retracts.
     * Beam is read from the ontology's per-type {@code default(VType, has_beam, _)}
     * fact (not a parameter), so the deciding dimension here is draft vs. length.
     */
    public static boolean isCompatible(String berthId, String vesselType,
                                       double draft, double length, int tonnage) {
        PrologEngine e = engine();
        String v = tempVesselId();
        return e.inLock(() -> {
            try {
                assertVessel(e, v, vesselType, draft, length, tonnage);
                return e.hasSolution("compatible(" + quoteAtom(berthId) + ", " + v + ")");
            } finally {
                retractVessel(e, v);
            }
        });
    }

    /** Every berth compatible with {@code vesselType} (RULE R8). */
    public static List<String> findCompatibleBerths(String vesselType,
                                                    double draft, double length, int tonnage) {
        PrologEngine e = engine();
        String v = tempVesselId();
        return e.inLock(() -> {
            try {
                assertVessel(e, v, vesselType, draft, length, tonnage);
                Optional<Map<String, Term>> sol =
                        e.oneSolution("find_compatible_berths(" + v + ", Berths)");
                return sol.map(m -> PrologTerms.toStringList(m.get("Berths"))).orElse(List.of());
            } finally {
                retractVessel(e, v);
            }
        });
    }

    // --- Escort (R9–R14) ---------------------------------------------------

    /** Tugs required for {@code vesselType} at {@code tonnage} (RULE R11). */
    public static int tugsRequired(String vesselType, int tonnage, double length) {
        PrologEngine e = engine();
        String v = tempVesselId();
        return e.inLock(() -> {
            try {
                e.assertFact("default(" + v + ", has_tonnage, " + tonnage + ")");
                e.assertFact("default(" + v + ", has_length, " + length + ")");
                e.assertFact("instance_of(" + v + ", " + quoteAtom(vesselType) + ")");
                return e.oneSolution("tugs_required(" + v + ", N)")
                        .map(m -> PrologTerms.toInt(m.get("N")))
                        .orElseThrow(() -> new PrologException(
                                "tugs_required produced no solution for " + vesselType));
            } finally {
                retractVessel(e, v);
            }
        });
    }

    /** Top-{@code n} tug ids by score, descending (RULE R13). */
    public static List<String> selectBestBids(List<TugBid> bids, int n) {
        PrologEngine e = engine();
        String list = PrologTerms.bidsToPrologList(bids);
        return e.oneSolution("best_n_bids(" + list + ", " + n + ", Winners)")
                .map(m -> PrologTerms.toStringList(m.get("Winners")))
                .orElse(List.of());
    }

    // --- Weather (R15–R20) -------------------------------------------------

    /** True iff operating is safe for {@code vesselType} at the given wind/visibility (RULE R20). */
    public static boolean operationSafe(String vesselType, double windKnots, String visibility) {
        PrologEngine e = engine();
        String v = tempVesselId();
        return e.inLock(() -> {
            try {
                e.assertFact("instance_of(" + v + ", " + quoteAtom(vesselType) + ")");
                return e.hasSolution(
                        "operation_safe(" + v + ", " + windKnots + ", " + quoteAtom(visibility) + ")");
            } finally {
                e.retractAllMatching("instance_of(" + v + ", _)");
            }
        });
    }

    // --- Customs (R21–R24) -------------------------------------------------

    /**
     * True iff {@code vesselType}/{@code cargoClass} are both valid and the pair
     * is not blacklisted (RULE R21). Uncached — reads the dynamic blacklist.
     */
    public static boolean clearanceOk(String vesselType, String cargoClass) {
        return engine().hasSolution(
                "clearance_ok(" + quoteAtom(vesselType) + ", " + quoteAtom(cargoClass) + ")");
    }

    /**
     * True iff {@code cargoClass} is a hazmat cargo. Goal {@code is_hazmat(<cargoClass>)};
     * the predicate is owned by the ontology converter (port_ontology.pl), reading static
     * {@code subclass_of/2} edges — pure, so cached.
     */
    public static boolean isHazmat(String cargoClass) {
        return engine().hasSolutionCached("is_hazmat(" + quoteAtom(cargoClass) + ")");
    }

    /** Inspection probability for the pair: 0.8 hazmat, 0.2 otherwise (RULE R23). Pure → cached. */
    public static double inspectionProbability(String vesselType, String cargoClass) {
        return engine().oneSolutionCached(
                        "inspection_probability(" + quoteAtom(vesselType) + ", "
                                + quoteAtom(cargoClass) + ", P)")
                .map(m -> PrologTerms.toDouble(m.get("P")))
                .orElseThrow(() -> new PrologException(
                        "inspection_probability undefined for " + vesselType + "/" + cargoClass));
    }

    // --- Priority (R25–R30) ------------------------------------------------

    /** Rank of a priority class atom: 1 (emergency) … 6 (maintenance) (RULE R25). Pure → cached. */
    public static int priorityRank(String vesselClassAtom) {
        return engine().oneSolutionCached("priority_rank(" + quoteAtom(vesselClassAtom) + ", N)")
                .map(m -> PrologTerms.toInt(m.get("N")))
                .orElseThrow(() -> new PrologException(
                        "priority_rank undefined for class " + vesselClassAtom));
    }

    // --- Shared transient-fact helpers -------------------------------------

    private static void assertVessel(PrologEngine e, String v, String vesselType,
                                     double draft, double length, int tonnage) {
        e.assertFact("default(" + v + ", has_draft, " + draft + ")");
        e.assertFact("default(" + v + ", has_length, " + length + ")");
        e.assertFact("default(" + v + ", has_tonnage, " + tonnage + ")");
        e.assertFact("instance_of(" + v + ", " + quoteAtom(vesselType) + ")");
    }

    private static void retractVessel(PrologEngine e, String v) {
        e.retractAllMatching("default(" + v + ", _, _)");
        e.retractAllMatching("instance_of(" + v + ", _)");
    }
}
