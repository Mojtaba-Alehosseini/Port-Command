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
        @JsonSubTypes.Type(value = NotificationScriptedEvent.class, name = "Notification")})
public sealed interface ScriptedEvent
        permits SpawnVesselEvent, WeatherChangeScriptedEvent, TugRefuelCompleteEvent,
                TutorialStepAdvanceEvent, NotificationScriptedEvent {

    /** Sim-seconds since scenario start at which this event is due. */
    long simTimeSeconds();

    /** Executes the event against the live world. HarbourMaster thread only; never EDT. */
    void apply(GameContext ctx);
}
