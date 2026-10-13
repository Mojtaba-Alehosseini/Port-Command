package it.unige.portcommand.gui.model;

/**
 * The comm log's four one-click views (S15, 2026-10-12). They replace the performative / sender /
 * receiver combos, the "Hide weather" checkbox and the From/To fields: those were the widgets
 * that captured the player's typing when focus fell out of the chat, and nobody could say in one
 * sentence what a given combination of them showed.
 *
 * <p>Pure selection state — {@link CommLogModel} owns the predicate each preset stands for, so
 * the meaning is testable without a panel. No Swing/AWT here.
 */
public enum CommLogPreset {

    /**
     * Every row. Literally all of them, the weather agent's routine checks included: the log has
     * to keep matching the end-of-day performative tally, which counts those INFORMs too.
     */
    ALL,

    /** Rows about the vessel whose chat tab is selected ({@link CommLogModel#setFocusVessel}). */
    THIS_VESSEL,

    /** Rows that cite a rule of the Prolog kernel, e.g. {@code [R7]}. */
    RULES,

    /** Weather alerts, routine checks, and the holds, resumes and tug cancels they cause. */
    WEATHER
}
