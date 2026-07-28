package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Published by {@code gui.SettingsForm} (task 21) when the player applies the settings
 * screen's four editable knobs. Carries only the editable knobs, not the full
 * {@code core.Settings} record (whose other 9 fields are not user-editable in v1 and would
 * force the form to fabricate values it never shows).
 *
 * <p><b>Per-knob apply semantics are DELIBERATELY UNEVEN, and honestly so</b> (session
 * reconciliation 2026-07-18, planning/21 §21.1):
 * <ul>
 *   <li><b>{@code realSecondsPerGameDay}</b> — LIVE. {@code MainWindow} applies it straight
 *       to the shared {@link it.unige.portcommand.util.SimClock} via
 *       {@code setRealSecondsPerGameDay}, so the clock (HUD time, negotiation countdowns)
 *       visibly re-paces at once. A scenario pins its own pacing at BOOT
 *       ({@code GameSession.resolveDayLength}: CLI &gt; scenario &gt; sandbox default) and the
 *       form's slider initialises FROM the live clock, so opening the dialog mid-scenario
 *       shows the scenario's value rather than stomping it; only an explicit drag+apply
 *       changes it (the same "player intent wins" rule as {@code --day-length}).</li>
 *   <li><b>{@code autopilotEnabled}</b> — LIVE. {@link it.unige.portcommand.agents.AssistantAgent}
 *       subscribes and flips its Channel-B toggle; {@code ChatPanel} reads the live value so
 *       the per-tab Hint button appears/disappears immediately.</li>
 *   <li><b>{@code difficulty}</b> — RECORDED ONLY. As of task 24 NOTHING in the running
 *       negotiation engine or HarbourMaster reads {@code Settings.difficulty()} (grep-verified
 *       2026-07-18: the field is plumbed through save/scenario merge but has no runtime
 *       consumer). The form still exposes it (it is a canonical §21.1 knob and rides the save
 *       file), and this event carries it, but there is no live effect to apply — the honest
 *       state of v1, not a wiring omission this task should paper over.</li>
 *   <li><b>{@code llmModel}</b> — NEXT LAUNCH. The Flask sidecar reads its model at process
 *       start; a live change cannot restart it, so the form labels it "takes effect on next
 *       launch". Carried here for completeness.</li>
 * </ul>
 *
 * <p>Like task 22's accepted "settings not re-applied on load" cost, these knobs are NOT
 * re-plumbed back through the save file at load time; a reload restores the persisted
 * {@code Settings} into {@code GameState} but the live SimClock/assistant adopt boot defaults.
 */
public record SettingsChangedEvent(String difficulty, long realSecondsPerGameDay,
                                   boolean autopilotEnabled, String llmModel) implements Event {
}
