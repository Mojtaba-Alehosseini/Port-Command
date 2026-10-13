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

    /**
     * True iff {@code windKnots} is under {@code vesselType}'s wind limit (RULE R15 alone).
     * Added in S16 for {@code MovementGate}, which names the failing rule: {@link #operationSafe}
     * (R20) folds R15 and R16 together and cannot say which of the two refused. Takes the type
     * atom directly, so no transient vessel fact and no lock. Not cached: the wind is a
     * continuous input (see {@link #windThresholdsCrossed}).
     */
    public static boolean windWithinLimit(String vesselType, double windKnots) {
        return engine().hasSolution(
                "wind_within_limit(" + quoteAtom(vesselType) + ", " + windKnots + ")");
    }

    /**
     * The wind limit (knots) of {@code vesselType}: the {@code wind_limit/2} fact RULE R15
     * compares against, read as data. Empty for a type the ontology does not know. Added in S16
     * for {@code MovementGate}'s release margin, which is measured from this fact instead of a
     * second copy of the five numbers in Java. Pure lookup over static facts, so cached.
     */
    public static java.util.OptionalDouble windLimit(String vesselType) {
        return engine().oneSolutionCached("wind_limit(" + quoteAtom(vesselType) + ", L)")
                .map(m -> java.util.OptionalDouble.of(PrologTerms.toDouble(m.get("L"))))
                .orElse(java.util.OptionalDouble.empty());
    }

    /**
     * True iff {@code swellMetres} is within the operating limit (RULE R17). Takes no
     * vessel context — pure lookup against the static {@code max_swell/1} fact, so
     * (unlike {@link #operationSafe}) it is cached.
     */
    public static boolean swellWithinLimit(double swellMetres) {
        return engine().hasSolutionCached("swell_within_limit(" + swellMetres + ")");
    }

    /**
     * True iff {@code state} (the weather Markov state atom, e.g. {@code stormy}) is
     * unsafe regardless of the numeric wind/visibility/swell readings (RULE R19). Pure
     * lookup against a static fact → cached.
     */
    public static boolean weatherStateUnsafe(String state) {
        return engine().hasSolutionCached("weather_state_unsafe(" + quoteAtom(state) + ")");
    }

    /**
     * True iff {@code visibility} ({@code good|fair|poor}) is adequate for operations
     * (RULE R16). Pure lookup against the static {@code visibility_adequate/1} facts →
     * cached. Added by task 24 (adversarial review M1): {@code WeatherAlertPolicy} needs
     * the visibility dimension of {@code operation_safe} as a standalone transition
     * check, through the same rule the HarbourMaster's hold gate evaluates.
     */
    public static boolean visibilityOk(String visibility) {
        return engine().hasSolutionCached("visibility_ok(" + quoteAtom(visibility) + ")");
    }

    /**
     * Every wind threshold (knots) that {@code windKnots} has crossed, ascending — the
     * enumeration of RULE R18's {@code threshold_crossed/2}. Empty below the lowest band (30).
     *
     * <p>Since S5 this is the SINGLE source of the alert bands: {@code WeatherAlertPolicy}
     * derives its band index from this list's size instead of a duplicated Java array, so
     * "alert-worthy wind" cannot drift from the rule the report cites.
     *
     * <p><b>Deliberately NOT cached:</b> the input is a continuous double, so a cache would grow
     * without bound and hit only on exact repeats — the same reasoning {@code berthExists}
     * documents, and the same latent bug {@code swellWithinLimit}'s cache already has. No
     * transient facts → no {@code inLock} needed (the {@code selectBestBids} precedent).
     */
    public static List<Integer> windThresholdsCrossed(double windKnots) {
        return engine().oneSolution("findall(T, threshold_crossed(" + windKnots + ", T), Ts)")
                .map(m -> PrologTerms.toStringList(m.get("Ts")).stream()
                        .map(Integer::parseInt).toList())
                .orElse(List.of());
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

    /**
     * True iff {@code cargoClass} requires a customs inspection (RULE R22) — i.e. it is a valid
     * cargo class and hazmat under the reflexive-transitive {@code is_hazmat/1}. Pure static
     * ontology walk → cached, same as {@link #isHazmat}.
     */
    public static boolean needsInspection(String cargoClass) {
        return engine().hasSolutionCached("needs_inspection(" + quoteAtom(cargoClass) + ")");
    }

    /**
     * True iff the {@code vesselType}/{@code cargoClass} pair is high-risk — inspection
     * probability at or above even odds (RULE R24, composing R23). Pure → cached.
     */
    public static boolean highRisk(String vesselType, String cargoClass) {
        return engine().hasSolutionCached(
                "high_risk(" + quoteAtom(vesselType) + ", " + quoteAtom(cargoClass) + ")");
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

    // --- Ontology existence checks (task 16, for nlp.OntologyValidator) -----

    /**
     * True iff {@code berthId} names a berth instance in the ontology ({@code berth_1}..
     * {@code berth_4}). Narrowed to berth subclasses so a vessel type can never answer it.
     *
     * <p><b>Deliberately NOT cached</b>, unlike the other read-only lookups here. {@code
     * instance_of/2} is declared {@code dynamic} and is the very predicate {@link #isCompatible}
     * / {@link #tugsRequired} / {@link #operationSafe} assert transient per-vessel facts under —
     * so this goal reads a dynamic predicate, which INVARIANTS.md forbids caching. It happens to
     * be safe today only because temp vessels live in a disjoint {@code tmp_v_<uuid>} namespace
     * and the grammar only ever emits {@code berth_1}..{@code berth_4}; that is a coincidence of
     * two unrelated conventions, not a guarantee, and it would break silently the day either
     * changes. This runs once per typed chat line, so the cache would buy nothing measurable.
     */
    public static boolean berthExists(String berthId) {
        return engine().hasSolution(
                "( instance_of(" + quoteAtom(berthId) + ", C), subclass_of(C, berth) )");
    }

    /**
     * True iff {@code vesselType} is one of the five ontology vessel types. {@code vessel_type/1}
     * is a static generated fact (never dynamic, never asserted at runtime), so this one IS a
     * genuinely pure goal → cached, same as {@link #isHazmat}.
     */
    public static boolean vesselTypeExists(String vesselType) {
        return engine().hasSolutionCached("vessel_type(" + quoteAtom(vesselType) + ")");
    }

    // --- Priority (R25–R30) ------------------------------------------------

    /** Rank of a priority class atom: 1 (emergency) … 6 (maintenance) (RULE R25). Pure → cached. */
    public static int priorityRank(String vesselClassAtom) {
        return engine().oneSolutionCached("priority_rank(" + quoteAtom(vesselClassAtom) + ", N)")
                .map(m -> PrologTerms.toInt(m.get("N")))
                .orElseThrow(() -> new PrologException(
                        "priority_rank undefined for class " + vesselClassAtom));
    }

    /**
     * A vessel's effective priority — the class atom and its rank — through the R26 bridge and
     * R30's rank join ({@code priority_class_of/2} + {@code effective_priority/2}).
     *
     * <p>{@code overrideClassOrNull} is the caller's runtime escalation ({@code emergency} /
     * {@code medical}); the HarbourMaster owns those in a plain Java map and passes one per
     * query, so the dynamic {@code vessel_priority_override/2} fact exists only transiently
     * inside this call's {@code inLock} window, under the disjoint {@code tmp_v_} namespace —
     * never a persistent write, never a real vessel id in the fact base (the {@code berthExists}
     * coincidence stays a coincidence).
     *
     * <p>Fails loudly on an unknown type: R26 has no fallback clause — {@code priority_class_of}
     * simply has no solution — and a silent default rank would mask a real wiring bug.
     */
    public static VesselPriority effectivePriority(String vesselType, String overrideClassOrNull) {
        PrologEngine e = engine();
        String v = tempVesselId();
        return e.inLock(() -> {
            try {
                e.assertFact("instance_of(" + v + ", " + quoteAtom(vesselType) + ")");
                if (overrideClassOrNull != null) {
                    e.assertFact("vessel_priority_override(" + v + ", "
                            + quoteAtom(overrideClassOrNull) + ")");
                }
                return e.oneSolution(
                                "( priority_class_of(" + v + ", C), effective_priority(" + v + ", R) )")
                        .map(m -> new VesselPriority(m.get("C").name(), PrologTerms.toInt(m.get("R"))))
                        .orElseThrow(() -> new PrologException(
                                "effective_priority produced no solution for type " + vesselType
                                        + " (override " + overrideClassOrNull + ")"));
            } finally {
                e.retractAllMatching("instance_of(" + v + ", _)");
                e.retractAllMatching("vessel_priority_override(" + v + ", _)");
            }
        });
    }

    /** The R26/R30 answer: the priority class atom and its R25 rank (1 highest … 6 lowest). */
    public record VesselPriority(String priorityClass, int rank) {
    }

    /** True iff class {@code a} outranks class {@code b} (RULE R27). Pure over R25's table → cached. */
    public static boolean higherPriority(String classA, String classB) {
        return engine().hasSolutionCached(
                "higher_priority(" + quoteAtom(classA) + ", " + quoteAtom(classB) + ")");
    }

    /** True iff class {@code a} preempts class {@code b} (RULE R28). Pure → cached. */
    public static boolean preempts(String classA, String classB) {
        return engine().hasSolutionCached(
                "preempts(" + quoteAtom(classA) + ", " + quoteAtom(classB) + ")");
    }

    /**
     * True iff the emergency class overrides {@code priorityClass} (RULE R29) — i.e. the class is
     * a valid non-emergency class. Pure → cached.
     */
    public static boolean emergencyOverridesAll(String priorityClass) {
        return engine().hasSolutionCached(
                "emergency_overrides_all(" + quoteAtom(priorityClass) + ")");
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
