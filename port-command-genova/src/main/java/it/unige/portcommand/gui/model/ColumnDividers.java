package it.unige.portcommand.gui.model;

/**
 * Arithmetic for the window's two column dividers (S15, 2026-10-12). Pure: no Swing/AWT import,
 * so the clamping is unit-tested without a split pane.
 *
 * <p><b>Why it exists.</b> A divider's position is stored as a proportion, so it survives a
 * different window size on the next launch. But setting a split pane's divider location
 * programmatically does not consult the two sides' minimum sizes — only a drag and a window
 * resize do — so a stored proportion applied as-is can leave a column narrower than its minimum
 * (a 0.95 saved on a wide monitor, or old preferences from a different layout). This turns a
 * stored proportion into a location that respects both minimums.
 *
 * <p>"Span" is the width the two sides share: the split pane's inner width minus the divider.
 * "Location" is the left side's width within that span.
 */
public final class ColumnDividers {

    /** A stored proportion is never trusted outside this band, whatever the minimums allow. */
    public static final double MIN_PROPORTION = 0.1;
    public static final double MAX_PROPORTION = 0.9;

    /** What an unusable proportion (NaN, or one asked of an empty span) falls back to. */
    private static final double NEUTRAL_PROPORTION = 0.5;

    private ColumnDividers() {
    }

    /**
     * The left side's width for a stored proportion.
     *
     * <p>When the span holds both minimums, the result gives each side at least its own. When it
     * does not (a window squeezed below its own minimum size), no location can — so both sides
     * give way in proportion to their minimums rather than one of them collapsing. The result is
     * always within {@code 0..span}; a non-positive span gives 0.
     *
     * @param span       the width the two sides share
     * @param proportion the left side's stored share of the span
     * @param minLeft    the left side's minimum width
     * @param minRight   the right side's minimum width
     */
    public static int location(int span, double proportion, int minLeft, int minRight) {
        if (span <= 0) {
            return 0;
        }
        int left = Math.max(0, minLeft);
        int right = Math.max(0, minRight);
        long bothMinimums = (long) left + right;
        if (bothMinimums > span) {
            // bothMinimums > span > 0, so the division is safe and the share is within 0..1.
            return (int) Math.round(span * (left / (double) bothMinimums));
        }
        int wanted = (int) Math.round(span * clampProportion(proportion));
        return Math.max(left, Math.min(wanted, span - right));
    }

    /**
     * The proportion to store for a divider the player dragged to {@code leftSize}. An empty span
     * carries no information and gives the neutral middle; callers should not store that.
     */
    public static double proportion(int leftSize, int span) {
        if (span <= 0) {
            return NEUTRAL_PROPORTION;
        }
        return clampProportion(leftSize / (double) span);
    }

    /**
     * Keeps a proportion within {@value #MIN_PROPORTION}..{@value #MAX_PROPORTION}. NaN — a
     * hand-edited or corrupt preference parses to one — becomes the neutral middle.
     */
    public static double clampProportion(double proportion) {
        if (Double.isNaN(proportion)) {
            return NEUTRAL_PROPORTION;
        }
        return Math.max(MIN_PROPORTION, Math.min(MAX_PROPORTION, proportion));
    }
}
