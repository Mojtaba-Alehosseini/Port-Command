package it.unige.portcommand.gui.model;

import java.util.Set;

/**
 * What the comm log's presets need to know about ONE row (S15, 2026-10-12), derived once by
 * {@link CommLogClassifier#classify} when the row is added and never recomputed: a render walks
 * up to 500 rows, and re-parsing every row's JSON content on every render would be the wrong
 * place to spend the EDT's time.
 *
 * <p>Immutable. No Swing/AWT.
 *
 * @param vesselKeys      the vessels this row names by itself — as sender, as receiver, in its
 *                        conversation id or in its content — as keys in local-name space (see
 *                        {@link CommLogClassifier#keyOfVesselId}). Empty for a tug bid or a
 *                        contentless REJECT: those are attributed through the model's
 *                        conversation index instead.
 * @param weather         a weather alert or routine check, or a hold, resume or tug cancel caused
 *                        by one
 * @param periodicWeather the weather agent's routine 15-minute check, specifically. S18b (PT9):
 *                        "All" leaves out the checks that read the same as the one before
 *                        ({@code CommLogModel}); "Weather" still shows every one, and the
 *                        end-of-day tally still counts them.
 * @param citesRule       the paraphrase or the content cites at least one catalogued rule id
 */
public record CommLogFacts(Set<String> vesselKeys, boolean weather, boolean periodicWeather, boolean citesRule) {

    /** A row nothing is known about: the classifier's answer for anything it cannot read. */
    public static final CommLogFacts NONE = new CommLogFacts(Set.of(), false, false, false);

    public CommLogFacts {
        vesselKeys = vesselKeys == null ? Set.of() : Set.copyOf(vesselKeys);
    }
}
