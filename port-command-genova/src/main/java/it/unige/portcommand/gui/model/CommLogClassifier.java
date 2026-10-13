package it.unige.portcommand.gui.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.CommLogEvent;

/**
 * Reads a {@link CommLogEvent} for the comm log's presets (S15, 2026-10-12): is it weather, does
 * it cite a rule, which vessels does it name.
 *
 * <p>Everything here is derived from the event's own fields — the sender and receiver names, the
 * protocol, the conversation id and the content exactly as it went over the wire. Nothing is
 * asked of an agent and nothing is decided for one: this is a reading of the log, on the GUI
 * side, for filtering only.
 *
 * <p>It never throws on what an event carries. The content is whatever some agent put on the
 * wire, and this runs on the EDT for every logged message; content that is absent, not JSON or
 * not an object simply yields no facts.
 *
 * <p>No Swing/AWT. The two weather protocol names are written out here instead of being imported
 * so that {@code gui.model} does not depend on the behaviour packages; {@code
 * CommLogClassifierTest} pins them to the behaviours' constants (the same arrangement as
 * {@code PerformativeCounter}'s copy of the performative list).
 */
public final class CommLogClassifier {

    /** The local name the WeatherAgent is spawned under ({@code AgentRoster}). */
    public static final String WEATHER_SENDER = "weather_agent";

    /** Copy of {@code HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL} — test-pinned. */
    static final String WEATHER_ALERT_PROTOCOL = "port-command-weather-alert";

    /** Copy of {@code HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL} — test-pinned. */
    static final String VESSEL_WEATHER_PROTOCOL = "port-command-vessel-weather";

    /** Every vessel agent's local name is this prefix plus its key ({@code AgentRoster}). */
    private static final String VESSEL_NAME_PREFIX = "vessel_";

    /** Conversation ids built from the vessel's RAW id: negotiation, terminal, customs. */
    private static final List<String> RAW_ID_CONVERSATION_PREFIXES = List.of("nego-", "term-", "cust-");

    /** Conversation ids built from the vessel agent's LOCAL NAME: the berth request and grant. */
    private static final String BERTH_CONVERSATION_PREFIX = "berth-";

    private static final String WEATHER_EVENT_PREFIX = "weather_";
    private static final String PERIODIC_WEATHER_EVENT = "weather_periodic";
    private static final String WEATHER_HOLD_REASON = "weather_hold";

    /** What a terminal writes when it does not know which vessel it handled — not a vessel. */
    private static final String UNKNOWN_VESSEL = "unknown";

    private CommLogClassifier() {
    }

    /**
     * All the facts about one row, with ONE parse of its content. Never throws on the event:
     * {@code null}, or anything unreadable in it, gives {@link CommLogFacts#NONE}.
     *
     * @param knownRuleId which rule ids exist (in production {@code RuleCatalogue.get()::contains});
     *                    a citation of an id it does not know does not make a row a rule row
     */
    public static CommLogFacts classify(CommLogEvent event, Predicate<String> knownRuleId) {
        // A missing predicate is a wiring mistake, not something a message can cause: fail here
        // rather than quietly answer NONE for every row.
        Objects.requireNonNull(knownRuleId, "knownRuleId");
        if (event == null) {
            return CommLogFacts.NONE;
        }
        try {
            JsonNode content = parse(event.content());
            boolean citesRule = RuleRefs.citesAny(event.paraphrase(), knownRuleId)
                    || RuleRefs.citesAny(event.content(), knownRuleId);
            return new CommLogFacts(vesselsOf(event, content), isWeather(event, content),
                    isPeriodicWeather(content), citesRule);
        } catch (RuntimeException e) {
            // Belt and braces: every step above is already null-safe, but a row that cannot be
            // classified must still be logged, so nothing escapes to the EDT from here.
            return CommLogFacts.NONE;
        }
    }

    /**
     * The vessels this row names BY ITSELF, as keys in local-name space — the union of:
     * <ul>
     *   <li>the sender and every receiver that is a vessel agent;</li>
     *   <li>the conversation id, when its scheme names a vessel
     *       ({@link #vesselKeyOfConversationId});</li>
     *   <li>the content's {@code vessel_id}, {@code target_vessel_id}, {@code vessel} and
     *       {@code vessel_spec.vessel_id} (blank and {@code "unknown"} skipped).</li>
     * </ul>
     * Direct attribution only: a tug's bid names no vessel anywhere, and is tied to one through
     * the conversation index {@link CommLogModel} keeps, not here.
     */
    public static Set<String> vesselsOf(CommLogEvent event) {
        if (event == null) {
            return Set.of();
        }
        try {
            return Set.copyOf(vesselsOf(event, parse(event.content())));
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /**
     * Weather traffic, all six kinds: the weather agent's routine check, its worsening alert and
     * its clearing alert (sender, protocol and content event all say so), the HarbourMaster's
     * hold and resume to a vessel (protocol and content event), and its CANCEL to a tug because
     * of a hold (content reason only — that message has no protocol).
     *
     * <p>A {@code tug_hold} (no tug bid) and a {@code player_withdrew} cancel are NOT weather.
     */
    public static boolean isWeather(CommLogEvent event) {
        if (event == null) {
            return false;
        }
        try {
            return isWeather(event, parse(event.content()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The weather agent's routine check and nothing else: content {@code event} is exactly
     * {@code weather_periodic}. Alerts, holds and resumes are weather but not periodic. */
    public static boolean isPeriodicWeather(CommLogEvent event) {
        if (event == null) {
            return false;
        }
        try {
            return isPeriodicWeather(parse(event.content()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A raw vessel id ({@code WALKIN-3}) as a key: lower-cased, every character outside
     * {@code [a-z0-9_]} replaced by an underscore ({@code walkin_3}). {@code null} or blank
     * names no vessel and gives {@code null}.
     *
     * <p>This is the SAME expression {@code AgentRoster.vesselLocalName} applies after its
     * {@code vessel_} prefix — default-locale lower-casing included, on purpose: the key has to
     * equal what the agent was actually named on this machine, whatever that is. Because keys
     * therefore live in local-name space, where JADE enforces uniqueness, two live vessels cannot
     * share one. Matching is always set membership, never a prefix test, so {@code WALKIN-3}
     * does not match {@code vessel_walkin_30}. {@code CommLogClassifierTest} pins the equality.
     */
    public static String keyOfVesselId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return rawId.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * The key of a vessel agent's local name: {@code vessel_walkin_3} gives {@code walkin_3}.
     * Every other agent — the HarbourMaster, a tug, a terminal — and {@code null} give
     * {@code null}.
     */
    public static String keyOfLocalName(String localName) {
        if (localName == null || !localName.startsWith(VESSEL_NAME_PREFIX)
                || localName.length() == VESSEL_NAME_PREFIX.length()) {
            return null;
        }
        return localName.substring(VESSEL_NAME_PREFIX.length());
    }

    /**
     * The vessel a conversation id names by its scheme, or {@code null} when it names none:
     * <ul>
     *   <li>{@code nego-<ID>}, {@code term-<ID>}, {@code cust-<ID>} carry the raw vessel id;</li>
     *   <li>{@code berth-<localName>} carries the vessel agent's local name (a raw id after it is
     *       tolerated);</li>
     *   <li>{@code cnp-<UUID>}, any other id and {@code null} name no vessel — a tug tender is
     *       attributed through the conversation index instead.</li>
     * </ul>
     */
    public static String vesselKeyOfConversationId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        for (String prefix : RAW_ID_CONVERSATION_PREFIXES) {
            if (conversationId.startsWith(prefix)) {
                return keyOfVesselId(conversationId.substring(prefix.length()));
            }
        }
        if (conversationId.startsWith(BERTH_CONVERSATION_PREFIX)) {
            String rest = conversationId.substring(BERTH_CONVERSATION_PREFIX.length());
            return rest.startsWith(VESSEL_NAME_PREFIX) ? keyOfLocalName(rest) : keyOfVesselId(rest);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // The same three readings over an already-parsed content, so classify() parses only once.
    // ---------------------------------------------------------------------------------------

    private static Set<String> vesselsOf(CommLogEvent event, JsonNode content) {
        Set<String> keys = new LinkedHashSet<>();
        addKey(keys, keyOfLocalName(event.sender()));
        for (String receiver : event.receivers()) {
            addKey(keys, keyOfLocalName(receiver));
        }
        addKey(keys, vesselKeyOfConversationId(event.conversationId()));
        addContentVessel(keys, text(content, "vessel_id"));
        addContentVessel(keys, text(content, "target_vessel_id"));
        // handling_complete says "vessel", not "vessel_id" (ProcessCargoHandlingBehaviour).
        addContentVessel(keys, text(content, "vessel"));
        // A walk-in's opening offer and a contracted berth request nest the whole spec.
        addContentVessel(keys, text(content == null ? null : content.get("vessel_spec"), "vessel_id"));
        return keys;
    }

    private static boolean isWeather(CommLogEvent event, JsonNode content) {
        if (WEATHER_SENDER.equals(event.sender())) {
            return true;
        }
        String protocol = event.protocol();
        if (WEATHER_ALERT_PROTOCOL.equals(protocol) || VESSEL_WEATHER_PROTOCOL.equals(protocol)) {
            return true;
        }
        String contentEvent = text(content, "event");
        if (contentEvent != null && contentEvent.startsWith(WEATHER_EVENT_PREFIX)) {
            return true;
        }
        return WEATHER_HOLD_REASON.equals(text(content, "reason"));
    }

    private static boolean isPeriodicWeather(JsonNode content) {
        return PERIODIC_WEATHER_EVENT.equals(text(content, "event"));
    }

    private static void addKey(Set<String> keys, String key) {
        if (key != null) {
            keys.add(key);
        }
    }

    private static void addContentVessel(Set<String> keys, String rawId) {
        if (rawId == null || rawId.isBlank() || UNKNOWN_VESSEL.equals(rawId)) {
            return;
        }
        addKey(keys, keyOfVesselId(rawId));
    }

    /**
     * The content as a JSON object, or {@code null} for anything else: absent, blank, not JSON,
     * or JSON that is not an object (a bare string, a number, an array) — none of which has
     * fields to read.
     */
    private static JsonNode parse(String content) {
        JsonNode node = TerminalJson.readTreeOrNull(content);
        return node != null && node.isObject() ? node : null;
    }

    /** A field's scalar value as text; {@code null} when it is absent, JSON null or not a scalar. */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }
}
