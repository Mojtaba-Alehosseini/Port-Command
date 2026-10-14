package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The single HUD notice channel (planning/17: "THE ONLY {@code NotificationEvent} class" —
 * downstream tasks import this, they do not redefine it). Used here by {@code nlp.PolicyParser}
 * (parse-failure clarification toast) and {@code PolicyParseBehaviour} (policy-registered
 * confirmation) — there is no separate {@code POLICY_REGISTERED} channel (planning/10 §10.8).
 * See {@link HintButtonEvent}'s ownership note.
 *
 * <p>S18b (playtest 2, PT10): a notice may name its {@code topic} — "weather", a berth, a
 * vessel's hold or queue place. A newer notice on the same topic replaces the older one's chip:
 * "Weather clearing" replaces "Weather worsening", "Berth 2 reopened" replaces its flood notice,
 * and a repeat replaces itself instead of piling up. Chips expire after 8 real seconds whatever
 * the topic; at ×4 that is 2.5 game hours, which is why the same chips used to seem to stay.
 *
 * @param text          the notice text
 * @param severity      how prominently the HUD should show it
 * @param simTimeMillis sim time the notice was raised
 * @param topic         what the notice is about, for replacing an older notice on it; {@code null}
 *                      when it is about nothing another notice will follow up (a notice with no
 *                      topic replaces only a chip with the very same words)
 */
public record NotificationEvent(String text, Severity severity, long simTimeMillis, String topic) implements Event {

    /** A notice with no topic. */
    public NotificationEvent(String text, Severity severity, long simTimeMillis) {
        this(text, severity, simTimeMillis, null);
    }

    /** S18b (PT10): the weather notices' topic. */
    public static final String WEATHER_TOPIC = "weather";

    /** S18b (PT10): a berth's flood and reopening share one topic. */
    public static String berthTopic(String berthId) {
        return "berth:" + berthId;
    }

    /** S18b (PT10): a vessel held outside or at its berth by the weather. */
    public static String holdTopic(String vesselId) {
        return "hold:" + vesselId;
    }

    /** S18b (PT10): a vessel queued for a berth. */
    public static String queueTopic(String vesselId) {
        return "queue:" + vesselId;
    }

    /** S18b (PT10): a vessel waiting for a free tug. */
    public static String tugWaitTopic(String vesselId) {
        return "tug-wait:" + vesselId;
    }

    public enum Severity {
        INFO, WARNING, ERROR
    }
}
