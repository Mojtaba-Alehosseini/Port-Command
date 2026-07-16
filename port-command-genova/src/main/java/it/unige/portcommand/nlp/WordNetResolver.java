package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.data.Word;
import net.sf.extjwnl.dictionary.Dictionary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a surface word to an ontology class — the "WordNet synonymy" stage of
 * PROJECT_DEFINITION.md §6.1 ("freighter" ≡ "cargo ship" ≡ "merchantman" &rarr; {@code
 * cargo_vessel}). Sits between the DCG parse and {@link OntologyValidator}, so a frame naming a
 * vessel class in the player's words is normalised before the ontology is asked about it.
 *
 * <p><b>The handcrafted table has precedence</b> (planning/16 Step 16.5): it is consulted BEFORE
 * the dictionary, which makes the negotiation vocabulary deterministic regardless of the WordNet
 * version installed. WordNet is the fallthrough for everything else, and it earns its place by
 * doing work the table cannot: morphological lookup ({@code ships} &rarr; {@code ship},
 * {@code ferries} &rarr; {@code ferry}) over the whole English noun lexicon.
 *
 * <p><b>Degradation.</b> If the dictionary cannot be loaded (no {@code extjwnl-data-wn31} on the
 * classpath), the resolver logs once and serves the handcrafted table alone rather than failing
 * the whole app. That is deliberately NOT an excuse to leave the layer untested — the fallthrough
 * tests assert real dictionary results and fail loudly if the data jar is missing.
 */
public final class WordNetResolver {

    private static final Logger log = LoggerFactory.getLogger(WordNetResolver.class);

    /**
     * The negotiation vocabulary, source of truth (planning/16 Step 16.5's table). Checked before
     * WordNet so these mappings cannot drift with the dictionary version.
     */
    private static final Map<String, String> HANDCRAFTED = Map.of(
            "freighter", "cargo_vessel",
            "merchantman", "cargo_vessel",
            "merchant ship", "cargo_vessel",
            "cargo ship", "cargo_vessel",
            "slip", "berth",
            "dock", "berth",
            "loading", "berth");

    /**
     * Words whose port meaning is NOT their dominant WordNet sense, so a bare lookup would resolve
     * them wrongly and the table's mapping needs defending with real disambiguation.
     *
     * <p>Only {@code dock} qualifies, which deviates from planning/16's table — it flags
     * {@code loading} as context-sensitive too. Recorded in ADR-10: {@code loading}'s WordNet
     * senses are all cargo-sense ({@code [cargo, lading, freight, load, loading, payload, ...]}),
     * never berth-sense, so running Lesk on it could only ever DELETE the {@code loading -> berth}
     * mapping the table deliberately makes. That inverts the table-has-precedence rule, so
     * {@code loading} keeps its unconditional table mapping. {@code dock} is the genuine case: its
     * first WordNet sense is a courtroom enclosure and its harbour sense is #5.
     */
    private static final Set<String> CONTEXT_SENSITIVE = Set.of("dock");

    /**
     * Lemmas that DISCRIMINATE the berth/quay sense of an ambiguous word. Used to ask "which
     * sense won?" after Lesk — the only way to tell dock-the-harbour from dock-the-courtroom.
     * Drawn from the actual WordNet 3.1 synsets (verified by running):
     * <pre>
     *   berth-sense: [pier, wharf, wharfage, dock] · [dock, dockage, docking facility]
     *                [dock, loading dock]          · [mooring, moorage, berth, slip]
     *   other:       [dock] (courtroom) · [dock, sorrel, sour grass] · [bobtail, bob, dock]
     * </pre>
     *
     * <p><b>The ambiguous word itself must NEVER appear here.</b> "dock" is a member of the
     * courtroom synset AND the harbour synsets, so listing it would match every sense and the
     * disambiguation would be a no-op — the exact circularity this set exists to break. A
     * discriminator has to be a lemma the OTHER senses lack ({@code dockage}, {@code wharfage},
     * {@code moorage}, …). {@code WordNetResolverTest.leskRefusesTheBerthMappingInACourtroomContext}
     * is the regression guard: it fails the moment "dock" is added back.
     */
    private static final Set<String> BERTH_SENSE_LEMMAS = Set.of(
            "dockage", "docking facility", "loading dock",
            "pier", "wharf", "wharfage", "mooring", "moorage", "berth", "slip");

    private final boolean autoLoad;
    private volatile Dictionary dictionary;
    private volatile LeskWSD lesk;
    private volatile boolean loaded;

    /**
     * Serves the WordNet 3.1 dictionary bundled as a resource jar, loaded LAZILY on first use.
     * Reading the WordNet database costs seconds, and most callers only ever hit the handcrafted
     * table — so the cost is paid on the first genuine fallthrough, not at construction (which
     * would tax every {@code JadeBootstrap.start()} and every test that merely builds a validator).
     */
    public WordNetResolver() {
        this.autoLoad = true;
    }

    /** @param dictionary the WordNet dictionary, or {@code null} to run table-only (degraded). */
    public WordNetResolver(Dictionary dictionary) {
        this.autoLoad = false;
        this.dictionary = dictionary;
        this.lesk = dictionary == null ? null : new LeskWSD(dictionary);
        this.loaded = true;
    }

    private Dictionary dictionary() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    dictionary = autoLoad ? loadDefaultDictionary() : null;
                    lesk = dictionary == null ? null : new LeskWSD(dictionary);
                    loaded = true;
                }
            }
        }
        return dictionary;
    }

    private LeskWSD lesk() {
        dictionary(); // forces the lazy load, which also populates lesk
        return lesk;
    }

    private static Dictionary loadDefaultDictionary() {
        try {
            return Dictionary.getDefaultResourceInstance();
        } catch (Exception e) {
            log.warn("WordNet dictionary unavailable — falling back to the handcrafted table only. "
                    + "Check that extjwnl-data-wn31 is on the classpath.", e);
            return null;
        }
    }

    /** True iff the WordNet dictionary loaded; false means table-only degradation. */
    public boolean isDictionaryAvailable() {
        return dictionary() != null;
    }

    /**
     * Context-free resolution: the handcrafted table, then a plain WordNet lookup, then the input.
     * A context-sensitive word keeps its table mapping — this game's utterances are negotiation
     * context by construction, so the table's scope note is satisfied by default.
     */
    public String resolveToOntology(String word, String posHint) {
        return resolveToOntology(word, posHint, List.of());
    }

    /**
     * @param posHint reserved for a future non-noun lookup; every word this resolver is asked
     *                about today is a noun (vessel class / berth), so it is not yet consulted.
     * @param context surrounding tokens; when non-empty, a context-sensitive word
     *                ({@link #CONTEXT_SENSITIVE}) is disambiguated by Lesk rather than trusting
     *                the table blind
     */
    public String resolveToOntology(String word, String posHint, List<String> context) {
        Objects.requireNonNull(word, "word");
        Objects.requireNonNull(context, "context");
        String normalised = word.toLowerCase(Locale.ROOT).trim();
        if (normalised.isEmpty()) {
            return word;
        }

        if (!context.isEmpty() && CONTEXT_SENSITIVE.contains(normalised) && lesk() != null) {
            return resolveContextSensitive(word, normalised, context);
        }

        String mapped = HANDCRAFTED.get(normalised);
        if (mapped != null) {
            return mapped;
        }
        return lookupLemma(word);
    }

    /** Lesk decides which SENSE is meant; only a berth-sense win earns the table's mapping. */
    private String resolveContextSensitive(String original, String normalised, List<String> context) {
        Optional<Synset> sense = lesk().disambiguate(normalised, context);
        if (sense.isEmpty()) {
            // No signal either way — keep the negotiation-domain default rather than inventing one.
            return HANDCRAFTED.get(normalised);
        }
        if (isBerthSense(sense.get())) {
            return HANDCRAFTED.get(normalised);
        }
        // A confidently non-port sense (dock-the-courtroom): do NOT map it into the ontology.
        log.debug("Lesk resolved '{}' to a non-port sense ({}) — leaving it unmapped",
                normalised, sense.get().getGloss());
        return lookupLemma(original);
    }

    private static boolean isBerthSense(Synset synset) {
        for (Word word : synset.getWords()) {
            if (BERTH_SENSE_LEMMAS.contains(word.getLemma().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** The WordNet fallthrough: morphological lookup over the full noun lexicon; unknown words
     * fall through to the literal input (planning/16 Step 16.5). */
    private String lookupLemma(String word) {
        Dictionary dict = dictionary();
        if (dict == null) {
            return word;
        }
        try {
            IndexWord indexWord = dict.lookupIndexWord(POS.NOUN, word);
            return indexWord != null ? indexWord.getLemma() : word;
        } catch (Exception e) {
            log.warn("WordNet lookup failed for '{}' — returning it unchanged", word, e);
            return word;
        }
    }
}
