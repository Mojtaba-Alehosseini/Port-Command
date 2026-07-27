package it.unige.portcommand.scenario.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import it.unige.portcommand.scenario.GameContext;
import it.unige.portcommand.scenario.ScriptedEventBehaviour;

/**
 * One timeline entry in a scenario script (task 23). Sealed + Jackson-polymorphic on
 * the {@code type} discriminator; the 5 wire names are locked by planning/23 §23.1
 * ({@code SpawnVessel}, {@code WeatherChange}, {@code TugRefuelComplete},
 * {@code TutorialStepAdvance}, {@code Notification} — the last publishes task 17's
 * canonical {@code gui.events.NotificationEvent}, never a second class).
 *
 * <p><b>2026-07-27 (task 26): 5 wire names become 6.</b> {@code BerthFlood} was added because
 * PROJECT_DEFINITION §13's "DISCONFIRM via the scripted Busy-Day terminal-flood" had no
 * implementation — {@code RetractIfFloodBehaviour} waited for an INFORM the shipped game never
 * sent, so one of the ten performatives was unreachable in live play. See
 * {@link BerthFloodEvent}; it adds no behaviour class and no gameplay rule.
 *
 * <p>Lives HERE (not in the parent {@code scenario} package the planning file's table
 * sketched) because Java's sealed-type rule requires unnamed-module permitted subtypes
 * in the SAME package as their sealed interface — dated reconciliation 2026-07-18; the
 * planning table's {@code scenario.events} placement for the subtypes wins.
 *
 * <p>Timestamps are <b>sim-seconds since scenario start</b> (hard constraint: sim
 * time, not wall clock — {@code SimClock} decides when they fire). Firing (exactly
 * once, replay-proof across save/load via {@code GameState.firedEventIds}) is
 * {@link ScriptedEventBehaviour}'s job; each event only knows how to apply itself.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SpawnVesselEvent.class, name = "SpawnVessel"),
        @JsonSubTypes.Type(value = WeatherChangeScriptedEvent.class, name = "WeatherChange"),
        @JsonSubTypes.Type(value = TugRefuelCompleteEvent.class, name = "TugRefuelComplete"),
        @JsonSubTypes.Type(value = TutorialStepAdvanceEvent.class, name = "TutorialStepAdvance"),
        @JsonSubTypes.Type(value = NotificationScriptedEvent.class, name = "Notification"),
        @JsonSubTypes.Type(value = BerthFloodEvent.class, name = "BerthFlood")})
public sealed interface ScriptedEvent
        permits SpawnVesselEvent, WeatherChangeScriptedEvent, TugRefuelCompleteEvent,
                TutorialStepAdvanceEvent, NotificationScriptedEvent, BerthFloodEvent {

    /** Sim-seconds since scenario start at which this event is due. */
    long simTimeSeconds();

    /** Executes the event against the live world. HarbourMaster thread only; never EDT. */
    void apply(GameContext ctx);
}
