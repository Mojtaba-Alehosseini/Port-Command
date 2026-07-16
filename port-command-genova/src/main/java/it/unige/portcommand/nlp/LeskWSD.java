package it.unige.portcommand.nlp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.dictionary.Dictionary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simplified Lesk word-sense disambiguation over WordNet glosses (planning/16 Step 16.6): score
 * each sense of a word by how many content words its gloss shares with the surrounding context,
 * and take the winner. Used ONLY for the handful of nouns whose port meaning differs from their
 * dominant WordNet sense — it is not run on every token (it is slow, and needless).
 *
 * <p><b>Why this is not decorative.</b> WordNet 3.1's FIRST sense of "dock" is <i>"an enclosure in
 * a court of law where the defendant sits during the trial"</i>; the harbour sense is #5. So the
 * naive first-sense heuristic that most pipelines fall back on resolves "dock" to a COURTROOM.
 * Lesk over a negotiation context picks the harbour sense instead — a real disambiguation with a
 * different outcome, which is the point of the NLP-report claim.
 *
 * <p><b>Deviation from planning/16 Step 16.6, recorded in ADR-10.</b> The sketch has
 * {@code disambiguate} return the winning sense's LEMMA (a {@code String}) and then map that lemma
 * through the handcrafted table. That cannot work: "dock" is a member of both the courtroom synset
 * and the harbour synset, so the winning lemma is the string "dock" either way and the table maps
 * it to {@code berth} regardless of which sense won — the WSD would change nothing. Returning the
 * {@link Synset} lets {@link WordNetResolver} ask which SENSE won, which is the only version of
 * this that does any work.
 */
public final class LeskWSD {

    private static final Logger log = LoggerFactory.getLogger(LeskWSD.class);

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    /** Function words carry no sense signal and would otherwise dominate every gloss overlap
     * ("a"/"the"/"of" appear in nearly all of them). Classic Lesk filters them out. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "from", "by", "with", "and",
            "or", "but", "is", "are", "was", "were", "be", "been", "being", "as", "that", "this",
            "these", "those", "it", "its", "into", "out", "up", "down", "over", "under", "than",
            "then", "there", "where", "when", "which", "who", "whom", "may", "can", "will",
            "would", "should", "could", "have", "has", "had", "do", "does", "did", "not", "no",
            "so", "such", "more", "most", "some", "any", "all", "each", "other", "certain",
            "usually", "sometimes", "etc", "i", "you", "he", "she", "they", "we", "my", "your");

    private final Dictionary dictionary;

    public LeskWSD(Dictionary dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    }

    /**
     * @param word    the ambiguous noun
     * @param context the surrounding tokens (the utterance, plus any domain seed the caller adds)
     * @return the highest-overlap sense, or {@link Optional#empty()} when the word is unknown to
     *         WordNet or NO sense shares a single content word with the context (a zero-overlap
     *         "winner" would be the first sense by accident, which is exactly the naive behaviour
     *         this class exists to avoid — the caller must decide what to do with no signal)
     */
    public Optional<Synset> disambiguate(String word, List<String> context) {
        Objects.requireNonNull(word, "word");
        Objects.requireNonNull(context, "context");
        try {
            IndexWord indexWord = dictionary.lookupIndexWord(POS.NOUN, word);
            if (indexWord == null) {
                return Optional.empty();
            }
            Set<String> contextWords = contentWords(context);
            // The target word itself appears in its own glosses and carries no sense signal.
            contextWords.remove(word.toLowerCase(Locale.ROOT));
            if (contextWords.isEmpty()) {
                return Optional.empty();
            }

            Synset best = null;
            int bestScore = 0; // strictly-positive overlap required; ties keep the earlier sense
            for (Synset sense : indexWord.getSenses()) {
                int score = overlap(sense, contextWords);
                if (score > bestScore) {
                    bestScore = score;
                    best = sense;
                }
            }
            if (best == null) {
                log.debug("Lesk: no sense of '{}' overlaps the context {}", word, contextWords);
                return Optional.empty();
            }
            return Optional.of(best);
        } catch (JWNLException | RuntimeException e) {
            log.warn("Lesk lookup failed for '{}' — treating as unresolved", word, e);
            return Optional.empty();
        }
    }

    private static int overlap(Synset sense, Set<String> contextWords) {
        Set<String> glossWords = contentWords(List.of(sense.getGloss()));
        glossWords.retainAll(contextWords);
        return glossWords.size();
    }

    private static Set<String> contentWords(List<String> texts) {
        Set<String> words = new HashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Arrays.stream(NON_WORD.split(text.toLowerCase(Locale.ROOT)))
                    .filter(w -> !w.isBlank())
                    .filter(w -> !STOP_WORDS.contains(w))
                    .forEach(words::add);
        }
        return words;
    }
}
