package it.unige.portcommand.nlp;

/**
 * Minimum edit distance (Levenshtein), written out as the course's dynamic programme — NOT
 * imported from a library, on purpose: PROJECT_DEFINITION §2.2 claims the topic ("Marsk
 * Genoba" → "Maersk Genova"), so the implementation IS the claim (audit P2-1, S6).
 *
 * <p>Unit costs — insertion 1, deletion 1, substitution 1 — over the classic recurrence
 * {@code D(i,j) = min(D(i-1,j)+1, D(i,j-1)+1, D(i-1,j-1)+[a_i != b_j])} with base cases
 * {@code D(i,0)=i}, {@code D(0,j)=j}. Implemented with a two-row rolling table: O(|a|·|b|)
 * time, O(min-side) space. {@link #within(String,String,int)} packages the free
 * length-difference lower bound ({@code D >= ||a|-|b||} — each unmatched length unit costs at
 * least one edit); {@code RosterNameResolver} applies the same bound inline instead, because
 * its tie-break needs the exact distance rather than a boolean (adversarial finding 15).
 */
public final class MinimumEditDistance {

    private MinimumEditDistance() {
    }

    /** The Levenshtein distance between {@code a} and {@code b}. */
    public static int distance(String a, String b) {
        // Roll over the shorter string so the working rows are as small as possible.
        if (a.length() < b.length()) {
            String t = a;
            a = b;
            b = t;
        }
        int m = b.length();
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j; // D(0, j): j insertions build b's prefix from the empty string
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i; // D(i, 0): i deletions erase a's prefix
            char ai = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int substitution = previous[j - 1] + (ai == b.charAt(j - 1) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] t = previous;
            previous = current;
            current = t;
        }
        return previous[m];
    }

    /** True iff {@code distance(a, b) <= k}, with the length-difference bound as a fast exit. */
    public static boolean within(String a, String b, int k) {
        return Math.abs(a.length() - b.length()) <= k && distance(a, b) <= k;
    }
}
